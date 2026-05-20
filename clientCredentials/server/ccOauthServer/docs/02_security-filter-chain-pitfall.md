# `@ConditionalOnDefaultWebSecurity` 陷阱：自訂 SecurityFilterChain 會關掉整組 autoconfig

## 症狀

在 oauthserver 加入 `spring-boot-starter-actuator` 並新增一個 `SecurityFilterChain` bean 來放行 `/actuator/**` 之後：

| Endpoint | HTTP |
|----------|------|
| `/oauth2/token` | **404** |
| `/oauth2/jwks` | **404** |
| `/.well-known/oauth-authorization-server` | **404** |
| `/actuator/health` | 200 ✅ |

啟動 log 也看不到任何 OAuth2 Authorization Server 的初始化訊息。

## 根本原因

Boot 4 的 `OAuth2AuthorizationServerWebSecurityConfiguration` 帶有兩個關鍵 annotation：

```java
@Configuration
@ConditionalOnDefaultWebSecurity   // ⚠️ 這個是關鍵
@ConditionalOnBean(...)
class OAuth2AuthorizationServerWebSecurityConfiguration {
    SecurityFilterChain authorizationServerSecurityFilterChain(...) { ... }
    SecurityFilterChain defaultSecurityFilterChain(...) { ... }
}
```

`@ConditionalOnDefaultWebSecurity` 的語意是：**「只有在使用者沒有自己定義任何 `SecurityFilterChain` bean 時才啟用」**。

當在專案中加入任何一個 `SecurityFilterChain` bean（例如 `actuatorSecurityFilterChain`）後：

1. 條件不成立 → 整個 `OAuth2AuthorizationServerWebSecurityConfiguration` 被略過
2. 因此**兩條** filter chain 都沒註冊（包括負責 `/oauth2/token`、`/oauth2/jwks`、`/.well-known/**` 的那條）
3. OAuth Authorization Server 的 endpoint filter 沒有被任何 chain 接管 → DispatcherServlet 找不到 mapping → 404

`/actuator/**` 一切正常是因為自己那條 chain 還在運作。

> ⚠️ 注意：只有 `SecurityFilterChain` bean 會觸發 backoff。其他 OAuth 相關 bean（如 `OAuth2TokenCustomizer`、`RegisteredClientRepository`、`JWKSource`、`AuthorizationServerSettings`）**不會**影響 `@ConditionalOnDefaultWebSecurity`。所以 `AuthorizationServerConfig` 內只放 `OAuth2TokenCustomizer` 沒有問題；新增 actuator 那條 chain 才壓死整組。

## 修正

**全有或全無** —— 一旦自訂任一條 `SecurityFilterChain`，就必須**自己宣告全部三條**：

| Chain | `@Order` | `securityMatcher` | 用途 |
|-------|---------|-------------------|------|
| `actuatorSecurityFilterChain` | 0 | `EndpointRequest.toAnyEndpoint()` | actuator permitAll |
| `authorizationServerSecurityFilterChain` | 1 | `OAuth2AuthorizationServerConfigurer.getEndpointsMatcher()` | 處理所有 OAuth2 / `.well-known` 端點 |
| `defaultSecurityFilterChain` | 2 | （無，fallback） | 其他請求要求 authenticated |

關鍵程式碼：

```java
@Bean
@Order(1)
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
            new OAuth2AuthorizationServerConfigurer();

    http
        .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
        .with(authorizationServerConfigurer, Customizer.withDefaults())
        .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
        .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServerConfigurer.getEndpointsMatcher()))
        .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()));

    return http.build();
}
```

`getEndpointsMatcher()` 會回傳一個複合的 `RequestMatcher`，涵蓋 Authorization Server 所有預設端點 —— 這正是 Boot autoconfig 原本做的事，現在改由我們手動執行。

## 注意：Spring Security 7 / Boot 4 的 API 差異

舊版（Spring Security 6.x）常見寫法：

```java
// ❌ Spring Security 7 已無此 class
OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);

// ❌ Spring Security 7 亦無此 static factory
OAuth2AuthorizationServerConfigurer.authorizationServer();
```

Spring Security 7 的正確寫法：

```java
// ✅ 直接 new
OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
http.with(configurer, Customizer.withDefaults());
```

另外，Boot 4 把若干 actuator security 工具類移到新套件：

```java
// ❌ Boot 3 路徑
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;

// ✅ Boot 4 路徑
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
```

## 教訓

1. **Spring Boot 的 autoconfig 常用 `@ConditionalOn*` 做 backoff** —— 自訂任何相關 bean 都可能默默地關掉一整組原本免費的設定。
2. **`@ConditionalOnDefaultWebSecurity`** 是 Boot 安全相關 autoconfig 的常見 backoff 條件：偵測到使用者自訂 `SecurityFilterChain` 就退讓。
3. **退讓是「整組」退讓**，不是只退讓被你覆蓋的那一條。Boot 不知道你想保留哪些、覆蓋哪些。
4. 當需要新增 chain（例如為了 actuator）時，**有意識地補回被關掉的內容**：把 Authorization Server chain 與 default chain 一起搬到自己的 `@Configuration`。
5. 除錯時的線索：
   - 啟動 log 不再出現 OAuth2 相關初始化訊息
   - `/actuator/conditions` 端點可看到哪些 autoconfig 被 negative match
   - 加上 `--debug` 啟動旗標會印出完整 condition evaluation report

## 相關連結

- `OAuth2AuthorizationServerWebSecurityConfiguration` 原始碼（`spring-boot-security-oauth2-authorization-server` 4.0.6）
- `@ConditionalOnDefaultWebSecurity` 位於 `org.springframework.boot.security.autoconfigure.web.servlet`
- 本專案實作：`src/main/java/tw/elliot/oauthserver/config/ActuatorSecurityConfig.java`
