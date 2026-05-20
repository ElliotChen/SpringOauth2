# OAuth2AuthorizedClientManager 與 RestClient 的協作

本文件介紹 Spring Security OAuth2 Client 模組中 `OAuth2AuthorizedClientManager` 的職責、設計，
以及它如何透過 `OAuth2ClientHttpRequestInterceptor` 與 `RestClient` 串接，達成
「呼叫端不需要自己處理 token 申請、快取、刷新」的目標。

對應本專案實作：

- `resourceClient/src/main/java/tw/elliot/resourceclient/config/RestClientConfig.java`
- `resourceClient/src/main/java/tw/elliot/resourceclient/api/UpdateParameterClientController.java`

---

## 1. 為什麼需要 `OAuth2AuthorizedClientManager`

呼叫受 OAuth2 保護的下游服務時，呼叫端永遠要回答三個問題：

1. **要不要重新申請 token？**（之前沒申請過 / 已過期 / 即將過期）
2. **用哪種 grant 申請？**（client_credentials、authorization_code、refresh_token…）
3. **拿到 token 後存哪裡？**（in-memory 共用、綁 HTTP session、綁特定 principal…）

若每個 Controller / Service 都自己寫一遍這三段邏輯，會出現重複程式碼、過期判斷不一致、refresh 競態等問題。

`OAuth2AuthorizedClientManager` 就是把這三個責任收攏起來的 **token 生命週期協調器**：
傳入「我要哪個 client registration」+「以誰的身分」，回傳「目前可用的 OAuth2AuthorizedClient（內含 access token）」。

---

## 2. 三個核心抽象的關係

```
ClientRegistration         ← 「我是誰」+「授權伺服器在哪」（從 yaml 讀進來的靜態設定）
        ↓
OAuth2AuthorizedClient     ← 「目前持有的授權狀態」（access token + refresh token + 過期時間）
        ↓
OAuth2AuthorizedClientManager  ← 「協調者」：去拿 / 換 / 快取 OAuth2AuthorizedClient
```

- `ClientRegistration`：對應 `application.yaml` 中
  `spring.security.oauth2.client.registration.<id>.*` 區塊，是不可變的設定值。
- `OAuth2AuthorizedClient`：執行期物件，封裝某個 principal 對某個 ClientRegistration
  **目前**持有的授權。
- `OAuth2AuthorizedClientManager`：把上面兩者串起來——「拿著 ClientRegistration
  去授權伺服器申請、或從快取拿出 OAuth2AuthorizedClient」。

---

## 3. `authorize()` 的內部流程

當呼叫 `manager.authorize(OAuth2AuthorizeRequest)`，內部步驟：

1. **查快取**：從 `OAuth2AuthorizedClientRepository`（Web 情境）
   或 `OAuth2AuthorizedClientService`（背景情境）查
   `(clientRegistrationId, principal)` 是否已有授權。
2. **判斷狀態**：
   - 沒有 token → 走「初次授權」（依 grant type）。
   - 有 token 但快過期 / 已過期 → 走「重新授權」或 `refresh_token`。
   - 有 token 且仍有效 → 直接回傳快取的 `OAuth2AuthorizedClient`。
3. **委派給 `OAuth2AuthorizedClientProvider`**：實際執行授權流程
   （`client_credentials`、`authorization_code`、`refresh_token`、`password`、`jwt_bearer`…）。
4. **儲存結果**：把新的 `OAuth2AuthorizedClient` 寫回 repository / service。
5. **回傳** `OAuth2AuthorizedClient`，呼叫端可用 `.getAccessToken().getTokenValue()` 取得字串 token。

---

## 4. 兩種 Manager 實作

| 實作                                              | 適用情境                          | 儲存層                                       |
|---------------------------------------------------|-----------------------------------|--------------------------------------------|
| `DefaultOAuth2AuthorizedClientManager`           | Web / Servlet（使用者已登入）       | `OAuth2AuthorizedClientRepository`（綁 session） |
| `AuthorizedClientServiceOAuth2AuthorizedClientManager` | **背景任務 / 機器對機器**           | `OAuth2AuthorizedClientService`（預設 in-memory，全 app 共用） |

`resourceClient` 用的是 `client_credentials`、沒有最終使用者，因此選用
`AuthorizedClientServiceOAuth2AuthorizedClientManager`：

