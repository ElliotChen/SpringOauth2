# 為什麼需要 `actuatorSecurityFilterChain`

## 一句話結論

因為 **Spring Security 預設「全部請求都要登入」**，而 actuator endpoints 不該要登入 —— 必須宣告一條專屬 chain 把 `/actuator/**` 放行。

---

## 拆解原因

### 1. 只要 classpath 有 `spring-security`，所有請求預設被擋

加入 `spring-boot-starter-security-oauth2-authorization-server` 後，Spring Security 就會啟用。沒有任何放行設定的情況下，**所有 HTTP 請求都會被導向登入頁或回 401**。

這條規則對 `/actuator/**` 一樣適用——actuator 本身不會自動「公開」自己，它只是註冊一堆 `@Endpoint`，能不能被外部存取要看 Spring Security 是否放行。

### 2. `management.endpoints.web.exposure.include: "*"` 只解一半的問題

這個 property 控制的是 **actuator 框架層** 的開關：

| 屬性 | 控制的事 |
|------|---------|
| `management.endpoints.enabled-by-default` | endpoint 物件是否被建立 |
| `management.endpoints.web.exposure.include` | endpoint 是否註冊到 HTTP（即「在不在 /actuator 路徑下」） |

`include: "*"` 解決的是「endpoint 有沒有掛上 HTTP」，但**完全不影響 Spring Security**。

兩層必須都打通才會通：

```
請求 → [Spring Security filter chain] → [actuator endpoint handler]
              ↑ 這層擋住                   ↑ exposure.include 控制這層
```

只開 exposure 而不放行 security，會得到 **401**；只放行 security 而 exposure 沒開，會得到 **404**。

### 3. 為什麼不能讓 actuator 走「Authorization Server chain」或「default chain」?

我們現在有三條 chain：

| Order | Chain | securityMatcher 命中條件 |
|-------|-------|------------------------|
| 0 | `actuatorSecurityFilterChain` | URL 命中 `EndpointRequest.toAnyEndpoint()` |
| 1 | `authorizationServerSecurityFilterChain` | URL 命中 OAuth2 endpoint matcher |
| 2 | `defaultSecurityFilterChain` | 沒有 securityMatcher，所有未命中的請求 |

**Spring Security 的 chain 路由邏輯是「第一條 securityMatcher 命中就由那條處理，後面不會再跑」**。

如果沒有 actuator 那條：

- `/actuator/health` **不會命中** OAuth2 endpoint matcher（chain 1 跳過）
- 落到 chain 2 —— 但 chain 2 是 `anyRequest().authenticated()` 加 `formLogin()`
- 結果：呼叫 `/actuator/health` 會被重導到登入頁

### 4. 為什麼順序要 `@Order(0)`（最前面）?

Spring 會用所有 SecurityFilterChain bean 的 `@Order` 由小到大排序，**依序比對 `securityMatcher`**，命中即停。

- 即使 actuator chain 排第二、第三也行（因為它的 `securityMatcher` 很精確），但慣例上把「最特化、最不該被誤判」的放最前面
- 萬一未來有人把 default chain 改成沒設 matcher 又放 `@Order(0)`，所有請求都會被它吃掉 → actuator 也被擋。`@Order(0)` 是防呆

---

## 對照範例

**沒有** `actuatorSecurityFilterChain`：

```bash
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9000/actuator/health
# 401 或 302 (重導到 /login)
```

**有** `actuatorSecurityFilterChain` + `permitAll()`：

```bash
curl http://localhost:9000/actuator/health
# {"status":"UP", ...}
```

---

## 簡化記憶

```
exposure.include    →  endpoint 在 HTTP 上「存在」
SecurityFilterChain →  endpoint 在 HTTP 上「可訪問」
```

兩件不同的事，**actuator 要能用，兩個都必須設**。`actuatorSecurityFilterChain` 就是負責後者，把 actuator 從 Spring Security 的「預設全部要登入」這條規則裡豁免出來。

---

## 進階：為什麼不直接在 default chain 放 `permitAll("/actuator/**")` ?

也可以，例如：

```java
http.authorizeHttpRequests(auth -> auth
    .requestMatchers("/actuator/**").permitAll()
    .anyRequest().authenticated())
```

但 Spring Boot 官方推薦獨立 chain，原因：

1. **`EndpointRequest.toAnyEndpoint()` 自動跟著 `management.endpoints.web.base-path` 變動**——如果之後把 actuator base path 從 `/actuator` 改成 `/manage`，硬編碼的 `"/actuator/**"` 就失效，但 `EndpointRequest` 不會
2. **獨立 chain 可以關掉 CSRF、自訂錯誤處理、加 IP 限制**而不影響 default chain
3. **責任分離**，閱讀時一眼看到「這條 chain 專管 actuator」

所以 `actuatorSecurityFilterChain` 不是必需的最小寫法，但它是**最穩健、最不會誤傷的寫法**。

> 本專案保留了「在 default chain 直接 permitAll」的替代版本作為註解，置於 `AuthorizationServerConfig.java` 末端，做為對照與教學用。
