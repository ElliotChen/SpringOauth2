# 為什麼需要 3 組 SecurityFilterChain？

## 核心觀念：Spring Security 的 chain 是「整段攔截規則」，不是「路由規則」

每一條 `SecurityFilterChain` 都帶著一整套設定：

- 哪些路徑歸我管（`securityMatcher`）
- 進來的請求要怎麼處理（filter list：CSRF、CORS、auth、session...）
- 認證方式（formLogin / httpBasic / JWT / 不需要）
- 授權規則（`anyRequest().permitAll()` / `.authenticated()` / `.hasRole(...)`）

**這些設定無法在同一條 chain 內針對不同路徑「分組套用不同 auth 方式」**。換言之，你沒辦法在「一條 chain」裡同時做到：

- `/actuator/**` → 不認證
- `/oauth2/**` → 用 client_secret_basic + 啟用 `OAuth2AuthorizationServerConfigurer`
- 其他 → formLogin

因為每條 chain 的「認證方式」是整條共用的。所以必須**拆成多條**，每條負責一個職責。

---

## 三條的具體職責

| Chain | securityMatcher | 認證方式 | 授權 | 為什麼必要 |
|-------|----------------|---------|------|-----------|
| **actuatorSecurityFilterChain** | `EndpointRequest.toAnyEndpoint()` | 無 | `permitAll()` | actuator 需匿名訪問，**且要關閉 CSRF**（POST `/actuator/shutdown` 之類會被擋），這跟其他 chain 的需求互斥 |
| **authorizationServerSecurityFilterChain** | `OAuth2AuthorizationServerConfigurer.getEndpointsMatcher()` | client_secret_basic（透過 `OAuth2ClientAuthenticationFilter`） + JWT resource server | `.authenticated()` | 必須掛上 `OAuth2AuthorizationServerConfigurer` 才會註冊 `OAuth2TokenEndpointFilter`、`NimbusJwkSetEndpointFilter` 等 OAuth 專屬 filter；認證方式是「client 認證」而非「user 認證」 |
| **defaultSecurityFilterChain** | （無，fallback） | formLogin | `.authenticated()` | 接住所有未被上面命中的請求；用 `admin/admin` 登入。**也是 Spring Security 默認的「安全保險絲」** —— 沒有它，未匹配的路徑會無人處理或行為未定義 |

---

## 視覺化：請求進來的決策樹

```
HTTP request
    │
    ▼
是否命中 actuatorSecurityFilterChain.securityMatcher?
    │ 是 → 走 actuator chain → permitAll → 200
    │ 否
    ▼
是否命中 authorizationServerSecurityFilterChain.securityMatcher?
    │ 是 → 走 OAuth chain → 用 client_secret 認證 → 200/401
    │ 否
    ▼
落到 defaultSecurityFilterChain (沒有 matcher，全部都接)
    │ → 用 formLogin 認證 → 200/302 to /login
```

---

## 為什麼不能少一條？

| 試圖移除 | 後果 |
|---------|------|
| 移除 actuator chain | `/actuator/**` 落到 default chain → formLogin → 監控/健康檢查被 302 重導 |
| 移除 authorization server chain | `/oauth2/token` 沒有任何 filter 處理 → 404；或落到 default chain 被當一般 URL 要登入 |
| 移除 default chain | 未匹配的請求 **理論上可能被放行**（取決於是否有 `springSecurityFilterChain` fallback） —— Boot 4 + Security 7 會視為配置錯誤；正式環境是嚴重安全洞 |

---

## 為什麼不能用一條搞定？

理論上你可以在 default chain 裡做：

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/**").permitAll()
    .requestMatchers("/oauth2/**", "/.well-known/**").permitAll()
    .anyRequest().authenticated())
.formLogin(...)
.csrf(csrf -> csrf.ignoringRequestMatchers("/oauth2/**"));
```

但這樣**不會**讓 `/oauth2/token` 真的能簽 token —— 因為 `OAuth2AuthorizationServerConfigurer` 必須掛到一條 `HttpSecurity` 上才會註冊它那 10+ 個 OAuth 專屬 filter。`permitAll()` 只是把 Security 放行，請求最終會落到 DispatcherServlet 找不到 handler → 404。

換句話說，**Authorization Server 的存在本身就要求至少兩條 chain**（一條給 OAuth endpoints、一條給其他）。再加上 actuator 的特殊需求，自然變三條。

---

## 為什麼 `OAuth2AuthorizationServerConfigurer` 放在 `WebSecurityConfig` 而不是 `AuthorizationServerConfig`？

### 職責邊界

```
AuthorizationServerConfig  →  OAuth 領域層
                              · 簽 token 的邏輯（OAuth2TokenCustomizer）
                              · 註冊 client 的資料源（未來 RegisteredClientRepository）
                              · 簽章金鑰來源（未來 JWKSource）

WebSecurityConfig          →  HTTP / Servlet 安全層
                              · 哪些 URL 怎麼認證怎麼授權
                              · 三條 SecurityFilterChain
```

### 為什麼 `OAuth2AuthorizationServerConfigurer` 算 HTTP 安全層

`OAuth2AuthorizationServerConfigurer` 的本質是「**把一堆 OAuth 專屬 Servlet Filter 註冊到 `HttpSecurity`**」。它的工作包含：

- 註冊 `OAuth2TokenEndpointFilter`（處理 `/oauth2/token`）
- 註冊 `NimbusJwkSetEndpointFilter`（處理 `/oauth2/jwks`）
- 註冊 `OAuth2ClientAuthenticationFilter`（處理 client 認證）
- 註冊 OIDC discovery filter 等

這些都是 HTTP 層的 filter chain 元件，**必須與 `SecurityFilterChain` 綁在一起宣告**（透過 `http.with(configurer, ...)`），所以它的位置就在 `WebSecurityConfig` 而非 `AuthorizationServerConfig`。

`OAuth2TokenCustomizer` 則不同 —— 它是被 `OAuth2TokenGenerator` 自動注入的 bean，與 `HttpSecurity` 沒有直接組裝關係，純粹是「token 內容」的設定，屬於領域層，放在 `AuthorizationServerConfig`。

---

## 簡化記憶

```
SecurityFilterChain 處理「請求進來的路由 + 認證 + 授權」
                   ↑
                  HTTP 層 → 全部放 WebSecurityConfig

OAuth2TokenCustomizer / RegisteredClientRepository / JWKSource
                   ↓
                  領域層 → 放 AuthorizationServerConfig
```

兩個檔案不該互相覆蓋對方的職責，否則重構或升級時容易產生隱性耦合。
