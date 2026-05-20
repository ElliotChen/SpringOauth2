package tw.elliot.oauthserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.Collections;

/**
 * OAuth 領域層 bean：與「簽 token、token 內容、client/key 來源」有關的設定。
 *
 * 不放 SecurityFilterChain — HTTP / Servlet 安全層設定統一放在 {@link WebSecurityConfig}。
 *
 * @see WebSecurityConfig HTTP 安全層（三條 SecurityFilterChain）
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            String clientId = context.getRegisteredClient().getClientId();
            String role;
            if ("admin-client".equals(clientId)) {
                role = "admin";
            } else if ("member-client".equals(clientId)) {
                role = "member";
            } else {
                role = "none";
            }
            context.getClaims().claim("role", role);
            context.getClaims().claim(
                    "authorities",
                    Collections.singletonList("ROLE_" + role.toUpperCase()));
        };
    }

    /*
     * 替代方案（停用）：不另開 actuator 專屬 SecurityFilterChain，
     * 改成在 default chain 直接 permitAll /actuator/**。
     *
     * 啟用條件：刪除 WebSecurityConfig 中的 actuatorSecurityFilterChain，
     * 並把下面這段拷貝到取代 defaultSecurityFilterChain 的位置（或解開註解後改名為 @Bean）。
     *
     * 不建議使用的原因：
     *   1. "/actuator/**" 是硬編碼，若改 management.endpoints.web.base-path 會失效。
     *   2. 無法獨立關閉 CSRF、設定獨立錯誤處理、限制 IP。
     *   3. 責任不分離，default chain 同時混合應用授權與管理端點豁免邏輯。
     *
     * @Bean
     * @Order(2)
     * public SecurityFilterChain defaultSecurityFilterChainWithActuatorPermitAll(HttpSecurity http) throws Exception {
     *     http
     *             .authorizeHttpRequests(auth -> auth
     *                     .requestMatchers("/actuator/**").permitAll()
     *                     .anyRequest().authenticated())
     *             .formLogin(Customizer.withDefaults());
     *     return http.build();
     * }
     */
}