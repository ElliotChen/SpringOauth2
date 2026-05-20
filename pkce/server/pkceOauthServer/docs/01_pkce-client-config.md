# `web-client` 的 PKCE client 設定：`none` 認證 + `require-proof-key`

## 核心設定

```yaml
spring:
  security:
    oauth2:
      authorizationserver:
        client:
          web-client:
            registration:
              client-id: "web-client"
              client-authentication-methods:
                - "none"
              authorization-grant-types:
                - "authorization_code"
                - "refresh_token"
              redirect-uris:
                - "http://localhost:8080/login/oauth2/code/pkce-client"
              scopes:
                - "openid"
                - "profile"
                - "read"
            require-authorization-consent: true
            require-proof-key: true
```

## 為何用 `client-authentication-methods: none`

cc demo 的 `admin-client` / `member-client` 是機器對機器流程，使用 `client_secret_basic`；client secret 只在後端服務之間傳遞，不會暴露給瀏覽器。

`web-client` 是 browser-based application：授權碼要透過瀏覽器 redirect 回來，若保留 client secret，任何人打開開發者工具就能看到。因此正確做法是不設 secret，讓 `client-authentication-methods` 為 `none`，改用 PKCE 作為安全替代機制——`code_verifier` 每次隨機產生，攻擊者截到授權碼也無法換取 token。

`require-proof-key: true` 確保 Authorization Server 在端點層強制拒絕不帶 `code_challenge` 的授權請求，防止設定遺漏時悄悄退化成無保護的 public client。

## `redirect-uris` 必須與 Spring Security 完全一致

Spring Security 的 `oauth2Login` 預設把 callback 路徑固定為：

```
{baseUrl}/login/oauth2/code/{registrationId}
```

本專案的 registrationId 是 `pkce-client`、client port 是 `8080`，所以完整 URI 是：

```
http://localhost:8080/login/oauth2/code/pkce-client
```

Authorization Server 在 redirect 時會逐字元比對 `redirect_uri` 參數與已登記的清單。`localhost` 與 `127.0.0.1` 是不同的字串，即使 TCP 層連到同一台機器也會被拒絕並回傳 `redirect_uri_mismatch`。因此兩邊必須使用完全相同的 host、port 與路徑。
