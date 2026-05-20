# OAuthServer Endpoints 與公鑰取得方式

## 1. 完整 Endpoint 列表

啟用 `spring-boot-starter-security-oauth2-authorization-server` 後，Boot 4 自動暴露以下端點（base URL：`http://localhost:9000`）：

### OAuth 2.1 / OAuth 2.0 標準端點

| 路徑 | 方法 | 用途 | 本專案使用 |
|------|------|------|-----------|
| `/oauth2/token` | POST | 換取 access token（所有 grant type 共用） | ✅ Client Credentials 主要端點 |
| `/oauth2/authorize` | GET | 授權碼流程的使用者授權入口 | ❌ 未啟用 authorization_code |
| `/oauth2/introspect` | POST | 驗證 token 是否有效（RFC 7662） | ⚠️ 可用，但 JWT 場景通常不需要 |
| `/oauth2/revoke` | POST | 撤銷 token（RFC 7009） | ⚠️ 可用 |
| `/oauth2/jwks` | GET | **JWK Set 公鑰端點**（供 Resource Server 驗章用） | ✅ **核心** |
| `/oauth2/device_authorization` | POST | Device flow 啟動 | ❌ 未啟用 |
| `/oauth2/device_verification` | GET | Device flow 使用者驗證頁 | ❌ 未啟用 |
| `/oauth2/par` | POST | Pushed Authorization Request | ❌ 未啟用 |

### Metadata / Discovery 端點

| 路徑 | 方法 | 用途 |
|------|------|------|
| `/.well-known/oauth-authorization-server` | GET | OAuth 2.0 Authorization Server Metadata（RFC 8414） |
| `/.well-known/openid-configuration` | GET | OpenID Connect Discovery |

> 這兩個端點回傳一份 JSON，列出所有上述端點的完整 URL —— Resource Server 通常只需要知道 `issuer-uri`，就能透過 discovery 自動找到 `jwks_uri`、`introspection_endpoint` 等。

### OIDC 端點（autoconfig 預設啟用）

| 路徑 | 方法 | 用途 |
|------|------|------|
| `/userinfo` | GET | 取得使用者資訊（需 OIDC `openid` scope） |
| `/connect/register` | POST | Dynamic Client Registration |
| `/connect/logout` | GET | OIDC RP-Initiated Logout |

> 由於本專案僅使用 `client_credentials`（無使用者參與），OIDC 端點實務上**不會被觸發**。

### Actuator 端點（已加入 starter）

| 路徑 | 用途 |
|------|------|
| `/actuator` | 端點索引 |
| `/actuator/health` | 健康檢查（已顯示 details） |
| `/actuator/info` | 應用資訊 |
| `/actuator/env` | 環境變數與設定 |
| `/actuator/configprops` | 所有 `@ConfigurationProperties` |
| `/actuator/beans` | Spring beans 清單 |
| `/actuator/mappings` | HTTP mapping 清單 |
| `/actuator/loggers` | 動態調整 log level |
| `/actuator/metrics` | JVM / HTTP metrics |
| `/actuator/threaddump` | 執行緒快照 |
| `/actuator/sbom` | 軟體物料清單 |

---

## 2. 預設登入帳號密碼

### Client Credentials（OAuth client 認證）

本專案在 `application.yaml` 透過 `spring.security.oauth2.authorizationserver.client.*` 宣告了兩組 client：

| client_id | client_secret | scope | role claim |
|-----------|--------------|-------|-----------|
| `admin-client` | `admin-secret` | `admin` | `admin` / `ROLE_ADMIN` |
| `member-client` | `member-secret` | `member` | `member` / `ROLE_MEMBER` |

呼叫 token endpoint 範例：

```bash
curl -u admin-client:admin-secret \
  -d 'grant_type=client_credentials&scope=admin' \
  http://localhost:9000/oauth2/token
```

> Secret 在 yaml 中以 `{noop}` 前綴儲存（明文），僅適用 demo。正式環境改用 `{bcrypt}...`。

### Default User（瀏覽器登入用）

`defaultSecurityFilterChain` 啟用了 `formLogin()`，意即非 actuator、非 OAuth 端點的任何請求都會被導向 `/login` 表單登入頁。

由於 `application.yaml` 沒有設定 `spring.security.user.*`，Spring Boot 會**自動建立一個預設使用者**：

| 帳號 | 密碼 | 來源 |
|------|------|------|
| `user` | 啟動 log 中隨機產生的 UUID | Boot 內建 `UserDetailsServiceAutoConfiguration` |

啟動時 log 會印出類似下面這行：

```
Using generated security password: 8e4d3a5b-1234-...

This generated password is for development use only. Your security configuration must be updated before running your application in production.
```

每次重啟都會重新生成，**只能在 log 找到**。

#### 如果要固定預設帳密

在 `application.yaml` 加入：

```yaml
spring:
  security:
    user:
      name: "admin"
      password: "admin"
      roles:
        - "ADMIN"
```

或在 Java 改用 `InMemoryUserDetailsManager`。

#### 本專案實務上幾乎用不到 default user

因為：
- OAuth2 endpoints 走 client 認證（Basic Auth），不需 user 登入
- Actuator 已 permitAll
- 沒有業務 controller 落在 default chain

唯一會碰到的場景：**直接用瀏覽器打 `http://localhost:9000/`**——會被導向 `/login`，這時才需要 `user` + log 裡的隨機密碼。

---

