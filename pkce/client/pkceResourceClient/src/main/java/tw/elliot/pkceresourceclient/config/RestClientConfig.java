package tw.elliot.pkceresourceclient.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

/**
 * 與 cc 的 RestClientConfig 對照：
 * - cc 用 AuthorizedClientServiceOAuth2AuthorizedClientManager（無使用者上下文）
 *   + clientCredentials() provider，token 屬於 client。
 * - 這裡用 DefaultOAuth2AuthorizedClientManager（從 HttpSession 取得當前使用者 client）
 *   + authorizationCode() / refreshToken() provider，token 屬於登入的使用者。
 */
@Configuration
public class RestClientConfig {

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientRepository authorizedClientRepository) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .authorizationCode()
                .refreshToken()
                .build();

        DefaultOAuth2AuthorizedClientManager manager =
                new DefaultOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientRepository);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public RestClient resourceServerRestClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${app.resource-server.base-url}") String baseUrl) {

        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        interceptor.setClientRegistrationIdResolver(request -> "pkce-client");

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestInterceptor(interceptor)
                .build();
    }
}
