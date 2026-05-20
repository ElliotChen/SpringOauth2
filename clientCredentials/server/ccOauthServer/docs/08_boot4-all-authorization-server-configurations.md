# Spring Boot 4 內所有的 `AuthorizationServer*Configuration`

`spring-boot-security-oauth2-authorization-server-4.0.6.jar` 裡完整只有 **4 個** 以 `-Configuration` 結尾的類別，全部位於同一個 servlet 套件下（沒有 reactive 變體）：

```
org.springframework.boot.security.oauth2.server.authorization.autoconfigure.servlet
├── OAuth2AuthorizationServerAutoConfiguration         (#1 入口)
├── OAuth2AuthorizationServerConfiguration             (#2 yaml → bean adapter)
├── OAuth2AuthorizationServerJwtAutoConfiguration      (#3 JWK / JwtDecoder)
└── OAuth2AuthorizationServerWebSecurityConfiguration  (#4 SecurityFilterChain)
```

---

## 1. `OAuth2AuthorizationServerAutoConfiguration` —— **進場點**

| 項目 | 內容 |
|------|------|
| 角色 | `@AutoConfiguration` 入口（列在 `META-INF/spring/.../AutoConfiguration.imports`） |
| 註解 | `@ConditionalOnClass(OAuth2Authorization.class)`、`@ConditionalOnWebApplication(SERVLET)` |
| 主要動作 | `@Import({ OAuth2AuthorizationServerConfiguration.class, OAuth2AuthorizationServerWebSecurityConfiguration.class })` |
| 自己的 `@Bean` | **無** —— 純粹是「進場點 + Import」 |

這是 Boot 啟動掃描到的第一個 class，它的責任就是「**啟動條件成立時，把 #2 與 #4 拉進 Spring 容器**」。

> **#3 (JwtAutoConfiguration) 不在這裡 Import**，它自己是獨立的 `@AutoConfiguration`，也列在 imports 檔案內。

---

## 2. `OAuth2AuthorizationServerConfiguration` —— **yaml → bean adapter**

| 項目 | 內容 |
|------|------|
| 角色 | `@Configuration`，從 yaml 讀出 client / settings |
| 條件 | 無 conditional，被 #1 直接 `@Import` |
| 提供的 bean | `RegisteredClientRepository`（in-memory）、`AuthorizationServerSettings` |
| 本專案狀態 | ✅ 啟用 |

> 詳細說明見 `07_boot4-authorization-server-autoconfig.md`。

---

## 3. `OAuth2AuthorizationServerJwtAutoConfiguration` —— **JWT 簽章基礎設施**

| 項目 | 內容 |
|------|------|
| 角色 | 獨立 `@AutoConfiguration`（列在 imports） |
| 條件 | `@ConditionalOnClass(JWKSource.class)`、`@ConditionalOnMissingBean(JWKSource.class)` |
| 提供的 bean | `JWKSource<SecurityContext>`（啟動時隨機生成 RSA 2048 金鑰）、`JwtDecoder` |
| 本專案狀態 | ✅ 啟用 |

關鍵程式碼骨架（從 javap 拆出來）：

```java
@Bean
JWKSource<SecurityContext> jwkSource() {
    KeyPair keyPair = generateRsaKey();
    RSAKey rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
        .privateKey((RSAPrivateKey) keyPair.getPrivate())
        .keyID(UUID.randomUUID().toString())
        .build();
    return new ImmutableJWKSet<>(new JWKSet(rsaKey));
}
```

`@ConditionalOnMissingBean(JWKSource.class)` 表示**你可以自己 `@Bean JWKSource`** 來覆蓋（例如從 keystore / KMS 載入固定金鑰），Boot 就會退讓。本專案沒覆蓋，因此用 Boot 自動生成的金鑰，**每次重啟金鑰會變**。

---

## 4. `OAuth2AuthorizationServerWebSecurityConfiguration` —— **SecurityFilterChain 工廠**

| 項目 | 內容 |
|------|------|
| 角色 | `@Configuration`，提供兩條 `SecurityFilterChain` |
| 條件 | `@ConditionalOnDefaultWebSecurity`、`@ConditionalOnBean(RegisteredClientRepository.class)` |
| 提供的 bean | `authorizationServerSecurityFilterChain`、`defaultSecurityFilterChain` |
| 本專案狀態 | ❌ **被退讓**（因 `WebSecurityConfig` 已自訂 chain） |

兩個條件的意義：

- **`@ConditionalOnDefaultWebSecurity`**：使用者沒有自訂 `SecurityFilterChain` bean 時才啟用 —— 這就是先前 actuator 觸發 404 的根本原因
- **`@ConditionalOnBean(RegisteredClientRepository.class)`**：必須 #2 已先註冊 client repo 才會啟用（沒有 client 就不該啟用 server）

