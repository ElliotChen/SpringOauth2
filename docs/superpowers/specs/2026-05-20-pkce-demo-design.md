# PKCE Demo 設計規格

**日期**：2026-05-20
**主題**：在 `pkce/` 目錄下新增 authorization_code + PKCE 的 demo，結構對稱於既有的 `clientCredentials/`。

## 目標

在現有 multi-module repo 中新增一組「authorization_code + PKCE」的 Spring Boot demo，與既有的 `clientCredentials/` 對稱，方便讀者對照兩種 grant type 的差異：
- **client_credentials**（cc demo）：機器對機器、token 中的 subject 是 client id
- **authorization_code + PKCE**（pkce demo）：代表終端使用者、token 中的 subject 是 user

## 目錄與模組結構

```
pkce/
├── server/
│   ├── pkceOauthServer/        # :9000 Spring Authorization Server (PKCE)
│   └── pkceResourceServer/     # :8081 JWT-protected API
└── client/
    └── pkceResourceClient/     # :8080 Thymeleaf web (OAuth2 login)
```

- Maven module 命名為 `pkce*`，與 `cc*` 系列對稱。
- Java package 沿用 `tw.elliot.*`：`tw.elliot.pkceoauthserver` / `pkceresourceserver` / `pkceresourceclient`。
- Port 沿用 9000 / 8081 / 8080（與 cc demo **不同時運行**）。
- 根 `pom.xml` 新增三個 module（沿用 `parent` BOM）：
  ```xml
  <module>pkce/server/pkceOauthServer</module>
  <module>pkce/server/pkceResourceServer</module>
  <module>pkce/client/pkceResourceClient</module>
  ```

## pkceOauthServer (`:9000`)

### Client 註冊（`application.yaml`）

| 欄位 | 值 |
|---|---|
| `client-id` | `web-client` |
| `client-authentication-methods` | `none`（public client） |
| `authorization-grant-types` | `authorization_code`, `refresh_token` |
| `redirect-uris` | `http://localhost:8080/login/oauth2/code/pkce-client`（必須與 client 端 `{baseUrl}` 解析後的值完全一致，因此統一用 `localhost`） |
| `scopes` | `openid`, `profile`, `read` |
| `require-proof-key` | `true`（強制 PKCE） |
| `require-authorization-consent` | `true`（顯示同意頁） |

### 使用者帳號（InMemoryUserDetailsManager）

- `user / password`，role `USER`
- `admin / admin`，role `ADMIN`

於 bean 中以 `User.withDefaultPasswordEncoder()` 或 `BCryptPasswordEncoder` 硬編。

### SecurityFilterChain（三鏈架構，沿用 ccOauthServer 模式）

1. `@Order(0)` actuator chain
2. `@Order(1)` authorization server chain
3. `@Order(2)` default chain：啟用 `formLogin()`（**必要**：使用者要輸入帳密）

Boot 4 的 `@ConditionalOnDefaultWebSecurity` 陷阱同樣適用 —— 一旦定義任何 user-defined chain，Boot 預設 AS chain 與 default chain 全部失效，因此三條都要顯式宣告。

### Token customizer

注入 `role`、`authorities` claim，來源是當前 **使用者** 的 authorities（與 cc demo 對照：cc 是依 clientId 注入）。

### 配置類別分工（沿用 cc 的雙類別設計）

- `WebSecurityConfig` — 三條 `SecurityFilterChain`
- `AuthorizationServerConfig` — OAuth 領域 bean（含 `OAuth2TokenCustomizer`、`UserDetailsService`）

## pkceResourceServer (`:8081`)

### 設定

- `issuer-uri: http://localhost:9000`
- Stateless、no CSRF、no sessions、純 Bearer JWT 驗證
- 設定方式與 ccResourceServer 幾乎相同

### Endpoints

**1. `POST /sEQI/Param/UpdateParameter`** — 與 cc demo 同名同行為
- `Param_Type=01` → 回傳 `0`
- `Param_Type=00` → 回傳 `-1`
- 授權要求：`scope=read`
- 用途：與 cc demo 完全對照「同一支 API，不同 grant type」

**2. `GET /me`** — 顯示當前 JWT 的關鍵資訊

```json
{
  "subject": "user",
  "scopes": ["openid", "profile", "read"],
  "role": "USER",
  "authorities": ["ROLE_USER"],
  "clientId": "web-client",
  "issuer": "http://localhost:9000",
  "expiresAt": "..."
}
```

實作上以 `@AuthenticationPrincipal Jwt` 取得 token，將 claims map 出來。此端點是 PKCE demo 的核心對照點：subject 是真實使用者，而非 cc demo 的 client id。

