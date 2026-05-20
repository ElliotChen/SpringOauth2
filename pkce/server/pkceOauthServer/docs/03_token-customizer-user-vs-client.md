# Token Customizer 對照：cc 從 clientId 派生 role，pkce 從使用者 authorities 派生

## 並排對照

**cc 版本**（`client_credentials`，`ccOauthServer/AuthorizationServerConfig`）：

```java
return context -> {
    String clientId = context.getRegisteredClient().getClientId();
    String role;
    if ("admin-client".equals(clientId)) {
        role = "admin";
    } else if ("member-client".equals(clientId)) {
        role = "member";
    } else {
        role = "none";
    }
    context.getClaims().claim("role", role);
    context.getClaims().claim("authorities", Collections.singletonList("ROLE_" + role.toUpperCase()));
};
```

**pkce 版本**（`authorization_code`，`pkceOauthServer/AuthorizationServerConfig`）：

```java
return context -> {
    if (!"access_token".equals(context.getTokenType().getValue())) {
        return;
    }
    List<String> authorities = context.getPrincipal().getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .collect(Collectors.toList());

    String role = authorities.stream()
            .filter(a -> a.startsWith("ROLE_"))
            .findFirst()
            .map(a -> a.substring("ROLE_".length()).toLowerCase())
            .orElse("none");

    context.getClaims().claim("role", role);
    context.getClaims().claim("authorities", authorities);
};
```

## 為何 pkce 版本需要 `access_token` type check

`authorization_code` grant 在完整 OIDC 流程中會產生多種 token：access token、refresh token、以及 ID token（`openid` scope 啟用時）。`context.getTokenType()` 可能是 `access_token`、`refresh_token`，或 OIDC ID token 類型。

對 refresh token 和 ID token 注入自訂 claim 沒有意義（refresh token 不會被 resource server 解析；ID token 有自己的結構規範）。cc 版本不需要這個判斷，因為 `client_credentials` 流程只會產生 access token，沒有 ID token 的問題。

## `subject` 的根本差異

| 項目 | cc（`client_credentials`） | pkce（`authorization_code`） |
|------|--------------------------|--------------------------|
| `sub` claim | clientId（如 `admin-client`） | 登入的使用者名稱（如 `user`、`admin`） |
| `role` 來源 | `RegisteredClient.getClientId()` 硬編碼映射 | 使用者的 `GrantedAuthority` |
| `authorities` | 固定一個（如 `ROLE_ADMIN`） | 使用者所有 authority（可多個） |
| Principal 型別 | `OAuth2ClientAuthenticationToken` | 使用者的 `Authentication` 物件 |

這個差異直接影響 pkceResourceServer 的 `/me` 回應——pkce 流程的 `/me` 會帶使用者名稱作為 subject，cc 流程帶 clientId。兩個 demo 共用同一個 `/me` endpoint 設計，正是為了凸顯這個對比。
