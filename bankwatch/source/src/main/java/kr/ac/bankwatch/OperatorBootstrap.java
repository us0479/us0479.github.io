package kr.ac.bankwatch;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 웹 기동과 분리된 일회성 도구. DB 소유자 접속 정보와 초기 비밀번호는 환경에서만 받는다. */
public final class OperatorBootstrap {
    private static final String DB_USER = "BANKWATCH_BOOTSTRAP_DB_USER";
    private static final String DB_PASSWORD = "BANKWATCH_BOOTSTRAP_DB_PASSWORD";
    private static final List<Operator> OPERATORS = List.of(
            new Operator("admin", "관제 관리자", "ADMIN", "BANKWATCH_ADMIN_PASSWORD"),
            new Operator("analyst_a", "관제 담당자 A", "ANALYST", "BANKWATCH_ANALYST_A_PASSWORD"),
            new Operator("analyst_b", "관제 담당자 B", "ANALYST", "BANKWATCH_ANALYST_B_PASSWORD"));

    private record Operator(String login, String name, String role, String passwordKey) {}

    private OperatorBootstrap() {}

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException("명령 인자 대신 로컬 환경변수를 사용하세요.");
            Map<String, String> env = System.getenv();
            validate(env);
            int created = initialize(env);
            System.out.printf("운영자 초기화 완료: 신규 %d, 기존 유지 %d.%n", created, OPERATORS.size() - created);
        } catch (IllegalArgumentException | IllegalStateException error) {
            System.err.println(error.getMessage());
            System.exit(1);
        } catch (SQLException error) {
            System.err.printf("DB 초기화 실패(SQLSTATE %s). 계정 상태와 접속 설정을 확인하세요.%n", error.getSQLState());
            System.exit(1);
        }
    }

    private static void validate(Map<String, String> env) {
        var keys = new java.util.ArrayList<>(List.of(DB_USER, DB_PASSWORD));
        OPERATORS.forEach(operator -> keys.add(operator.passwordKey()));
        var missing = keys.stream().filter(key -> env.getOrDefault(key, "").isBlank()).toList();
        if (!missing.isEmpty()) throw new IllegalArgumentException("필수 환경변수 누락: " + String.join(", ", missing));
        if (!databaseUrl(env).startsWith("jdbc:postgresql:")) {
            throw new IllegalArgumentException("BANKWATCH_DB_URL은 PostgreSQL JDBC URL이어야 합니다.");
        }
        for (var operator : OPERATORS) {
            if (env.get(operator.passwordKey()).getBytes(StandardCharsets.UTF_8).length > 72) {
                throw new IllegalArgumentException(operator.passwordKey() + "는 UTF-8 72바이트 이하여야 합니다.");
            }
        }
    }

    private static String databaseUrl(Map<String, String> env) {
        return env.getOrDefault("BANKWATCH_DB_URL", "jdbc:postgresql://localhost:5432/bankwatch_db");
    }

    private static int initialize(Map<String, String> env) throws SQLException {
        var properties = new Properties();
        properties.setProperty("user", env.get(DB_USER));
        properties.setProperty("password", env.get(DB_PASSWORD));
        properties.setProperty("connectTimeout", "5");
        properties.setProperty("socketTimeout", "30");
        properties.setProperty("ApplicationName", "bankwatch-operator-bootstrap");
        try (Connection connection = DriverManager.getConnection(databaseUrl(env), properties)) {
            connection.setAutoCommit(false);
            try {
                checkOwner(connection);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET LOCAL search_path = pg_catalog, pg_temp");
                    statement.execute("SET LOCAL lock_timeout = '5s'");
                    statement.execute("SET LOCAL statement_timeout = '20s'");
                    statement.execute("LOCK TABLE bankwatch.operator_user IN SHARE ROW EXCLUSIVE MODE");
                }
                int created = 0;
                var encoder = new BCryptPasswordEncoder();
                for (var operator : OPERATORS) {
                    if (createIfMissing(connection, operator, env.get(operator.passwordKey()), encoder)) created++;
                }
                connection.commit();
                return created;
            } catch (SQLException | RuntimeException error) {
                try { connection.rollback(); } catch (SQLException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        }
    }

    private static void checkOwner(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT EXISTS (
                    SELECT 1 FROM pg_catalog.pg_namespace n
                    JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid
                    WHERE n.nspname = 'bankwatch' AND c.relname = 'operator_user' AND c.relkind = 'r'
                      AND pg_catalog.pg_get_userbyid(n.nspowner) = CURRENT_USER
                      AND pg_catalog.pg_get_userbyid(c.relowner) = CURRENT_USER
                )
                """); var result = statement.executeQuery()) {
            result.next();
            if (!result.getBoolean(1)) {
                throw new IllegalStateException("bankwatch 스키마와 operator_user 테이블의 소유자로 실행하세요.");
            }
        }
    }

    private static boolean createIfMissing(Connection connection, Operator operator, String password,
                                           BCryptPasswordEncoder encoder) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT role, enabled FROM bankwatch.operator_user WHERE login_id = ?")) {
            statement.setString(1, operator.login());
            try (var result = statement.executeQuery()) {
                if (result.next()) {
                    if (!operator.role().equals(result.getString("role")) || !result.getBoolean("enabled")) {
                        throw new IllegalStateException("기존 운영자의 역할 또는 활성 상태가 다릅니다: " + operator.login());
                    }
                    return false;
                }
            }
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO bankwatch.operator_user (login_id, password_hash, display_name, role)
                VALUES (?, ?, ?, ?)
                """)) {
            statement.setString(1, operator.login());
            statement.setString(2, encoder.encode(password));
            statement.setString(3, operator.name());
            statement.setString(4, operator.role());
            statement.executeUpdate();
            return true;
        }
    }
}
