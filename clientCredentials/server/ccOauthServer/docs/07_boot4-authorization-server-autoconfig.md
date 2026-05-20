# Spring Boot 4 的 `OAuth2AuthorizationServerConfiguration` 做了什麼？

先定位：完整類名是

```
org.springframework.boot.security.oauth2.server.authorization
    .autoconfigure.servlet.OAuth2AuthorizationServerConfiguration
```

位於 `spring-boot-security-oauth2-authorization-server-4.0.6.jar`。它是 Boot autoconfig 的一部分，**不是** Spring Security 的 configurer 工具，也**不是**本專案自己寫的 `AuthorizationServerConfig`。

---

## 1. 它的本體：兩個 `@Bean` + 一個 properties 注入

從 javap 拆解出來的結構：

```java
class OAuth2AuthorizationServerConfiguration {
    private final OAuth2AuthorizationServerPropertiesMapper propertiesMapper;

    OAuth2AuthorizationServerConfiguration(OAuth2AuthorizationServerProperties properties) {
        this.propertiesMapper = new OAuth2AuthorizationServerPropertiesMapper(properties);
    }

    @Bean
    RegisteredClientRepository registeredClientRepository() {
        // 把 yaml 裡 spring.security.oauth2.authorizationserver.client.* 轉成
        // 一個 InMemoryRegisteredClientRepository
        return new InMemoryRegisteredClientRepository(propertiesMapper.asRegisteredClients());
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings() {
        // 把 yaml 裡的 issuer / endpoint-uri / multiple-issuers-allowed 轉成 settings
        return propertiesMapper.asAuthorizationServerSettings();
    }
}
```

**它只做兩件事**：

1. 讀 `OAuth2AuthorizationServerProperties`（由 `@ConfigurationProperties("spring.security.oauth2.authorizationserver")` 綁定 yaml）
2. 把 properties 轉成兩個 Spring bean：`RegisteredClientRepository`、`AuthorizationServerSettings`

---

## 2. 從 yaml 到 bean 的具體對應

### yaml 設定

```yaml
spring:
  security:
    oauth2:
      authorizationserver:
        client:
          admin-client:
            registration:
              client-id: "admin-client"
              client-secret: "{noop}admin-secret"
              client-authentication-methods: ["client_secret_basic"]
              authorization-grant-types: ["client_credentials"]
              scopes: ["admin"]
            token:
              access-token-time-to-live: "30m"
              access-token-format: "self-contained"
```

### 轉換為 bean

#### A. `RegisteredClientRepository`（in-memory）

`OAuth2AuthorizationServerPropertiesMapper.asRegisteredClients()` 會逐個 client：

| yaml 欄位 | 對應到 `RegisteredClient` 的 setter |
|----------|----------------------------------|
| `registration.client-id` | `.clientId(...)` |
| `registration.client-secret` | `.clientSecret(...)`（前綴 `{noop}` 透過 `DelegatingPasswordEncoder` 解析） |
| `registration.client-authentication-methods` | `.clientAuthenticationMethod(...)` × N |
| `registration.authorization-grant-types` | `.authorizationGrantType(...)` × N |
| `registration.scopes` | `.scope(...)` × N |
| `registration.redirect-uris` | `.redirectUri(...)` × N |
| `token.access-token-time-to-live` | `TokenSettings.accessTokenTimeToLive(...)` |
| `token.access-token-format` | `TokenSettings.accessTokenFormat(...)` |
| `token.reuse-refresh-tokens` | `TokenSettings.reuseRefreshTokens(...)` |
| `require-proof-key` | `ClientSettings.requireProofKey(...)` |
| `require-authorization-consent` | `ClientSettings.requireAuthorizationConsent(...)` |

每個 client 變成一個 `RegisteredClient` 物件，集合起來包進 `InMemoryRegisteredClientRepository`。

#### B. `AuthorizationServerSettings`

| yaml 欄位 | 對應 setting |
|----------|------------|
| `issuer` | `.issuer(...)` |
| `multiple-issuers-allowed` | `.multipleIssuersAllowed(...)` |
| `endpoint.authorization-uri` | `.authorizationEndpoint(...)` |
| `endpoint.token-uri` | `.tokenEndpoint(...)` |
| `endpoint.jwk-set-uri` | `.jwkSetEndpoint(...)` |
| `endpoint.token-introspection-uri` | `.tokenIntrospectionEndpoint(...)` |
| `endpoint.token-revocation-uri` | `.tokenRevocationEndpoint(...)` |
| `endpoint.device-authorization-uri` | `.deviceAuthorizationEndpoint(...)` |
| `endpoint.oidc.user-info-uri` | OIDC userinfo 端點 |
| `endpoint.oidc.client-registration-uri` | OIDC client registration 端點 |
| `endpoint.oidc.logout-uri` | OIDC logout 端點 |

本專案沒設這些（所以 issuer 變成自動推導的 `http://localhost:9000`、所有端點都是預設路徑）。

---

## 3. 它**沒做**的事

這是常見誤解點。`OAuth2AuthorizationServerConfiguration` **不**做以下事情：

