# OIDC、`Customizer.withDefaults()` 與 `OAuth2AuthorizationServerConfigurer` 深度解析

---

## 1. OIDC 的作用

### 一句話定位

**OIDC (OpenID Connect) = 建立在 OAuth 2.0 之上的「身份驗證 (Authentication)」協議**。

| 協議 | 回答的問題 |
|------|----------|
| OAuth 2.0 | 「這個 client 能做什麼？」（**授權** / authorization） |
| OIDC | 「這個 user 是誰？」（**身份** / authentication） |

### OAuth 不夠用的痛點

OAuth 2.0 的 access token 只說「持有者有資格存取某些 scope」，但**沒有定義 user 身份的標準格式**。每家 Authorization Server 回傳的 user info 結構都不同：

- Google 給 `email`、`sub`、`name`
- Facebook 給 `id`、`first_name`
- 自家 server 可能給 `userId`、`account`

→ Client 端必須為每個 provider 寫一套不同的 parsing。

### OIDC 補上了什麼

| 補充項 | 內容 |
|--------|------|
| **ID Token** | 一個 JWT，包含 `sub`、`name`、`email`、`iat`、`aud`、`iss` 等**標準化** user claim |
| **UserInfo endpoint** | 標準路徑 `/userinfo`，回 user 資料 |
| **Discovery 文件** | `/.well-known/openid-configuration`，client 可自動發現所有 OIDC endpoint |
| **新的 scope `openid`** | client 加上 `openid` scope 才會觸發 ID Token 發行 |
| **OIDC 流程** | 在 OAuth Authorization Code 上加 ID Token 回傳 |

### 對本專案的意義

本專案用 **Client Credentials**（M2M，無 user）—— OIDC 實務上**不會被觸發**：

- 沒有 user → 不會發 ID Token
- 沒有 user → 不會呼叫 `/userinfo`
- 沒有 redirect 流程 → 不會走 `/connect/logout`

那為什麼還要啟用？

**只因為 `/.well-known/openid-configuration` 這支 discovery endpoint 被許多通用 OAuth client library 預設探測**。即使本專案只做 OAuth，常見的 client SDK（如某些 Java、Python OAuth library）會優先呼叫 OIDC discovery 而非 OAuth discovery。啟用 OIDC 不啟用 user flow，純粹是為了**讓 discovery 端點不要 404**。

> 如果確認下游 client 只用 `/.well-known/oauth-authorization-server`，就可以不啟用 OIDC。

---

## 2. `Customizer.withDefaults()` 做了什麼

### 字面定義

來自 `org.springframework.security.config.Customizer`：

```java
@FunctionalInterface
public interface Customizer<T> {
    void customize(T t);

    static <T> Customizer<T> withDefaults() {
        return (t) -> { };   // ← 空 lambda，什麼都不做
    }
}
```

它就是一個**空的 lambda** —— `c -> {}`。

### 等價寫法

下面三段完全等價：

```java
http.formLogin(Customizer.withDefaults());
http.formLogin(c -> {});
http.formLogin();   // ← Spring Security 6 起 deprecated，要明寫 customizer
```

### 為什麼空 lambda 有用？

Spring Security 的 fluent API 是「**進入 sub-configurer 並套用設定**」。形式是：

```java
http.someFeature(configurer -> {
    configurer.optionA(...);
    configurer.optionB(...);
});
```

呼叫 `.someFeature(...)` 這個動作本身就會**啟用該功能 + 套用 sub-configurer 的內建預設值**。你傳進去的 customizer 是**進一步調整**。

當你不想調整、只想用預設行為時，傳一個空 customizer 即可 → 那就是 `withDefaults()`。

### 在本專案中出現的位置

