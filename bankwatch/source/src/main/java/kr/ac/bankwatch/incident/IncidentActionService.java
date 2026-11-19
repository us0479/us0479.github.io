package kr.ac.bankwatch.incident;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.stereotype.Service;
import static kr.ac.bankwatch.Db.p;

@Service
public class IncidentActionService {
    private static final Set<String> RESOLUTIONS = Set.of("CONFIRMED", "FALSE_POSITIVE", "INCONCLUSIVE");
    // QRY-01: actor는 Controller가 인증 세션에서 얻는다. 잠금 대기 뒤에도 다시 검사한다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    // QRY-14 변형: 대상 고객을 찾고, 두 행을 잠근 뒤 관계와 현재 상태를 다시 읽는다.
    private static final String CURRENT = """
            SELECT i.incident_id, i.alert_id, i.assignee_id, i.closed_at, i.resolution, i.version,
                   a.account_id, c.lock_incident_id, la.account_id AS lock_account_id
            FROM bankwatch.incident i
            JOIN bankwatch.alert a ON a.alert_id = i.alert_id
            JOIN bankwatch.customer_account c ON c.account_id = a.account_id
            LEFT JOIN bankwatch.incident li ON li.incident_id = c.lock_incident_id
            LEFT JOIN bankwatch.alert la ON la.alert_id = li.alert_id
            WHERE i.incident_id = :incident
            """;
    // QRY-17, 18: 모든 조치에서 반드시 고객 한 행 → 사건 한 행 순서로 잠근다.
    private static final String LOCK_CUSTOMER = """
            SELECT account_id FROM bankwatch.customer_account WHERE account_id = :account FOR UPDATE
            """;
    private static final String LOCK_INCIDENT = """
            SELECT incident_id FROM bankwatch.incident WHERE incident_id = :incident FOR UPDATE
            """;
    // QRY-19: 잠금 원인은 확인한 이전 값과 일치할 때만 바꾼다. 종료에서는 실행하지 않는다.
    private static final String CHANGE_CAUSE = """
            UPDATE bankwatch.customer_account SET lock_incident_id = :cause
            WHERE account_id = :account AND lock_incident_id IS NOT DISTINCT FROM :previous
            """;
    // QRY-20: 보호 잠금·해제의 버전 증가. 오래된 버전은 추가 이력 없이 거절한다.
    private static final String BUMP_VERSION = """
            UPDATE bankwatch.incident SET version = version + 1
            WHERE incident_id = :incident AND version = :version
            RETURNING version, closed_at, resolution
            """;
    // QRY-21: 종료 시각·판정·버전을 하나의 UPDATE로 변경한다. 계정 잠금은 유지한다.
    private static final String CLOSE_INCIDENT = """
            UPDATE bankwatch.incident
            SET closed_at = pg_catalog.clock_timestamp(), resolution = :resolution, version = version + 1
            WHERE incident_id = :incident AND version = :version AND closed_at IS NULL
            RETURNING version, closed_at, resolution
            """;
    // QRY-16 변형: 원인 변경·버전 변경·조치 이력은 같은 트랜잭션에서 함께 커밋한다.
    private static final String INSERT_HISTORY = """
            INSERT INTO bankwatch.incident_history (incident_id, actor_id, action_type, reason)
            VALUES (:incident, :actor, :action, :reason)
            """;
    private final Db db;

    public IncidentActionService(Db db) { this.db = Objects.requireNonNull(db); }

    public record ActionResult(String incidentId, int version, String processingStatus, String accessStatus,
                               String lockIncidentId, String closedAt, String resolution) {}

    private enum Action { LOCK_ACCOUNT, UNLOCK_ACCOUNT, CLOSE }

    // FUNC-07: 담당 ANALYST의 조사 중 사건에서 보호 잠금한다.
    public ActionResult lock(long actor, long incident, int version, String reason) {
        return act(actor, incident, version, reason, null, Action.LOCK_ACCOUNT);
    }

    // FUNC-08: ADMIN은 종료 여부와 관계없이 현재 원인 사건에서만 해제할 수 있다.
    public ActionResult unlock(long actor, long incident, int version, String reason) {
        return act(actor, incident, version, reason, null, Action.UNLOCK_ACCOUNT);
    }

    // FUNC-09: 종료는 계정 접근 상태와 독립적이며 재개·자동 해제를 수행하지 않는다.
    public ActionResult close(long actor, long incident, int version, String reason, String resolution) {
        return act(actor, incident, version, reason, resolution, Action.CLOSE);
    }

