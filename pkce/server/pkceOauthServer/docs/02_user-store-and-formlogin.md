# 使用者帳號與 formLogin：為何 PKCE 需要第三條 chain 真正擋登入

## 與 cc demo 的根本差異

cc demo 的流程是 `client_credentials`——沒有使用者，也就不需要人工登入畫面。cc 的 default chain（`@Order(2)`）設的是 `anyRequest().authenticated()`，但實際上不會有任何人形使用者去觸發 formLogin；那條 chain 的主要用途是作為安全保險絲。

PKCE 流程是 `authorization_code`：Authorization Server 必須**先驗證使用者身份**才能核發授權碼。使用者按下授權後，AS 會把帶有 `code_challenge` 的 `/oauth2/authorize` 請求重導到 formLogin 頁面，登入成功才繼續。少了真實的 `UserDetailsService`，Spring Security 會拋出 `UsernameNotFoundException`，整個 PKCE 流程中斷在登入步驟。

## `UserConfig` 程式碼

```java
@Configuration
public class UserConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsManager(PasswordEncoder passwordEncoder) {
        UserDetails user = User.builder()
                .username("user")
                .password(passwordEncoder.encode("password"))
                .roles("USER")
                .build();
        UserDetails admin = User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin"))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(user, admin);
    }
}
```

使用 BCrypt 而非明文存密碼，即使 demo 環境也維持最低安全標準——Spring Security 的 `DaoAuthenticationProvider` 在比對密碼時一律呼叫 `PasswordEncoder.matches()`，若 encoder 為 `NoOpPasswordEncoder`，Spring Security 5+ 啟動時會打印 `WARN`，且升級到生產時容易產生安全漏洞。

`roles("USER")` 會在 authorities 清單中自動加入 `ROLE_USER`，供 `AuthorizationServerConfig` 的 token customizer 讀取（見 `03_token-customizer-user-vs-client.md`）。

## 生產環境的差異

| 項目 | Demo（本專案） | 生產環境 |
|------|--------------|--------|
| 儲存媒介 | 記憶體（重啟即消失） | DB（JDBC / JPA UserDetailsService） |
| 密碼管理 | 硬編碼 | 帳號管理服務、密碼強度政策 |
| 帳號鎖定 | 無 | `AccountStatusUserDetailsChecker` |
| 多因素認證 | 無 | 自訂 `AuthenticationProvider` 或 IdP 整合 |

替換 `UserDetailsService` 不需要修改 `WebSecurityConfig`；Spring Security 自動注入 bean，保持介面一致即可。
