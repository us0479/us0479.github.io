package kr.ac.bankwatch;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.postgresql.util.PGobject;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Component
public class Db {
    public final NamedParameterJdbcTemplate jdbc;
    public final DataSource source;
    public final JsonMapper json;
    private final DataSourceTransactionManager transactions;

    public Db(DataSource source, JsonMapper json) {
        this.source = Objects.requireNonNull(source);
        this.json = Objects.requireNonNull(json);
        this.jdbc = new NamedParameterJdbcTemplate(source);
        this.transactions = new DataSourceTransactionManager(source);
    }

    public static Map<String, Object> p(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("바인딩은 이름과 값의 쌍이어야 합니다.");
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (!(pairs[i] instanceof String key) || key.isBlank() || params.containsKey(key)) {
                throw new IllegalArgumentException("바인딩 이름은 비어 있지 않고 중복되지 않는 문자열이어야 합니다.");
            }
            params.put(key, pairs[i + 1]);
        }
        return params;
    }

    public Object normalize(Object value) {
        if (value instanceof PGobject pg && ("jsonb".equals(pg.getType()) || "json".equals(pg.getType()))) {
            return pg.getValue() == null ? null : json.readValue(pg.getValue(), Object.class);
        }
        if (value instanceof PGobject pg) return pg.getValue();
        if (value instanceof Timestamp time) return time.toInstant().toString();
        if (value instanceof OffsetDateTime time) return time.toInstant().toString();
        if (value instanceof java.sql.Array array) {
            SQLException failure = null;
            try { return array.getArray(); }
            catch (SQLException error) {
                failure = error;
                throw new IllegalStateException("SQL 배열을 읽지 못했습니다.", error);
            }
            finally {
                try { array.free(); }
                catch (SQLException error) {
                    if (failure != null) failure.addSuppressed(error);
                    else throw new IllegalStateException("SQL 배열을 해제하지 못했습니다.", error);
                }
            }
        }
        return value;
    }

    public List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
        return jdbc.queryForList(sql, params).stream().map(row -> {
            Map<String, Object> normalized = new LinkedHashMap<>();
            row.forEach((key, value) -> normalized.put(key, normalize(value)));
            return normalized;
        }).toList();
    }

    public Map<String, Object> one(String sql, Map<String, ?> params) {
        var result = rows(sql, params);
        if (result.isEmpty()) throw new ApiProblem(404, "대상이 없습니다.");
        if (result.size() != 1) throw new IncorrectResultSizeDataAccessException(1, result.size());
        return result.get(0);
    }

    public Object scalar(String sql, Map<String, ?> params) {
        return normalize(jdbc.queryForObject(sql, params, Object.class));
    }

    public long number(String sql, Map<String, ?> params) {
        return ((Number) scalar(sql, params)).longValue();
    }

    public Object call(String sql, Map<String, ?> params) {
        return scalar(sql, params);
    }

    // Controller/Service가 세션에서 얻은 actor의 활성 여부·권한을 검사한다.
    // 이 도우미는 인증을 대신하지 않으며, SQL 세션 변수로 actor를 전달하지 않는다.
    // work는 재시도될 수 있으므로 DB 작업만 포함하고 응답·외부 전송은 커밋 뒤에 한다.
    public <T> T tx(long actor, boolean snapshot, Supplier<T> work) {
        if (actor <= 0) throw new IllegalArgumentException("운영자 ID는 양수여야 합니다.");
        Objects.requireNonNull(work);
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.hasResource(source)) {
            throw new IllegalStateException("업무 트랜잭션은 바깥에서 한 번만 시작해야 합니다.");
        }
        for (int attempt = 0; ; attempt++) {
            var transaction = new TransactionTemplate(transactions);
            transaction.setIsolationLevel(snapshot ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                    : TransactionDefinition.ISOLATION_READ_COMMITTED);
            transaction.setTimeout(45);
            try {
                return transaction.execute(status -> {
                    jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '5s'");
                    jdbc.getJdbcTemplate().execute("SET LOCAL statement_timeout = '40s'");
                    return work.get();
                });
            } catch (RuntimeException error) {
                String state = sqlState(error);
                boolean rollbackFailed = error instanceof TransactionSystemException system
                        && system.getApplicationException() != null;
                if (rollbackFailed || attempt >= 2 || !("40P01".equals(state) || "40001".equals(state))) throw error;
                try { Thread.sleep(50L * (attempt + 1)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw error;
                }
            }
        }
    }

    // Spring 래퍼와 JDBC 배치 예외를 순환 참조 없이 탐색한다.
    public static String sqlState(Throwable error) {
        if (error == null) return "";
        var pending = new ArrayDeque<Throwable>();
        var visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        pending.add(error);
        while (!pending.isEmpty()) {
            var current = pending.removeFirst();
            if (!visited.add(current)) continue;
            if (current instanceof TransactionSystemException system && system.getApplicationException() != null) {
                pending.addLast(system.getApplicationException());
            }
            if (current instanceof SQLException sql) {
                if (sql.getSQLState() != null && !sql.getSQLState().isBlank()) return sql.getSQLState();
                if (sql.getNextException() != null) pending.addLast(sql.getNextException());
            }
            if (current.getCause() != null) pending.addLast(current.getCause());
        }
        return "";
    }
}
