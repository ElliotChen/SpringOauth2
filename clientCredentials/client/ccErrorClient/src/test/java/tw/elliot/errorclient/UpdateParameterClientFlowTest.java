package tw.elliot.errorclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
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

@SpringBootTest
class UpdateParameterClientFlowTest {

    private static MockWebServer authServer;
    private static MockWebServer resourceServer;

    @Autowired private WebApplicationContext webContext;
    @Autowired private OAuth2AuthorizedClientService authorizedClientService;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startMocks() throws IOException {
        authServer = new MockWebServer();
        resourceServer = new MockWebServer();
        authServer.start();
        resourceServer.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        authServer.shutdown();
        resourceServer.shutdown();
    }

    @DynamicPropertySource
    static void wireMocks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.client.provider.resource-server.token-uri",
                () -> "http://localhost:" + authServer.getPort() + "/oauth2/token");
        r.add("app.resource-server.base-url",
                () -> "http://localhost:" + resourceServer.getPort());
    }

    @BeforeEach
    void resetState() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        // Drop any cached access token from previous test
        authorizedClientService.removeAuthorizedClient("resource-server", "errorClient");
        authorizedClientService.removeAuthorizedClient("resource-server", "anonymousUser");
    }

    private static MockResponse tokenOk() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"access_token\":\"fake-token\",\"token_type\":\"Bearer\",\"expires_in\":3600,\"scope\":\"admin\"}");
    }

    // ── Step 1: Happy path + INPUT ────────────────────────────────────────────

    @Test
    void happyPath_returnsResourceBody() throws Exception {
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"OK\"}"));

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
        authServer.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\"}"));

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
        authServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.TOKEN_ENDPOINT_UNREACHABLE.name());
    }

    // ── Step 3: RESOURCE scenarios ────────────────────────────────────────────

    @Test
    void resourceUnauthorized_returnsBadGateway() throws Exception {
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse().setResponseCode(401));

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
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.RESOURCE_TIMEOUT.name());
    }
}
