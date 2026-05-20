# `DefaultOAuth2AuthorizedClientManager`：為何 PKCE 不能用 `AuthorizedClientServiceOAuth2AuthorizedClientManager`

## 兩種 Manager 的根本差異

| 項目 | cc（`AuthorizedClientServiceOAuth2AuthorizedClientManager`） | pkce（`DefaultOAuth2AuthorizedClientManager`） |
|------|-------------------------------------------------------------|----------------------------------------------|
| token 儲存位置 | `OAuth2AuthorizedClientService`（記憶體 / DB，與 request 無關） | `OAuth2AuthorizedClientRepository`（預設存 `HttpSession`） |
| 使用者上下文 | 無（機器對機器，Principal 為 client） | 有（從當前 `HttpServletRequest` 取得登入使用者） |
| token 歸屬 | 屬於 client（service account） | 屬於登入使用者（每個 user 各自的 token） |
| 適用 grant | `client_credentials` | `authorization_code`、`refresh_token` |
| request 依賴 | 不需要 HttpServletRequest | 必須有 HttpServletRequest（session lookup） |

## 為何 `authorization_code` 流程必須用 `DefaultOAuth2AuthorizedClientManager`

`authorization_code` 完成後，Spring Security 把每個使用者的 `OAuth2AuthorizedClient`（含 access token + refresh token）存入 `HttpSession`。`DefaultOAuth2AuthorizedClientManager` 依賴 `OAuth2AuthorizedClientRepository` 去 session 裡找「當前使用者的已授權 client」。

若用 `AuthorizedClientServiceOAuth2AuthorizedClientManager`，它不感知 `HttpSession`，每次呼叫都找不到既有的 authorized client，然後嘗試重新授權——對 `authorization_code` grant 來說這意味著把使用者重導到登入頁面，造成無限 redirect loop。

## `RestClientConfig` 程式碼

```java
@Bean
public OAuth2AuthorizedClientManager authorizedClientManager(
        ClientRegistrationRepository clientRegistrationRepository,
        OAuth2AuthorizedClientRepository authorizedClientRepository) {

    OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .authorizationCode()
            .refreshToken()
            .build();

    DefaultOAuth2AuthorizedClientManager manager =
            new DefaultOAuth2AuthorizedClientManager(
                    clientRegistrationRepository, authorizedClientRepository);
    manager.setAuthorizedClientProvider(provider);
    return manager;
}
```

## `authorizationCode()` + `refreshToken()` provider 的角色

- **`authorizationCode()`**：在 manager 嘗試取得 authorized client 時，若 session 中尚無此 client 的 token（即尚未完成授權），觸發重導到 AS 的 `/oauth2/authorize`。在 `oauth2Login` 場景下這個 provider 幾乎不會主動觸發，因為 `oauth2Login` 本身已完成授權並把結果存入 session；它的作用是讓 manager 的邏輯完整。
- **`refreshToken()`**：當 access token 已過期，manager 自動使用 session 中的 refresh token 向 AS 換取新的 access token，並更新 session 中的 `OAuth2AuthorizedClient`，對呼叫端完全透明。

## 自動 refresh 行為

`OAuth2ClientHttpRequestInterceptor` 在每次 `RestClient` 呼叫 resource server 前執行：

1. 向 `DefaultOAuth2AuthorizedClientManager` 請求當前使用者的 authorized client。
2. manager 檢查 access token 是否有效。若已過期，呼叫 `refreshToken()` provider 自動換新。
3. 把有效的 Bearer token 注入 `Authorization` header。

整個 refresh 過程不需要使用者重新登入，對 HTTP 請求的上層邏輯完全透明。
