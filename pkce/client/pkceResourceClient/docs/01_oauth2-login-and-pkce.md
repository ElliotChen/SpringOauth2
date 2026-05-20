# Spring Security 的 `oauth2Login` 與 PKCE 自動啟用

## 設定片段

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

## PKCE 自動啟用機制

Spring Security 偵測到 `client-authentication-method: none` 時，`OAuth2AuthorizationRequestCustomizers` 會自動對此 registration 啟用 PKCE，不需要額外程式碼。

PKCE 的三個關鍵步驟：

1. **產生 `code_verifier`**：授權請求發起時隨機產生一個高熵字串（43–128 字元，URL-safe Base64）。
2. **計算 `code_challenge`**：`code_challenge = BASE64URL(SHA256(code_verifier))`，跟著 `/oauth2/authorize` 請求一起送給 AS，AS 儲存備查但不驗證。
3. **Token 請求帶 `code_verifier`**：用授權碼換 token 時附上原始 `code_verifier`，AS 重新計算 hash 並與儲存的 `code_challenge` 比對，確認是同一個 session 發起的請求。

這個機制讓攻擊者即使攔截到授權碼，也因為沒有 `code_verifier` 而無法換取 token。

## 完整授權流程序列

```
1. 使用者造訪受保護頁面
      ↓
2. Browser → /oauth2/authorize?response_type=code
              &client_id=web-client
              &code_challenge=<SHA256(verifier)>
              &code_challenge_method=S256
              &redirect_uri=http://localhost:8080/login/oauth2/code/pkce-client
              &scope=openid profile read
      ↓
3. AS → 重導至 formLogin 頁面（若未登入）
      ↓
4. 使用者輸入帳號密碼 → 登入成功 → AS 顯示授權同意畫面
      ↓
5. 使用者同意 → AS 核發 code，重導至 redirect_uri?code=<code>&state=<state>
      ↓
6. Spring Security（pkceResourceClient）接到 callback，
   自動發送 token request：
   POST /oauth2/token
     grant_type=authorization_code
     &code=<code>
     &code_verifier=<原始 verifier>
     &redirect_uri=...
      ↓
7. AS 驗證 code_verifier → 核發 access_token + refresh_token + id_token
      ↓
8. Spring Security 完成 OAuth2 login，使用者進入已認證狀態
```

## `{baseUrl}` 樣板說明

`redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"` 中的 `{baseUrl}` 和 `{registrationId}` 是 Spring Security 的執行期樣板變數：

- `{baseUrl}` → 根據實際請求的 scheme + host + port 決定（如 `http://localhost:8080`）
- `{registrationId}` → 此 registration 的 id（`pkce-client`）

這樣做的好處是不需要為不同環境（dev / staging / prod）修改 yaml，只要確保 AS 端的 `redirect-uris` 清單包含對應的完整 URI 即可。
