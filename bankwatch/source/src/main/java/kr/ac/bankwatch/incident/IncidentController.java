package kr.ac.bankwatch.incident;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import static kr.ac.bankwatch.Db.p;

@RestController
public class IncidentController {
    private static final Set<String> ACTION_FIELDS = Set.of("version", "reason");
    private static final Set<String> CLOSE_FIELDS = Set.of("version", "reason", "resolution");
    // QRY-01: 요청 본문·쿼리의 actor 대신 인증 세션의 로그인 이름으로 운영자를 찾는다.
    private static final String OPERATOR_ID = """
            SELECT operator_id FROM bankwatch.operator_user
            WHERE login_id = :login AND enabled
            """;
    private final Db db;
    private final IncidentClaimService claims;
    private final IncidentActionService actions;

    public IncidentController(Db db, IncidentClaimService claims, IncidentActionService actions) {
        this.db = Objects.requireNonNull(db);
        this.claims = Objects.requireNonNull(claims);
        this.actions = Objects.requireNonNull(actions);
    }

    // 선점 입력은 없으므로 빈 본문 또는 빈 JSON 객체만 허용한다.
    @PostMapping(value = "/alerts/{alertId}/claim", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IncidentClaimService.ClaimResult claim(Principal principal, @PathVariable("alertId") long alertId,
                                                 @RequestBody(required = false) String body) {
        long actor = operatorId(principal);
        if (body != null) object(body, Set.of());
        return claims.claim(actor, alertId);
    }

    @PostMapping(value = "/incidents/{incidentId}/lock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IncidentActionService.ActionResult lock(Principal principal, @PathVariable("incidentId") long incidentId,
                                                   @RequestBody String body) {
        long actor = operatorId(principal);
        var input = input(body, false);
        return actions.lock(actor, incidentId, input.version(), input.reason());
    }

    @PostMapping(value = "/incidents/{incidentId}/unlock", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IncidentActionService.ActionResult unlock(Principal principal, @PathVariable("incidentId") long incidentId,
                                                     @RequestBody String body) {
        long actor = operatorId(principal);
        var input = input(body, false);
        return actions.unlock(actor, incidentId, input.version(), input.reason());
    }

    @PostMapping(value = "/incidents/{incidentId}/close", consumes = MediaType.APPLICATION_JSON_VALUE)
    public IncidentActionService.ActionResult close(Principal principal, @PathVariable("incidentId") long incidentId,
                                                    @RequestBody String body) {
        long actor = operatorId(principal);
        var input = input(body, true);
        return actions.close(actor, incidentId, input.version(), input.reason(), input.resolution());
    }

    private long operatorId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ApiProblem(401, "로그인이 필요합니다.");
        }
        var users = db.rows(OPERATOR_ID, p("login", principal.getName()));
        if (users.isEmpty()) throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
        return ((Number) users.getFirst().get("operator_id")).longValue();
    }

    private record ActionInput(int version, String reason, String resolution) {}

    private ActionInput input(String body, boolean closing) {
        var values = object(body, closing ? CLOSE_FIELDS : ACTION_FIELDS);
        if (!(values.get("version") instanceof Integer version) || version < 0
                || !(values.get("reason") instanceof String reason)) {
            throw new ApiProblem(400, "version은 0 이상의 정수, reason은 문자열이어야 합니다.");
        }
        String resolution = null;
        if (closing) {
            if (!(values.get("resolution") instanceof String value)) {
                throw new ApiProblem(400, "종료에는 resolution 문자열이 필요합니다.");
            }
            resolution = value;
        }
        return new ActionInput(version, reason, resolution);
    }

    private Map<?, ?> object(String body, Set<String> fields) {
        Map<?, ?> values;
        try {
            values = db.json.readerFor(Map.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (JacksonException error) {
            throw new ApiProblem(400, "중복 키 없는 JSON 객체를 입력하세요.");
        }
        if (values == null || !values.keySet().equals(fields)) {
            throw new ApiProblem(400, "이 요청에 허용된 항목을 빠짐없이 입력하세요.");
        }
        return values;
    }
}
