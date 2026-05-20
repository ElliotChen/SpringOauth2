# PKCE demo 踩雷紀錄：localhost JSESSIONID 衝突導致 `authorization_request_not_found`

## 症狀

跑 PKCE demo 時，瀏覽器流程走到一半就壞掉：

1. 開 `http://localhost:8080/` → 被導到 `http://localhost:9000/login`
2. 在 :9000 用 `user / password` 登入成功
3. AS 同意授權、產出 authorization code，瀏覽器被導回
   `http://localhost:8080/login/oauth2/code/pkce-client?code=...&state=...`
4. **pkceResourceClient 立刻 redirect 到 `/login?error`，畫面顯示 "Invalid credentials"**

打開 TRACE log 後，pkceResourceClient 端會看到底層真正的錯誤：

```
OAuth2AuthorizationException: [authorization_request_not_found]
```

不是帳密錯，也不是 PKCE verifier 錯，更不是 client 認證方式錯 ——
是 Spring Security 在 callback 那一刻**找不到自己之前存的 `OAuth2AuthorizationRequest`**。

## 根因：兩個 Spring Boot app 都在 `localhost` 上寫名為 `JSESSIONID` 的 cookie

`HttpSessionOAuth2AuthorizationRequestRepository` 把 `state` / `code_verifier` 等資訊
存在 `HttpSession` 裡，callback 進來時靠 session 拿回原始 authorization request。

但是：

- pkceOauthServer (:9000) Tomcat 預設寫 cookie `JSESSIONID=AAA; Path=/`
- pkceResourceClient (:8080) Tomcat 預設寫 cookie `JSESSIONID=BBB; Path=/`

**HTTP cookie 不區分 port，只區分 host**（RFC 6265）。所以對瀏覽器來說，
兩個 cookie 同 host (`localhost`)、同 name (`JSESSIONID`)、同 path (`/`)，
**後寫的會覆蓋先寫的**。實際發生順序通常是：

1. 訪 :8080/ → :8080 寫入 `JSESSIONID=BBB`（這個 session 存了 PKCE state）
2. 被導去 :9000/oauth2/authorize → :9000 寫入 `JSESSIONID=AAA`，**覆蓋掉 BBB**
3. 登入、redirect 回 :8080/login/oauth2/code/...
4. 瀏覽器送的 cookie 是 `JSESSIONID=AAA`（AS 的 session id）
5. :8080 拿這個 id 去找 session → 找不到（或拿到一個空的新 session）
6. `removeAuthorizationRequest()` 回 `null` → `authorization_request_not_found`
7. Spring Security 把整個 OAuth2 login 當成失敗 → `/login?error` → 預設頁面顯示
   "Invalid credentials"（這是 form login 的通用錯誤訊息，跟使用者帳密無關）

`clientCredentials/` demo 不會踩到這個雷，因為 `client_credentials` grant 純粹是
server-to-server，瀏覽器完全不參與，沒有 session、沒有 cookie。
**PKCE 是這個 repo 第一個需要瀏覽器跨 port session 的流程**，所以雷在這裡才被引爆。

## 解法評估

| 方案 | 做法 | 優點 | 缺點 |
|------|------|------|------|
| A. 改 cookie name | 兩個 app 各自設定 `server.servlet.session.cookie.name` 為不同值 | 不需動作業系統設定，啟動即生效 | 仍使用 `localhost`，與 production 環境差很多；JWT `iss` claim 是 `http://localhost:9000`，不像真實 URL；未來再加任何同 host 服務還要繼續取名 |
| B. 改 `/etc/hosts`（**本專案採用**） | 把三個服務各自對應到獨立 host name | host 不同，cookie 完全隔離；URL 結構接近 production；JWT `iss`、`redirect_uri` 等都有可讀的 host 名 | 需要動 `/etc/hosts`（需 sudo），新人 onboarding 多一個步驟 |

本專案選用 **方案 B**，理由是這個 repo 是 OAuth grant types 的學習 demo，URL 結構
越接近 production 越能幫助理解 `issuer`、`redirect_uri` 等概念。設定方式見
[`pkce/README.md`](../README.md)。

## 為什麼不是改 cookie path 或 domain？

- `Path` 對 :9000 和 :8080 都是 `/`，沒法用 path 區分。
- `Domain` 在 localhost 上只能設 `localhost`（同一個值），也沒法區分。
- 改 port-specific cookie 屬性 → cookie 規範本身不支援。

## 排查時的關鍵 log 設定

未來再遇到 PKCE 流程「卡在 callback、被導去 `/login?error`、表面沒錯誤」時，
先把這段加到 client 端：

```yaml
logging:
  level:
    org.springframework.security: TRACE
    org.springframework.security.oauth2.client: TRACE
    org.springframework.security.oauth2.client.web: TRACE
    org.springframework.security.web.authentication: TRACE
```

特別注意：
- `OAuth2LoginAuthenticationFilter` 抓到 `OAuth2AuthorizationException` 後**只**會
  redirect 到 `/login?error`，不會把 exception 印在 ERROR level；要看到真正的
  `error_code` (`authorization_request_not_found` / `invalid_state` / `invalid_grant`
  …) 必須開到 DEBUG 以上，TRACE 最保險。
- AS 端如果**完全沒看到 `POST /oauth2/token`** 進來，幾乎可以確定問題在 callback
  進到 client 那一刻就失敗了（state / session 對不上），不是 token endpoint 的問題。

## 教訓總結

- 同主機跑多個 Spring Boot app 而且其中至少一個需要瀏覽器 session 時，
  **永遠**讓每個 app 跑在獨立 host name（或至少獨立 cookie name）。
- "Invalid credentials" 在 OAuth2 login client 端 99% 是**通用錯誤頁的籠統訊息**，
  跟使用者輸入的帳密無關；真正錯誤要去 client log 找 `OAuth2AuthorizationException`。
- DEBUG 不一定夠，OAuth2 client 的失敗細節常常只在 TRACE。