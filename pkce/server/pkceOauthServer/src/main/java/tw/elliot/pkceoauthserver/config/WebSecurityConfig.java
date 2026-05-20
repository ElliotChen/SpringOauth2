package tw.elliot.pkceoauthserver.config;

import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 三條 SecurityFilterChain，理由與 ccOauthServer.WebSecurityConfig 完全相同：
 * Boot 4 的 OAuth2AuthorizationServerWebSecurityConfiguration 有 @ConditionalOnDefaultWebSecurity，
 * 一旦自訂任何一條，整組預設都會被略過，所以必須把三條都顯式宣告。
 *
 * 與 cc 的差別：default chain 對 PKCE demo 是必要的，因為使用者必須以 formLogin 登入。
 */
@Configuration
public class WebSecurityConfig {

    /**
     * Actuator 專屬 SecurityFilterChain（@Order(0)，最先匹配）。
     *
     * <h3>1) securityMatcher(EndpointRequest.toAnyEndpoint())</h3>
     * 只攔截 {@code /actuator/**} 路徑（實際路徑由 {@code management.endpoints.web.base-path} 決定，
     * 用 {@code EndpointRequest} 不必硬編字串）。其他請求會落到 @Order(1) / @Order(2) 的鏈。
     *
     * <h3>2) anyRequest().permitAll()</h3>
     * 全部放行，不要求認證。Demo 方便起見直接開放 actuator；
     * 生產環境建議改成 {@code hasRole("ACTUATOR")} 或限制來源 IP。
     *
     * <h3>3) csrf().disable()</h3>
     * Actuator 端點主要由監控系統以 GET / POST 直接呼叫，沒有瀏覽器 cookie session，
     * 沒有 CSRF 攻擊面，關掉以免需要拿 CSRF token 才能 POST。
     */
    @Bean
    @Order(0)
    public SecurityFilterChain actuatorSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Authorization Server 端點專屬 SecurityFilterChain（@Order(1)）。
     * 處理 {@code /oauth2/**}、{@code /.well-known/**}、OIDC 與 PKCE 流程相關端點。
     *
     * <h3>1) OAuth2AuthorizationServerConfigurer + oidc(withDefaults())</h3>
     * 套用 Spring Authorization Server 預設配置，並啟用 OIDC（{@code /.well-known/openid-configuration}、
     * {@code /userinfo}、{@code id_token} 等）。OIDC 對 PKCE demo 必要，因為 client 申請 {@code openid} scope。
     *
     * <h3>2) securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())</h3>
     * 只攔截 AS configurer 內建的端點集合（{@code /oauth2/authorize}、{@code /oauth2/token}、
     * {@code /oauth2/jwks}、{@code /oauth2/revoke} 等），其他路徑落到 @Order(2)。
     *
     * <h3>3) with(authorizationServerConfigurer, withDefaults())</h3>
     * 把 configurer 實際掛上 HttpSecurity；這步之後 AS endpoints 才真的會運作。
     *
     * <h3>4) anyRequest().authenticated()</h3>
     * AS 端點原則上要求認證 —— 例如 {@code /oauth2/authorize} 需要先有登入的使用者；
     * {@code /oauth2/token} 雖然走 client authentication，AS configurer 內部會另行處理放行。
     *
     * <h3>5) csrf().ignoringRequestMatchers(...)</h3>
     * 對 AS endpoints 關閉 CSRF：{@code /oauth2/token}、{@code /oauth2/revoke} 是程式對程式的 POST，
     * 不帶瀏覽器 cookie session，無 CSRF 攻擊面。其他非 AS 路徑仍保留 CSRF（default chain 的範圍）。
     *
     * <h3>6) oauth2ResourceServer(jwt(withDefaults()))</h3>
     * 讓 AS 自己也能驗 Bearer JWT，主要是給 OIDC 的 {@code /userinfo} 端點 ——
     * client 取到 access_token 後可以拿來打 userinfo，這時 AS 本身就要以 Resource Server 身分驗 token。
     *
     * <h3>7) exceptionHandling.defaultAuthenticationEntryPointFor(LoginUrlAuthenticationEntryPoint, TEXT_HTML)</h3>
     * 關鍵 — 當瀏覽器（Accept: text/html）造訪 {@code /oauth2/authorize} 而沒登入時，
     * 預設行為是回 401；這裡改成導向 {@code /login} 由 default chain 的 formLogin 處理，
     * 登入後 Spring Security 會把使用者帶回原本的授權請求，完成 PKCE 流程。
     * 非 HTML 請求仍維持 401 回應。
     */
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
                .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
                .exceptionHandling(ex -> ex.defaultAuthenticationEntryPointFor(
                        new org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint("/login"),
                        new org.springframework.security.web.util.matcher.MediaTypeRequestMatcher(
                                org.springframework.http.MediaType.TEXT_HTML)));
        return http.build();
    }

    /**
     * Default fallback SecurityFilterChain（@Order(2)，最後匹配）。
     * 處理前兩條沒攔到的所有請求，主要任務是提供 {@code /login} 頁面與處理使用者登入。
     *
     * <h3>1) anyRequest().authenticated()</h3>
     * 落到這條的請求一律要求登入。對 PKCE demo 來說，這條的存在主要是為了讓 Spring Security
     * 把瀏覽器導到 {@code /login} —— 當 @Order(1) 的 exceptionHandling 把未登入請求轉過來時，
     * 由本條提供 formLogin 處理。
     *
     * <h3>2) formLogin(withDefaults())</h3>
     * 啟用內建的 username/password 登入頁（GET {@code /login} 顯示表單，POST {@code /login} 驗證帳密），
     * 帳密來源是 {@link UserConfig#userDetailsManager} 中硬編的 user / admin。
     * 登入成功後 Spring Security 會自動將使用者導回原本被攔截的 {@code /oauth2/authorize} 請求，
     * 繼續 PKCE 授權流程。
     *
     * <p>未啟用 CSRF 關閉設定 —— 預設保留 CSRF token 保護，formLogin 與其他 POST 都需要 CSRF token。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .formLogin(Customizer.withDefaults());
        return http.build();
    }
}
