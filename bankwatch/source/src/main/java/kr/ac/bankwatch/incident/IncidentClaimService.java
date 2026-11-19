package kr.ac.bankwatch.incident;

import java.util.Objects;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.stereotype.Service;
import static kr.ac.bankwatch.Db.p;

@Service
public class IncidentClaimService {
    private static final String CLAIM_REASON = "사건 담당자로 배정";
    // QRY-01: actor는 Controller가 인증 세션에서 얻는다. 선점은 활성 ANALYST만 가능하다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    // QRY-13: 앱에 경보 UPDATE 권한을 추가하지 않고 해당 경보 한 행만 잠근다.
    private static final String LOCK_ALERT = """
            SELECT bankwatch.lock_alert_for_claim(:alert)
            """;
    // QRY-14: 잠금 함수와 별도 문장으로 실행해 대기 중 커밋한 승자의 사건을 읽는다.
    private static final String CURRENT = """
            SELECT a.alert_id, a.account_id, c.customer_ref, i.incident_id,
                   i.assignee_id, o.display_name AS assignee_name, i.closed_at, i.version
            FROM bankwatch.alert a
            JOIN bankwatch.customer_account c ON c.account_id = a.account_id
            LEFT JOIN bankwatch.incident i ON i.alert_id = a.alert_id
            LEFT JOIN bankwatch.operator_user o ON o.operator_id = i.assignee_id
            WHERE a.alert_id = :alert
            """;
    // QRY-15: 담당자는 요청 폼의 값이 아니라 현재 세션의 운영자다.
    private static final String INSERT_INCIDENT = """
            INSERT INTO bankwatch.incident (alert_id, assignee_id) VALUES (:alert, :actor)
            RETURNING incident_id, alert_id, assignee_id, version
            """;
    // QRY-16: CLAIM 사유는 앱의 고정 문구이고, 이력은 사건과 함께 커밋한다.
    private static final String INSERT_HISTORY = """
            INSERT INTO bankwatch.incident_history (incident_id, actor_id, action_type, reason)
            VALUES (:incident, :actor, 'CLAIM', :reason)
            """;
    private final Db db;

    public IncidentClaimService(Db db) { this.db = Objects.requireNonNull(db); }

    public record ClaimResult(String incidentId, String alertId, String assigneeId, int version) {}

    public ClaimResult claim(long actor, long alert) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        return db.tx(actor, false, () -> {
            requireAnalyst(actor);
            if (alert <= 0) throw new ApiProblem(400, "경보 ID는 양수여야 합니다.");
            if (!Boolean.TRUE.equals(db.scalar(LOCK_ALERT, p("alert", alert)))) {
                throw new ApiProblem(404, "경보를 찾을 수 없습니다.");
            }
            // 잠금 대기 중 비활성화되거나 역할이 바뀐 운영자는 저장하지 않는다.
            requireAnalyst(actor);
            var current = db.one(CURRENT, p("alert", alert));
            if (current.get("incident_id") != null) {
                String assignee = Objects.toString(current.get("assignee_name"), "기존 담당자");
                throw new ApiProblem(409, "이미 배정된 사건입니다. 현재 담당자: " + assignee + ". 상세를 새로 조회하세요.");
            }
            var created = db.one(INSERT_INCIDENT, p("alert", alert, "actor", actor));
            long incident = ((Number) created.get("incident_id")).longValue();
            int written = db.jdbc.update(INSERT_HISTORY, p("incident", incident, "actor", actor, "reason", CLAIM_REASON));
            if (written != 1) throw new IllegalStateException("CLAIM 이력이 정확히 한 건 저장되지 않았습니다.");
            return new ClaimResult(Long.toString(incident), Long.toString(((Number) created.get("alert_id")).longValue()),
                    Long.toString(((Number) created.get("assignee_id")).longValue()), ((Number) created.get("version")).intValue());
        });
    }

    private void requireAnalyst(long actor) {
        var users = db.rows(OPERATOR, p("actor", actor));
        if (users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled"))
                || !"ANALYST".equals(users.getFirst().get("role"))) {
            throw new ApiProblem(403, "활성 관제 담당자 권한이 필요합니다.");
        }
    }
}