| 位置 | 啟用什麼 | 預設值含意 |
|------|---------|-----------|
| `http.with(authorizationServerConfigurer, Customizer.withDefaults())` | 把 `OAuth2AuthorizationServerConfigurer` 套到 HttpSecurity | 註冊全部預設啟用的 OAuth endpoint filter，但不對 configurer 做進一步調整 |
| `authorizationServerConfigurer.oidc(Customizer.withDefaults())` | 啟用 OIDC sub-configurer | 啟用 `/userinfo`、`/.well-known/openid-configuration`、`/connect/logout`，使用預設路徑與行為 |
| `.oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))` | 啟用 JWT resource server 子功能 | 使用預設 JWT decoder（從 context 中找 `JwtDecoder` bean），預設 audience/issuer 驗證 |
| `.formLogin(Customizer.withDefaults())` | 啟用 form login | 預設 `/login` 路徑、預設登入頁、登入成功重導原始請求或 `/`、失敗重導 `/login?error` |

### 不傳 `withDefaults()` 而做客製化的範例

```java
.formLogin(form -> form
    .loginPage("/my-login")
    .defaultSuccessUrl("/dashboard"))

authorizationServerConfigurer.oidc(oidc -> oidc
    .userInfoEndpoint(ui -> ui.userInfoMapper(myMapper)))
```

---

## 3. `authorizationServerConfigurer.oidc(...)` 實際做了什麼

當你呼叫 `.oidc(Customizer.withDefaults())`，`OAuth2AuthorizationServerConfigurer` 內部會：

### 註冊三個 sub-configurer

```
OidcConfigurer
├── OidcUserInfoEndpointConfigurer       → /userinfo
├── OidcClientRegistrationEndpointConfigurer → /connect/register
└── OidcLogoutEndpointConfigurer         → /connect/logout
```

每個 sub-configurer 對應一個 OIDC 端點，啟用後會在 `HttpSecurity` 上掛對應的 Filter。

### 註冊 4 個 Servlet Filter

| Filter | 路徑 | 功能 |
|--------|------|------|
| `OidcProviderConfigurationEndpointFilter` | `/.well-known/openid-configuration` | 回傳 OIDC discovery 文件 |
| `OidcUserInfoEndpointFilter` | `/userinfo` | 回傳 user info |
| `OidcLogoutEndpointFilter` | `/connect/logout` | RP-initiated logout |
| `OidcClientRegistrationEndpointFilter` | `/connect/register` | Dynamic Client Registration（**預設關閉，需手動 enable**） |

### 把端點納入 `getEndpointsMatcher()` 集合

這是**關鍵副作用**：呼叫 `.oidc(...)` 後，這些 OIDC 端點才會被列入 `getEndpointsMatcher()` 回傳的 `RequestMatcher`。

所以 `WebSecurityConfig` 裡這段：

```java
.securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())
```

**呼叫順序很重要**：必須先 `.oidc(...)` 再 `.getEndpointsMatcher()`，否則 OIDC 端點不會被納入 matcher，請求落到 default chain → 302 重導 `/login`（這就是先前的 bug）。

### 在 ID Token 發行時注入 OIDC 流程

如果 client 帶 `openid` scope 走 Authorization Code 流程，OIDC configurer 會：
1. 在 Authorization Code 換 token 時額外生成 ID Token
2. 從 `OidcUserInfoEndpointConfigurer` 取得 user claim 來源
3. 用 `OAuth2TokenGenerator<OidcIdToken>` 簽出 ID Token JWT

---

## 4. `OAuth2AuthorizationServerConfigurer` 還有哪些類似 `.oidc(...)` 的方法？

從 `OAuth2AuthorizationServerConfigurer` 的 source（Spring Security 7.0.5）來看，它有一系列**端點 sub-configurer**，全部對應一個 OAuth 端點 / 功能：

