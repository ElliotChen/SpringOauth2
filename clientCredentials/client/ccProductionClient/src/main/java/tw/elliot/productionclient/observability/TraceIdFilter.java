package tw.elliot.productionclient.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Complements the OpenTelemetry starter with the one thing it deliberately
 * does NOT do: surface the traceId as a response header so callers (curl,
 * support tickets, non-OTel clients) can quote it.
 *
 * Behavior:
 *   - If MDC already has a {@code traceId} (the OTel starter populated it
 *     from an incoming {@code traceparent} or a fresh root span), echo it
 *     verbatim to the {@code X-Trace-Id} response header.
 *   - If MDC is empty (tracing disabled, or the OTel servlet filter did not
 *     run — e.g. some MockMvc setups), generate a UUID fallback so the
 *     response header / log / ProblemDetail body still have a correlation id.
 *
 * This filter no longer generates trace context — that is the OTel starter's
 * job. It also no longer accepts a custom {@code X-Trace-Id} request header
 * for trace propagation; use W3C {@code traceparent} for that.
 */
@Component
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain)
            throws ServletException, IOException {
        String traceId = MDC.get(MDC_KEY);
        boolean usingFallback = (traceId == null || traceId.isBlank());
        if (usingFallback) {
            traceId = UUID.randomUUID().toString().replace("-", "");
            MDC.put(MDC_KEY, traceId);
        }
        res.setHeader(TRACE_ID_HEADER, traceId);
        try {
            chain.doFilter(req, res);
        } finally {
            if (usingFallback) {
                MDC.remove(MDC_KEY);
            }
        }
    }

    /** Returns the current request's traceId, or empty string if none. */
    public static String current() {
        String t = MDC.get(MDC_KEY);
        return t == null ? "" : t;
    }
}
