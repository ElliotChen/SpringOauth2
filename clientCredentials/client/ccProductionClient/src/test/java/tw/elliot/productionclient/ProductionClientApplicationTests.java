package tw.elliot.productionclient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
class ProductionClientApplicationTests {

    @DynamicPropertySource
    static void stubExternalEndpoints(DynamicPropertyRegistry r) {
        // Context load only — no real HTTP calls. Point to localhost stubs so
        // the OAuth2 client registration validates without contacting a server.
        r.add("spring.security.oauth2.client.provider.resource-server.token-uri",
                () -> "http://localhost:1/oauth2/token");
        r.add("app.resource-server.base-url", () -> "http://localhost:1");
    }

    @Test
    void contextLoads() {
    }
}
