package kr.ac.bankwatch.detection;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.stereotype.Service;
import static kr.ac.bankwatch.Db.p;

@Service
public class DetectionService {
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
    private static final Pattern OFFSET = Pattern.compile("(?:Z|[+-]\\d{2}:\\d{2})$", Pattern.CASE_INSENSITIVE);
    // QRY-01: actor는 Controller가 인증 세션에서 얻으며 활성 ADMIN만 탐지한다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    // QRY-07: 수정 가능한 규칙 목록이 아니라 고정 R02 시드 계약을 확인한다.
    private static final String RULE = """
            SELECT rule_id, rule_code, window_seconds, threshold_count
            FROM bankwatch.detection_rule ORDER BY rule_id
            """;
    // QRY-08: 성공·실패를 함께 윈도우에 넣고 같은 시각의 모든 행을 제외한다.
    private static final String CANDIDATES = """
            WITH bucketed AS (
                SELECT occurred_at, event_id, account_id, event_type, is_new_device,
                       date_bin(INTERVAL '10 minutes', occurred_at,
                           TIMESTAMPTZ '2000-01-01 00:00:00+09:00') AS bucket_start
                FROM bankwatch.security_event
                WHERE occurred_at >= :from AND occurred_at < :to
            ), windowed AS (
                SELECT bucketed.*,
                       count(*) FILTER (WHERE event_type = 'LOGIN_FAILED') OVER (
                           PARTITION BY account_id, bucket_start ORDER BY occurred_at
                           RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW EXCLUDE GROUP
                       ) AS preceding_failures
                FROM bucketed
            ), ranked AS (
                SELECT account_id, bucket_start, occurred_at, event_id, preceding_failures,
                       row_number() OVER (
                           PARTITION BY account_id, bucket_start ORDER BY occurred_at, event_id
                       ) AS position,
                       count(*) OVER (PARTITION BY account_id, bucket_start) AS candidate_count
                FROM windowed
                WHERE event_type = 'LOGIN_SUCCESS' AND is_new_device AND preceding_failures >= :threshold
            )
            SELECT account_id, bucket_start, occurred_at AS anchor_at, event_id AS anchor_id,
                   preceding_failures, candidate_count
            FROM ranked WHERE position = 1 ORDER BY bucket_start, account_id
            """;
    // QRY-09: 신규 경보만 RETURNING한다. 기존 경보와 최초 근거는 변경하지 않는다.
    private static final String ALERT = """
            INSERT INTO bankwatch.alert (rule_id, account_id, bucket_start)
            VALUES (:rule, :account, :bucket)
            ON CONFLICT (rule_id, account_id, bucket_start) DO NOTHING RETURNING alert_id
            """;
    // QRY-10: 원본 복합키 전체를 저장하며 대표와 같은 시각의 실패는 포함하지 않는다.
    private static final String EVIDENCE = """
            INSERT INTO bankwatch.alert_event (alert_id, event_occurred_at, event_id, is_anchor)
            SELECT :alert, e.occurred_at, e.event_id,
                   e.occurred_at = :anchorAt AND e.event_id = :anchorId
            FROM bankwatch.security_event e
            WHERE e.account_id = :account
              AND e.occurred_at >= :bucket AND e.occurred_at < :bucket + INTERVAL '10 minutes'
              AND ((e.event_type = 'LOGIN_FAILED' AND e.occurred_at < :anchorAt)
                   OR (e.occurred_at = :anchorAt AND e.event_id = :anchorId
                       AND e.event_type = 'LOGIN_SUCCESS' AND e.is_new_device))
            ORDER BY e.occurred_at, e.event_id
            """;
    // QRY-23: 실제 저장된 경보·근거·원본을 재조회하는 무결성 검증 질의다.
    private static final String CHECK_EVIDENCE = """
            WITH facts AS (
                SELECT ae.is_anchor, e.occurred_at, e.event_id, e.event_type, e.is_new_device,
                       (a.rule_id = :rule AND a.account_id = :account AND a.bucket_start = :bucket
                        AND e.account_id = a.account_id AND e.occurred_at >= a.bucket_start
                        AND e.occurred_at < a.bucket_start + INTERVAL '10 minutes') AS in_scope
                FROM bankwatch.alert_event ae
                JOIN bankwatch.alert a ON a.alert_id = ae.alert_id
                JOIN bankwatch.security_event e
                  ON e.occurred_at = ae.event_occurred_at AND e.event_id = ae.event_id
                WHERE ae.alert_id = :alert
            )
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE in_scope AND is_anchor AND occurred_at = :anchorAt
                       AND event_id = :anchorId AND event_type = 'LOGIN_SUCCESS' AND is_new_device) AS anchors,
                   count(*) FILTER (WHERE in_scope AND NOT is_anchor
                       AND event_type = 'LOGIN_FAILED' AND occurred_at < :anchorAt) AS failures
            FROM facts
            """;
    private final Db db;