| 方法 | 控制的端點 | 預設 | 用途 |
|------|-----------|------|------|
| `.authorizationEndpoint(...)` | `/oauth2/authorize` | ✅ 啟用 | Authorization Code 流程入口 |
| `.tokenEndpoint(...)` | `/oauth2/token` | ✅ 啟用 | 換取 access token（**本專案核心**） |
| `.tokenIntrospectionEndpoint(...)` | `/oauth2/introspect` | ✅ 啟用 | RFC 7662 token 驗證 |
| `.tokenRevocationEndpoint(...)` | `/oauth2/revoke` | ✅ 啟用 | RFC 7009 撤銷 token |
| `.authorizationServerMetadataEndpoint(...)` | `/.well-known/oauth-authorization-server` | ✅ 啟用 | RFC 8414 metadata |
| `.deviceAuthorizationEndpoint(...)` | `/oauth2/device_authorization` | ✅ 啟用 | RFC 8628 device flow |
| `.deviceVerificationEndpoint(...)` | `/oauth2/device_verification` | ✅ 啟用 | Device flow 使用者驗證頁 |
| `.pushedAuthorizationRequestEndpoint(...)` | `/oauth2/par` | ✅ 啟用 | RFC 9126 pushed authorization |
| `.clientAuthentication(...)` | Token endpoint 內 client 認證 | ✅ 啟用 | 自訂 client 認證方式（如 mTLS） |
| `.oidc(...)` | OIDC 相關端點 | ❌ **預設關閉** | 啟用 ID Token 流程與 OIDC discovery |
| `.clientRegistrationEndpoint(...)` | （OIDC sub） | ❌ 預設關閉 | Dynamic Client Registration |

### 觀察重點

1. **大部分端點預設啟用** —— 你不呼叫對應的 `.xxx(Customizer.withDefaults())` 也會運作（因為 configurer 內部初始化時會建立全部，除了 OIDC）
2. **OIDC 是特例** —— 預設關閉，必須顯式呼叫 `.oidc(...)` 才會啟用
3. **每個方法都接 `Customizer<XxxConfigurer>`** —— 不傳就是 `withDefaults()` 等效，傳 lambda 可細調該端點的路徑、認證方式、token 生成邏輯等

### 還有「非端點型」的設定方法

除了 sub-configurer 之外，`OAuth2AuthorizationServerConfigurer` 還有幾個**直接設值**的方法（不吃 Customizer，吃具體 bean）：

```java
.registeredClientRepository(repo)        // client 來源（本專案由 yaml 自動建立 InMemory 版本）
.authorizationService(service)           // 授權狀態儲存（記憶體 / JDBC / Redis）
.authorizationConsentService(service)    // 使用者同意狀態
.tokenGenerator(generator)               // 替換預設 JWT generator
.authorizationServerSettings(settings)   // 改 issuer、改 endpoint 路徑等
```

這些通常作為 bean 注入（`@Bean`），由 Spring DI 接到 configurer，不需要在 `WebSecurityConfig` 顯式呼叫。

### 客製化範例：改 token 端點路徑

```java
authorizationServerConfigurer.tokenEndpoint(token -> token
    .accessTokenRequestConverter(myConverter)
    .accessTokenResponseHandler(mySuccessHandler)
    .errorResponseHandler(myErrorHandler));
```

或從 settings bean 改全域路徑：

```java
@Bean
public AuthorizationServerSettings authorizationServerSettings() {
    return AuthorizationServerSettings.builder()
        .tokenEndpoint("/api/oauth/token")
        .issuer("https://auth.elliot.tw")
        .build();
}
```

---

## 串起來看 `WebSecurityConfig` 的 OAuth chain

```java
OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();  // 預設啟用所有 OAuth 端點

authorizationServerConfigurer.oidc(Customizer.withDefaults());  // 額外啟用 OIDC

http
    .securityMatcher(authorizationServerConfigurer.getEndpointsMatcher())  // 涵蓋全部啟用的端點
    .with(authorizationServerConfigurer, Customizer.withDefaults())        // 註冊全部 filter
    .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
    .csrf(csrf -> csrf.ignoringRequestMatchers(authorizationServerConfigurer.getEndpointsMatcher()))
    .oauth2ResourceServer(resourceServer -> resourceServer.jwt(Customizer.withDefaults()));
```

每個 `withDefaults()` 都是「啟用，不調整」。整段三個 `withDefaults()` 用一句話描述：

> 把 OAuth Authorization Server（含 OIDC）的全部預設端點 / filter 都掛起來，並讓這條 chain 接受 JWT 作為 access token 驗證機制。

需要客製化時，把對應的 `withDefaults()` 換成 lambda 即可。
