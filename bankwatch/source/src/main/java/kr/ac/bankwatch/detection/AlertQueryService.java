package kr.ac.bankwatch.detection;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.stereotype.Service;
import static kr.ac.bankwatch.Db.p;

@Service
public class AlertQueryService {
    private static final Set<String> ROLES = Set.of("ADMIN", "ANALYST");
    private static final Set<String> FILTERS = Set.of("account", "status", "mine", "beforeAt", "beforeId", "limit");
    private static final Map<String, String> STATES = Map.of(
            "UNASSIGNED", " AND i.incident_id IS NULL",
            "IN_PROGRESS", " AND i.incident_id IS NOT NULL AND i.closed_at IS NULL",
            "CLOSED", " AND i.closed_at IS NOT NULL");
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
    private static final Pattern OFFSET = Pattern.compile("(?:Z|[+-]\\d{2}:\\d{2})$", Pattern.CASE_INSENSITIVE);
    // QRY-01: actor는 Controller가 인증 세션에서 얻는다. 두 활성 역할은 전체 경보를 조회한다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    // QRY-11: 상태는 계산값이며, 선택 관계인 사건·담당자를 LEFT JOIN으로 보존한다.
    private static final String SUMMARY = """
            SELECT a.alert_id::text AS alert_id, a.rule_id::text AS rule_id,
                   r.rule_code, r.rule_name, a.account_id::text AS account_id,
                   c.customer_ref, c.display_name AS customer_name, a.bucket_start,
                   a.bucket_start + INTERVAL '10 minutes' AS bucket_end, a.detected_at,
                   i.incident_id::text AS incident_id, i.assignee_id::text AS assignee_id,
                   o.display_name AS assignee_name, i.created_at AS incident_created_at,
                   i.closed_at, i.resolution, i.version AS incident_version,
                   CASE WHEN i.incident_id IS NULL THEN 'UNASSIGNED'
                        WHEN i.closed_at IS NULL THEN 'IN_PROGRESS' ELSE 'CLOSED' END AS processing_status,
                   c.lock_incident_id::text AS lock_incident_id,
                   CASE WHEN c.lock_incident_id IS NULL THEN 'ACTIVE' ELSE 'LOCKED' END AS access_status,
                   c.lock_incident_id IS NULL AS access_allowed,
                   coalesce(c.lock_incident_id = i.incident_id, false) AS is_lock_cause,
                   coalesce(i.assignee_id = :actor, false) AS is_mine,
                   (SELECT count(*) FROM bankwatch.alert_event ae WHERE ae.alert_id = a.alert_id) AS evidence_count
            FROM bankwatch.alert a
            JOIN bankwatch.detection_rule r ON r.rule_id = a.rule_id
            JOIN bankwatch.customer_account c ON c.account_id = a.account_id
            LEFT JOIN bankwatch.incident i ON i.alert_id = a.alert_id
            LEFT JOIN bankwatch.operator_user o ON o.operator_id = i.assignee_id
            """;
    // QRY-12: 재계산한 주변 로그 대신 저장된 근거의 전체 복합키로 원본을 조회한다.
    private static final String EVIDENCE = """
            SELECT ae.alert_id::text AS alert_id, e.occurred_at, e.event_id::text AS event_id,
                   e.account_id::text AS account_id, e.device_ref, e.event_type,
                   e.is_new_device, e.ingested_at, ae.is_anchor
            FROM bankwatch.alert_event ae
            JOIN bankwatch.security_event e
              ON e.occurred_at = ae.event_occurred_at AND e.event_id = ae.event_id
            WHERE ae.alert_id = :alert ORDER BY e.occurred_at, e.event_id
            """;
    // QRY-22: 과거 실행자의 현재 활성 여부로 이력을 누락시키지 않는다.
    private static final String HISTORY = """
            SELECT h.history_id::text AS history_id, h.incident_id::text AS incident_id,
                   h.actor_id::text AS actor_id, o.display_name AS actor_name,
                   h.action_type, h.reason, h.created_at
            FROM bankwatch.incident_history h
            JOIN bankwatch.operator_user o ON o.operator_id = h.actor_id
            WHERE h.incident_id = :incident ORDER BY h.created_at, h.history_id
            """;
    private final Db db;

    public AlertQueryService(Db db) { this.db = Objects.requireNonNull(db); }

    public record Cursor(String beforeAt, String beforeId) {}

    public record AlertPage(List<Map<String, Object>> items, boolean hasMore, Cursor nextCursor) {}

    public record AlertDetail(Map<String, Object> alert, List<Map<String, Object>> evidence,
                              List<Map<String, Object>> history) {}

    private record Filter(String where, Map<String, Object> params, int limit) {}

