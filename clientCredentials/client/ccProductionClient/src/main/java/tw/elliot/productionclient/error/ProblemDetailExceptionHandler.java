package tw.elliot.productionclient.error;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tw.elliot.productionclient.observability.TraceIdFilter;

/**
 * RFC 7807 (Problem Details for HTTP APIs) exception handler.
 *
 * Design choices:
 *   - Every response carries a traceId; the same id appears in MDC, the
 *     response header (X-Trace-Id) and the body — so a caller pasting their
 *     traceId is enough for ops to find the log line.
 *   - Outward-facing messages are deliberately short. Stack traces / upstream
 *     bodies stay in the server log to avoid leaking internals.
 *   - We rely on Spring's built-in exception types rather than inventing a
 *     code enum. The {@code type} URI is the public contract.
 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);
    private static final String TYPE_BASE = "https://api.example.com/problems/";

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ProblemDetail> onMissingParam(MissingServletRequestParameterException ex,
                                                        HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "bad-request",
                "Missing required parameter: " + ex.getParameterName(), req);
        return respond(pd);
    }

    @ExceptionHandler(ClientAuthorizationException.class)
    public ResponseEntity<ProblemDetail> onTokenError(ClientAuthorizationException ex,
                                                      HttpServletRequest req) {
        Throwable root = rootCause(ex);
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.http.HttpTimeoutException) {
            ProblemDetail pd = problem(HttpStatus.GATEWAY_TIMEOUT, "token-unavailable",
                    "Authorization server is unreachable or did not respond in time", req);
            log.warn("Token endpoint failure: {}", ex.toString());
            return respond(pd);
        }
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "token-rejected",
                "Authorization server rejected the request", req);
        log.warn("Token rejected: oauthCode={}", ex.getError() == null ? null : ex.getError().getErrorCode());
        return respond(pd);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    public ResponseEntity<ProblemDetail> onCircuitOpen(CallNotPermittedException ex,
                                                       HttpServletRequest req) {
        // Fail fast while the circuit is open — do not even attempt the upstream.
        ProblemDetail pd = problem(HttpStatus.SERVICE_UNAVAILABLE, "upstream-circuit-open",
                "Upstream is being shielded due to repeated failures; try again shortly", req);
        return respond(pd);
    }

    @ExceptionHandler(HttpClientErrorException.Unauthorized.class)
    public ResponseEntity<ProblemDetail> onUnauthorized(HttpClientErrorException.Unauthorized ex,
                                                        HttpServletRequest req) {
        // This is the SECOND 401 (post-evict-retry); the first one was handled in service.
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "upstream-unauthorized",
                "Resource server rejected the token after refresh", req);
        return respond(pd);
    }

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ProblemDetail> onUpstream4xx(HttpClientErrorException ex,
                                                       HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "upstream-client-error",
                "Resource server returned " + ex.getStatusCode().value(), req);
        return respond(pd);
    }

    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<ProblemDetail> onUpstream5xx(HttpServerErrorException ex,
                                                       HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.BAD_GATEWAY, "upstream-server-error",
                "Resource server returned " + ex.getStatusCode().value(), req);
        log.warn("Upstream 5xx after retries: {}", ex.getStatusCode());
        return respond(pd);
    }

    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<ProblemDetail> onTransport(ResourceAccessException ex,
                                                     HttpServletRequest req) {
        ProblemDetail pd = problem(HttpStatus.GATEWAY_TIMEOUT, "upstream-unreachable",
                "Resource server is unreachable or did not respond in time", req);
        log.warn("Transport failure after retries: {}", rootCause(ex).toString());
        return respond(pd);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> onUnexpected(Exception ex, HttpServletRequest req) {
        // Never expose ex.getMessage() — could leak internals/PII.
        ProblemDetail pd = problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred", req);
        log.error("Unhandled exception", ex);
        return respond(pd);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ProblemDetail problem(HttpStatus status, String typeSlug,
                                         String detail, HttpServletRequest req) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create(TYPE_BASE + typeSlug));
        pd.setTitle(humanize(typeSlug));
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("traceId", TraceIdFilter.current());
        return pd;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).body(pd);
    }

    private static String humanize(String slug) {
        StringBuilder sb = new StringBuilder();
        for (String part : slug.split("-")) {
            if (part.isEmpty()) continue;
            sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }
}
