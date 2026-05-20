# `spring.security.oauth2.authorizationserver.client` 設定詳解

這是 Spring Boot 4 為 Spring Authorization Server 提供的設定前綴，用來宣告 **in-memory 註冊的 OAuth2 client（RegisteredClient）**。底層綁定的類別是 `OAuth2AuthorizationServerProperties`。

## 結構總覽

```yaml
spring.security.oauth2.authorizationserver.client:
  <client-key>:           # 自訂 key，僅作為 Map 索引
    registration: { ... } # 必填：client 身份與授權設定
    token:        { ... } # 選填：此 client 的 token 行為
    require-proof-key: false               # 選填：PKCE
    require-authorization-consent: false   # 選填：是否顯示同意頁
    jwk-set-uri: ...                       # 選填：client 自己的 JWKS（用於 private_key_jwt）
    token-endpoint-authentication-signing-algorithm: ...  # 選填
```

`<client-key>`（範例中的 `admin-client`、`member-client`）只是 YAML Map 的索引，**不會被 OAuth 流程使用**，真正識別 client 的是 `registration.client-id`。

---

## 1. `registration`（必填區塊）

對應 `RegisteredClient` 物件，是 client 的核心身份描述。

| 屬性 | 用途 | 影響 |
|------|------|------|
| `client-id` | Client 的唯一識別字串 | 呼叫 `/oauth2/token` 時必須提供；錯誤即 401 `invalid_client` |
| `client-secret` | Client 的密碼 | 必須加前綴標示加密方式，如 `{noop}xxx`（明文）、`{bcrypt}xxx`。沒寫前綴會在啟動或驗證時失敗 |
| `client-name` | 顯示名稱（同意頁、log 用） | 不影響協議流程 |
| `client-authentication-methods` | client 自我認證方式（Set） | 決定 `/oauth2/token` 接受哪種驗證：<br>• `client_secret_basic`：HTTP Basic header（最常用）<br>• `client_secret_post`：放在 body<br>• `client_secret_jwt`：用 HMAC 簽 JWT<br>• `private_key_jwt`：用私鑰簽 JWT（搭配 `jwk-set-uri`）<br>• `none`：public client（搭配 PKCE） |
| `authorization-grant-types` | 允許的授權類型（Set） | OAuth 2.1 常用：<br>• `client_credentials`：機器對機器<br>• `authorization_code`：使用者授權碼流程<br>• `refresh_token`：換取新 access token<br>• `urn:ietf:params:oauth:grant-type:device_code`：裝置流程<br>**未列入的 grant type 會被拒絕** |
| `redirect-uris` | Authorization Code 流程的回調 URL（Set） | 僅 `authorization_code` 需要；嚴格比對，不符合即 `invalid_redirect_uri`。Client Credentials **不需要** |
| `post-logout-redirect-uris` | OIDC 登出後的回調 | 僅 OIDC 場景用 |
| `scopes` | 此 client 可申請的 scope（Set） | Client 要 token 時傳的 `scope` 參數必須是這個集合的子集；超出範圍會被剔除或拒絕 |

---

## 2. `token`（選填區塊）

對應 `TokenSettings`，控制這個 client 拿到的 token 樣貌與生命週期。

| 屬性 | 預設值 | 用途與影響 |
|------|--------|-----------|
| `access-token-time-to-live` | `5m` | Access token 有效期。短 → 安全性高但需常換；長 → 效能好但撤銷困難 |
| `access-token-format` | `self-contained` | 格式：<br>• `self-contained`（JWT）：Resource Server 可本地驗證<br>• `reference`（opaque）：必須走 `/oauth2/introspect` 驗證 |
| `refresh-token-time-to-live` | `60m` | Refresh token 有效期。Client Credentials 不發 refresh token，此設定無效 |
| `reuse-refresh-tokens` | `true` | 換 access token 時是否重用舊 refresh token。`false` 會 rotate（更安全） |
| `authorization-code-time-to-live` | `5m` | 授權碼換 token 的視窗；過短易失敗，過長易被截獲 |
| `device-code-time-to-live` | `5m` | Device flow 用 |
| `id-token-signature-algorithm` | `RS256` | OIDC ID Token 簽章演算法 |

---

## 3. Client 等級的其他開關

| 屬性 | 預設 | 用途 |
|------|------|------|
| `require-proof-key` | `false` | 強制要求 PKCE。Public client（SPA / Mobile）建議 `true`；Client Credentials 用不到 |
| `require-authorization-consent` | `false` | 是否每次都顯示「使用者同意」頁。Client Credentials 沒有使用者，設了也不會觸發 |
| `jwk-set-uri` | – | 當 `client-authentication-methods` 包含 `private_key_jwt` 時，告訴 Authorization Server 去哪取 client 的公鑰來驗 client 的 JWT |
| `token-endpoint-authentication-signing-algorithm` | – | 搭配 `private_key_jwt` / `client_secret_jwt`，限定 client assertion 的簽章演算法 |

---

## 4. 對照目前專案的設定

```yaml
admin-client:
  registration:
    client-id: "admin-client"                    # 識別字串
    client-secret: "{noop}admin-secret"          # 明文密碼（demo 用，正式環境改 {bcrypt}）
    client-authentication-methods:
      - "client_secret_basic"                    # 走 HTTP Basic 驗 client
    authorization-grant-types:
      - "client_credentials"                     # 只開機器對機器流程
    scopes:
      - "admin"                                  # 此 client 僅能要 admin scope
  token:
    access-token-time-to-live: "30m"             # JWT 30 分鐘過期
    access-token-format: "self-contained"        # 發 JWT，可本地驗證
```

**整體效果**：
- 只能用 `Basic Auth(admin-client:admin-secret)` 呼叫 `/oauth2/token`
- 只能 grant_type=client_credentials、scope=admin
- 拿到的是 RS256 簽章的 JWT，30 分鐘有效
- 不需要 redirect URI、不需要 PKCE、不需要使用者同意

---

## 5. 正式環境的調整建議

| 項目 | demo 設定 | 正式建議 |
|------|----------|---------|
| `client-secret` | `{noop}admin-secret` | `{bcrypt}$2a$10$...`（用 `BCryptPasswordEncoder` 雜湊） |
| `access-token-time-to-live` | 30m | 5–15m，搭配 refresh 或 cache |
| 金鑰來源 | Boot 自動生成（重啟即變） | 從 keystore 或 KMS 載入固定 RSA key，避免重啟後舊 token 失效 |
| Client secret 來源 | yaml 明文 | 外部 secret manager（Vault、AWS Secrets Manager）+ `${...}` 注入 |

---

## 6. 補充：YAML key 的角色

```yaml
client:
  admin-client:    # ← 這個 key 只是 Map 索引
    registration:
      client-id: "admin-client"   # ← 這個才是真正的 client_id
```

可以這樣寫也合法（但容易混淆）：

```yaml
client:
  foo:
    registration:
      client-id: "admin-client"
```

協議上完全等效。**慣例上會讓兩者相同**以便閱讀。