| 不做的事 | 由誰做 |
|---------|-------|
| 註冊 `SecurityFilterChain` / OAuth servlet filter | `OAuth2AuthorizationServerWebSecurityConfiguration`（**另一個 class**） |
| 生成 RSA 金鑰、提供 `JWKSource` | `OAuth2AuthorizationServerJwtAutoConfiguration` |
| 提供 `JwtDecoder` | `OAuth2AuthorizationServerJwtAutoConfiguration` |
| 提供 `JwtEncoder` / `OAuth2TokenGenerator` | Spring Authorization Server 內部預設 |
| 設定 default user (form login 用) | `UserDetailsServiceAutoConfiguration`（Boot security 提供） |

它**只負責「從 yaml 餵 client 與 settings 進 Spring 容器」**這一件事。

---

## 4. 與另外兩個鄰居的關係

Boot 4 的 OAuth2 Authorization Server autoconfig 模組裡有三個關鍵 class：

```
spring-boot-security-oauth2-authorization-server-4.0.6.jar
└── .../autoconfigure/servlet/
    ├── OAuth2AuthorizationServerAutoConfiguration        ← @AutoConfiguration 入口
    │      • @Import(OAuth2AuthorizationServerConfiguration.class)
    │      • @Import(OAuth2AuthorizationServerJwtAutoConfiguration.class)
    │      • @Import(OAuth2AuthorizationServerWebSecurityConfiguration.class)
    │
    ├── OAuth2AuthorizationServerConfiguration            ← 本篇主角
    │      • @Bean RegisteredClientRepository  (in-memory, 來自 yaml)
    │      • @Bean AuthorizationServerSettings           (來自 yaml)
    │
    ├── OAuth2AuthorizationServerJwtAutoConfiguration
    │      • @Bean JWKSource<SecurityContext>            (自動生成 RSA key)
    │      • @Bean JwtDecoder
    │
    └── OAuth2AuthorizationServerWebSecurityConfiguration
           ← @ConditionalOnDefaultWebSecurity  (一旦自訂 chain 就整組消失)
           • @Bean SecurityFilterChain authorizationServerSecurityFilterChain
           • @Bean SecurityFilterChain defaultSecurityFilterChain
```

### 串起來的啟動流程

1. Boot 啟動 → 載入 `OAuth2AuthorizationServerAutoConfiguration`
2. `@Import` 三個 sub-configuration
3. **`OAuth2AuthorizationServerConfiguration`**：讀 yaml → 註冊 `RegisteredClientRepository` 與 `AuthorizationServerSettings`
4. `OAuth2AuthorizationServerJwtAutoConfiguration`：生成 RSA key → 註冊 `JWKSource`、`JwtDecoder`
5. `OAuth2AuthorizationServerWebSecurityConfiguration`：
   - 檢查 `@ConditionalOnDefaultWebSecurity`（沒有使用者自訂 SecurityFilterChain）
   - 若通過：建立 `SecurityFilterChain` bean，內部 `new OAuth2AuthorizationServerConfigurer()` 並把上面三個 bean 透過 DI 接過來
   - 若不通過：**整組退讓**（先前 actuator 引發的 404 bug 就在這）

---

## 5. 本專案目前的狀態

| Boot 提供 | 狀態 | 用途 |
|----------|------|------|
| `OAuth2AuthorizationServerConfiguration` | ✅ 啟用 | 從 yaml 建立 admin-client / member-client 的 `RegisteredClientRepository` |
| `OAuth2AuthorizationServerJwtAutoConfiguration` | ✅ 啟用 | 啟動時生成 RSA 金鑰、提供 `JWKSource` |
| `OAuth2AuthorizationServerWebSecurityConfiguration` | ❌ **被退讓** | 因為我們自訂了 `WebSecurityConfig` 裡的 chain bean |

所以**「讀 yaml → bean」這條鏈仍在運作**（這是為什麼 yaml 改 client 設定立刻生效）；只有「組裝 chain」那段是我們自己接手做的。

---

## 6. 三個容易混淆的命名

| 名字 | 是什麼 | 由誰提供 | 放在哪 |
|------|------|---------|-------|
| `OAuth2AuthorizationServerConfigurer` | Security 的 configurer 工具（用來組裝 chain） | Spring Security 7 | `WebSecurityConfig` 內 `new` 出來 |
| `OAuth2AuthorizationServerConfiguration`（Boot 4） | Boot 提供的 `@Configuration` autoconfig（讀 yaml → bean） | Spring Boot 4 | autoconfig jar |
| `AuthorizationServerConfig`（本專案） | 我們自己寫的 `@Configuration`（OAuth 領域 bean） | 本專案 | `config/AuthorizationServerConfig.java` |

第一個是工具，後兩個都是 `@Configuration` —— **真正的「平行對應」是後兩個**，第一個只是名字像。

實務上記憶法則：

```
名字結尾是 -Configurer   →  HttpSecurity 工具
名字結尾是 -Configuration / -Config  →  Spring 容器層的 bean 工廠
```

---

## 結論

`OAuth2AuthorizationServerConfiguration` 等於「**yaml 設定 ↔ Spring Authorization Server 領域 bean**」的 adapter。

它是 yaml 能讓我們宣告 client 而不寫 Java 的根本原因 —— 沒有它，所有 `RegisteredClient` 都得自己用 Java builder 寫出來再 `@Bean` 註冊。
