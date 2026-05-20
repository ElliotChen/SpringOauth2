# OAuth 2.1 總覽

OAuth 2.1 是 OAuth 2.0 的「整理版」（consolidation），由 IETF 的 `draft-ietf-oauth-v2-1` 推動。它不是一份從零開始的新協定，而是把過去十年散落在 RFC 6749 + 多份 BCP / 補充 RFC 的最佳實踐合併、把已知不安全的部分移除，得到一份**更小、更安全、預設更嚴格**的核心規範。

本文件聚焦於「跟 OAuth 2.0 相比有何不同」、「對本專案（Spring Authorization Server + `client_credentials`）的實際影響」。

---

## 1. OAuth 2.1 vs OAuth 2.0 — 規範層面的差異

### 1.1 移除的功能（不再合法）

| 移除項目 | 為什麼移除 |
|---------|-----------|
| **Implicit Grant**（`response_type=token`） | Token 直接以 URL fragment 回傳，容易透過瀏覽器歷史、referer、log 外洩；無法刷新；幾乎全可被 `authorization_code + PKCE` 取代。 |
| **Resource Owner Password Credentials Grant**（ROPC） | 要求第三方應用直接收使用者的帳密，完全違反 OAuth「不交付密碼」的初衷。 |
| **Authorization code without PKCE**（公開 client 用授權碼但沒帶 PKCE） | 沒有 PKCE 的授權碼會被攔截/重放，特別是行動 App、SPA。 |
| **Bearer token in URI query string** | Token 被寫進伺服器 access log、proxy log、referer header，洩漏風險高。改強制放在 `Authorization: Bearer` header。 |

### 1.2 強制收緊的部分（仍合法但條件更嚴）

| 項目 | OAuth 2.0 | OAuth 2.1 |
|------|-----------|-----------|
| **PKCE** | 僅建議（公開 client） | **所有使用 `authorization_code` 的 client 都必須 PKCE**（含機密 client） |
| **Redirect URI 比對** | 容許部分匹配（前綴、萬用字元） | **必須 exact string match** |
| **Refresh Token（公開 client）** | 可長期使用 | **必須 sender-constrained 或 rotation**（用一次就換新的） |
| **Token 傳遞** | query / header 皆可 | 只允許 `Authorization` header（或受嚴格條件的 form body） |

### 1.3 保留的 grant types

- `authorization_code`（+ 強制 PKCE）— 給有使用者的應用。
- `client_credentials` — 給後端服務之間 machine-to-machine，**沒有使用者**。本專案用的就是這個。
- `refresh_token` — 換新的 access token。

`password`、`implicit` 不在 2.1 內。`device_authorization_grant`（RFC 8628，OAuth 2.1 沒列為核心 grant，但 Spring AS 仍然支援，於電視/IoT 等沒有瀏覽器的裝置使用）通常作為延伸。

### 1.4 整併進來的 RFC / BCP

OAuth 2.1 把以下文件納為基線（不再是「可選增強」）：

- **RFC 7636** — PKCE
- **RFC 8252** — OAuth for Native Apps（強制系統瀏覽器 + PKCE）
- **RFC 6750** — Bearer Token Usage（限制只放 header）
- **RFC 8628** — Device Authorization Grant（可選）
- **OAuth 2.0 Security Best Current Practice**（`draft-ietf-oauth-security-topics`）的大部分內容

---

## 2. 本專案實際使用 OAuth 2.1 的方式

本專案是純「機器對機器」場景，沒有使用者、沒有瀏覽器，因此**只用到 `client_credentials`** 這個 grant type。

```
resourceClient ──(1) client_id + client_secret──▶ oauthserver
                ◀─────(2) access_token (JWT)─────
               ──(3) Authorization: Bearer JWT──▶ resourceServer
```

對應 OAuth 2.1 的點：

| 規範點 | 本專案落地 |
|--------|-----------|
| Token 只能放在 Authorization header | `OAuth2ClientHttpRequestInterceptor` 自動加 `Authorization: Bearer ...`；resourceServer 端 `oauth2ResourceServer().jwt()` 只讀 header。 |
| 不使用 implicit / password | `application.yaml` 只註冊 `authorization-grant-types: client_credentials`。 |
| PKCE | `client_credentials` **本來就不需要 PKCE**（PKCE 是用來保護 authorization code 在前端被攔截，這裡沒有授權碼也沒有 redirect）。 |
| Redirect URI exact match | 同上，本流程沒有 redirect。 |
| Token 格式 | `access-token-format: self-contained` → JWT，由 resourceServer 透過 JWKS（`/oauth2/jwks`）驗章。詳見 `04_endpoints-and-public-key.md`。 |
| Token 壽命 | `access-token-time-to-live: 30m`，到期 client 重新走 `client_credentials` 取新 token；`client_credentials` 不發 refresh token（規範禁止，因 client 自己就能再取新的）。 |

### 2.1 為什麼 `client_credentials` 在 2.1 仍合法

`client_credentials` 的安全模型不依賴使用者，而依賴 **client 認證**本身（這裡用 `client_secret_basic`）。OAuth 2.1 對它的要求是：

- Client 必須是「機密 client」（confidential client），能安全保存 secret —— 後端服務符合。
- 不能發 refresh token —— Spring AS 預設就不發。
- 不能把使用者拉進來 —— 本來就沒有。

公開 client（SPA、行動 App）**不允許**用 `client_credentials`，因為它們無法保密 secret。

---

## 3. 與 OpenID Connect 的關係

OAuth 2.1 仍**只負責授權**（access token），不負責「使用者是誰」。要驗證使用者身分需 OpenID Connect（OIDC）疊在 `authorization_code` 上、回傳 `id_token`。

本專案有啟用 OIDC（`authorizationServerConfigurer.oidc(Customizer.withDefaults())`），對應端點為 `/.well-known/openid-configuration`、`/userinfo` 等，但因為實際只跑 `client_credentials`、沒有使用者，這些端點目前不會被呼叫。詳見 `06_oidc-and-configurer-deep-dive.md`。

---

## 4. 升級到 OAuth 2.1 時要檢查的常見 migration 點

即便本專案目前沒有需求，未來若新增 `authorization_code` 流程，以下是必須對齊的清單：

1. **強制 PKCE**：client 註冊時加 `client-settings.require-proof-key: true`，或在 `RegisteredClient` builder 加 `.clientSettings(ClientSettings.builder().requireProofKey(true).build())`。
2. **Redirect URI 改為完整字串**：不允許 `http://localhost:*` 之類的 pattern，必須列出每一個合法 URL。
3. **公開 client 的 refresh token 必須 rotate**：`token-settings.reuse-refresh-tokens: false`。
4. **檢查所有呼叫端**：禁止任何把 access token 放在 URL query 的呼叫（如 `?access_token=...`），改為 header。
5. **移除 ROPC / implicit**：如果舊系統有 `password` 或 `implicit` grant，需重新設計成 `authorization_code + PKCE`。

---

## 5. 延伸閱讀

- IETF: `draft-ietf-oauth-v2-1`（OAuth 2.1 草案最新版）
- RFC 6749（OAuth 2.0 核心）
- RFC 7636（PKCE）
- RFC 8252（Native App BCP）
- `draft-ietf-oauth-security-topics`（OAuth Security BCP）
- 同目錄 `04_endpoints-and-public-key.md`：本專案 endpoint 與 JWKS 公鑰機制
- 同目錄 `01_authorizationserver-client-config.md`：本專案 client 註冊細節