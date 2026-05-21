# ccErrorClient

`client_credentials` 流程下，**邊緣 client 的錯誤處理示範模組**。

與 `ccResourceClient`（happy-path demo）對照，本模組聚焦於：當下游的 `ccOauthServer`（token endpoint）或 `ccResourceServer`（resource API）發生各類異常時，邊緣 client 該如何**辨識錯誤來源、分類、回傳一致的錯誤格式給呼叫端**。

---

## 模組定位

```
clientCredentials/
├── server/
│   ├── ccOauthServer        :9000  Spring Authorization Server
│   └── ccResourceServer     :8081  JWT-protected API
└── client/
    ├── ccResourceClient     :8080  happy-path edge client
    └── ccErrorClient        :8080  ← 本模組：error-handling 範例
```

`ccErrorClient` 與 `ccResourceClient` 在 production code 結構上幾乎一致（同樣用 `OAuth2AuthorizedClientManager` + `RestClient` + `OAuth2ClientHttpRequestInterceptor`），差異在於它**多了一整套錯誤分流與測試**。**不要同時啟動 ccResourceClient 與 ccErrorClient**，兩者都佔 `:8080`。

---

## 核心設計

### 1. 三階段錯誤分類（`ErrorStage`）

外部呼叫 ccErrorClient 失敗時，要能立刻判斷錯誤發生在哪一段：

| Stage | 涵義 | 例子 |
|---|---|---|
| `INPUT` | 呼叫方傳進來的請求本身就壞 | 缺參數、型別不符、HTTP method 不對 |
| `TOKEN` | 跟 `ccOauthServer` 拿 access token 失敗 | `invalid_client`、token endpoint timeout / unreachable / 5xx |
| `RESOURCE` | token 拿到了，呼叫 `ccResourceServer` 失敗 | 401/403/4xx/5xx、resource timeout / unreachable、回應壞 JSON |
| `UNKNOWN` | 上述都不是的未預期錯誤 | 內部 bug |

### 2. `ErrorCode` enum

每一個錯誤情境對應一個 `ErrorCode`，包含三個欄位：

```java
ErrorCode.TOKEN_ENDPOINT_TIMEOUT
    .stage()           // ErrorStage.TOKEN
    .httpStatus()      // 504 GATEWAY_TIMEOUT
    .defaultMessage()  // "token endpoint 回應逾時"
```

涵蓋 21 個情境，集中在 `tw.elliot.errorclient.error.ErrorCode`。

### 3. `ClientFlowException` + `GlobalExceptionHandler`

- Service 層偵測到下游錯誤時，包裝成 `ClientFlowException(ErrorCode, cause)` 丟出。
- `@RestControllerAdvice` 的 `GlobalExceptionHandler` 統一處理：
  - `ClientFlowException` → 直接用其 `ErrorCode` 對應 HTTP status / body
  - Spring MVC binding 例外（`MissingServletRequestParameterException` 等） → 映射到 `INPUT_*`
  - 其它未預期例外 → `INTERNAL_UNEXPECTED`
- 回傳格式統一為 `ErrorResponse`：

```json
{
  "code": "TOKEN_ENDPOINT_TIMEOUT",
  "stage": "TOKEN",
  "message": "token endpoint 回應逾時",
  "timestamp": "2026-05-21T13:41:08.606+08:00"
}
```

### 4. 連線/讀取 timeout 設定

`RestClientConfig` 統一 2 秒 connect timeout、2 秒 read timeout（適用於 token client 與 resource client）。讓 timeout 類錯誤（`TOKEN_ENDPOINT_TIMEOUT`、`RESOURCE_TIMEOUT`）在測試中能在合理時間內觸發。

---

## 套件結構

```
tw.elliot.errorclient
├── ErrorClientApplication        @SpringBootApplication
├── api/
│   └── UpdateParameterClientController   POST /client/UpdateParameter
├── service/
│   └── UpdateParameterService            包裝 RestClient 呼叫，捕捉並重拋為 ClientFlowException
├── config/
│   ├── SecurityConfig                    permitAll（這是 public edge）
│   └── RestClientConfig                  token client / resource client / OAuth2AuthorizedClientManager
└── error/
    ├── ErrorStage                        4 個階段
    ├── ErrorCode                         21 個情境
    ├── ClientFlowException               攜帶 ErrorCode 的 runtime exception
    ├── ErrorResponse                     對外回應 DTO
    └── GlobalExceptionHandler            @RestControllerAdvice
```