### Package 結構

`tw.elliot.pkceresourceserver` 下分：
- `config/` — SecurityConfig
- `controller/` — ParamController、MeController

## pkceResourceClient (`:8080`) — Thymeleaf web

### 核心依賴

- `spring-boot-starter-web`
- `spring-boot-starter-thymeleaf`
- `spring-boot-starter-security`
- `spring-boot-starter-oauth2-client`

### `application.yaml`

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          pkce-client:
            provider: pkce-oauth
            client-id: web-client
            client-authentication-method: none
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope: openid, profile, read
        provider:
          pkce-oauth:
            issuer-uri: http://localhost:9000
```

`client-authentication-method: none` 會讓 Spring Security 自動啟用 PKCE（產生 code_verifier / code_challenge）。

### SecurityFilterChain

- 全部路徑 `authenticated()`（與 cc demo 的 `permitAll` 不同）
- `.oauth2Login(Customizer.withDefaults())` 開啟登入流程
- `.logout(...)` 提供登出

### RestClient 呼叫 Resource Server

沿用 cc demo 的 `OAuth2ClientHttpRequestInterceptor` 模式，但 token 來源改為「**當前登入使用者的 access token**」（透過 `OAuth2AuthorizedClientManager` + `authorization_code` provider 取得 / refresh）。registration id 使用 `pkce-client`。

### 頁面（Thymeleaf templates）

- `index.html` — 顯示登入使用者名稱與 3 個 link（`/me`、`UpdateParameter`、登出）
- `me.html` — 顯示 resource server 回傳的 `/me` JSON
- `update-parameter.html` — `Param_Type` 表單 + 呼叫結果

### Package 結構

`tw.elliot.pkceresourceclient` 下分：
- `config/` — SecurityConfig、RestClientConfig
- `controller/` — HomeController
- `service/` — ResourceServerClient

## 啟動順序與 Smoke Test

**啟動順序**：`pkceOauthServer → pkceResourceServer → pkceResourceClient`（理由與 cc 相同：JWKS 與 token 都從 AS 拉）。

### 手動 smoke test（瀏覽器）

1. 三服務啟動後，瀏覽器開 `http://localhost:8080/`
2. 自動 redirect 至 `:9000` 的 formLogin 頁
3. 輸入 `user / password` → 同意頁 → 回到 client
4. 點「查看 /me」 → 確認 `subject = "user"`、`role = "USER"`
5. 點「呼叫 UpdateParameter」：`Param_Type=01` → `0`；`Param_Type=00` → `-1`
6. 用 `admin / admin` 重新登入，`/me` 應顯示 `role = "ADMIN"`

### Shell smoke test

不寫。authorization_code + PKCE 牽涉瀏覽器互動與 redirect，無法以純 curl 簡潔重現。改以瀏覽器手動驗證步驟記錄在文件中。

## 文件（Traditional Chinese，沿用 cc 風格）

- `pkce/server/pkceOauthServer/docs/01_pkce-client-config.md` — public client + `require-proof-key`、redirect-uri 規則
- `pkce/server/pkceOauthServer/docs/02_user-store-and-formlogin.md` — InMemoryUserDetailsManager 與第三條 default chain 的角色
- `pkce/server/pkceOauthServer/docs/03_token-customizer-user-vs-client.md` — 與 cc 對照：claims 來源由 clientId 變成 user authorities
- `pkce/client/pkceResourceClient/docs/01_oauth2-login-and-pkce.md` — Spring Security 如何自動啟用 PKCE
- `pkce/client/pkceResourceClient/docs/02_authorized-client-manager-with-user-token.md` — 與 cc 的 OAuth2AuthorizedClientManager 對照
- `pkce/server/pkceResourceServer/docs/01_me-endpoint.md` — `/me` 設計與 JWT claims 對照表

## 對 `CLAUDE.md` 的同步更新

將 `pkce/` 從 placeholder 改為正式段落，包含：
- 目錄結構區塊新增三個 pkce module
- Build & Run 新增 pkce 三個服務的指令
- 啟動順序段落補上 pkce demo
- Smoke test 段落補上「瀏覽器手動驗證」說明
- Architecture 段落新增 pkce 三服務的描述
- Reference docs 段落補上新文件

## Out of Scope

- 不做 SPA 前端（React/Vue）
- 不做 JDBC / JPA user store
- 不做 refresh_token 的進階情境（auto refresh 由 Spring Security 預設行為負責）
- 不寫 shell-based PKCE flow 模擬腳本
