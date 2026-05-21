package tw.elliot.errorclient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
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
import tw.elliot.errorclient.error.ErrorCode;
import tw.elliot.errorclient.error.ErrorStage;

/**
 * WireMock 版本的端到端錯誤路徑測試，與 {@link UpdateParameterClientFlowMockWebServerIT}（MockWebServer 版）
 * 涵蓋相同情境，目的是對照兩種測試框架在同一份 production code 上的寫法差異。
 *
 * 對照重點：
 *   - MockWebServer 用 FIFO {@code enqueue}；WireMock 用 {@code stubFor} + request matching DSL
 *   - MockWebServer 用 {@code SocketPolicy.NO_RESPONSE} 模擬 timeout；
 *     WireMock 對應的等價做法是 {@code withFixedDelay(...)}（搭配 client 端較短的 read timeout）。
 *     若要模擬連線層異常，WireMock 可改用 {@link Fault#CONNECTION_RESET_BY_PEER} 等 fault 注入。
 */
@SpringBootTest
class UpdateParameterClientFlowWireMockIT {

    private static WireMockServer authServer;
    private static WireMockServer resourceServer;

    @Autowired private WebApplicationContext webContext;
    @Autowired private OAuth2AuthorizedClientService authorizedClientService;

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
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        // WireMock stubs accumulate across tests; reset per test for isolation.
        authServer.resetAll();
        resourceServer.resetAll();
        // Drop any cached access token from previous test
        authorizedClientService.removeAuthorizedClient("resource-server", "errorClient");
        authorizedClientService.removeAuthorizedClient("resource-server", "anonymousUser");
    }

    private static void stubTokenOk() {
        authServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"fake-token\",\"token_type\":\"Bearer\","
                                + "\"expires_in\":3600,\"scope\":\"admin\"}")));
    }

    // ── Step 1: Happy path + INPUT ────────────────────────────────────────────

    @Test
    void happyPath_returnsResourceBody() throws Exception {
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
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(0);
    }

    @Test
    void missingParameter_returnsInputError() throws Exception {
        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.INPUT_MISSING_PARAMETER.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.INPUT.name());
    }

    // ── Step 2: TOKEN scenarios ───────────────────────────────────────────────

    @Test
    void tokenInvalidClient_returnsTokenError() throws Exception {
        // OAuth2 spec allows 400 or 401 for invalid_client. Spring Security's
        // OAuth2ErrorResponseErrorHandler parses the OAuth2 error body only on 400,
        // so we use 400 to get a ClientAuthorizationException with code "invalid_client".
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
        assertThat(body.get("code")).isEqualTo(ErrorCode.TOKEN_INVALID_CLIENT.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.TOKEN.name());
    }

    @Test
    void tokenEndpointTimeout_returnsGatewayTimeout() throws Exception {
        // WireMock 沒有 MockWebServer 的 NO_RESPONSE 等價；以 withFixedDelay 製造一個
        // 超過 client read timeout 的延遲，讓 RestClient 端拋出 HttpTimeoutException。
        authServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(60_000)
                        .withBody("{\"access_token\":\"never\"}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.TOKEN_ENDPOINT_TIMEOUT.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.TOKEN.name());
    }

    // ── Step 3: RESOURCE scenarios ────────────────────────────────────────────

    @Test
    void resourceUnauthorized_returnsBadGateway() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(any(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse().withStatus(401)));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.RESOURCE_UNAUTHORIZED.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.RESOURCE.name());
    }

    @Test
    void resourceTimeout_returnsGatewayTimeout() throws Exception {
        stubTokenOk();
        resourceServer.stubFor(WireMock.post(urlPathEqualTo("/sEQI/Param/UpdateParameter"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withFixedDelay(60_000)
                        .withBody("{\"code\":0}")));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.RESOURCE_TIMEOUT.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.RESOURCE.name());
    }
}
