package tw.elliot.productionclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies that an incoming W3C {@code traceparent} header is honored by the
 * OpenTelemetry starter: the server-side span adopts the inbound trace-id,
 * MDC inherits it, and {@link tw.elliot.productionclient.observability.TraceIdFilter}
 * surfaces it as the {@code X-Trace-Id} response header.
 *
 * Uses {@code RANDOM_PORT} + a real {@link HttpClient} rather than MockMvc,
 * because the OTel servlet instrumentation filter is registered against the
 * servlet container and does not necessarily run in a MockMvc filter chain.
 *
 * Endpoint choice: deliberately omit the {@code version} param so the request
 * fails fast at validation (400 ProblemDetail). That avoids needing to stub
 * the auth server / resource server just to verify trace propagation — the
 * traceparent → MDC → response header chain runs before any outbound call.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class W3CTraceparentPropagationIT {

    private static final String INCOMING_TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String INCOMING_TRACEPARENT =
            "00-" + INCOMING_TRACE_ID + "-b7ad6b7169203331-01";

    @LocalServerPort int port;

    @DynamicPropertySource
    static void stubExternal(DynamicPropertyRegistry r) {
        // Not actually contacted in this test, but the OAuth2 client registration
        // needs a parseable token-uri at context startup.
        r.add("spring.security.oauth2.client.provider.resource-server.token-uri",
                () -> "http://localhost:1/oauth2/token");
        r.add("app.resource-server.base-url", () -> "http://localhost:1");
    }

    @Test
    void incomingTraceparent_isAdoptedAndSurfacedOnResponse() throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/client/UpdateParameter"))
                .timeout(Duration.ofSeconds(5))
                .header("traceparent", INCOMING_TRACEPARENT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                // Missing "version" → ProblemDetail 400 before any service call.
                .POST(HttpRequest.BodyPublishers.ofString("paramType=01"))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);

        String xTraceId = response.headers().firstValue("X-Trace-Id").orElse(null);
        assertThat(xTraceId)
                .as("X-Trace-Id should reflect the trace-id portion of incoming traceparent")
                .isEqualTo(INCOMING_TRACE_ID);

        assertThat(response.body())
                .as("ProblemDetail body should carry the same traceId")
                .contains("\"traceId\":\"" + INCOMING_TRACE_ID + "\"");
    }
}
