package kr.ac.bankwatch.detection;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import static kr.ac.bankwatch.Db.p;

@RestController
public class DetectionController {
    private static final Set<String> PERIOD_FIELDS = Set.of("from", "to");
    // QRY-01: 운영자 ID는 인증 Principal의 로그인 이름으로 조회한다.
    private static final String OPERATOR_ID = """
            SELECT operator_id FROM bankwatch.operator_user
            WHERE login_id = :login AND enabled
            """;
    private final Db db;
    private final DetectionService detection;
    private final AlertQueryService queries;

    public DetectionController(Db db, DetectionService detection, AlertQueryService queries) {
        this.db = Objects.requireNonNull(db);
        this.detection = Objects.requireNonNull(detection);
        this.queries = Objects.requireNonNull(queries);
    }

    @GetMapping("/alerts")
    public AlertQueryService.AlertPage alerts(Principal principal,
                                              @RequestParam MultiValueMap<String, String> parameters) {
        return queries.alerts(operatorId(principal), filters(parameters));
    }

    @GetMapping("/alerts/{alertId}")
    public AlertQueryService.AlertDetail detail(Principal principal, @PathVariable("alertId") long alertId) {
        return queries.detail(operatorId(principal), alertId);
    }

    @PostMapping(value = "/alerts/detect", consumes = MediaType.APPLICATION_JSON_VALUE)
    public DetectionService.DetectionResult detect(Principal principal, @RequestBody String body) {
        long actor = operatorId(principal);
        var period = period(body);
        return detection.detect(actor, period.get("from"), period.get("to"));
    }

    private long operatorId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ApiProblem(401, "로그인이 필요합니다.");
        }
        var users = db.rows(OPERATOR_ID, p("login", principal.getName()));
        if (users.isEmpty()) throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
        return ((Number) users.getFirst().get("operator_id")).longValue();
    }

    private Map<String, String> period(String body) {
        Map<?, ?> values;
        try {
            values = db.json.readerFor(Map.class).with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(body);
        } catch (JacksonException error) {
            throw new ApiProblem(400, "중복 키 없이 from과 to를 담은 JSON 객체를 입력하세요.");
        }
        if (values == null || !values.keySet().equals(PERIOD_FIELDS)
                || !(values.get("from") instanceof String from) || !(values.get("to") instanceof String to)) {
            throw new ApiProblem(400, "탐지 입력은 from과 to 두 시각 문자열만 허용합니다.");
        }
        return Map.of("from", from, "to", to);
    }

    private static Map<String, String> filters(MultiValueMap<String, String> parameters) {
        Map<String, String> result = new LinkedHashMap<>();
        parameters.forEach((key, values) -> {
            if (values.size() != 1) throw new ApiProblem(400, "조회 조건은 항목마다 한 번만 입력하세요.");
            result.put(key, values.getFirst());
        });
        return result;
    }
}
