package kr.ac.bankwatch;

import java.io.IOException;
import java.util.Locale;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import static kr.ac.bankwatch.Db.p;

@Configuration
public class SecurityConfig {
    private static final String LOGIN_REQUIRED = "{\"message\":\"로그인이 필요합니다.\"}";
    private static final String LOGIN_FAILED = "{\"message\":\"로그인 정보를 확인하세요.\"}";
    private static final String ACCESS_DENIED = "{\"message\":\"권한 또는 보안 토큰을 확인하세요.\"}";

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService users(Db db) {
        return login -> {
            var rows = db.rows("""
                SELECT login_id, password_hash, role, enabled
                FROM bankwatch.operator_user WHERE login_id = :login
                """, p("login", login));
            if (rows.isEmpty()) throw new UsernameNotFoundException("계정을 확인하세요.");
            var user = rows.get(0);
            return User.withUsername((String) user.get("login_id"))
                    .password((String) user.get("password_hash"))
                    .roles((String) user.get("role"))
                    .disabled(!Boolean.TRUE.equals(user.get("enabled"))).build();
        };
    }

    // 현재 화면의 인증·업무 경로만 허용하는 단일 체인이다.
    @Bean
    SecurityFilterChain filter(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/login", "/app.js", "/app.css",
                        "/favicon.ico", "/error", "/api/session").permitAll()
                .requestMatchers(HttpMethod.POST, "/login", "/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/logs", "/alerts", "/alerts/*", "/samples/**")
                        .hasAnyRole("ADMIN", "ANALYST")
                .requestMatchers(HttpMethod.POST, "/logs/import", "/alerts/detect").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/alerts/*/claim", "/incidents/*/lock", "/incidents/*/close")
                        .hasRole("ANALYST")
                .requestMatchers(HttpMethod.POST, "/incidents/*/unlock").hasRole("ADMIN")
                .anyRequest().denyAll());
        authentication(http, "/login", "/logout");
        return http.build();
    }

    private static void authentication(HttpSecurity http, String loginPath, String logoutPath) throws Exception {
        http.csrf(csrf -> csrf.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(form -> form.loginProcessingUrl(loginPath).permitAll()
                        .successHandler((request, response, auth) -> {
                            if (wantsJson(request)) json(response, 200, "{\"ok\":true}");
                            else response.sendRedirect(request.getContextPath() + "/");
                        })
                        .failureHandler((request, response, error) -> json(response, 401, LOGIN_FAILED)))
                .logout(logout -> logout.logoutUrl(logoutPath)
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(204)))
                .exceptionHandling(errors -> errors.defaultAuthenticationEntryPointFor(
                                (request, response, error) -> json(response, 401, LOGIN_REQUIRED),
                                request -> !"GET".equals(request.getMethod()) || wantsJson(request))
                        .accessDeniedHandler(SecurityConfig::denied));
    }

    private static void denied(HttpServletRequest request, HttpServletResponse response,
                               AccessDeniedException error) throws IOException {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous = auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken;
        boolean login = "/login".equals(request.getServletPath());
        if (anonymous && !login) json(response, 401, LOGIN_REQUIRED);
        else json(response, 403, ACCESS_DENIED);
    }

    private static boolean wantsJson(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        return request.getServletPath().startsWith("/api/")
                || "XMLHttpRequest".equals(request.getHeader("X-Requested-With"))
                || accept == null || !accept.toLowerCase(Locale.ROOT).contains("text/html");
    }

    private static void json(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }
}
