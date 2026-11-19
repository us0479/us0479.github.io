package kr.ac.bankwatch;

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import static kr.ac.bankwatch.Db.p;

@Controller
public class ApiController {
    // QRY-01: 비밀번호 해시 없이 현재 활성 운영자의 화면 표시 정보만 조회한다.
    private static final String OPERATOR = """
            SELECT operator_id, login_id, display_name, role
            FROM bankwatch.operator_user WHERE login_id = :login AND enabled
            """;
    private final Db db;

    public ApiController(Db db) { this.db = Objects.requireNonNull(db); }

    @GetMapping("/")
    public String index() { return "index"; }

    // 로그인 전에도 CSRF 토큰을 제공한다. 로그인·로그아웃 처리는 Spring Security가 맡는다.
    @GetMapping("/api/session")
    @ResponseBody
    public Map<String, Object> session(Principal principal, CsrfToken csrf) {
        var actor = principal == null ? null : actor(principal);
        // 기존 JS의 호환 필드다. 제거한 검증 센터는 설정과 관계없이 활성화하지 않는다.
        return p("mode", "POSTGRESQL", "authenticated", actor != null, "actor", actor,
                "csrf", csrf.getToken(), "lab_enabled", false);
    }

    private Map<String, Object> actor(Principal principal) {
        String login = principal.getName();
        if (login == null || login.isBlank()) throw new ApiProblem(401, "로그인이 필요합니다.");
        var users = db.rows(OPERATOR, p("login", login));
        if (users.isEmpty()) throw new ApiProblem(403, "활성 운영자 권한이 필요합니다.");
        var user = users.getFirst();
        return p("operator_id", Long.toString(((Number) user.get("operator_id")).longValue()),
                "login_id", user.get("login_id"), "display_name", user.get("display_name"), "role", user.get("role"));
    }
}
