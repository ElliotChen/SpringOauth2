# PKCE Demo (authorization_code + PKCE)

本目錄示範以 **authorization_code grant + PKCE** 完成完整的 OAuth 2.1 流程，由三個 Spring Boot 服務組成：

| 服務 | Host name | Port | 角色 |
|------|-----------|------|------|
| `pkceOauthServer` | `local.pkce.oauth` | 9000 | Spring Authorization Server（發 JWT、提供 `/login`） |
| `pkceResourceServer` | `local.pkce.resource` | 8081 | JWT 保護的 API（`/me`、`/sEQI/Param/UpdateParameter`） |
| `pkceResourceClient` | `local.pkce.client` | 8080 | Thymeleaf 前端，以 `oauth2Login()` 觸發 PKCE，拿到 user token 後呼叫 resource server |

## 為何要動 `/etc/hosts`

PKCE demo 走「瀏覽器 ↔ 兩個 Spring Boot app」的雙 redirect 流程，AS 端與 client 端都會在瀏覽器寫入名為 `JSESSIONID` 的 cookie。**HTTP cookie 只看 host，不看 port**（RFC 6265），如果兩個服務都跑在 `localhost`：

- `localhost:8080` 寫的 `JSESSIONID` 跟 `localhost:9000` 寫的同名 cookie 會互相覆蓋
- 走完 AS 登入再 redirect 回 client 時，client 端 session 已經被 AS 的 session id 蓋掉
- client 找不到原本存的 `OAuth2AuthorizationRequest` → 報 `authorization_request_not_found`
- Spring Security 把整個 OAuth2 login 視為失敗，直接 redirect 到 `/login?error`，畫面顯示 **"Invalid credentials"**（其實跟帳密無關）

詳細根因分析見 [`docs/01_localhost-jsessionid-collision.md`](docs/01_localhost-jsessionid-collision.md)。

最乾淨的解法是讓三個服務各自跑在獨立 host name，cookie 自然按 host 隔離；同時 URL 結構（issuer、redirect_uri、JWT `iss` claim）也比 `localhost:xxxx` 接近 production，學習效果更好。

## 一次性設定：加入 `/etc/hosts`

```bash
sudo sh -c 'cat >> /etc/hosts <<EOF

# OAuth PKCE demo hosts
127.0.0.1 local.pkce.oauth
127.0.0.1 local.pkce.resource
127.0.0.1 local.pkce.client
EOF'
```

驗證：

```bash
ping -c 1 local.pkce.oauth     # 應該回 127.0.0.1
ping -c 1 local.pkce.resource
ping -c 1 local.pkce.client
```

> macOS 若 DNS 沒立即生效，可執行 `sudo dscacheutil -flushcache; sudo killall -HUP mDNSResponder`。

## 啟動順序

必須照這個順序，因為 `pkceResourceServer` 啟動時要去抓 JWKS、`pkceResourceClient` 每次發 token request 都會打 AS：

```bash
# Terminal 1
cd pkce/server/pkceOauthServer    && ./mvnw spring-boot:run    # → http://local.pkce.oauth:9000

# Terminal 2
cd pkce/server/pkceResourceServer && ./mvnw spring-boot:run    # → http://local.pkce.resource:8081

# Terminal 3
cd pkce/client/pkceResourceClient && ./mvnw spring-boot:run    # → http://local.pkce.client:8080
```

## 測試流程（瀏覽器手動）

1. **訪問首頁** — 打開 <http://local.pkce.client:8080/>
   - 應該被 redirect 到 `http://local.pkce.oauth:9000/login`

2. **登入** — 使用以下任一組帳號（定義於 `pkceOauthServer.UserConfig`）：

   | username | password | role |
   |----------|----------|------|
   | `user` | `password` | `USER` |
   | `admin` | `admin` | `ADMIN` |

   登入成功後 AS 會帶你回 `http://local.pkce.client:8080/`。

3. **檢查使用者 token** — 點 `/me` 連結或直接訪問 <http://local.pkce.client:8080/me>
   - 應顯示登入使用者的 JWT 資訊
   - `subject` 是登入的 username（**和 cc demo 的最大差別**：那邊 subject 是 clientId）
   - `issuer` 是 `http://local.pkce.oauth:9000`
   - `role` 對應登入帳號的 `ROLE_*`

4. **呼叫 UpdateParameter** — 點連結或訪問 <http://local.pkce.client:8080/update-parameter>
   - `Param_Type=01` → response `code: 0`（成功）
   - `Param_Type=00` → response `code: -1`（錯誤情境）

5. **登出** — 點 logout，回到首頁應再次被導去登入頁。

## 與 cc demo 的核心差異

| 項目 | clientCredentials | pkce |
|------|-------------------|------|
| grant type | `client_credentials` | `authorization_code` + PKCE |
| 是否登入使用者 | 否 | 是（formLogin） |
| token `subject` | clientId（`admin-client` / `member-client`） | username（`user` / `admin`） |
| `role` 來源 | clientId 對應表 | 登入使用者的 `GrantedAuthority` |
| client 認證 | `client_secret_basic` | **無**（公開 client，`client-authentication-method: none`） |
| 防 code 攔截 | 不適用 | **PKCE `code_verifier` / `code_challenge`** |

## 相關文件

- [`docs/01_localhost-jsessionid-collision.md`](docs/01_localhost-jsessionid-collision.md) — `/etc/hosts` 解法背後的踩雷分析
- [`server/pkceOauthServer/docs/`](server/pkceOauthServer/docs/) — AS 端：PKCE client 設定、user store + formLogin、token customizer 差異
- [`server/pkceResourceServer/docs/01_me-endpoint.md`](server/pkceResourceServer/docs/01_me-endpoint.md) — `/me` 端點規格
- [`client/pkceResourceClient/docs/`](client/pkceResourceClient/docs/) — Client 端：oauth2Login + PKCE、AuthorizedClientManager 帶 user token

## 清除設定

不再需要這個 demo 時，把 `/etc/hosts` 的三行刪除即可，無其他系統影響。