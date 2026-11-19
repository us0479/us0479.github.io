package kr.ac.bankwatch.event;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import static kr.ac.bankwatch.Db.p;

@RestController
public class EventController {
    // QRY-01: 로그인 이름은 Spring Security의 Principal에서만 받는다.
    private static final String OPERATOR_ID = """
            SELECT operator_id FROM bankwatch.operator_user
            WHERE login_id = :login AND enabled
            """;
    private final Db db;
    private final EventService imports;
    private final EventQueryService queries;

    public EventController(Db db, EventService imports, EventQueryService queries) {
        this.db = Objects.requireNonNull(db);
        this.imports = Objects.requireNonNull(imports);
        this.queries = Objects.requireNonNull(queries);
    }

    @GetMapping("/logs")
    public EventQueryService.QueryResult logs(Principal principal,
                                              @RequestParam MultiValueMap<String, String> parameters) {
        long actor = operatorId(principal);
        return queries.events(actor, filters(parameters));
    }

    @PostMapping("/logs/import")
    public EventService.ImportResult importCsv(Principal principal,
            @RequestParam(name = "file", required = false) List<MultipartFile> files) {
        long actor = operatorId(principal);
        if (files == null || files.isEmpty()) throw new ApiProblem(400, "CSV 파일을 선택하세요.");
        if (files.size() != 1) throw new ApiProblem(400, "CSV 파일은 한 번에 하나만 입력하세요.");
        return imports.importCsv(actor, files.getFirst());
    }

    private long operatorId(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new ApiProblem(401, "로그인이 필요합니다.");
        }
        var users = db.rows(OPERATOR_ID, p("login", principal.getName()));
        if (users.isEmpty()) throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
        return ((Number) users.getFirst().get("operator_id")).longValue();
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
