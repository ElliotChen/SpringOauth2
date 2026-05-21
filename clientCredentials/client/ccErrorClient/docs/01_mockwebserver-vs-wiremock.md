# MockWebServer 與 WireMock 比較

ccErrorClient 的測試需要覆蓋 `RestClient` 與下游服務之間的各類錯誤情境（HTTP 4xx/5xx、read timeout、connect refused、TLS、JSON 解析失敗等）。
這類情境必須由「真的會起 socket 的 HTTP 伺服器」來驅動，純攔截型工具（如 `MockRestServiceServer`）無法模擬 transport 層行為。
本文比較目前最常用的兩個選擇：**MockWebServer**（Square / OkHttp 出品）與 **WireMock**。

---

## 一句話結論

| 場景 | 建議 |
|---|---|
| 單元測試／單一 client 的錯誤路徑驗證（timeout、connect refused、5xx、回傳壞 JSON） | **MockWebServer** |
| 跨服務整合測試、契約測試、需 stub 大量 endpoint、需 record/replay、需在多個程序共用 mock | **WireMock** |

ccErrorClient 目前的測試需求（針對單一 `RestClient` 驗證錯誤分流）落在前者，所以選 MockWebServer。

---

## 基本介紹

### MockWebServer
- 出自 `com.squareup.okhttp3:mockwebserver`（與 OkHttp 同源）。
- 設計目標：在單元測試裡啟動一個極輕量的 HTTP server，逐筆 `enqueue(MockResponse)` 回應請求。
- API 風格：命令式、**FIFO queue**——你 enqueue 幾個回應，server 就依序回。
- 啟動／關閉以「per test」為單位，幾乎沒有額外成本。

### WireMock
- 出自 `org.wiremock:wiremock-standalone`（也有 `wiremock-jetty12` 等變體）。
- 設計目標：完整的 **HTTP service virtualization**——支援 stub mapping、request matching DSL、scenarios（狀態機）、record/replay、proxy、verification、JSON 檔案管理 stub。
- 可內嵌（`WireMockServer` / `@WireMockTest` JUnit 5 extension）或獨立進程（standalone jar、Docker image）。
- 適合當作「一個會長期存在、被多個測試或多個服務共用」的 mock。

---

## 適用場景對照

### ✅ 選 MockWebServer

1. **只測一個 client / 一個 outbound 呼叫的錯誤分流**
   每個 test 想精準控制「這次呼叫回什麼」，FIFO queue 剛好對應「第 N 次呼叫 → 第 N 個回應」。
2. **要模擬 transport 層異常**
   - `MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)` → read timeout
   - `SocketPolicy.DISCONNECT_AT_START` / `DISCONNECT_AFTER_REQUEST` → 連線中斷
   - `setHeadersDelay(...)` / `setBodyDelay(...)` → 模擬慢回應
   - server 直接 `shutdown()` 後再呼叫 → connect refused
3. **要驗證請求內容**
   `server.takeRequest()` 拿到 `RecordedRequest`，直接斷言 path / headers / body。
4. **測試啟動成本敏感**
   啟動極快、無 Jetty／Servlet container 開銷，適合大量 unit test。
5. **與 OkHttp / Retrofit / 任意 `HttpClient` 共用**
   雖然出自 Square，但對被測 client 沒有任何要求，`RestClient`（底層是 `JdkClientHttpRequestFactory` 或 `HttpComponents`）一樣能打。

### ✅ 選 WireMock

1. **要 stub 大量 endpoint、或同一個 endpoint 需 request matching**
   ```java
   stubFor(get(urlPathEqualTo("/api/foo"))
       .withQueryParam("id", equalTo("1"))
       .withHeader("Authorization", matching("Bearer .*"))
       .willReturn(okJson("{\"x\":1}")));
   ```
   比 MockWebServer 的 `Dispatcher` 自寫 routing 乾淨非常多。
2. **需要狀態機（scenarios）**
   例如「第一次回 503、第二次回 200」這種 retry 測試，WireMock 用 `inScenario(...).whenScenarioStateIs(...)` 表達清楚。
   （MockWebServer 也能用 enqueue 兩個 response 達成，但「依呼叫順序」與「依狀態」語意不同，後者更貼近 retry / circuit breaker 測試。）