```java
@Bean
public OAuth2AuthorizedClientManager authorizedClientManager(
        ClientRegistrationRepository clientRegistrationRepository,
        OAuth2AuthorizedClientService authorizedClientService) {

    OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
            .clientCredentials()
            .build();

    AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
            new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                    clientRegistrationRepository, authorizedClientService);
    manager.setAuthorizedClientProvider(provider);
    return manager;
}
```

> 重點：**沒呼叫 `.clientCredentials()`，即使 yaml 裡 grant type 寫對，Manager 也不會幫你做 client_credentials**。
> 這是 Manager 與 Provider 分離設計造成的常見坑。

---

## 5. `OAuth2AuthorizedClientProvider`：真正幹活的人

Manager 自己不執行授權流程，而是把工作委派給一條 Provider 鏈。
透過 `OAuth2AuthorizedClientProviderBuilder` 組裝：

```java
OAuth2AuthorizedClientProviderBuilder.builder()
        .clientCredentials()       // 啟用 client_credentials grant
        .refreshToken()            // 啟用 refresh_token grant
        .authorizationCode()       // 啟用 authorization_code grant
        .build();
```

每種 grant 對應一個 Provider 實作：

- `ClientCredentialsOAuth2AuthorizedClientProvider`：呼叫 `/oauth2/token` 帶 `grant_type=client_credentials`。
- `RefreshTokenOAuth2AuthorizedClientProvider`：用 refresh_token 換新的 access_token。
- `AuthorizationCodeOAuth2AuthorizedClientProvider`：觸發瀏覽器 redirect 流程。

Provider 內部會看 `OAuth2AuthorizedClient.getAccessToken().getExpiresAt()`，
並有個預設 **clockSkew = 60 秒**：token 在到期前 60 秒就被視為過期、自動重新申請。
因此呼叫端不必自己寫快取或排程刷新邏輯。

若要調整 skew：

```java
ClientCredentialsOAuth2AuthorizedClientProvider provider =
        new ClientCredentialsOAuth2AuthorizedClientProvider();
provider.setClockSkew(Duration.ofSeconds(30));
```

---

## 6. 與 `RestClient` 的協作：`OAuth2ClientHttpRequestInterceptor`

`RestClient` 是 Spring 6 起的同步 HTTP client（取代 RestTemplate）。
它支援透過 `ClientHttpRequestInterceptor` 對每個 request 加工，
Spring Security 提供現成的 `OAuth2ClientHttpRequestInterceptor` 把
「取 token → 貼 Authorization header」的邏輯封裝起來。

### 6.1 串接流程

```
應用程式碼
    │
    ↓ restClient.post().uri(...).body(...).retrieve()
RestClient
    │
    ↓ 觸發 ClientHttpRequestInterceptor 鏈
OAuth2ClientHttpRequestInterceptor
    │ 1. 解析 clientRegistrationId（本專案固定回 "resource-server"）
    │ 2. 呼叫 authorizedClientManager.authorize(...)
    │ 3. 從回傳的 OAuth2AuthorizedClient 取 access token
    │ 4. 在 request 上加 Authorization: Bearer <token>
    ↓
下游服務 (resourceServer /sEQI/Param/UpdateParameter)
```

### 6.2 本專案 Bean 組裝

```java
@Bean
public RestClient resourceServerRestClient(
        OAuth2AuthorizedClientManager authorizedClientManager,
        @Value("${app.resource-server.base-url}") String baseUrl) {

    OAuth2ClientHttpRequestInterceptor interceptor =
            new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
    interceptor.setClientRegistrationIdResolver(request -> "resource-server");

    return RestClient.builder()
            .baseUrl(baseUrl)
            .requestInterceptor(interceptor)
            .build();
}
```

- `setClientRegistrationIdResolver(request -> "resource-server")`：
  告訴 interceptor「每個 request 都用 `resource-server` 這個 ClientRegistration」。
  在多下游、多 registration 的場景，可改成根據 URL / header 判斷。
- 之後 Controller 只要注入 `RestClient resourceServerRestClient` 直接呼叫，**完全不用碰 token**：

```java
return resourceServerRestClient.post()
        .uri("/sEQI/Param/UpdateParameter")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .retrieve()
        .body(...);
```

