package tw.elliot.resourceserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server 的 HTTP 安全層設定。
 *
 * 本服務角色為「純 API 資源伺服器」：
 *   - 不維護 Session、不發 cookie、不渲染畫面。
 *   - 所有請求都必須帶授權伺服器（oauthserver:9000）簽發的 JWT。
 *   - JWT 公鑰透過 application.yaml 的 issuer-uri 自動向 /.well-known/... 取 JWKS 驗證。
 */
@Configuration
public class SecurityConfig {

    /**
     * 建立 Resource Server 的 SecurityFilterChain。
     *
     * <h3>1) authorizeHttpRequests / anyRequest().authenticated()</h3>
     * 授權規則：所有路徑一律要求已通過認證（= 必須帶有效 JWT）。
     * 本服務沒有對外公開端點，因此不使用 permitAll；如未來要開放健康檢查
     * 或公開資源，可在 anyRequest 之前加上 {@code requestMatchers(...).permitAll()}。
     *
     * <h3>2) oauth2ResourceServer(...jwt(...))</h3>
     * 啟用 OAuth2 Resource Server，並以 JWT 模式驗證 Bearer Token。
     * {@code withDefaults()} 會：
     * <ul>
     *   <li>依 application.yaml 的 {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}
     *       到 oauthserver 取得 JWKS、驗證簽章與 {@code iss} / {@code exp} 等標準 claim。</li>
     *   <li>將 JWT 轉成 {@code JwtAuthenticationToken} 放入 SecurityContext，
     *       Controller 可用 {@code @AuthenticationPrincipal Jwt} 取得內容。</li>
     * </ul>
     *
     * <h3>3) SessionCreationPolicy.STATELESS</h3>
     * Session 策略設為 STATELESS：
     * <ul>
     *   <li>Spring Security 不建立、也不使用 HttpSession。</li>
     *   <li>每個請求都必須自己帶 JWT，伺服器端不保留任何登入狀態。</li>
     *   <li>適合純 API / 微服務情境，可水平擴充而不需 sticky session。</li>
     * </ul>
     *
     * <h3>4) csrf().disable()</h3>
     * 關閉 CSRF 保護：
     * <ul>
     *   <li>CSRF 攻擊依賴瀏覽器自動附帶 cookie 進行的同源憑證重放，
     *       而本服務以 {@code Authorization: Bearer <JWT>} 認證，且為 STATELESS、
     *       不發 cookie，本質上不存在 CSRF 的攻擊面。</li>
     *   <li>若改為使用 cookie / session 認證或加入 form 端點，必須重新啟用。</li>
     * </ul>
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
