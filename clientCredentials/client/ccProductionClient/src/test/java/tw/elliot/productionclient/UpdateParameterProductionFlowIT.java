package tw.elliot.productionclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tw.elliot.productionclient.observability.TraceIdFilter;

/**
 * Integration tests exercising the production patterns end-to-end:
 *   - RFC 7807 ProblemDetail + traceId envelope
 *   - Resilience4j retry on transport / 5xx
 *   - Resilience4j circuit breaker open behavior
 *   - 401-evict-and-retry-once for revoked tokens
 *
 * WireMock is used (rather than MockWebServer) because retry / CB tests rely on
 * stateful scenarios that WireMock expresses natively.
 */
@SpringBootTest
class UpdateParameterProductionFlowIT {

    private static WireMockServer authServer;
    private static WireMockServer resourceServer;

    @Autowired private WebApplicationContext webContext;
    @Autowired private OAuth2AuthorizedClientService authorizedClientService;
    @Autowired private CircuitBreaker resourceCircuitBreaker;
    @Autowired private TraceIdFilter traceIdFilter;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startMocks() {
        authServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        resourceServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        authServer.start();
        resourceServer.start();
    }

    @AfterAll
    static void stopMocks() {
        authServer.stop();
        resourceServer.stop();
    }

    @DynamicPropertySource
    static void wireMocks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.client.provider.resource-server.token-uri",
                () -> "http://localhost:" + authServer.port() + "/oauth2/token");
        r.add("app.resource-server.base-url",
                () -> "http://localhost:" + resourceServer.port());
    }

    @BeforeEach
    void resetState() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext)
                .addFilters(traceIdFilter)
                .build();
        authServer.resetAll();
        resourceServer.resetAll();
        authorizedClientService.removeAuthorizedClient("resource-server", "resource-server");
        authorizedClientService.removeAuthorizedClient("resource-server", "anonymousUser");
        // Each test starts with a closed circuit.
        resourceCircuitBreaker.reset();
    }

    private static void stubTokenOk() {
        authServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"fake-token\",\"token_type\":\"Bearer\","
                                + "\"expires_in\":3600,\"scope\":\"admin\"}")));
    }

    // ── ProblemDetail + traceId envelope ───────────────────────────────────────

    @Test
    void happyPath_returnsBodyAndTraceIdHeader() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"message\":\"OK\"}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("X-Trace-Id")).isNotBlank();
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(0);
    }

    @Test
    void missingParam_returnsProblemDetail() throws Exception {
        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType())
                .startsWith("application/problem+json");
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("/problems/bad-request");
        assertThat(body.get("title")).isEqualTo("Bad Request");
        assertThat(body.get("status")).isEqualTo(400);
        assertThat(body.get("instance")).isEqualTo("/client/UpdateParameter");
        assertThat(body.get("traceId")).asString().isNotBlank();
        assertThat(result.getResponse().getHeader("X-Trace-Id"))
                .isEqualTo(body.get("traceId"));
    }

    // (Custom X-Trace-Id incoming-header propagation was removed when the
    //  OpenTelemetry starter was adopted; callers should use W3C `traceparent`.
    //  The response still echoes whatever traceId ended up in MDC.)

    // ── Resilience4j retry on transport / 5xx ──────────────────────────────────

    @Test
    void resource5xx_isRetriedAndEventuallySucceeds() throws Exception {
        stubTokenOk();
        String scenario = "retry-5xx";
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("second"));
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .inScenario(scenario)
                .whenScenarioStateIs("second")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0}"))
                .willSetStateTo("done"));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // Verify retry actually fired (2 attempts: 503 then 200)
        resourceServer.verify(2, postRequestedFor(urlPathEqualTo("/sEQI/Param/UpdateParameter")));
    }

    @Test
    void resourcePersistent5xx_exhaustsRetriesAndReturnsBadGateway() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse().withStatus(503)));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("upstream-server-error");
        // maxAttempts=3 in ResilienceConfig
        resourceServer.verify(3, postRequestedFor(urlPathEqualTo("/sEQI/Param/UpdateParameter")));
    }

    // ── 401 evict-and-retry-once ───────────────────────────────────────────────

    @Test
    void resource401_evictsCachedTokenAndRetriesOnceWithFreshToken() throws Exception {
        // Two token requests expected: the original cached one, plus a fresh fetch
        // after the 401 triggers eviction. Both go to the same stub.
        stubTokenOk();

        String scenario = "evict-401";
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .inScenario(scenario)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(401))
                .willSetStateTo("after-evict"));
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .inScenario(scenario)
                .whenScenarioStateIs("after-evict")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"code\":0,\"message\":\"OK after refresh\"}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("message")).isEqualTo("OK after refresh");
        // Token endpoint hit twice (original + post-evict refresh)
        authServer.verify(2, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    void resourceRepeated401_returnsBadGatewayAfterSecondAttempt() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse().withStatus(401)));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("upstream-unauthorized");
    }

    // ── Circuit breaker open ───────────────────────────────────────────────────

    @Test
    void circuitOpens_afterSustainedFailures_shortCircuitsNextCall() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse().withStatus(503)));

        // ResilienceConfig: minimumNumberOfCalls=5, failureRateThreshold=50%,
        // slidingWindow=10. Each request consumes up to 3 retry attempts → 3 CB
        // calls. Two failing requests = 6 CB calls → exceeds minimum and trips CB.
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/client/UpdateParameter")
                    .param("paramType", "01").param("version", "v1"));
        }

        assertThat(resourceCircuitBreaker.getState())
                .isEqualTo(CircuitBreaker.State.OPEN);

        // Next call should be short-circuited; CallNotPermittedException →
        // 503 upstream-circuit-open. WireMock should NOT see any new call.
        resourceServer.resetRequests();
        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(503);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("upstream-circuit-open");
        resourceServer.verify(0, postRequestedFor(urlPathEqualTo("/sEQI/Param/UpdateParameter")));
    }

    // ── Transport-level timeouts ───────────────────────────────────────────────

    @Test
    void resourceReadTimeout_isRetriedThenReturnsGatewayTimeout() throws Exception {
        stubTokenOk();
        // withFixedDelay >> RestClientConfig READ_TIMEOUT (2s) → client aborts
        // with HttpTimeoutException, wrapped as ResourceAccessException.
        // Resilience4j classifies it as retryable → 3 attempts total.
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5_000)
                        .withBody("{\"code\":0}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("upstream-unreachable");
        // Verifies retry policy actually retried on transport failure
        resourceServer.verify(3, postRequestedFor(urlPathEqualTo("/sEQI/Param/UpdateParameter")));
    }

    @Test
    void tokenEndpointTimeout_returnsGatewayTimeoutAndDoesNotRetry() throws Exception {
        // Critical test: ResilienceConfig deliberately does NOT wrap the token
        // client in Resilience4j. This test asserts the design decision is
        // honored — token endpoint is called only ONCE, even on timeout.
        authServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(5_000)
                        .withBody("{\"access_token\":\"never\"}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("token-unavailable");
        authServer.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    @Test
    void resourceConnectRefused_isRetriedThenReturnsGatewayTimeout() throws Exception {
        stubTokenOk();
        // resourceServer is running on a dynamic port. Stopping it for the
        // duration of this test forces ConnectException on every attempt.
        int port = resourceServer.port();
        resourceServer.stop();
        try {
            MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                            .param("paramType", "01")
                            .param("version", "v1"))
                    .andReturn();

            assertThat(result.getResponse().getStatus()).isEqualTo(504);
            Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
            assertThat(body.get("type")).asString().contains("upstream-unreachable");
        } finally {
            // Restart on the SAME port so subsequent tests' baseUrl still resolves.
            // (resourceServer is static, port was bound at class start.)
            resourceServer = new WireMockServer(
                    WireMockConfiguration.options().port(port));
            resourceServer.start();
        }
    }

    // ── Token endpoint error response ──────────────────────────────────────────

    @Test
    void tokenInvalidClient_returnsBadGatewayTokenRejected() throws Exception {
        // OAuth2 spec: 400 with {"error":"invalid_client"} is what Spring
        // Security's OAuth2ErrorResponseErrorHandler parses into a
        // ClientAuthorizationException with error.errorCode="invalid_client".
        authServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"invalid_client\"}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("type")).asString().contains("token-rejected");
        authServer.verify(1, postRequestedFor(urlPathEqualTo("/oauth2/token")));
    }

    // ── Actuator metrics endpoint is wired ─────────────────────────────────────

    @Test
    void actuatorMetricsEndpoint_isAvailable() throws Exception {
        MvcResult result = mockMvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/actuator/metrics")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("resilience4j");
    }

}