### 6.3 Interceptor 與 Manager 的責任切分

| 元件                                | 職責                                                                 |
|-------------------------------------|--------------------------------------------------------------------|
| `RestClient`                        | 發 HTTP request、處理序列化 / 反序列化、組裝 URI。                       |
| `OAuth2ClientHttpRequestInterceptor`| **薄殼**：解析 registrationId、貼 `Authorization: Bearer` header。      |
| `OAuth2AuthorizedClientManager`     | 取得 / 快取 / 過期判斷 / 刷新 token，回傳可用的 `OAuth2AuthorizedClient`。 |
| `OAuth2AuthorizedClientProvider`    | 真正執行 grant 流程，向授權伺服器發 `/oauth2/token` 請求。                |
| `OAuth2AuthorizedClientService`     | 儲存 `OAuth2AuthorizedClient`（預設 in-memory）。                       |

---

## 7. 脫離 RestClient 也可以用

Manager 也能單獨使用，例如在排程任務、訊息消費者中：

```java
OAuth2AuthorizeRequest req = OAuth2AuthorizeRequest
        .withClientRegistrationId("resource-server")
        .principal("system")
        .build();

OAuth2AuthorizedClient client = authorizedClientManager.authorize(req);
String token = client.getAccessToken().getTokenValue();
```

---

## 8. 對應本專案的設定脈絡

`application.yaml` 中：

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          resource-server:                       # ← clientRegistrationId
            client-id: "admin-client"
            client-secret: "admin-secret"
            authorization-grant-type: "client_credentials"
            scope: "admin"
        provider:
          resource-server:
            token-uri: "http://localhost:9000/oauth2/token"
```

啟動後 Spring Boot 會：

1. 由 `OAuth2ClientRegistrationRepositoryAutoConfiguration` 讀 yaml 建立
   `InMemoryClientRegistrationRepository`，內含 id 為 `resource-server` 的 `ClientRegistration`。
2. 由 `OAuth2AuthorizedClientServiceAutoConfiguration` 建立預設
   `InMemoryOAuth2AuthorizedClientService`。
3. 本專案 `RestClientConfig` 手動組裝 `AuthorizedClientServiceOAuth2AuthorizedClientManager`、
   啟用 `clientCredentials()` provider，並透過 `OAuth2ClientHttpRequestInterceptor`
   接到 `RestClient` 上。

之後流程變成：

```
curl POST /client/UpdateParameter?paramType=01&version=v1
    ↓
UpdateParameterClientController.updateParameter(...)
    ↓
resourceServerRestClient.post().uri(...).body(...).retrieve()
    ↓ Interceptor 介入
OAuth2AuthorizedClientManager.authorize("resource-server", principal="system")
    ├─ 快取沒有 → 呼叫 ClientCredentialsOAuth2AuthorizedClientProvider
    │            → POST http://localhost:9000/oauth2/token (grant_type=client_credentials)
    │            → 取得 JWT、存入 OAuth2AuthorizedClientService
    └─ 已有 token 且未過期 → 直接回傳
    ↓
Interceptor 加上 Authorization: Bearer <jwt>
    ↓
POST http://localhost:8081/sEQI/Param/UpdateParameter
    ↓ resourceServer 用 issuer-uri 取 JWKS 驗章
    ↓ 通過 → 進入 UpdateParameterController
回傳結果
```

---

## 9. 常見坑

1. **忘了呼叫 `.clientCredentials()`**：Manager 不會主動「猜」grant type，
   只執行 Provider 鏈裡有的流程。沒加就會回 `null`、interceptor 進而拋 `ClientAuthorizationRequiredException`。
2. **registrationId 不一致**：yaml 中的 key 與 `clientRegistrationIdResolver` 回傳值必須完全相同。
3. **以為 RestTemplate 寫法可以照搬**：`OAuth2AuthorizedClientInterceptor` 是 RestClient 用的，
   舊版 `ServletOAuth2AuthorizedClientExchangeFilterFunction` 則是 WebClient 用的，不要混用。
4. **沒設 `SecurityFilterChain`**：引入 `spring-boot-starter-oauth2-client`
   會啟用預設保護（formLogin / httpBasic / CSRF），會擋掉自己的 API。本專案 `SecurityConfig`
   宣告 `permitAll` 就是為了關掉這層。