---

## 啟動

依下游服務的順序啟動：

```bash
# 1. Authorization Server
cd clientCredentials/server/ccOauthServer    && ./mvnw spring-boot:run    # :9000

# 2. Resource Server
cd clientCredentials/server/ccResourceServer && ./mvnw spring-boot:run    # :8081

# 3. ccErrorClient（本模組）
cd clientCredentials/client/ccErrorClient    && ./mvnw spring-boot:run    # :8080
```

> 注意：`ccResourceClient` 與 `ccErrorClient` 都用 `:8080`，**不要同時啟動**。

### 手動 smoke test

```bash
# Happy path → 200，body code = 0
curl -i -X POST 'http://localhost:8080/client/UpdateParameter' \
  --data 'paramType=01&version=v1'

# INPUT 錯誤（缺 version）→ 400 INPUT_MISSING_PARAMETER
curl -i -X POST 'http://localhost:8080/client/UpdateParameter' \
  --data 'paramType=01'

# 停掉 ccOauthServer 後再呼叫 → 504 TOKEN_ENDPOINT_UNREACHABLE
# 停掉 ccResourceServer 後再呼叫 → 504 RESOURCE_UNREACHABLE
```

---

## 測試

兩種風格的 integration test，覆蓋同一組 6 個情境（happy path、missing param、token invalid_client、token timeout、resource 401、resource timeout）：

| 測試類別 | 框架 | 何時使用 |
|---|---|---|
| `UpdateParameterClientFlowMockWebServerIT` | MockWebServer (OkHttp) | 透過 `SocketPolicy.NO_RESPONSE` 直接驅動 transport 層異常；FIFO `enqueue` 對應「一次呼叫對一個回應」 |
| `UpdateParameterClientFlowWireMockIT` | WireMock | request matching DSL + `withFixedDelay`；對照組，示範同情境的另一種寫法 |

加上 `error/GlobalExceptionHandlerTest`（純單元）共 **25 unit + 12 IT**。

兩者差異與選型理由見 [`docs/01_mockwebserver-vs-wiremock.md`](docs/01_mockwebserver-vs-wiremock.md)。

### 執行指令

```bash
# Unit test only（Surefire，*Test / *Tests）
./mvnw test

# 含 integration test（Failsafe，*IT）
./mvnw verify

# 只跑單一 test 類別
./mvnw -Dtest=UpdateParameterClientFlowWireMockIT test
```

Failsafe 由 `parent/pom.xml` 統一配置，命名為 `*IT` 即會被掛入 `verify` 階段。

---

## 與 ccResourceClient 的差異

| 面向 | ccResourceClient | ccErrorClient |
|---|---|---|
| 定位 | happy-path demo | error-handling demo |
| 例外處理 | 沿用 Spring 預設 | 自訂 `ErrorStage` / `ErrorCode` / `GlobalExceptionHandler` |
| Timeout | 預設（無上限） | connect 2s / read 2s |
| 對外回應 | 下游 body 直通 | 統一 `ErrorResponse` JSON |
| 測試 | 只有 smoke shell script | 12 個 IT，覆蓋各錯誤分支 |

如果要新增的錯誤分類在現有 21 個 `ErrorCode` 內找不到對應，**先在 `ErrorCode` 加項目**，再回到 `UpdateParameterService` / `GlobalExceptionHandler` 加映射，最後補一條 IT（兩個風格擇一即可）。

---

## 參考

- [docs/01_mockwebserver-vs-wiremock.md](docs/01_mockwebserver-vs-wiremock.md) — MockWebServer vs WireMock 比較與選型
- `../ccResourceClient/docs/OAuth2AuthorizedClientManager-with-RestClient.md` — client-side manager + interceptor 配線
- `../../server/ccOauthServer/docs/` — Spring Authorization Server 細節
- 根目錄 `CLAUDE.md` — 全 repo 規約與其它 grant type 對照