內部行為：

```java
@Bean
SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
    OAuth2AuthorizationServerConfigurer configurer = new OAuth2AuthorizationServerConfigurer();
    configurer.oidc(Customizer.withDefaults());

    http.securityMatcher(configurer.getEndpointsMatcher())
        .with(configurer, Customizer.withDefaults())
        .authorizeHttpRequests(a -> a.anyRequest().authenticated())
        .csrf(c -> c.ignoringRequestMatchers(configurer.getEndpointsMatcher()))
        .oauth2ResourceServer(rs -> rs.jwt(Customizer.withDefaults()))
        .exceptionHandling(...);
    return http.build();
}
```

**幾乎跟本專案 `WebSecurityConfig` 寫的一模一樣** —— 我們其實是把這個 class 的內容搬到自己的 config 中（只多加了 actuator chain）。

---

## 整體啟動流程

```
Boot ApplicationContext 啟動
    │
    ▼
讀取 META-INF/spring/.../AutoConfiguration.imports
    │
    ├── 載入 OAuth2AuthorizationServerAutoConfiguration   [#1]
    │       │ @ConditionalOnClass / @ConditionalOnWebApplication 通過
    │       │
    │       └──@Import {
    │             OAuth2AuthorizationServerConfiguration       [#2] → 註冊 RegisteredClientRepository / Settings
    │             OAuth2AuthorizationServerWebSecurityConfiguration  [#4]
    │                  └── @ConditionalOnDefaultWebSecurity ❌ 因為使用者已自訂 chain
    │                  └── 整組退讓，不註冊任何 SecurityFilterChain
    │         }
    │
    └── 載入 OAuth2AuthorizationServerJwtAutoConfiguration  [#3]
            │ @ConditionalOnMissingBean(JWKSource) 通過
            │
            └── 註冊 JWKSource + JwtDecoder
```

---

## 對照表：4 個 Configuration 的職責邊界

| Class | 是 @AutoConfiguration 嗎 | 提供什麼 bean | 退讓條件 | 本專案 |
|-------|----------------------|------------|---------|-------|
| `OAuth2AuthorizationServerAutoConfiguration` | ✅ | 無（純 @Import） | 沒有 OAuth class / 非 servlet | ✅ |
| `OAuth2AuthorizationServerConfiguration` | ❌ | `RegisteredClientRepository`、`AuthorizationServerSettings` | 無 | ✅ |
| `OAuth2AuthorizationServerJwtAutoConfiguration` | ✅ | `JWKSource`、`JwtDecoder` | `@ConditionalOnMissingBean` —— 自己宣告就退讓 | ✅ |
| `OAuth2AuthorizationServerWebSecurityConfiguration` | ❌ | 兩條 `SecurityFilterChain` | `@ConditionalOnDefaultWebSecurity` —— 自訂任一 chain 就退讓 | ❌ |

---

## 關鍵觀察

1. **Boot 4 的 OAuth Authorization Server autoconfig 切成 4 個 class 是有目的的**：把「資料層 (#2)」、「金鑰層 (#3)」、「HTTP 層 (#4)」分離，每一塊都可獨立替換
2. **替換策略**：
   - 想用 JDBC 存 client？→ 自己 `@Bean RegisteredClientRepository`，**#2 並沒有 conditional 退讓**——所以你的 bean 會與 Boot 提供的 in-memory 衝突，必須**完全移除 yaml 設定**或 `@Primary` 標記
   - 想固定 JWT 金鑰？→ 自己 `@Bean JWKSource`，#3 會因 `@ConditionalOnMissingBean` 退讓
   - 想自訂 chain？→ 自己 `@Bean SecurityFilterChain`，#4 會因 `@ConditionalOnDefaultWebSecurity` 退讓 —— 但**會整組退讓**，必須補回 default chain
3. **沒有 reactive 變體**：Spring Authorization Server 本身就只支援 Servlet（Boot 4 內部也只有 `servlet` 子套件，沒有 `reactive` 子套件）。要做 reactive 的授權伺服器，目前只能自己組

---

## 相關連結

- `07_boot4-authorization-server-autoconfig.md` —— 深入解析 #2 `OAuth2AuthorizationServerConfiguration`
- `02_security-filter-chain-pitfall.md` —— `@ConditionalOnDefaultWebSecurity` 觸發 #4 退讓造成的 404 bug
- `05_why-three-security-filter-chains.md` —— 為什麼自訂 chain 後必須補回 3 條（取代 #4 提供的 2 條 + 補一條 actuator）