    private ActionResult act(long actor, long incident, int version, String reason, String resolution, Action action) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        return db.tx(actor, false, () -> {
            requireRole(actor, action);
            if (incident <= 0 || version < 0) throw new ApiProblem(400, "양수 사건 ID와 0 이상의 버전이 필요합니다.");
            String checkedReason = checkedReason(reason);
            if (action == Action.CLOSE && (resolution == null || !RESOLUTIONS.contains(resolution))) {
                throw new ApiProblem(400, "종료 판정은 CONFIRMED, FALSE_POSITIVE, INCONCLUSIVE 중 하나여야 합니다.");
            }
            var args = p("incident", incident, "actor", actor, "version", version,
                    "reason", checkedReason, "resolution", resolution, "action", action.name());
            long account = number(db.one(CURRENT, args), "account_id");
            db.one(LOCK_CUSTOMER, p("account", account));
            db.one(LOCK_INCIDENT, args);
            requireRole(actor, action);
            var current = db.one(CURRENT, args);
            validateCurrent(current, actor, account, version, action);
            Long previous = nullableNumber(current.get("lock_incident_id"));
            Long cause = previous;
            if (action != Action.CLOSE) {
                cause = action == Action.LOCK_ACCOUNT ? incident : null;
                int changed = db.jdbc.update(CHANGE_CAUSE,
                        p("account", account, "cause", cause, "previous", previous));
                if (changed != 1) throw conflict();
            }
            var updated = db.rows(action == Action.CLOSE ? CLOSE_INCIDENT : BUMP_VERSION, args);
            if (updated.size() != 1) throw conflict();
            int written = db.jdbc.update(INSERT_HISTORY, args);
            if (written != 1) throw new IllegalStateException("조치 이력이 정확히 한 건 저장되지 않았습니다.");
            var saved = updated.getFirst();
            String closedAt = (String) saved.get("closed_at");
            return new ActionResult(Long.toString(incident), ((Number) saved.get("version")).intValue(),
                    closedAt == null ? "IN_PROGRESS" : "CLOSED", cause == null ? "ACTIVE" : "LOCKED",
                    cause == null ? null : Long.toString(cause), closedAt, (String) saved.get("resolution"));
        });
    }

    private void validateCurrent(Map<String, Object> current, long actor, long account, int version, Action action) {
        if (number(current, "account_id") != account) throw conflict();
        Long cause = nullableNumber(current.get("lock_incident_id"));
        if (cause != null && !Objects.equals(nullableNumber(current.get("lock_account_id")), account)) {
            throw new ApiProblem(409, "잠금 원인 사건과 고객이 일치하지 않습니다. 상태를 확인하세요.");
        }
        if (action != Action.UNLOCK_ACCOUNT && number(current, "assignee_id") != actor) {
            throw new ApiProblem(403, "본인이 맡은 사건만 변경할 수 있습니다.");
        }
        if (number(current, "version") != version) throw conflict();
        if (version == Integer.MAX_VALUE) throw new ApiProblem(409, "사건 버전 한도에 도달했습니다.");
        if (action != Action.UNLOCK_ACCOUNT && current.get("closed_at") != null) {
            throw new ApiProblem(409, "이미 종료된 사건입니다.");
        }
        if (action == Action.LOCK_ACCOUNT && cause != null) throw new ApiProblem(409, "이미 보호 잠금된 계정입니다.");
        if (action == Action.UNLOCK_ACCOUNT && !Objects.equals(cause, number(current, "incident_id"))) {
            throw new ApiProblem(409, "현재 잠금의 원인 사건에서 해제해야 합니다.");
        }
    }

    private void requireRole(long actor, Action action) {
        String role = action == Action.UNLOCK_ACCOUNT ? "ADMIN" : "ANALYST";
        var users = db.rows(OPERATOR, p("actor", actor));
        if (users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled"))
                || !role.equals(users.getFirst().get("role"))) {
            throw new ApiProblem(403, role.equals("ADMIN") ? "활성 관리자 권한이 필요합니다." : "활성 관제 담당자 권한이 필요합니다.");
        }
    }

    private static String checkedReason(String reason) {
        if (reason == null) throw new ApiProblem(400, "1~2,000자의 조치 사유가 필요합니다.");
        String value = reason.strip();
        if (value.codePointCount(0, value.length()) > 2000
                || value.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))
                || value.codePoints().anyMatch(c -> c == 0 || (c >= 0xD800 && c <= 0xDFFF))) {
            throw new ApiProblem(400, "1~2,000자의 유효한 조치 사유가 필요합니다.");
        }
        return value;
    }

    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static Long nullableNumber(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static ApiProblem conflict() { return new ApiProblem(409, "사건이 변경되었습니다. 상세를 새로 조회하세요."); }
}
