package tw.elliot.oauthserver.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP / Servlet 安全層設定，宣告全部三條 SecurityFilterChain。
 *
 * 三條必須一起宣告：Boot 4 的 OAuth2AuthorizationServerWebSecurityConfiguration
 * 帶 {@code @ConditionalOnDefaultWebSecurity}，使用者一旦自訂任一條 SecurityFilterChain，
 * Boot 提供的 Authorization Server chain 與 default chain 會「整組」被略過。
 * 所以這裡必須親自把三條都宣告：
 * <ul>
 *   <li>actuator chain：放行 {@code /actuator/**}</li>
 *   <li>authorization server chain：掛上 {@link OAuth2AuthorizationServerConfigurer}，
 *       處理 {@code /oauth2/**}、{@code /.well-known/**}、OIDC 等端點</li>
 *   <li>default chain：fallback，formLogin 攔截其他請求</li>
 * </ul>
 *
 * @see AuthorizationServerConfig OAuth 領域層 bean（token customizer 等）
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    @Order(0)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        authorizationServerConfigurer.oidc(Customizer.withDefaults());

        http
                .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
                .with(authorizationServerConfigurer, Customizer.withDefaults())
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServerConfigurer.getEndpointsMatcher()))
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