    public AlertPage alerts(long actor, Map<String, String> filters) {
        return read(actor, () -> {
            var filter = parse(filters == null ? Map.of() : filters);
            var params = new LinkedHashMap<>(filter.params());
            params.put("actor", actor);
            params.put("fetchLimit", filter.limit() + 1);
            var rows = db.rows(SUMMARY + filter.where()
                    + " ORDER BY a.detected_at DESC, a.alert_id DESC LIMIT :fetchLimit", params);
            boolean more = rows.size() > filter.limit();
            var items = List.copyOf(rows.subList(0, Math.min(rows.size(), filter.limit())));
            Cursor next = null;
            if (more) {
                var last = items.getLast();
                next = new Cursor((String) last.get("detected_at"), (String) last.get("alert_id"));
            }
            return new AlertPage(items, more, next);
        });
    }

    public AlertDetail detail(long actor, long alert) {
        return read(actor, () -> {
            if (alert <= 0) throw new ApiProblem(400, "경보 ID는 양수여야 합니다.");
            var summary = db.one(SUMMARY + " WHERE a.alert_id = :alert", p("actor", actor, "alert", alert));
            var evidence = db.rows(EVIDENCE, p("alert", alert));
            var history = summary.get("incident_id") == null ? List.<Map<String, Object>>of()
                    : history(Long.parseLong((String) summary.get("incident_id")));
            return new AlertDetail(summary, List.copyOf(evidence), List.copyOf(history));
        });
    }

    private List<Map<String, Object>> history(long incident) {
        return db.rows(HISTORY, p("incident", incident));
    }

    private <T> T read(long actor, Supplier<T> work) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        return db.tx(actor, true, () -> {
            db.jdbc.getJdbcTemplate().execute("SET TRANSACTION READ ONLY");
            var users = db.rows(OPERATOR, p("actor", actor));
            if (users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled"))
                    || !ROLES.contains(users.getFirst().get("role"))) {
                throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
            }
            return work.get();
        });
    }

    private static Filter parse(Map<String, String> filters) {
        if (filters.keySet().stream().anyMatch(key -> key == null || !FILTERS.contains(key))) {
            throw new ApiProblem(400, "지원하지 않는 경보 조회 조건입니다.");
        }
        String account = value(filters, "account");
        String status = value(filters, "status");
        String mine = value(filters, "mine");
        if (account.indexOf('\0') >= 0 || account.codePointCount(0, account.length()) > 40) {
            throw new ApiProblem(400, "고객 참조는 최대 40자이며 NUL을 포함할 수 없습니다.");
        }
        if (!status.isEmpty() && !STATES.containsKey(status)) throw new ApiProblem(400, "사건 상태를 확인하세요.");
        if (!mine.isEmpty() && !Set.of("true", "false").contains(mine)) throw new ApiProblem(400, "mine은 true 또는 false여야 합니다.");
        String beforeAt = value(filters, "beforeAt");
        String beforeId = value(filters, "beforeId");
        if (beforeAt.isEmpty() != beforeId.isEmpty()) throw new ApiProblem(400, "beforeAt과 beforeId를 함께 입력하세요.");
        long limit = value(filters, "limit").isEmpty() ? 20 : positive(value(filters, "limit"), "limit");
        if (limit > 100) throw new ApiProblem(400, "페이지 크기는 1~100이어야 합니다.");
        Map<String, Object> params = new LinkedHashMap<>();
        var where = new StringBuilder(" WHERE true");
        if (!account.isEmpty()) { where.append(" AND c.customer_ref = :account"); params.put("account", account); }
        if (!status.isEmpty()) where.append(STATES.get(status));
        if ("true".equals(mine)) where.append(" AND i.assignee_id = :actor");
        if (!beforeAt.isEmpty()) {
            where.append(" AND (a.detected_at, a.alert_id) < (:beforeAt, :beforeId)");
            params.put("beforeAt", time(beforeAt));
            params.put("beforeId", positive(beforeId, "beforeId"));
        }
        return new Filter(where.toString(), Map.copyOf(params), (int) limit);
    }

    private static String value(Map<String, String> filters, String key) {
        String value = filters.get(key);
        return value == null || value.isBlank() ? "" : value;
    }

    private static long positive(String value, String key) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            long result = Long.parseLong(value);
            if (result <= 0) throw new NumberFormatException();
            return result;
        } catch (NumberFormatException error) {
            throw new ApiProblem(400, key + "는 bigint 범위의 양의 정수여야 합니다.");
        }
    }

    private static OffsetDateTime time(String value) {
        var fraction = FRACTION.matcher(value);
        if (!OFFSET.matcher(value).find() || fraction.find() && fraction.group(1).length() > 6) {
            throw new ApiProblem(400, "커서 시각은 UTC 오프셋을 포함하고 소수 초는 최대 6자리여야 합니다.");
        }
        try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException error) { throw new ApiProblem(400, "커서의 ISO 8601 시각을 확인하세요."); }
    }
}
