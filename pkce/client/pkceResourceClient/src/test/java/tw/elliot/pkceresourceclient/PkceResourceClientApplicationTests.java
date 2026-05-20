package tw.elliot.pkceresourceclient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context-load test.
 * src/test/resources/application.yaml overrides the provider to use explicit endpoint URIs
 * (no issuer-uri) so Spring does not attempt OIDC discovery against a live :9000 server.
 */
@SpringBootTest
class PkceResourceClientApplicationTests {

    @Test
    void contextLoads() {
    }
}
