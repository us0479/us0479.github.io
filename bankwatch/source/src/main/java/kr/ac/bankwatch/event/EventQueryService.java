package kr.ac.bankwatch.event;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.stereotype.Service;
import static kr.ac.bankwatch.Db.p;

@Service
public class EventQueryService {
    private static final Set<String> FILTERS = Set.of("account", "type", "from", "to", "beforeAt", "beforeId", "limit");
    private static final Set<String> TYPES = Set.of("LOGIN_FAILED", "LOGIN_SUCCESS");
    private static final Set<String> ROLES = Set.of("ADMIN", "ANALYST");
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
    private static final Pattern OFFSET = Pattern.compile("(?:Z|[+-]\\d{2}:\\d{2})$", Pattern.CASE_INSENSITIVE);
    // QRY-01: actor는 Controller가 인증 세션에서 얻는다. 두 활성 역할의 조회를 허용한다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    private static final String SOURCE = """
            FROM bankwatch.security_event e
            JOIN bankwatch.customer_account c ON c.account_id = e.account_id
            """;
    // QRY-05: 응답의 bigint ID는 문자열로 보존하고, 정렬·커서는 원본 bigint를 사용한다.
    private static final String ITEMS = """
            SELECT e.occurred_at, e.event_id::text AS event_id, e.account_id::text AS account_id,
                   c.customer_ref, c.display_name AS customer_name, e.device_ref,
                   e.event_type, e.is_new_device, e.ingested_at
            """;
    // QRY-06: 고정 KST 10분 구간·결과별 집계. 커서와 LIMIT를 적용하지 않는다.
    private static final String COUNTS = """
            SELECT date_bin(INTERVAL '10 minutes', e.occurred_at,
                       TIMESTAMPTZ '2000-01-01 00:00:00+09:00') AS bucket_start,
                   e.event_type, count(*) AS event_count
            """;
    private final Db db;

    public EventQueryService(Db db) { this.db = Objects.requireNonNull(db); }

    public record Cursor(String beforeAt, String beforeId) {}

    public record QueryResult(List<Map<String, Object>> items, long total, boolean hasMore,
                              Cursor nextCursor, List<Map<String, Object>> aggregates) {}

    private record Filter(String where, Map<String, Object> params, OffsetDateTime beforeAt,
                          Long beforeId, int limit) {}

    public QueryResult events(long actor, Map<String, String> filters) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        var filter = parse(filters == null ? Map.of() : filters);
        // 한 응답의 목록과 집계는 같은 스냅샷이다. 페이지 요청 사이에는 새 스냅샷을 연다.
        return db.tx(actor, true, () -> {
            db.jdbc.getJdbcTemplate().execute("SET TRANSACTION READ ONLY");
            requireViewer(actor);
            var params = new LinkedHashMap<>(filter.params());
            var aggregates = db.rows(COUNTS + SOURCE + filter.where()
                    + " GROUP BY 1, e.event_type ORDER BY 1, e.event_type", params);
            long total = aggregates.stream().mapToLong(row -> ((Number) row.get("event_count")).longValue()).sum();
            String cursor = "";
            if (filter.beforeAt() != null) {
                cursor = " AND (e.occurred_at, e.event_id) < (:beforeAt, :beforeId)";
                params.put("beforeAt", filter.beforeAt());
                params.put("beforeId", filter.beforeId());
            }
            params.put("fetchLimit", filter.limit() + 1);
            var rows = db.rows(ITEMS + SOURCE + filter.where() + cursor
                    + " ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT :fetchLimit", params);
            boolean hasMore = rows.size() > filter.limit();
            var items = List.copyOf(rows.subList(0, Math.min(rows.size(), filter.limit())));
            Cursor next = null;
            if (hasMore) {
                var last = items.getLast();
                next = new Cursor((String) last.get("occurred_at"), (String) last.get("event_id"));
            }
            return new QueryResult(items, total, hasMore, next, List.copyOf(aggregates));
        });
    }

    private void requireViewer(long actor) {
        var users = db.rows(OPERATOR, p("actor", actor));
        if (users.isEmpty() || !Boolean.TRUE.equals(users.get(0).get("enabled"))
                || !ROLES.contains(users.get(0).get("role"))) {
            throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
        }
    }

    private static Filter parse(Map<String, String> filters) {
        if (filters.keySet().stream().anyMatch(key -> key == null || !FILTERS.contains(key))) {
            throw new ApiProblem(400, "지원하지 않는 로그 조회 조건입니다.");
        }
        String account = value(filters, "account");
        String type = value(filters, "type");
        if (account.indexOf('\0') >= 0 || account.codePointCount(0, account.length()) > 40) {
            throw new ApiProblem(400, "고객 참조는 최대 40자이며 NUL을 포함할 수 없습니다.");
        }
        if (!type.isEmpty() && !TYPES.contains(type)) throw new ApiProblem(400, "로그 결과는 LOGIN_FAILED 또는 LOGIN_SUCCESS여야 합니다.");
        var from = time(value(filters, "from"), "from");
        var to = time(value(filters, "to"), "to");
        if (from != null && to != null && !from.isBefore(to)) throw new ApiProblem(400, "시작 시각은 끝 시각보다 앞서야 합니다.");
        String beforeAt = value(filters, "beforeAt");
        String beforeId = value(filters, "beforeId");
        if (beforeAt.isEmpty() != beforeId.isEmpty()) throw new ApiProblem(400, "beforeAt과 beforeId를 함께 입력하세요.");
        var cursorTime = time(beforeAt, "beforeAt");
        Long cursorId = beforeId.isEmpty() ? null : positive(beforeId, "beforeId");
        long requestedLimit = value(filters, "limit").isEmpty() ? 20 : positive(value(filters, "limit"), "limit");
        if (requestedLimit > 100) throw new ApiProblem(400, "페이지 크기는 1~100이어야 합니다.");
        Map<String, Object> params = new LinkedHashMap<>();
        var where = new StringBuilder(" WHERE true");
        if (!account.isEmpty()) { where.append(" AND c.customer_ref = :account"); params.put("account", account); }
        if (!type.isEmpty()) { where.append(" AND e.event_type = :type"); params.put("type", type); }
        if (from != null) { where.append(" AND e.occurred_at >= :from"); params.put("from", from); }
        if (to != null) { where.append(" AND e.occurred_at < :to"); params.put("to", to); }
        return new Filter(where.toString(), Map.copyOf(params), cursorTime, cursorId, (int) requestedLimit);
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

    private static OffsetDateTime time(String value, String key) {
        if (value.isEmpty()) return null;
        var fraction = FRACTION.matcher(value);
        if (!OFFSET.matcher(value).find() || fraction.find() && fraction.group(1).length() > 6) {
            throw new ApiProblem(400, key + "는 UTC 오프셋을 포함하고 소수 초는 최대 6자리여야 합니다.");
        }
        try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException error) { throw new ApiProblem(400, key + "의 ISO 8601 시각을 확인하세요."); }
    }
}
