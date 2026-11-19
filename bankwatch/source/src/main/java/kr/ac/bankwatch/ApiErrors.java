package kr.ac.bankwatch;

import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiErrors extends ResponseEntityExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);
    private static final Set<String> CONFLICTS = Set.of("40P01", "40001", "55P03", "57014");
    private static final Set<String> DISCONNECTED = Set.of("57P01", "57P02", "57P03");

    @ExceptionHandler(ApiProblem.class)
    public ResponseEntity<Object> domain(ApiProblem error) {
        if (error.status < 400 || error.status > 599) return response(500, message(500), HttpHeaders.EMPTY);
        return response(error.status, error.getMessage() == null ? message(error.status) : error.getMessage(), HttpHeaders.EMPTY);
    }

    // 크기 초과의 413은 부모 클래스가 처리한다. 일반 multipart 파싱 실패는 입력 오류다.
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Object> multipart(MultipartException error) {
        return response(400, "업로드 요청 형식을 확인하세요.", HttpHeaders.EMPTY);
    }

    @ExceptionHandler({AuthenticationException.class, AccessDeniedException.class})
    public ResponseEntity<Object> security(Exception error) {
        int status = error instanceof AuthenticationException ? 401 : 403;
        return response(status, message(status), HttpHeaders.EMPTY);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> error(Exception error) {
        String state = Db.sqlState(error);
        if (!state.isEmpty()) LOG.warn("Database request failed: type={}, SQLSTATE={}", error.getClass().getSimpleName(), state);
        boolean rollbackFailed = error instanceof TransactionSystemException system
                && system.getApplicationException() != null;
        if (rollbackFailed || state.startsWith("08") || DISCONNECTED.contains(state)) {
            return response(503, message(503), HttpHeaders.EMPTY);
        }
        if (CONFLICTS.contains(state)) return response(409, message(409), HttpHeaders.EMPTY);
        if (state.startsWith("22") || state.startsWith("23")) {
            return response(400, "입력 자료형과 데이터 제약을 확인하세요.", HttpHeaders.EMPTY);
        }
        if (error instanceof IllegalArgumentException) return response(400, message(400), HttpHeaders.EMPTY);
        // DB의 DETAIL·메시지로 HTTP 상태를 정하지 않는다. 재시도는 Db.tx에서만 수행한다.
        LOG.error("Unhandled request failure: type={}, SQLSTATE={}", error.getClass().getSimpleName(), state.isEmpty() ? "-" : state);
        return response(500, message(500), HttpHeaders.EMPTY);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
                                                              HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) LOG.error("Framework request failure: type={}, status={}", error.getClass().getSimpleName(), status.value());
        return super.handleExceptionInternal(error, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers,
                                                          HttpStatusCode status, WebRequest request) {
        // Allow 등 표준 오류 헤더와 상태는 보존하고 예외 원문·ProblemDetail은 노출하지 않는다.
        return response(status.value(), message(status.value()), headers);
    }

    private static ResponseEntity<Object> response(int status, String message, HttpHeaders headers) {
        return ResponseEntity.status(status).headers(headers).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message, "status", status));
    }

    private static String message(int status) {
        return switch (status) {
            case 400 -> "요청 값과 형식을 확인하세요.";
            case 401 -> "로그인이 필요합니다.";
            case 403 -> "요청을 처리할 권한이 없습니다.";
            case 404 -> "대상을 찾을 수 없습니다.";
            case 405 -> "허용하지 않는 요청 방식입니다.";
            case 406 -> "요청한 응답 형식을 제공할 수 없습니다.";
            case 409 -> "처리 중 충돌이 발생했습니다. 최신 상태를 확인한 뒤 다시 시도하세요.";
            case 413 -> "파일은 2 MiB 이하이고 전체 요청은 3 MiB 이하여야 합니다.";
            case 415 -> "요청 본문의 형식을 확인하세요.";
            case 503 -> "처리 결과를 확인할 수 없습니다. 최신 상태를 조회한 뒤 다시 시도하세요.";
            default -> "처리 중 오류가 발생했습니다. 최신 상태를 확인하세요.";
        };
    }
}
