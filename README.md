# OAuth 2.1 Grant Type Demos (Spring Boot 4 + Spring Authorization Server)

本 repo 以「**每個 OAuth grant type 一個獨立子專案**」的方式，示範常見授權流程在 Spring Boot 4 / Spring Authorization Server 上的最小可運行實作，並針對每種流程留下足夠的中文 docs，方便對照 OAuth 2.1 規格學習。

目前包含兩個 demo：

| 子專案 | Grant type | 適用情境 | 入口文件 |
|--------|------------|----------|----------|
| [`clientCredentials/`](clientCredentials/) | `client_credentials` | 機器對機器（M2M）、無使用者登入 | _尚未獨立 README，可直接看 [`docs/`](clientCredentials/server/ccOauthServer/docs/)_ |
| [`pkce/`](pkce/) | `authorization_code` + PKCE | 公開 client（SPA / 行動 / 瀏覽器有人登入） | [`pkce/README.md`](pkce/README.md) |

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
│       └── ccResourceClient/            # :8080 — public edge / token consumer
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

## 共用建置指令

```bash
# 從 repo root 一次建置所有模組
./parent/mvnw -f pom.xml clean install

# 跑單一服務（在該模組目錄）
./mvnw spring-boot:run

# 單一模組測試
./mvnw test

# 從 root 跑指定模組測試
./parent/mvnw -pl pkce/server/pkceOauthServer test
```

---

## Demo 1 — `client_credentials`（M2M）

三個服務組成的純後端流程，**沒有使用者登入**，所有授權由 client 的 `clientId / clientSecret` 換到 token。

| 服務 | Port | 角色 |
|------|------|------|
| `ccOauthServer` | 9000 | Authorization Server，定義 `admin-client` / `member-client` 兩個 client |
| `ccResourceServer` | 8081 | JWT 保護的 API |
| `ccResourceClient` | 8080 | 公開 edge service，所有 endpoint `permitAll`，內部用 `OAuth2AuthorizedClientManager` 取 token |

### 啟動

必須依序（resource server 啟動時要抓 AS 的 JWKS）：

```bash
cd clientCredentials/server/ccOauthServer    && ./mvnw spring-boot:run
cd clientCredentials/server/ccResourceServer && ./mvnw spring-boot:run
cd clientCredentials/client/ccResourceClient && ./mvnw spring-boot:run
```

### 煙霧測試

兩個 shell 腳本，不需瀏覽器：

```bash
# 透過 client 端走完整流程
clientCredentials/client/ccResourceClient/shell/update-parameter.sh

# 直接打 AS 拿 token 再呼叫 resource server，會 dump JWT 內容
clientCredentials/server/ccResourceServer/shell/call-update-parameter.sh
```

### 重點 docs（中文）

- `clientCredentials/server/ccOauthServer/docs/01_..08_*.md` — AS client 設定、三條 SecurityFilterChain 拆解、Boot 4 AS autoconfig 行為
- `clientCredentials/server/ccResourceServer/docs/API_UpdateParameter_V2.md` — 範例 API 契約
- `clientCredentials/client/ccResourceClient/docs/OAuth2AuthorizedClientManager-with-RestClient.md` — client 端 manager + interceptor 接線

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