    public DetectionService(Db db) { this.db = Objects.requireNonNull(db); }

    public record DetectionResult(long candidateSuccesses, int candidateBuckets, int createdAlerts,
                                  int existingAlerts, long insertedEvidenceRows) {}

    private record Period(OffsetDateTime from, OffsetDateTime to) {}

    public DetectionResult detect(long actor, String from, String to) {
        requireAdmin(actor);
        var period = period(from, to);
        // 충돌 시 재시도는 Db.tx가 전체 작업을 새 트랜잭션에서 수행한다.
        return db.tx(actor, true, () -> run(actor, period));
    }

    private DetectionResult run(long actor, Period period) {
        requireAdmin(actor);
        long rule = rule();
        var candidates = db.rows(CANDIDATES, p("from", period.from(), "to", period.to(), "threshold", 5));
        long successes = 0;
        long evidence = 0;
        int created = 0;
        for (var candidate : candidates) {
            successes += number(candidate, "candidate_count");
            var params = p("rule", rule, "account", number(candidate, "account_id"),
                    "bucket", OffsetDateTime.parse((String) candidate.get("bucket_start")),
                    "anchorAt", OffsetDateTime.parse((String) candidate.get("anchor_at")),
                    "anchorId", number(candidate, "anchor_id"));
            var inserted = db.rows(ALERT, params);
            if (inserted.isEmpty()) continue;
            params.put("alert", number(inserted.getFirst(), "alert_id"));
            int count = db.jdbc.update(EVIDENCE, params);
            long failures = number(candidate, "preceding_failures");
            var stored = db.one(CHECK_EVIDENCE, params);
            if (count != failures + 1 || number(stored, "total") != count
                    || number(stored, "anchors") != 1 || number(stored, "failures") != failures) {
                throw new IllegalStateException("경보 근거 무결성 검사가 실패했습니다.");
            }
            created++;
            evidence += count;
        }
        return new DetectionResult(successes, candidates.size(), created, candidates.size() - created, evidence);
    }

    private void requireAdmin(long actor) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        var users = db.rows(OPERATOR, p("actor", actor));
        if (users.isEmpty() || !Boolean.TRUE.equals(users.getFirst().get("enabled"))
                || !"ADMIN".equals(users.getFirst().get("role"))) {
            throw new ApiProblem(403, "활성 관리자 권한이 필요합니다.");
        }
    }

    private long rule() {
        var rules = db.rows(RULE, p());
        if (rules.size() != 1 || !"R02".equals(rules.getFirst().get("rule_code"))
                || number(rules.getFirst(), "window_seconds") != 600
                || number(rules.getFirst(), "threshold_count") != 5) {
            throw new ApiProblem(409, "고정 R02 규칙이 준비되지 않았습니다. DB 초기화를 확인하세요.");
        }
        return number(rules.getFirst(), "rule_id");
    }

    private static Period period(String from, String to) {
        var start = time(from, "from");
        var end = time(to, "to");
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofHours(24)) > 0) {
            throw new ApiProblem(400, "탐지 시작은 끝보다 앞서야 하고 기간은 최대 24시간입니다.");
        }
        return new Period(start, end);
    }

    private static OffsetDateTime time(String value, String key) {
        if (value == null || value.isBlank()) throw new ApiProblem(400, key + " 시각이 필요합니다.");
        var fraction = FRACTION.matcher(value);
        if (!OFFSET.matcher(value).find() || fraction.find() && fraction.group(1).length() > 6) {
            throw new ApiProblem(400, key + "는 UTC 오프셋을 포함하고 소수 초는 최대 6자리여야 합니다.");
        }
        OffsetDateTime time;
        try { time = OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException error) { throw new ApiProblem(400, key + "의 ISO 8601 시각을 확인하세요."); }
        var kst = time.withOffsetSameInstant(ZoneOffset.ofHours(9));
        if (kst.getMinute() % 10 != 0 || kst.getSecond() != 0 || kst.getNano() != 0) {
            throw new ApiProblem(400, "탐지 시각은 KST 10분 경계여야 합니다.");
        }
        return time;
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
