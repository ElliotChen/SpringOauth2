# OAuth 2.1 Grant Type Demos (Spring Boot 4 + Spring Authorization Server)

本 repo 以「**每個 OAuth grant type 一個獨立子專案**」的方式，示範常見授權流程在 Spring Boot 4 / Spring Authorization Server 上的最小可運行實作，並針對每種流程留下足夠的中文 docs，方便對照 OAuth 2.1 規格學習。

目前包含兩個 demo：

| 子專案 | Grant type | 適用情境 | 入口文件 |
|--------|------------|----------|----------|
| [`clientCredentials/`](clientCredentials/) | `client_credentials` | 機器對機器（M2M）、無使用者登入 | [`ccErrorClient/README.md`](clientCredentials/client/ccErrorClient/README.md) / [`ccProductionClient/README.md`](clientCredentials/client/ccProductionClient/README.md) |
| [`pkce/`](pkce/) | `authorization_code` + PKCE | 公開 client（SPA / 行動 / 瀏覽器有人登入） | [`pkce/README.md`](pkce/README.md) |

`clientCredentials/` 下有**三個漸進式 client 模組**，從 happy-path 一路走到 production-grade（詳見 [Demo 1](#demo-1--client_credentialsm2m)）。

OAuth 2.1 規格層級的整體說明見 [`docs/09_oauth-2.1-overview.md`](docs/09_oauth-2.1-overview.md)。

## 技術棧

- Java 25
- Spring Boot 4.0.6
- Spring Authorization Server（內建於 Boot 4 的 OAuth2 autoconfiguration）
- Maven 多模組（無 root `mvnw`；用任一子模組的 wrapper）

## Repo 結構

```
oauth/
├── parent/                              # 共用 BOM + Maven wrapper
│
├── clientCredentials/                   # client_credentials demo
│   ├── server/
│   │   ├── ccOauthServer/               # :9000 — Authorization Server
│   │   └── ccResourceServer/            # :8081 — JWT 保護的 API
│   └── client/
│       ├── ccResourceClient/            # :8080 — happy-path baseline
│       ├── ccErrorClient/               # :8080 — 教學版錯誤分類（21 ErrorCode / 4 stage）
│       └── ccProductionClient/          # :8080 — production-grade（ProblemDetail + Resilience4j + OpenTelemetry）
│
├── pkce/                                # authorization_code + PKCE demo
│   ├── README.md                        # ★ 走 PKCE 流程前先讀這個
│   ├── docs/                            # PKCE 跨 host cookie 衝突等踩雷紀錄
│   ├── server/
│   │   ├── pkceOauthServer/             # :9000 — Authorization Server（含 user store）
│   │   └── pkceResourceServer/          # :8081 — JWT 保護的 API（/me + UpdateParameter）
│   └── client/
│       └── pkceResourceClient/          # :8080 — Thymeleaf + oauth2Login(PKCE)
│
├── docs/                                # 跨 demo 的共通說明
│   └── 09_oauth-2.1-overview.md
│
├── CLAUDE.md                            # Claude Code 的專案規範
└── pom.xml                              # 多模組 aggregator
```

> ⚠️ `clientCredentials/` 與 `pkce/` **共用相同的 9000 / 8081 / 8080 三個 port**，請勿同時啟動兩組 demo。
> ⚠️ 三個 cc client 模組（`ccResourceClient` / `ccErrorClient` / `ccProductionClient`）也都用 :8080，**不要同時啟動**。

## 共用建置指令

```bash
# 從 repo root 一次建置所有模組
./parent/mvnw -f pom.xml clean install

# 跑單一服務（在該模組目錄）
./mvnw spring-boot:run

# 單一模組 unit test（Surefire，*Test）
./mvnw test

# 單一模組含 integration test（Failsafe，*IT）
./mvnw verify

# 從 root 跑指定模組測試
./parent/mvnw -pl pkce/server/pkceOauthServer test
./parent/mvnw -pl clientCredentials/client/ccProductionClient verify
```

> `parent/pom.xml` 統一配置 Surefire / Failsafe；命名為 `*IT` 的測試會自動被 `mvn verify` 掛入，不需各模組重複設定。
> Spring Boot fat jar 透過 `<classifier>exec</classifier>` 與普通 jar 分開，**啟動程式請改用 `target/<module>-exec.jar`**（或繼續用 `./mvnw spring-boot:run`）。

---

## Demo 1 — `client_credentials`（M2M）

純後端流程，**沒有使用者登入**，所有授權由 client 的 `clientId / clientSecret` 換到 token。

兩個 server 固定不變，**client 端有三個漸進式範本**（依需求二擇一啟動）：

| 服務 | Port | 角色 |
|------|------|------|
| `ccOauthServer` | 9000 | Authorization Server，定義 `admin-client` / `member-client` 兩個 client |
| `ccResourceServer` | 8081 | JWT 保護的 API |
| **client 端三選一（同樣 :8080）** | | |
| `ccResourceClient` | 8080 | **happy-path baseline**——最小可運行範例 |
| `ccErrorClient` | 8080 | **教學版錯誤分類**——自訂 `ErrorStage` + 21 `ErrorCode` 把失敗點分層展示，含 MockWebServer / WireMock 雙風格 IT。詳見 [`ccErrorClient/README.md`](clientCredentials/client/ccErrorClient/README.md) |
| `ccProductionClient` | 8080 | **production-grade**——RFC 7807 ProblemDetail + Resilience4j（retry / circuit breaker）+ 401-evict-retry + Spring Boot 4 `spring-boot-starter-opentelemetry`。詳見 [`ccProductionClient/README.md`](clientCredentials/client/ccProductionClient/README.md)、[Resilience4j 介紹](clientCredentials/client/ccProductionClient/docs/01_resilience4j-intro.md) |

### 啟動

必須依序（resource server 啟動時要抓 AS 的 JWKS），第三個 client 三選一：

```bash
cd clientCredentials/server/ccOauthServer    && ./mvnw spring-boot:run
cd clientCredentials/server/ccResourceServer && ./mvnw spring-boot:run

# 三選一
cd clientCredentials/client/ccResourceClient   && ./mvnw spring-boot:run
cd clientCredentials/client/ccErrorClient      && ./mvnw spring-boot:run
cd clientCredentials/client/ccProductionClient && ./mvnw spring-boot:run
```

### 煙霧測試

兩個 shell 腳本，不需瀏覽器：

```bash
# 透過 client 端走完整流程
clientCredentials/client/ccResourceClient/shell/update-parameter.sh

# 直接打 AS 拿 token 再呼叫 resource server，會 dump JWT 內容
clientCredentials/server/ccResourceServer/shell/call-update-parameter.sh
```

### 三個 client 怎麼挑

| 你想學什麼 | 看哪個 |
|---|---|
| OAuth2 happy path 怎麼接 `OAuth2AuthorizedClientManager` + `RestClient` | `ccResourceClient` |
| 各種失敗情境**該如何分類**、stage / code enum 怎麼設計、MockWebServer vs WireMock 怎麼選 | `ccErrorClient` |
| Production 上真的會用的組合：**ProblemDetail + traceId / Resilience4j / 401 evict-retry / OTel** | `ccProductionClient` |

### 重點 docs（中文）

- `clientCredentials/server/ccOauthServer/docs/01_..08_*.md` — AS client 設定、三條 SecurityFilterChain 拆解、Boot 4 AS autoconfig 行為
- `clientCredentials/server/ccResourceServer/docs/API_UpdateParameter_V2.md` — 範例 API 契約
- `clientCredentials/client/ccResourceClient/docs/OAuth2AuthorizedClientManager-with-RestClient.md` — client 端 manager + interceptor 接線
- `clientCredentials/client/ccErrorClient/docs/01_mockwebserver-vs-wiremock.md` — RestClient 測試框架選型
- `clientCredentials/client/ccProductionClient/docs/01_resilience4j-intro.md` — Resilience4j 入門 + 與 Spring Retry / Boot 內建機制的對照

---

## Demo 2 — `authorization_code` + PKCE（瀏覽器使用者登入）

走完整的「瀏覽器登入 → AS 授權碼 → client 兌換 token → 帶 user token 呼叫 resource server」。

| 服務 | Host name | Port | 角色 |
|------|-----------|------|------|
| `pkceOauthServer` | `local.pkce.oauth` | 9000 | AS + formLogin（user store: `user/password`, `admin/admin`） |
| `pkceResourceServer` | `local.pkce.resource` | 8081 | JWT 保護 API（`/me`、`/sEQI/Param/UpdateParameter`） |
| `pkceResourceClient` | `local.pkce.client` | 8080 | Thymeleaf + `oauth2Login()`，自動執行 PKCE |

### ⚠️ 必須先設定 `/etc/hosts`

PKCE demo **不能**直接用 `localhost` 跑，否則三個服務的 `JSESSIONID` cookie 會互相覆蓋，造成 callback 時 `authorization_request_not_found`。詳見 [`pkce/README.md`](pkce/README.md) 與 [`pkce/docs/01_localhost-jsessionid-collision.md`](pkce/docs/01_localhost-jsessionid-collision.md)。

```bash
sudo sh -c 'cat >> /etc/hosts <<EOF

# OAuth PKCE demo hosts
127.0.0.1 local.pkce.oauth
127.0.0.1 local.pkce.resource
127.0.0.1 local.pkce.client
EOF'
```

### 啟動 & 測試流程

依序啟動三個服務後，打開 <http://local.pkce.client:8080/> 並用 `user / password` 或 `admin / admin` 登入，再走 `/me`、`/update-parameter` 兩個範例頁。完整步驟見 [`pkce/README.md`](pkce/README.md)。

---

## 兩個 demo 的核心差異一覽

| 項目 | clientCredentials | pkce |
|------|-------------------|------|
| grant type | `client_credentials` | `authorization_code` + PKCE |
| 是否有使用者登入 | ❌ | ✅（formLogin） |
| Token `subject` | clientId | username |
| `role` claim 來源 | clientId 對應表 | 登入使用者的 `GrantedAuthority` |
| Client 認證方式 | `client_secret_basic` | **無**（公開 client） |
| 是否需要 PKCE | 不適用 | **必須**（`code_verifier` / `code_challenge`） |
| Smoke test 方式 | shell 腳本 | 瀏覽器手動 |
| 是否需要動 `/etc/hosts` | 否 | **是** |

---

## Claude Code 使用者

本 repo 已寫好 [`CLAUDE.md`](CLAUDE.md)，包含：

- 模組佈局與啟動順序
- Boot 4 + Spring Authorization Server 的 `@ConditionalOnDefaultWebSecurity` 雷區提醒
- 所有 demo 子目錄已存在的中文 docs 索引

新增 / 改動授權流程前，請先把對應 demo 的 `docs/` 看過，避免重複踩雷。

## 授權

Demo 用途，未指定授權條款。