package kr.ac.bankwatch;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import kr.ac.bankwatch.detection.AlertQueryService;
import kr.ac.bankwatch.detection.DetectionService;
import kr.ac.bankwatch.event.EventService;
import kr.ac.bankwatch.event.EventQueryService;
import kr.ac.bankwatch.incident.IncidentActionService;
import kr.ac.bankwatch.incident.IncidentClaimService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import static kr.ac.bankwatch.Db.p;
import static org.junit.jupiter.api.Assertions.*;

/** -Ppostgres-it: bankwatch_test_* 전용 DB의 현재 db/ SQL과 실제 서비스를 검증한다. */
@EnabledIfEnvironmentVariable(named = "BW_INTEGRATION_TEST", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
@Timeout(60)
class PostgresIntegrationTest {
    private static final String FROM = "2026-11-02T10:00:00+09:00";
    private static final String TO = "2026-11-02T10:10:00+09:00";
    private static final String HEADER = "event_id,customer_ref,occurred_at,device_ref,event_type,is_new_device\n";
    private static final long HISTORY_GATE = 187650027L;
    private static final long DEADLOCK_GATE = 187650028L;
    private static final List<String> TABLES = List.of("incident_history", "alert_event", "incident", "alert",
            "security_event", "customer_account", "detection_rule", "operator_user");
    private final Path root = Path.of(System.getProperty("basedir", "."));
    private HikariDataSource app;
    private DriverManagerDataSource ownerSource;
    private Db owner;
    private Db db;
    private Db verifier;
    private EventService events;
    private DetectionService detection;
    private AlertQueryService queries;
    private IncidentClaimService claims;
    private IncidentActionService actions;
    private String fixtureHash;
    private boolean createdSchema;
    private long admin;
    private long analystA;
    private long analystB;

    @BeforeAll
    void initializeDedicatedDatabase() throws Exception {
        String url = required("BANKWATCH_TEST_DB_URL");
        String ownerUser = required("BANKWATCH_TEST_OWNER_USER");
        String ownerPassword = required("BANKWATCH_TEST_OWNER_PASSWORD");
        String appPassword = required("BANKWATCH_TEST_APP_PASSWORD");
        String verifierUser = required("BANKWATCH_TEST_VERIFIER_USER");
        String verifierPassword = required("BANKWATCH_TEST_VERIFIER_PASSWORD");
        var json = JsonMapper.builder().build();
        ownerSource = new DriverManagerDataSource(url, ownerUser, ownerPassword);
        owner = new Db(ownerSource, json);
        assertTrue(((String) owner.scalar("SELECT current_database()", p())).matches("bankwatch_test_[a-z0-9_]+"),
                "기존 업무 DB 대신 bankwatch_test_* 전용 DB를 지정하세요.");
        assertEquals(16, owner.number("SELECT current_setting('server_version_num')::integer / 10000", p()));
        assertNull(owner.scalar("SELECT to_regnamespace('bankwatch')", p()), "기존 bankwatch 스키마는 변경하지 않습니다.");
        assertNotEquals("bankwatch_app", ownerUser);
        assertNotEquals("bankwatch_app", verifierUser);
        verifier = new Db(new DriverManagerDataSource(url, verifierUser, verifierPassword), json);
        assertEquals(Boolean.TRUE, verifier.scalar("SELECT pg_has_role(current_user, 'pg_signal_backend', 'USAGE')", p()),
                "SC-04용 별도 검증 역할에만 pg_signal_backend가 필요합니다.");
        executeOwner(read("db/schema.sql"));
        createdSchema = true;
        executeOwner(read("db/grant_runtime.sql"));
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername("bankwatch_app");
        config.setPassword(appPassword);
        config.setSchema("bankwatch");
        config.setConnectionInitSql("SET TIME ZONE 'Asia/Seoul'");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5000);
        app = new HikariDataSource(config);
        db = new Db(app, json);
        assertEquals("bankwatch_app", db.scalar("SELECT current_user", p()));
        assertEquals(Boolean.FALSE, db.scalar("SELECT pg_has_role(current_user, 'pg_signal_backend', 'USAGE')", p()));
        events = new EventService(db);
        detection = new DetectionService(db);
        queries = new AlertQueryService(db);
        claims = new IncidentClaimService(db);
        actions = new IncidentActionService(db);
        fixtureHash = new BCryptPasswordEncoder().encode(UUID.randomUUID().toString());
    }

    @BeforeEach
    void resetOnlyClassOwnedFixtures() throws Exception {
        String tables = String.join(", ", TABLES.stream().map(name -> "bankwatch." + name).toList());
        executeOwner("TRUNCATE " + tables + " RESTART IDENTITY");
        executeOwner(read("db/seed.sql"));
        owner.jdbc.update("""
                INSERT INTO bankwatch.operator_user (login_id, password_hash, display_name, role) VALUES
                    ('admin', :hash, '관제 관리자', 'ADMIN'),
                    ('analyst_a', :hash, '관제 담당자 A', 'ANALYST'),
                    ('analyst_b', :hash, '관제 담당자 B', 'ANALYST')
                """, p("hash", fixtureHash));
        admin = operator("admin");
        analystA = operator("analyst_a");
        analystB = operator("analyst_b");
        assertEquals(8, db.number("""
                SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'bankwatch' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
                """, p()));
    }

    @AfterAll
    void removeOnlySchemaCreatedByThisClass() throws Exception {
        try { if (app != null) app.close(); }
        finally {
            if (createdSchema) executeOwner("DROP SCHEMA bankwatch CASCADE");
        }
    }

    @Test
    @DisplayName("SC-01 / TEST-02·04·07: 정상 R02와 배정·잠금·관리자 해제·종료")
    void normalDetectionAndResponseMatchContract() throws Exception {
        assertEquals(new EventService.ImportResult(7, 7, 0), importSample("events-r02.csv"));
        assertEquals(new EventService.ImportResult(7, 0, 7), importSample("events-r02.csv"));
        assertEquals(new DetectionService.DetectionResult(2, 1, 1, 0, 6), detection.detect(admin, FROM, TO));
        long alert = singleAlert();
        var detail = queries.detail(analystA, alert);
        assertEquals(List.of("6"), detail.evidence().stream().filter(row -> Boolean.TRUE.equals(row.get("is_anchor")))
                .map(row -> (String) row.get("event_id")).toList());
        assertEquals(6, detail.evidence().size());
        var before = fingerprint("alert", "alert_event");
        assertEquals(new DetectionService.DetectionResult(2, 1, 0, 1, 0), detection.detect(admin, FROM, TO));
        assertEquals(before, fingerprint("alert", "alert_event"));
        long incident = Long.parseLong(claims.claim(analystA, alert).incidentId());
        var locked = actions.lock(analystA, incident, 0, "본인 접속 아님: 모의 확인");
        assertEquals(1, locked.version());
        assertEquals(Long.toString(incident), locked.lockIncidentId());
        assertEquals(2, actions.unlock(admin, incident, 1, "관리자 확인 후 해제").version());
        var closed = actions.close(analystA, incident, 2, "조사 완료", "CONFIRMED");
        assertEquals(3, closed.version());
        assertNotNull(closed.closedAt());
        assertEquals("CLOSED", closed.processingStatus());
        assertEquals("ACTIVE", closed.accessStatus());
        assertEquals(List.of("CLAIM", "LOCK_ACCOUNT", "UNLOCK_ACCOUNT", "CLOSE"), historyTypes(alert));
        assertEquals(7, count("security_event"));
    }

    @Test
    @DisplayName("SC-03 / TEST-03: 첫 신규 INSERT 뒤 없는 고객 조회 실패, 새 입력 전체 롤백")
    void missingCustomerRollsBackEarlierSuccessfulInsert() throws Exception {
        importSample("events-r02.csv");
        var before = fingerprint("security_event");
        installInsertObservation();
        try {
            var error = assertThrows(ApiProblem.class, () -> importSample("events-invalid-customer.csv"));
            assertEquals(400, error.status);
            assertTrue(error.getMessage().contains("2행"));
            assertTrue(error.getMessage().contains("등록되지 않은 고객"));
            assertEquals(Boolean.TRUE, owner.scalar("SELECT is_called FROM bankwatch.test_insert_marker", p()),
                    "비트랜잭션 시퀀스로 첫 신규 INSERT가 실제 실행됐음을 확인한다.");
            assertEquals(1, owner.number("SELECT last_value FROM bankwatch.test_insert_marker", p()));
            assertEquals(before, fingerprint("security_event"));
            assertEquals(0, db.number("SELECT count(*) FROM bankwatch.security_event WHERE event_id IN (8, 9)", p()));
            assertEquals(7, count("security_event"));
        } finally {
            executeOwner("DROP TRIGGER test_insert_observation ON bankwatch.security_event; "
                    + "DROP FUNCTION bankwatch.test_observe_insert(); DROP SEQUENCE bankwatch.test_insert_marker");
        }
    }

    @Test
    @DisplayName("TEST-05: 고정 구간·동일 시각·고객·기존 기기 경계 샘플의 후보는 0")
    void boundaryFixturesDoNotCreateCandidates() throws Exception {
        assertEquals(41, importSample("events-boundaries.csv").insertedRows());
        assertEquals(new DetectionService.DetectionResult(0, 0, 0, 0, 0), detection.detect(admin,
                "2026-11-02T11:00:00+09:00", "2026-11-02T12:40:00+09:00"));
        assertEquals(0, count("alert"));
        assertEquals(0, count("alert_event"));
    }

    @Test
    @DisplayName("SC-02 / TEST-06: 서로 다른 담당자 선점과 승자 동일 버전의 두 DB 세션")
    void concurrentClaimAndStaleVersionHaveOneWinner() throws Exception {
        long alert = normalAlert();
        var claimed = race("SELECT alert_id FROM bankwatch.alert WHERE alert_id = ? FOR UPDATE", alert,
                () -> claims.claim(analystA, alert), () -> claims.claim(analystB, alert));
        var winner = onlySuccess(claimed, IncidentClaimService.ClaimResult.class);
        var loser = onlySuccess(claimed, ApiProblem.class);
        assertEquals(409, loser.status);
        long actor = Long.parseLong(winner.assigneeId());
        long incident = Long.parseLong(winner.incidentId());
        assertTrue(Set.of(analystA, analystB).contains(actor));
        assertTrue(loser.getMessage().contains(actor == analystA ? "관제 담당자 A" : "관제 담당자 B"));
        assertEquals(1, count("incident"));
        assertEquals(List.of("CLAIM"), historyTypes(alert));
        long account = db.number("SELECT account_id FROM bankwatch.alert WHERE alert_id = :id", p("id", alert));
        var changed = race("SELECT account_id FROM bankwatch.customer_account WHERE account_id = ? FOR UPDATE", account,
                () -> actions.lock(actor, incident, 0, "승자의 첫 화면"),
                () -> actions.lock(actor, incident, 0, "승자의 두 번째 화면"));
        assertEquals(1, onlySuccess(changed, IncidentActionService.ActionResult.class).version());
        assertEquals(409, onlySuccess(changed, ApiProblem.class).status);
        assertEquals(1, incidentVersion(incident));
        assertEquals(List.of("CLAIM", "LOCK_ACCOUNT"), historyTypes(alert));
        var before = caseState();
        assertStatus(409, () -> actions.close(actor, incident, 0, "구버전 화면", "CONFIRMED"));
        assertEquals(before, caseState());
    }

    @Test
    @DisplayName("TEST-01·07: 역할·담당자·원인 확인과 종료 후 관리자 해제")
    void causeAndClosureAreIndependent() throws Exception {
        long alert = normalAlert();
        long incident = Long.parseLong(claims.claim(analystA, alert).incidentId());
        var before = caseState();
        assertStatus(403, () -> actions.lock(admin, incident, 0, "권한 오류"));
        assertStatus(403, () -> actions.lock(analystB, incident, 0, "담당자 오류"));
        assertStatus(403, () -> actions.unlock(analystA, incident, 0, "권한 오류"));
        assertStatus(400, () -> actions.lock(analystA, incident, 0, " \t\n"));
        assertStatus(400, () -> actions.close(analystA, incident, 0, "판정 오류", "UNKNOWN"));
        assertEquals(before, caseState());
        actions.lock(analystA, incident, 0, "보호 필요");
        long otherAlert = secondAlert();
        long otherIncident = Long.parseLong(claims.claim(analystB, otherAlert).incidentId());
        before = caseState();
        assertStatus(409, () -> actions.unlock(admin, otherIncident, 0, "다른 원인 사건"));
        assertEquals(before, caseState());
        var otherClosed = actions.close(analystB, otherIncident, 0, "정상 기기 변경", "FALSE_POSITIVE");
        assertEquals(Long.toString(incident), otherClosed.lockIncidentId());
        var closed = actions.close(analystA, incident, 1, "보호 유지한 채 종료", "CONFIRMED");
        assertEquals("CLOSED", closed.processingStatus());
        assertEquals("LOCKED", closed.accessStatus());
        var unlocked = actions.unlock(admin, incident, 2, "종료 후 관리자 확인");
        assertEquals(3, unlocked.version());
        assertEquals("CLOSED", unlocked.processingStatus());
        assertEquals("ACTIVE", unlocked.accessStatus());
        assertEquals(closed.closedAt(), unlocked.closedAt());
        assertEquals(closed.resolution(), unlocked.resolution());
    }

    @Test
    @DisplayName("TEST-08: 이력 저장의 앱 예외·SQL 예외가 원인·버전·종료를 함께 롤백")
    void historyFailureRollsBackEntireAction() throws Exception {
        long alert = normalAlert();
        long incident = Long.parseLong(claims.claim(analystA, alert).incidentId());
        var before = caseState();
        installHistoryFailure(incident, "RETURN NULL;");
        try {
            assertThrows(IllegalStateException.class, () -> actions.lock(analystA, incident, 0, "이력 누락"));
            assertEquals(before, caseState());
        } finally { removeHistoryFailure(); }
        installHistoryFailure(incident, "NEW.reason = ' '; ");
        try {
            assertSqlState("23514", () -> actions.lock(analystA, incident, 0, "CHECK 오류"));
            assertEquals(before, caseState());
        } finally { removeHistoryFailure(); }
        actions.lock(analystA, incident, 0, "정상 재요청");
        before = caseState();
        installHistoryFailure(incident, "RETURN NULL;");
        try {
            assertThrows(IllegalStateException.class, () -> actions.unlock(admin, incident, 1, "해제 이력 누락"));
            assertThrows(IllegalStateException.class, () -> actions.close(analystA, incident, 1, "종료 이력 누락", "CONFIRMED"));
            assertEquals(before, caseState());
        } finally { removeHistoryFailure(); }
        assertEquals(2, actions.unlock(admin, incident, 1, "정상 해제").version());
        assertEquals(List.of("CLAIM", "LOCK_ACCOUNT", "UNLOCK_ACCOUNT"), historyTypes(alert));
    }

    @Test
    @DisplayName("SC-04 / TEST-08: 원인·버전 UPDATE 뒤 별도 검증 연결이 실제 백엔드를 종료")
    void connectionTerminationRollsBackProtection() throws Exception {
        long alert = normalAlert();
        long incident = Long.parseLong(claims.claim(analystA, alert).incidentId());
        var before = caseState();
        installHistoryGate(incident);
        try (var executor = Executors.newSingleThreadExecutor(); var gate = ownerSource.getConnection()) {
            gate.setAutoCommit(false);
            lock(gate, "SELECT pg_advisory_xact_lock(?)", HISTORY_GATE);
            var pending = executor.submit(() -> actions.lock(analystA, incident, 0, "SC-04 중단"));
            try {
                long pid = waitingBackends(1, true).iterator().next();
                assertEquals(before, caseState(), "별도 관찰 연결은 이전 커밋 상태만 본다.");
                assertEquals(Boolean.TRUE, verifier.scalar("SELECT pg_terminate_backend(:pid)", p("pid", (int) pid)));
                var failure = assertThrows(ExecutionException.class, () -> pending.get(10, TimeUnit.SECONDS));
                String state = Db.sqlState(failure);
                assertTrue(state.startsWith("08") || state.equals("57P01"));
                assertEquals(before, caseState());
                assertEquals(1, owner.number("SELECT last_value FROM bankwatch.test_history_marker", p()),
                        "연결 중단은 자동 재시도하지 않는다.");
            } finally { gate.commit(); }
        } finally {
            executeOwner("DROP TRIGGER test_history_gate ON bankwatch.incident_history; "
                    + "DROP FUNCTION bankwatch.test_gate_history(); DROP SEQUENCE bankwatch.test_history_marker");
        }
        var retried = actions.lock(analystA, incident, 0, "SC-04 새 정상 요청");
        assertEquals(1, retried.version());
        assertEquals(Long.toString(incident), retried.lockIncidentId());
        assertEquals(List.of("CLAIM", "LOCK_ACCOUNT"), historyTypes(alert));
    }

    @Test
    @DisplayName("TEST-09: KST 월별 라우팅, 복합 PK/FK·UQ·CHECK·NULL 및 앱 최소 권한")
    void partitionRoutingAndDirectConstraints() throws Exception {
        var times = List.of("2026-10-01T00:00:00+09:00", "2026-10-31T23:59:59.999999+09:00",
                "2026-11-01T00:00:00+09:00", "2026-11-30T23:59:59.999999+09:00",
                "2026-12-01T00:00:00+09:00", "2026-12-31T23:59:59.999999+09:00");
        String rows = String.join("\n", times.stream().map(time -> "1,C1001," + time + ",known,LOGIN_FAILED,").toList());
        assertEquals(6, events.importCsv(admin, csv(HEADER + rows + "\n")).insertedRows());
        var partitions = db.rows("SELECT tableoid::regclass::text AS partition FROM bankwatch.security_event ORDER BY occurred_at", p());
        assertEquals(List.of("security_event_2026_10", "security_event_2026_10", "security_event_2026_11",
                "security_event_2026_11", "security_event_2026_12", "security_event_2026_12"),
                partitions.stream().map(row -> row.get("partition")).toList());
        long account = db.number("SELECT account_id FROM bankwatch.customer_account WHERE customer_ref = 'C1001'", p());
        var params = p("time", OffsetDateTime.parse(times.get(2)), "id", 1L, "account", account, "type", "LOGIN_FAILED", "flag", null);
        assertSqlState("23505", () -> insertEvent(params));
        var absentCustomer = p("time", OffsetDateTime.parse(FROM), "id", 99L, "account", Long.MAX_VALUE, "type", "LOGIN_FAILED", "flag", null);
        assertSqlState("23503", () -> insertEvent(absentCustomer));
        assertSqlState("23502", () -> insertEvent(p("time", OffsetDateTime.parse(FROM), "id", 99L, "account", null, "type", "LOGIN_FAILED", "flag", null)));
        assertSqlState("23514", () -> insertEvent(p("time", OffsetDateTime.parse(FROM), "id", 99L, "account", account, "type", "LOGIN_SUCCESS", "flag", null)));
        long alert = db.number("""
                INSERT INTO bankwatch.alert (rule_id, account_id, bucket_start)
                SELECT rule_id, :account, :time FROM bankwatch.detection_rule WHERE rule_code = 'R02'
                RETURNING alert_id
                """, p("account", account, "time", OffsetDateTime.parse(FROM)));
        assertSqlState("23505", () -> db.jdbc.update("""
                INSERT INTO bankwatch.alert (rule_id, account_id, bucket_start)
                SELECT rule_id, account_id, bucket_start FROM bankwatch.alert WHERE alert_id = :alert
                """, p("alert", alert)));
        assertSqlState("23503", () -> db.jdbc.update("""
                INSERT INTO bankwatch.alert_event (alert_id, event_occurred_at, event_id, is_anchor)
                VALUES (:alert, :wrongTime, 1, true)
                """, p("alert", alert, "wrongTime", OffsetDateTime.parse(times.get(2)).plusSeconds(1))));
        assertSqlState("42501", () -> db.rows("SELECT alert_id FROM bankwatch.alert FOR UPDATE", p()));
        assertSqlState("42501", () -> db.jdbc.update("UPDATE bankwatch.operator_user SET enabled = false", p()));
        var before = fingerprint("security_event");
        assertStatus(400, () -> events.importCsv(admin, csv(HEADER
                + "2,C1001,2026-11-03T10:00:00+09:00,known,LOGIN_FAILED,\n"
                + "3,C1001,2027-01-01T00:00:00+09:00,outside,LOGIN_FAILED,\n")));
        assertEquals(before, fingerprint("security_event"));
    }

    @Test
    @DisplayName("TEST-10: 합성 30,000행의 실제 QRY-05·파티션 제외·후보 인덱스 전후 계획")
    void queryPlansComparePruningAndIndexOnIdenticalData() throws Exception {
        var samples = new ArrayList<Map<String, Object>>();
        var report = p("test", "TEST-10", "status", "INCOMPLETE", "samples", samples,
                "recorded_at_kst", OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).toString(),
                "comparison", "Same captured QRY-05/bindings/data; natural planner, no speed or index-use threshold");
        boolean indexCreated = false;
        boolean complete = false;
        try {
            assertNull(owner.scalar("SELECT to_regclass('bankwatch.idx_security_event_account_time')", p()));
            preparePlanFixture();
            var before = fingerprint("security_event");
            var captured = capturePlanQuery();
            report.put("environment", planEnvironment());
            report.put("source_sha256", planSourceHashes());
            report.put("dataset", p("total", 30000, "per_month", 10000, "target_customer", "C1001",
                    "matching_november_events", 100, "page_items", 20, "fetched_rows", 21,
                    "fingerprint_before", before, "partitions", planPartitionCounts()));
            report.put("sql", captured.sql);
            report.put("bindings", planBindings(captured.bindings));
            report.put("indexes_before", planIndexes());
            var pruned = observePlan(captured, "pruning_on_before_index", true);
            samples.add(pruned);
            var unpruned = observePlan(captured, "pruning_off_before_index", false);
            samples.add(unpruned);
            assertEquals(Set.of("security_event_2026_11"), pruned.get("relations"));
            assertEquals(Set.of("security_event_2026_10", "security_event_2026_11", "security_event_2026_12"),
                    unpruned.get("relations"));
            executeOwner("CREATE INDEX idx_security_event_account_time ON bankwatch.security_event "
                    + "(account_id, occurred_at, event_id)");
            indexCreated = true;
            report.put("indexes_with_candidate", planIndexes());
            var indexed = observePlan(captured, "pruning_on_after_index", true);
            samples.add(indexed);
            assertEquals(Set.of("security_event_2026_11"), indexed.get("relations"));
            assertEquals(before, fingerprint("security_event"), "설정·인덱스 비교 중 원본은 바뀌지 않아야 합니다.");
            complete = true;
        } finally {
            if (indexCreated) executeOwner("DROP INDEX bankwatch.idx_security_event_account_time");
            boolean removed = owner.scalar("SELECT to_regclass('bankwatch.idx_security_event_account_time')", p()) == null;
            report.put("candidate_index_removed", removed);
            report.put("status", complete && removed ? "PASS" : "INCOMPLETE");
            writePlanReport(report);
            assertTrue(removed, "이 시험이 만든 후보 인덱스만 정리해야 합니다.");
        }
    }

    // 파일 입력 제한과 별개인 검증 전용 합성 픽스처다. 시각 생성은 세션 시간대에 의존하지 않는다.
    private void preparePlanFixture() throws Exception {
        assertEquals(0, owner.number("SELECT count(*) FROM pg_index WHERE indrelid = "
                + "'bankwatch.security_event'::regclass AND NOT indisprimary", p()));
        int inserted = owner.jdbc.update("""
                INSERT INTO bankwatch.security_event
                    (occurred_at, event_id, account_id, device_ref, event_type, is_new_device)
                SELECT (TIMESTAMP '2026-10-01 00:00:00' + month_no * INTERVAL '1 month'
                        + event_no * INTERVAL '4 minutes') AT TIME ZONE 'Asia/Seoul',
                       month_no * 10000 + event_no + 1, c.account_id,
                       'plan-device-' || (event_no % 17)::text, 'LOGIN_FAILED', NULL
                FROM generate_series(0, 2) AS months(month_no)
                CROSS JOIN generate_series(0, 9999) AS events(event_no)
                JOIN bankwatch.customer_account c ON c.customer_ref = CASE
                    WHEN event_no % 100 = 0 THEN 'C1001'
                    WHEN event_no % 2 = 0 THEN 'C1002' ELSE 'C1003' END
                """, p());
        assertEquals(30000, inserted);
        assertEquals(30000, count("security_event"));
        for (var row : planPartitionCounts()) assertEquals(10000, ((Number) row.get("row_count")).longValue());
        executeOwner("ANALYZE bankwatch.customer_account; ANALYZE bankwatch.security_event; "
                + "ANALYZE bankwatch.security_event_2026_10; ANALYZE bankwatch.security_event_2026_11; "
                + "ANALYZE bankwatch.security_event_2026_12");
    }

    private List<Map<String, Object>> planPartitionCounts() {
        return db.rows("SELECT tableoid::regclass::text AS partition, count(*) AS row_count "
                + "FROM bankwatch.security_event GROUP BY 1 ORDER BY 1", p());
    }

    // SQL을 복제하지 않고 현재 서비스가 실제 실행한 목록 SQL과 원래 타입의 바인딩을 받는다.
    private QueryCapture capturePlanQuery() {
        var captured = new QueryCapture(app, db.json);
        var result = new EventQueryService(captured).events(admin, Map.of(
                "account", "C1001", "type", "LOGIN_FAILED", "limit", "20",
                "from", "2026-11-01T00:00:00+09:00", "to", "2026-12-01T00:00:00+09:00"));
        assertNotNull(captured.sql, "현재 QRY-05 실행을 관찰해야 합니다.");
        assertEquals(100, result.total());
        assertEquals(20, result.items().size());
        assertTrue(result.hasMore());
        assertEquals(21, captured.fetched.size());
        return captured;
    }

    private static final class QueryCapture extends Db {
        private String sql;
        private Map<String, ?> bindings = Map.of();
        private List<Map<String, Object>> fetched = List.of();

        private QueryCapture(HikariDataSource source, JsonMapper json) { super(source, json); }

        @Override
        public List<Map<String, Object>> rows(String statement, Map<String, ?> params) {
            var result = super.rows(statement, params);
            if (statement.contains("ORDER BY e.occurred_at DESC, e.event_id DESC LIMIT :fetchLimit")) {
                assertNull(sql, "한 응답의 QRY-05는 한 번만 포착해야 합니다.");
                sql = statement;
                bindings = Map.copyOf(params);
                fetched = List.copyOf(result);
            }
            return result;
        }
    }

    private Map<String, Object> observePlan(QueryCapture captured, String label, boolean pruning) {
        return db.tx(admin, true, () -> {
            db.jdbc.getJdbcTemplate().execute("SET TRANSACTION READ ONLY");
            db.jdbc.getJdbcTemplate().execute("SET LOCAL enable_partition_pruning = " + (pruning ? "on" : "off"));
            assertEquals(pruning ? "on" : "off", db.scalar("SHOW enable_partition_pruning", p()));
            // 동일 결과를 먼저 읽는다. 아래 두 계획은 같은 연결·스냅샷의 warm 실행이다.
            assertEquals(captured.fetched, db.rows(captured.sql, captured.bindings));
            var text = db.rows("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT TEXT) " + captured.sql, captured.bindings);
            var json = db.scalar("EXPLAIN (ANALYZE, BUFFERS, SETTINGS, FORMAT JSON) " + captured.sql, captured.bindings);
            var document = (Map<?, ?>) ((List<?>) json).getFirst();
            var plan = (Map<?, ?>) document.get("Plan");
            assertEquals(21, ((Number) plan.get("Actual Rows")).longValue());
            var relations = new TreeSet<String>();
            var indexes = new TreeSet<String>();
            collectPlanFacts(plan, relations, indexes);
            return p("label", label, "partition_pruning", pruning, "relations", relations, "indexes_used", indexes,
                    "actual_rows", plan.get("Actual Rows"), "execution_ms", document.get("Execution Time"),
                    "planning_ms", document.get("Planning Time"), "shared_hit_blocks", plan.get("Shared Hit Blocks"),
                    "shared_read_blocks", plan.get("Shared Read Blocks"), "result_matches", true,
                    "plan_text", String.join("\n", text.stream().map(row -> (String) row.get("QUERY PLAN")).toList()),
                    "plan_json", json);
        });
    }

    private static void collectPlanFacts(Object value, Set<String> relations, Set<String> indexes) {
        if (value instanceof Map<?, ?> map) {
            if (map.get("Relation Name") instanceof String name && name.startsWith("security_event_")) relations.add(name);
            if (map.get("Index Name") instanceof String name) indexes.add(name);
            for (var child : map.values()) collectPlanFacts(child, relations, indexes);
        } else if (value instanceof List<?> list) {
            for (var child : list) collectPlanFacts(child, relations, indexes);
        }
    }

    private Map<String, Object> planEnvironment() {
        var environment = db.one("""
                SELECT version() AS postgresql, current_database() AS database_name,
                       current_setting('TimeZone') AS timezone, current_setting('enable_seqscan') AS enable_seqscan,
                       current_setting('enable_indexscan') AS enable_indexscan,
                       current_setting('enable_bitmapscan') AS enable_bitmapscan,
                       current_setting('plan_cache_mode') AS plan_cache_mode,
                       current_setting('shared_buffers') AS shared_buffers,
                       current_setting('work_mem') AS work_mem
                """, p());
        environment.put("java_version", System.getProperty("java.version"));
        environment.put("os", System.getProperty("os.name"));
        environment.put("architecture", System.getProperty("os.arch"));
        environment.put("timing_scope", "single warm text/JSON plan per condition; observational, not a speed guarantee");
        return environment;
    }

    private List<Map<String, Object>> planIndexes() {
        return db.rows("SELECT tablename, indexname, indexdef FROM pg_indexes WHERE schemaname = 'bankwatch' "
                + "AND tablename LIKE 'security_event%' ORDER BY tablename, indexname", p());
    }

    private static Map<String, Object> planBindings(Map<String, ?> bindings) {
        var result = p();
        bindings.forEach((key, value) -> result.put(key, p("type", value.getClass().getName(), "value", value.toString())));
        return result;
    }

    private Map<String, Object> planSourceHashes() throws Exception {
        var result = p();
        for (String file : List.of("db/schema.sql", "db/seed.sql", "db/grant_runtime.sql",
                "src/main/java/kr/ac/bankwatch/Db.java", "src/main/java/kr/ac/bankwatch/event/EventQueryService.java",
                "src/test/java/kr/ac/bankwatch/PostgresIntegrationTest.java")) {
            result.put(file, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(file)))));
        }
        return result;
    }

    private void writePlanReport(Map<String, Object> report) throws Exception {
        Path output = root.resolve("target/failsafe-reports/bankwatch-test10-query-plans.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, db.json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("TEST-11 / REQ-16: 실제 40P01 뒤 전체 보호 업무를 새 트랜잭션에서 재시도")
    void deadlockRetriesWholeProtectionWithoutMissingOrDuplicateChanges() throws Exception {
        long firstAlert = normalAlert();
        long secondAlert = otherCustomerAlert();
        long firstIncident = Long.parseLong(claims.claim(analystA, firstAlert).incidentId());
        long secondIncident = Long.parseLong(claims.claim(analystB, secondAlert).incidentId());
        var first = new RetryProbe(app, db.json, firstIncident);
        var second = new RetryProbe(app, db.json, secondIncident);
        var initial = List.of(deadlockState(db, firstIncident), deadlockState(db, secondIncident));
        var report = p("test", "TEST-11", "requirement", "REQ-16", "status", "INCOMPLETE",
                "recorded_at_kst", OffsetDateTime.now(java.time.ZoneOffset.ofHours(9)).toString(),
                "initial", initial, "first_attempts", first.attempts, "second_attempts", second.attempts,
                "fixture_scope", "Test-only history trigger locks the other customer; production lock order unchanged");
        boolean complete = false;
        try {
            installDeadlockFixture(firstIncident, secondIncident);
            report.put("environment", planEnvironment());
            var hashes = planSourceHashes();
            String service = "src/main/java/kr/ac/bankwatch/incident/IncidentActionService.java";
            hashes.put(service, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(root.resolve(service)))));
            report.put("source_sha256", hashes);
            var results = deadlockRace(first, second, firstIncident, secondIncident);
            for (var result : results) {
                var action = assertInstanceOf(IncidentActionService.ActionResult.class, result);
                assertEquals(1, action.version());
                assertEquals("IN_PROGRESS", action.processingStatus());
                assertEquals("LOCKED", action.accessStatus());
                assertEquals(action.incidentId(), action.lockIncidentId());
            }
            assertDeadlockAttempts(first, second, initial);
            for (long alert : List.of(firstAlert, secondAlert)) {
                assertEquals(List.of("CLAIM", "LOCK_ACCOUNT"), historyTypes(alert));
            }
            var saved = db.rows("""
                    SELECT i.incident_id, i.version, i.closed_at, i.resolution, c.lock_incident_id
                    FROM bankwatch.incident i JOIN bankwatch.alert a ON a.alert_id = i.alert_id
                    JOIN bankwatch.customer_account c ON c.account_id = a.account_id ORDER BY i.incident_id
                    """, p());
            assertEquals(2, saved.size());
            for (var row : saved) {
                assertEquals(1, ((Number) row.get("version")).intValue());
                assertEquals(row.get("incident_id"), row.get("lock_incident_id"));
                assertNull(row.get("closed_at"));
                assertNull(row.get("resolution"));
            }
            report.put("saved", saved);
            report.put("history_marker_counts", deadlockMarkerCounts());
            report.put("non_retryable", nonRetryableProtectionChecks(firstIncident));
            complete = true;
        } finally {
            removeDeadlockFixture();
            boolean removed = owner.scalar("SELECT to_regprocedure('bankwatch.test_deadlock_history()')", p()) == null;
            report.put("fixture_removed", removed);
            report.put("status", complete && removed ? "PASS" : "INCOMPLETE");
            writeDeadlockReport(report);
            assertTrue(removed);
        }
    }

    private long otherCustomerAlert() {
        var text = new StringBuilder(HEADER);
        var at = OffsetDateTime.parse("2026-11-02T10:10:00+09:00");
        for (int i = 0; i < 5; i++) text.append(100 + i).append(",C1002,").append(at.plusMinutes(i))
                .append(",known,LOGIN_FAILED,\n");
        text.append("105,C1002,").append(at.plusMinutes(5)).append(",new,LOGIN_SUCCESS,true\n");
        assertEquals(new EventService.ImportResult(6, 6, 0), events.importCsv(admin, csv(text.toString())));
        assertEquals(1, detection.detect(admin, at.toString(), at.plusMinutes(10).toString()).createdAlerts());
        return db.number("SELECT alert_id FROM bankwatch.alert WHERE bucket_start = :time", p("time", at));
    }

    private void installDeadlockFixture(long first, long second) throws Exception {
        // 시험 전용: 각 업무의 원인·버전 갱신 후 반대 고객을 잠가 실제 순환 대기를 만든다.
        // 운영 서비스의 고객 → 사건 잠금 순서와 실행 역할 권한은 변경하지 않는다.
        executeOwner("""
                CREATE SEQUENCE bankwatch.test_deadlock_first;
                CREATE SEQUENCE bankwatch.test_deadlock_second;
                CREATE FUNCTION bankwatch.test_deadlock_history() RETURNS trigger
                LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
                DECLARE other_incident bigint;
                BEGIN
                    IF NEW.action_type = 'LOCK_ACCOUNT' AND NEW.incident_id IN (%d, %d) THEN
                        IF NOT EXISTS (SELECT 1 FROM bankwatch.incident i
                            JOIN bankwatch.alert a ON a.alert_id = i.alert_id
                            JOIN bankwatch.customer_account c ON c.account_id = a.account_id
                            WHERE i.incident_id = NEW.incident_id AND i.version = 1 AND c.lock_incident_id = i.incident_id)
                        THEN RAISE EXCEPTION '보호 원인·버전 갱신 뒤 교착 시험에 진입해야 합니다.'; END IF;
                        IF NEW.incident_id = %d THEN
                            PERFORM nextval('bankwatch.test_deadlock_first'::regclass);
                            other_incident := %d;
                        ELSE
                            PERFORM nextval('bankwatch.test_deadlock_second'::regclass);
                            other_incident := %d;
                        END IF;
                        PERFORM pg_advisory_xact_lock_shared(%d);
                        PERFORM c.account_id FROM bankwatch.customer_account c
                            JOIN bankwatch.alert a ON a.account_id = c.account_id
                            JOIN bankwatch.incident i ON i.alert_id = a.alert_id
                            WHERE i.incident_id = other_incident FOR UPDATE OF c;
                    END IF;
                    RETURN NEW;
                END $$;
                REVOKE ALL ON FUNCTION bankwatch.test_deadlock_history() FROM PUBLIC;
                CREATE TRIGGER test_deadlock_gate BEFORE INSERT ON bankwatch.incident_history
                FOR EACH ROW EXECUTE FUNCTION bankwatch.test_deadlock_history();
                """.formatted(first, second, first, second, first, DEADLOCK_GATE));
    }

    private List<Object> deadlockRace(RetryProbe first, RetryProbe second, long firstId, long secondId) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2); var gate = ownerSource.getConnection()) {
            gate.setAutoCommit(false);
            lock(gate, "SELECT pg_advisory_xact_lock(?)", DEADLOCK_GATE);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var left = executor.submit(racer(() -> new IncidentActionService(first)
                    .lock(analystA, firstId, 0, "TEST-11 고객 A 보호"), ready, start));
            var right = executor.submit(racer(() -> new IncidentActionService(second)
                    .lock(analystB, secondId, 0, "TEST-11 고객 B 보호"), ready, start));
            try {
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(2, waitingBackends(2, true).size(), "독립 연결 둘이 각 고객 갱신 후 게이트에 도달해야 한다.");
            } finally { start.countDown(); gate.commit(); }
            return List.of(left.get(15, TimeUnit.SECONDS), right.get(15, TimeUnit.SECONDS));
        }
    }

    private static Map<String, Object> deadlockState(Db database, long incident) {
        return database.one("""
                SELECT to_jsonb(i) AS incident, to_jsonb(c) AS account,
                       (SELECT jsonb_agg(to_jsonb(h) ORDER BY h.history_id) FROM bankwatch.incident_history h
                        WHERE h.incident_id = i.incident_id) AS history
                FROM bankwatch.incident i JOIN bankwatch.alert a ON a.alert_id = i.alert_id
                JOIN bankwatch.customer_account c ON c.account_id = a.account_id WHERE i.incident_id = :incident
                """, p("incident", incident));
    }

    private static final class RetryProbe extends Db {
        private final long incident;
        private final List<Map<String, Object>> attempts = new ArrayList<>();

        private RetryProbe(HikariDataSource source, JsonMapper json, long incident) {
            super(source, json);
            this.incident = incident;
        }

        @Override
        public <T> T tx(long actor, boolean snapshot, Supplier<T> work) {
            // 상태 관찰만 감싼다. 재시도·격리·롤백·커밋은 실제 Db.tx가 수행한다.
            T result = super.tx(actor, snapshot, () -> {
                var attempt = one("""
                        SELECT pg_backend_pid() AS pid, pg_current_xact_id()::text AS transaction_id,
                               current_setting('deadlock_timeout') AS deadlock_timeout,
                               current_setting('lock_timeout') AS lock_timeout
                        """, p());
                attempt.put("number", attempts.size() + 1);
                attempt.put("before", deadlockState(this, incident));
                attempts.add(attempt);
                try { return work.get(); }
                catch (RuntimeException error) {
                    attempt.put("outcome", "FAILED_WORK");
                    attempt.put("sqlstate", Db.sqlState(error));
                    attempt.put("error", error.getMessage());
                    throw error;
                }
            });
            attempts.getLast().put("outcome", "COMMITTED");
            return result;
        }
    }

    private void assertDeadlockAttempts(RetryProbe first, RetryProbe second, List<Map<String, Object>> initial) {
        var probes = List.of(first, second);
        assertEquals(List.of(1, 2), probes.stream().map(probe -> probe.attempts.size()).sorted().toList());
        var all = probes.stream().flatMap(probe -> probe.attempts.stream()).toList();
        assertEquals(3, all.stream().map(attempt -> attempt.get("transaction_id")).distinct().count());
        assertNotEquals(first.attempts.getFirst().get("pid"), second.attempts.getFirst().get("pid"));
        assertEquals(1, all.stream().filter(attempt -> "40P01".equals(attempt.get("sqlstate"))).count());
        assertEquals(2, all.stream().filter(attempt -> "COMMITTED".equals(attempt.get("outcome"))).count());
        for (int i = 0; i < probes.size(); i++) {
            for (var attempt : probes.get(i).attempts) {
                assertEquals(initial.get(i), attempt.get("before"), "실패한 고객·사건·이력 변경 전체가 복원된 뒤 재실행해야 한다.");
            }
        }
        assertEquals(List.of(1L, 2L), deadlockMarkerCounts().stream().sorted().toList());
    }

    private List<Long> deadlockMarkerCounts() {
        return List.of(owner.number("SELECT last_value FROM bankwatch.test_deadlock_first", p()),
                owner.number("SELECT last_value FROM bankwatch.test_deadlock_second", p()));
    }

    private List<Map<String, Object>> nonRetryableProtectionChecks(long incident) {
        var unchanged = caseState();
        var results = new ArrayList<Map<String, Object>>();
        for (int status : List.of(400, 403, 409)) {
            var probe = new RetryProbe(app, db.json, incident);
            var service = new IncidentActionService(probe);
            assertStatus(status, () -> service.lock(status == 403 ? admin : analystA, incident,
                    status == 409 ? 0 : 1, status == 400 ? " " : "재시도하지 않는 업무 오류"));
            assertEquals(1, probe.attempts.size());
            assertEquals("", probe.attempts.getFirst().get("sqlstate"));
            assertEquals(unchanged, caseState());
            results.add(p("http_status", status, "attempts", probe.attempts, "db_unchanged", true));
        }
        return results;
    }

    private void removeDeadlockFixture() throws Exception {
        executeOwner("""
                DROP TRIGGER IF EXISTS test_deadlock_gate ON bankwatch.incident_history;
                DROP FUNCTION IF EXISTS bankwatch.test_deadlock_history();
                DROP SEQUENCE IF EXISTS bankwatch.test_deadlock_first, bankwatch.test_deadlock_second;
                """);
    }

    private void writeDeadlockReport(Map<String, Object> report) throws Exception {
        Path output = root.resolve("target/failsafe-reports/bankwatch-test11-deadlock.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, db.json.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n", StandardCharsets.UTF_8);
    }

    private long normalAlert() throws Exception {
        importSample("events-r02.csv");
        assertEquals(1, detection.detect(admin, FROM, TO).createdAlerts());
        return singleAlert();
    }

    private long secondAlert() {
        var text = new StringBuilder(HEADER);
        var at = OffsetDateTime.parse("2026-11-02T10:10:00+09:00");
        for (int i = 0; i < 5; i++) text.append(100 + i).append(",C1001,").append(at.plusMinutes(i))
                .append(",known,LOGIN_FAILED,\n");
        text.append("105,C1001,").append(at.plusMinutes(5)).append(",new,LOGIN_SUCCESS,true\n");
        events.importCsv(admin, csv(text.toString()));
        assertEquals(1, detection.detect(admin, at.toString(), at.plusMinutes(10).toString()).createdAlerts());
        return db.number("SELECT alert_id FROM bankwatch.alert WHERE bucket_start = :time", p("time", at));
    }

    private List<Object> race(String sql, long key, Supplier<?> first, Supplier<?> second) throws Exception {
        try (var executor = Executors.newFixedThreadPool(2); var gate = ownerSource.getConnection()) {
            gate.setAutoCommit(false);
            lock(gate, sql, key);
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var left = executor.submit(racer(first, ready, start));
            var right = executor.submit(racer(second, ready, start));
            try {
                assertTrue(ready.await(2, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(2, waitingBackends(2, false).size(), "서로 다른 실제 DB 연결 두 개가 대기해야 한다.");
            } finally { start.countDown(); gate.commit(); }
            return List.of(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS));
        }
    }

    private Callable<Object> racer(Supplier<?> action, CountDownLatch ready, CountDownLatch start) {
        return () -> {
            ready.countDown();
            assertTrue(start.await(3, TimeUnit.SECONDS));
            try { return action.get(); }
            catch (ApiProblem problem) { return problem; }
        };
    }

    private Set<Long> waitingBackends(int expected, boolean advisory) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            var rows = db.rows("""
                    SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND usename = current_user
                      AND state = 'active' AND wait_event_type = 'Lock'
                      AND ((:advisory AND wait_event = 'advisory') OR (NOT :advisory AND wait_event <> 'advisory'))
                    """, p("advisory", advisory));
            var pids = new java.util.HashSet<Long>();
            for (var row : rows) pids.add(((Number) row.get("pid")).longValue());
            if (pids.size() == expected) return Set.copyOf(pids);
            Thread.sleep(10);
        }
        fail("요청들이 실제 PostgreSQL 잠금 대기에 도달하지 않았습니다.");
        return Set.of();
    }

    private void installInsertObservation() throws Exception {
        executeOwner("""
                CREATE SEQUENCE bankwatch.test_insert_marker;
                CREATE FUNCTION bankwatch.test_observe_insert() RETURNS trigger
                LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
                BEGIN
                    IF NEW.event_id = 8 THEN PERFORM nextval('bankwatch.test_insert_marker'::regclass); END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER test_insert_observation AFTER INSERT ON bankwatch.security_event
                FOR EACH ROW EXECUTE FUNCTION bankwatch.test_observe_insert();
                """);
    }

    private void installHistoryGate(long incident) throws Exception {
        executeOwner("""
                CREATE SEQUENCE bankwatch.test_history_marker;
                CREATE FUNCTION bankwatch.test_gate_history() RETURNS trigger
                LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, pg_temp AS $$
                BEGIN
                    IF NEW.incident_id = %d AND NEW.action_type = 'LOCK_ACCOUNT' THEN
                        IF NOT EXISTS (SELECT 1 FROM bankwatch.incident i
                            JOIN bankwatch.alert a ON a.alert_id = i.alert_id
                            JOIN bankwatch.customer_account c ON c.account_id = a.account_id
                            WHERE i.incident_id = NEW.incident_id AND i.version = 1 AND c.lock_incident_id = i.incident_id)
                        THEN RAISE EXCEPTION '원인과 버전 UPDATE가 먼저 실행되어야 합니다.'; END IF;
                        PERFORM nextval('bankwatch.test_history_marker'::regclass);
                        PERFORM pg_advisory_xact_lock(%d);
                    END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER test_history_gate BEFORE INSERT ON bankwatch.incident_history
                FOR EACH ROW EXECUTE FUNCTION bankwatch.test_gate_history();
                """.formatted(incident, HISTORY_GATE));
    }

    private void installHistoryFailure(long incident, String body) throws Exception {
        // body는 아래 테스트의 고정 SQL 두 종류만 전달한다. 요청 입력은 사용하지 않는다.
        executeOwner("""
                CREATE FUNCTION bankwatch.test_fail_history() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.incident_id = %d AND NEW.action_type <> 'CLAIM' THEN %s END IF;
                    RETURN NEW;
                END $$;
                CREATE TRIGGER test_history_failure BEFORE INSERT ON bankwatch.incident_history
                FOR EACH ROW EXECUTE FUNCTION bankwatch.test_fail_history();
                """.formatted(incident, body));
    }

    private void removeHistoryFailure() throws Exception {
        executeOwner("DROP TRIGGER test_history_failure ON bankwatch.incident_history; DROP FUNCTION bankwatch.test_fail_history()");
    }

    private Map<String, String> caseState() { return fingerprint("customer_account", "incident", "incident_history"); }

    private Map<String, String> fingerprint(String... tables) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (String table : tables) {
            assertTrue(TABLES.contains(table));
            result.put(table, (String) db.scalar("SELECT md5(COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text), '[]'::jsonb)::text) FROM bankwatch." + table + " t", p()));
        }
        return Map.copyOf(result);
    }

    private List<Object> historyTypes(long alert) {
        return queries.detail(admin, alert).history().stream().map(row -> row.get("action_type")).toList();
    }

    private long operator(String login) { return db.number("SELECT operator_id FROM bankwatch.operator_user WHERE login_id = :login", p("login", login)); }
    private long singleAlert() { assertEquals(1, count("alert")); return db.number("SELECT alert_id FROM bankwatch.alert", p()); }
    private long incidentVersion(long id) { return db.number("SELECT version FROM bankwatch.incident WHERE incident_id = :id", p("id", id)); }
    private long count(String table) { assertTrue(TABLES.contains(table)); return db.number("SELECT count(*) FROM bankwatch." + table, p()); }

    private EventService.ImportResult importSample(String name) throws Exception {
        return events.importCsv(admin, new MockMultipartFile("file", name, "text/csv", Files.readAllBytes(root.resolve("samples").resolve(name))));
    }

    private static MockMultipartFile csv(String text) {
        return new MockMultipartFile("file", "fixture.csv", "text/csv", text.getBytes(StandardCharsets.UTF_8));
    }

    private void insertEvent(Map<String, Object> params) {
        db.jdbc.update("""
                INSERT INTO bankwatch.security_event (occurred_at, event_id, account_id, device_ref, event_type, is_new_device)
                VALUES (:time, :id, :account, 'direct-constraint-test', :type, :flag)
                """, params);
    }

    private static void assertStatus(int status, Runnable work) { assertEquals(status, assertThrows(ApiProblem.class, work::run).status); }
    private static void assertSqlState(String state, Runnable work) { assertEquals(state, Db.sqlState(assertThrows(DataAccessException.class, work::run))); }

    private static <T> T onlySuccess(List<Object> outcomes, Class<T> type) {
        var matching = outcomes.stream().filter(type::isInstance).map(type::cast).toList();
        assertEquals(1, matching.size(), "요청 두 개 중 해당 결과는 정확히 하나여야 합니다.");
        return matching.getFirst();
    }

    private static void lock(Connection connection, String sql, long key) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            statement.setLong(1, key);
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
        }
    }

    private void executeOwner(String sql) throws Exception {
        try (var connection = ownerSource.getConnection(); var statement = connection.createStatement()) { statement.execute(sql); }
    }

    private String read(String path) throws Exception { return Files.readString(root.resolve(path), StandardCharsets.UTF_8); }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("검증 환경 변수 필요: " + name);
        return value;
    }
}
