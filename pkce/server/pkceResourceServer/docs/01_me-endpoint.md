# `/me` 端點：JWT claims 設計與 `@AuthenticationPrincipal Jwt`

## 端點實作

```java
@GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
    List<String> scopes = jwt.getClaimAsStringList("scope");
    List<String> authorities = jwt.getClaimAsStringList("authorities");
    return new MeResponse(
            jwt.getSubject(),
            scopes,
            jwt.getClaimAsString("role"),
            authorities,
            jwt.getClaimAsString("client_id"),
            jwt.getIssuer() != null ? jwt.getIssuer().toString() : null,
            jwt.getExpiresAt()
    );
}
```

`@AuthenticationPrincipal Jwt` 讓 Spring Security 直接從 `SecurityContext` 中取出已驗證的 `Jwt` 物件，省去手動解析 `Authentication` 的樣板程式碼。Resource Server 在處理每個請求時已完成簽章驗證與 issuer 確認，所以這裡直接讀取 claim 是安全的。

## 回應範例 JSON（pkce 流程，使用者 `user` 登入）

```json
{
  "subject": "user",
  "scopes": ["openid", "profile", "read"],
  "role": "user",
  "authorities": ["ROLE_USER"],
  "clientId": "web-client",
  "issuer": "http://localhost:9000",
  "expiresAt": "2025-05-20T10:30:00Z"
}
```

## Claims 對照表

| Claim | JWT 標準 | 值的來源 | 備註 |
|-------|---------|---------|------|
| `sub` | 是 | AS 自動填入 | pkce 為使用者名稱；cc 為 clientId |
| `scope` | 否（OAuth 慣例）| client registration 申請的 scope | 空格分隔，Spring 轉為 list |
| `role` | 否（自訂）| `AuthorizationServerConfig` token customizer | 單一字串，小寫 |
| `authorities` | 否（自訂）| 同上 | list，如 `["ROLE_USER"]` |
| `client_id` | 否（OAuth 慣例）| AS 自動填入 | 永遠是 `web-client` |
| `iss` | 是 | AS 設定（`issuer-uri`） | `http://localhost:9000` |
| `exp` | 是 | AS 根據 TTL 計算 | token 到期時間 |

## cc vs pkce 在 `/me` 結果的差異

| 欄位 | cc（`client_credentials`） | pkce（`authorization_code`） |
|------|--------------------------|--------------------------|
| `subject` | `admin-client` 或 `member-client` | 使用者名稱（`user` / `admin`） |
| `role` | `admin` 或 `member`（依 clientId 映射） | `user` 或 `admin`（依使用者 authorities） |
| `authorities` | `["ROLE_ADMIN"]` 等 | `["ROLE_USER"]` 等 |
| `clientId` | 呼叫端 clientId | 永遠是 `web-client` |
| 代表「誰」 | 呼叫的服務 | 登入的人 |

這個對照是整個 PKCE demo 的核心展示點：相同的 `/me` endpoint，因 grant type 不同而回傳截然不同的身份語意。