## 3. 驗證 Endpoint 存在

```bash
curl http://localhost:9000/.well-known/oauth-authorization-server | jq
```

回應範例（節錄）：
```json
{
  "issuer": "http://localhost:9000",
  "token_endpoint": "http://localhost:9000/oauth2/token",
  "jwks_uri": "http://localhost:9000/oauth2/jwks",
  "introspection_endpoint": "http://localhost:9000/oauth2/introspect",
  "revocation_endpoint": "http://localhost:9000/oauth2/revoke",
  "grant_types_supported": ["authorization_code", "client_credentials", "refresh_token", ...]
}
```

---

## 4. Resource Server 如何取得公鑰

### 機制：JWKS（JSON Web Key Set）

公鑰透過 `/oauth2/jwks` 以 **JWK 格式** 公開。範例回應：

```json
{
  "keys": [{
    "kty": "RSA",
    "e": "AQAB",
    "kid": "c99f4edf-adea-4476-bd7b-fb135ecf778f",
    "n": "xGOr-H7A...（base64url 的 modulus）...",
    "alg": "RS256"
  }]
}
```

JWT header 帶有 `kid`，Resource Server 用 `kid` 對應到 JWKS 裡的公鑰來驗章。

### 三種典型整合方式

#### 方式 A：靠 `issuer-uri`（最推薦）✅

Resource Server 端只需要：

```yaml
# resource server 的 application.yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9000
```

啟動流程：
1. Spring 自動呼叫 `http://localhost:9000/.well-known/oauth-authorization-server`
2. 從 metadata 解析出 `jwks_uri`
3. 抓取公鑰並快取
4. 驗章時同時驗證 `iss` claim 必須等於 `issuer-uri`

**優點**：一個設定搞定，自動發現所有端點。

> 注意：Resource Server 啟動時 Authorization Server 必須已經運行，否則 discovery 失敗會啟動錯誤。

#### 方式 B：直接指定 `jwk-set-uri`

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:9000/oauth2/jwks
```

**優點**：啟動時不依賴 discovery，Authorization Server 不需要在線。
**缺點**：不會自動驗證 `iss` claim，需要另外設定。

#### 方式 C：本地內嵌公鑰

把公鑰匯出成 PEM 檔，放進 Resource Server 的 classpath：

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          public-key-location: classpath:auth-server.pub
```

**優點**：完全離線、無網路依賴。
**缺點**：金鑰輪替時必須重新部署 Resource Server——**目前本專案 Boot 自動生成金鑰，每次重啟都會變**，所以這個方式不適合 demo 環境。

---

## 5. 公鑰取得流程圖

```
┌──────────────────┐                 ┌──────────────────────┐
│ Resource Server  │                 │  Authorization Server │
│  (Spring Boot)   │                 │  localhost:9000       │
└────────┬─────────┘                 └──────────┬───────────┘
         │                                      │
         │ 1. 啟動: GET /.well-known/           │
         │    oauth-authorization-server        │
         ├─────────────────────────────────────>│
         │<─────── metadata JSON ───────────────│
         │   (含 jwks_uri)                      │
         │                                      │
         │ 2. GET /oauth2/jwks                  │
         ├─────────────────────────────────────>│
         │<─────── JWK Set (公鑰) ──────────────│
         │   ↓ 本地快取                          │
         │                                      │
         │ 3. 收到請求 Bearer <JWT>             │
         │    用快取的公鑰驗章 (本地, 無網路)    │
         │                                      │
         │ 4. (定期或遇未知 kid 時)             │
         │    重新抓 JWKS                       │
         ├─────────────────────────────────────>│
```

---

## 6. 實際 demo：建立 Resource Server

最小範例（任何 Spring Boot 應用）：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
</dependency>
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: http://localhost:9000
server:
  port: 8080
```

```java
@RestController
public class DemoController {
    @GetMapping("/admin/ping")
    @PreAuthorize("hasAuthority('SCOPE_admin')")
    public String adminOnly(@AuthenticationPrincipal Jwt jwt) {
        return "hello " + jwt.getClaim("role");
    }
}
```

呼叫：

```bash
TOKEN=$(curl -s -u admin-client:admin-secret \
  -d 'grant_type=client_credentials&scope=admin' \
  http://localhost:9000/oauth2/token | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/admin/ping
```

整個過程中 Resource Server 與 Authorization Server **僅在啟動時通訊一次抓 JWKS**，之後每個請求都是本地驗章，沒有網路往返。

---

## 7. 常見問題

| 問題 | 原因 / 解法 |
|------|------------|
| Resource Server 啟動報 `Unable to resolve Configuration` | Authorization Server 未啟動或 `issuer-uri` 寫錯 |
| `iss` claim 不匹配 | `issuer-uri` 必須與 JWT 內的 `iss` **字串完全相同**（含 scheme、port、有無斜線） |
| 重啟 Authorization Server 後舊 token 全部失效 | Boot 自動生成的 RSA 金鑰會變。正式環境須固定金鑰（keystore / KMS） |
| Resource Server 取不到新 kid | Spring 預設只抓一次 JWKS，遇未知 kid 才重抓——這是內建的；若想定期輪替可調 `NimbusJwtDecoder` 的 cache |
| 用瀏覽器打 `http://localhost:9000/` 跳到登入頁 | 預期行為。使用 `user` + 啟動 log 裡的隨機密碼登入；或設 `spring.security.user.*` 固定 |