3. **要 record / replay 真實 API**
   WireMock 可開 proxy 模式錄製真實第三方回應成 JSON，之後脫機回放。
4. **跨服務整合測試**
   把 WireMock 跑成獨立進程或 Docker container，多個服務（或 Testcontainers 起的多容器）都打它，stub 設定可被所有測試共用。
5. **契約測試 / Spring Cloud Contract**
   Spring Cloud Contract 直接以 WireMock stub 作為消費者端 mock。
6. **需 admin API 動態變更 stub**
   `/__admin/mappings` 可在測試執行中由其它程序動態切換行為（chaos / fault injection 工具鏈友善）。

---

## 細節對照表

| 面向 | MockWebServer | WireMock |
|---|---|---|
| 啟動方式 | `MockWebServer().start()` | `WireMockServer(options).start()` / `@WireMockTest` / standalone jar / Docker |
| 啟動成本 | 極低（純 socket） | 低～中（內建 Jetty） |
| 回應定義 | `enqueue(MockResponse)`（FIFO） | `stubFor(...)`（依 request 條件 match） |
| Request matching | 需自寫 `Dispatcher` | 內建豐富 DSL（path、query、header、body、JSONPath、XPath、regex） |
| 狀態機 | 自己用 queue / counter 模擬 | 原生 `Scenarios` |
| Transport 層錯誤 | `SocketPolicy` 一級支援（timeout、disconnect、TLS 中斷） | 透過 `Fault.*`（CONNECTION_RESET_BY_PEER、EMPTY_RESPONSE、MALFORMED_RESPONSE_CHUNK、RANDOM_DATA_THEN_CLOSE） |
| 慢回應模擬 | `setHeadersDelay` / `setBodyDelay` | `withFixedDelay` / `withUniformRandomDelay` / `withLogNormalRandomDelay` |
| 請求驗證 | `takeRequest()` 取 `RecordedRequest` 後手動 assert | `verify(getRequestedFor(...))` DSL |
| Record / Replay | ❌ | ✅ |
| Proxy 模式 | ❌ | ✅ |
| 動態 admin API | ❌ | ✅（`/__admin`） |
| Standalone / Docker | ❌（純 in-JVM） | ✅ |
| HTTPS / mTLS | 支援（`useHttps`） | 支援 |
| 依賴體積 | 很小 | 較大（含 Jetty） |
| JUnit 5 整合 | 手動 `@BeforeEach` / `@AfterEach` | `@WireMockTest` extension |

---

## 對 ccErrorClient 的選型理由

ccErrorClient 的測試重點是「同一個 outbound 呼叫，依下游不同行為走進不同 exception 分支」：

- TOKEN 階段 timeout vs 下游 unreachable → 需要 **真實 connect / read timeout**
- 5xx → `HttpServerErrorException`
- 4xx → `HttpClientErrorException`
- 壞 JSON → `RestClientResponseException` 或 converter 拋例外
- connect refused → server `shutdown()` 後再呼叫

這些情境 **每個 test 都是「一次呼叫對一個回應」**，沒有複雜的 request matching、沒有 retry 狀態機、也不需要跨進程共用 stub，正好命中 MockWebServer 的甜蜜點：

- `SocketPolicy` 對 transport 層異常的支援最直接（WireMock 的 `Fault` 雖然能模擬，但語意不如 `NO_RESPONSE` 直觀）。
- FIFO `enqueue` 與「每個 test 描述一條情境」一一對應，可讀性高。
- 啟動成本低，可在每個 test 重啟，避免狀態汙染。

如果未來測試需求擴大到——

- 同一個下游有多個 endpoint 要 stub
- 要模擬 retry / circuit breaker 的狀態變化
- 想把 ccErrorClient 與 ccOauthServer / ccResourceServer 一起做整合測試而需要共用 mock

——再考慮升級到 WireMock 或補一層 WireMock-based 整合測試即可，兩者並不互斥。

---

## 參考

- MockWebServer: https://github.com/square/okhttp/tree/master/mockwebserver
- WireMock: https://wiremock.org/
- Spring `MockRestServiceServer`（純攔截、非本文範圍）: https://docs.spring.io/spring-framework/reference/testing/spring-mvc-test-client.html
