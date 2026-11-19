package kr.ac.bankwatch.event;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import kr.ac.bankwatch.ApiProblem;
import kr.ac.bankwatch.Db;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import static kr.ac.bankwatch.Db.p;

@Service
public class EventService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_ROWS = 5000;
    private static final List<String> HEADERS = List.of(
            "event_id", "customer_ref", "occurred_at", "device_ref", "event_type", "is_new_device");
    private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
    private static final Pattern OFFSET = Pattern.compile("(?:Z|[+-]\\d{2}:\\d{2})$", Pattern.CASE_INSENSITIVE);
    // QRY-01: actor는 Controller가 인증 세션에서 얻은 운영자 ID이며 폼에서 받지 않는다.
    private static final String OPERATOR = """
            SELECT role, enabled FROM bankwatch.operator_user WHERE operator_id = :actor
            """;
    // QRY-02: 고객 존재 여부는 각 INSERT 직전에 조회한다. 파일 전체를 미리 조회하지 않는다.
    private static final String CUSTOMER = """
            SELECT account_id FROM bankwatch.customer_account WHERE customer_ref = :customer
            """;
    // QRY-03: 복합키 충돌만 건너뛰고, CHECK/FK/파티션 오류는 건너뛰지 않는다.
    private static final String INSERT = """
            INSERT INTO bankwatch.security_event
                (occurred_at, event_id, account_id, device_ref, event_type, is_new_device)
            VALUES (:time, :event, :account, :device, :type, :newDevice)
            ON CONFLICT (occurred_at, event_id) DO NOTHING
            """;
    // QRY-04: READ COMMITTED의 새 문장으로 충돌한 상대의 커밋 내용을 확인한다.
    private static final String EXISTING = """
            SELECT account_id, device_ref, event_type, is_new_device
            FROM bankwatch.security_event WHERE occurred_at = :time AND event_id = :event
            """;
    private final Db db;

    public EventService(Db db) { this.db = Objects.requireNonNull(db); }

    public record ImportResult(int totalRows, int insertedRows, int duplicateRows) {}

    private record EventRow(long rowNumber, long eventId, String customerRef, OffsetDateTime occurredAt,
                            String deviceRef, String eventType, Boolean newDevice) {}

    public ImportResult importCsv(long actor, MultipartFile file) {
        requireAdmin(actor);
        var rows = parse(read(file));
        return db.tx(actor, false, () -> {
            requireAdmin(actor);
            int inserted = 0;
            int duplicates = 0;
            for (var row : rows) {
                var accounts = db.rows(CUSTOMER, p("customer", row.customerRef()));
                if (accounts.isEmpty()) throw invalid(row.rowNumber(), "등록되지 않은 고객 참조입니다.");
                long account = ((Number) accounts.get(0).get("account_id")).longValue();
                if (store(row, account)) inserted++;
                else duplicates++;
            }
            return new ImportResult(rows.size(), inserted, duplicates);
        });
    }

    private void requireAdmin(long actor) {
        if (actor <= 0) throw new ApiProblem(401, "로그인이 필요합니다.");
        var users = db.rows(OPERATOR, p("actor", actor));
        if (users.isEmpty() || !Boolean.TRUE.equals(users.get(0).get("enabled"))
                || !"ADMIN".equals(users.get(0).get("role"))) {
            throw new ApiProblem(403, "활성 관리자 권한이 필요합니다.");
        }
    }

    private static byte[] read(MultipartFile file) {
        if (file == null) throw new ApiProblem(400, "CSV 파일을 선택하세요.");
        if (file.getSize() > MAX_BYTES) throw new ApiProblem(413, "파일은 2 MiB 이하로 입력하세요.");
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new ApiProblem(413, "파일은 2 MiB 이하로 입력하세요.");
            if (bytes.length == 0) throw new ApiProblem(400, "데이터가 없습니다.");
            return bytes;
        } catch (IOException error) {
            throw new ApiProblem(400, "CSV 파일을 읽지 못했습니다.");
        }
    }

    private static List<EventRow> parse(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new ApiProblem(400, "CSV 파일은 올바른 UTF-8이어야 합니다.");
        }
        if (text.startsWith("\ufeff")) text = text.substring(1);
        var format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(false).setTrim(false).setAllowMissingColumnNames(false)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();
        try (CSVParser csv = CSVParser.parse(new StringReader(text), format)) {
            if (!csv.getHeaderNames().equals(HEADERS)) throw new ApiProblem(400, "CSV 헤더의 이름과 순서를 샘플과 맞추세요.");
            List<EventRow> rows = new ArrayList<>();
            for (var record : csv) {
                if (rows.size() >= MAX_ROWS) throw invalid(record.getRecordNumber(), "최대 5,000행입니다.");
                if (!record.isConsistent()) throw invalid(record.getRecordNumber(), "열 수를 확인하세요.");
                rows.add(event(record));
            }
            if (rows.isEmpty()) throw new ApiProblem(400, "데이터가 없습니다.");
            rows.sort(Comparator.comparing(EventRow::occurredAt).thenComparingLong(EventRow::eventId)
                    .thenComparingLong(EventRow::rowNumber));
            return List.copyOf(rows);
        } catch (IOException | UncheckedIOException | IllegalArgumentException error) {
            throw new ApiProblem(400, "CSV 헤더·열·따옴표 형식을 확인하세요.");
        }
    }

    private static EventRow event(CSVRecord record) {
        long row = record.getRecordNumber();
        long eventId;
        try {
            String value = record.get("event_id");
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            eventId = Long.parseLong(value);
            if (eventId <= 0) throw new NumberFormatException();
        } catch (NumberFormatException error) {
            throw invalid(row, "event_id는 bigint 범위의 양의 정수여야 합니다.");
        }
        String customer = identifier(record.get("customer_ref"), 40, row, "customer_ref");
        String device = identifier(record.get("device_ref"), 100, row, "device_ref");
        String type = record.get("event_type");
        String flag = record.get("is_new_device");
        Boolean newDevice;
        if ("LOGIN_SUCCESS".equals(type)) {
            if (!"true".equals(flag) && !"false".equals(flag)) throw invalid(row, "성공은 신규 기기 true/false가 필요합니다.");
            newDevice = Boolean.valueOf(flag);
        } else if ("LOGIN_FAILED".equals(type)) {
            if (!flag.isEmpty()) throw invalid(row, "실패의 신규 기기 값은 비워야 합니다.");
            newDevice = null;
        } else throw invalid(row, "LOGIN_FAILED 또는 LOGIN_SUCCESS만 허용합니다.");
        return new EventRow(row, eventId, customer, time(record.get("occurred_at"), row), device, type, newDevice);
    }

    private static String identifier(String value, int maximum, long row, String field) {
        if (value.isBlank() || value.indexOf('\0') >= 0 || value.codePointCount(0, value.length()) > maximum) {
            throw invalid(row, field + "는 공백만으로 구성할 수 없고 최대 " + maximum + "자이며 NUL을 포함할 수 없습니다.");
        }
        return value;
    }

    private static OffsetDateTime time(String value, long row) {
        var fraction = FRACTION.matcher(value);
        if (!OFFSET.matcher(value).find() || fraction.find() && fraction.group(1).length() > 6) {
            throw invalid(row, "시각은 UTC 오프셋을 포함하고 소수 초는 최대 6자리여야 합니다.");
        }
        try { return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC); }
        catch (DateTimeParseException error) { throw invalid(row, "날짜와 UTC 오프셋을 포함한 ISO 8601 시각을 확인하세요."); }
    }

    private boolean store(EventRow row, long account) {
        var params = p("time", row.occurredAt(), "event", row.eventId(), "account", account,
                "device", row.deviceRef(), "type", row.eventType(), "newDevice", row.newDevice());
        try {
            if (db.jdbc.update(INSERT, params) == 1) return true;
            var matches = db.rows(EXISTING, params);
            if (matches.isEmpty()) throw new ApiProblem(409, "원본이 변경되었습니다. 파일을 다시 확인하세요.");
            var existing = matches.get(0);
            if (((Number) existing.get("account_id")).longValue() != account
                    || !row.deviceRef().equals(existing.get("device_ref"))
                    || !row.eventType().equals(existing.get("event_type"))
                    || !Objects.equals(row.newDevice(), existing.get("is_new_device"))) {
                throw invalid(row.rowNumber(), "같은 복합키의 기존 기록과 내용이 다릅니다. 신규 입력을 취소합니다.");
            }
            return false;
        } catch (DataAccessException error) {
            String state = Db.sqlState(error);
            if (state.startsWith("22") || state.startsWith("23")) {
                throw invalid(row.rowNumber(), "발생 시각의 저장 파티션과 로그 제약을 확인하세요.");
            }
            throw error;
        }
    }

    private static ApiProblem invalid(long row, String message) {
        return new ApiProblem(400, "CSV 데이터 " + row + "행(헤더 제외): " + message);
    }
}
