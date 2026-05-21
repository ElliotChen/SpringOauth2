# ccProductionClient

`client_credentials` 流程下，**production-grade 錯誤處理 / 韌性 / 可觀測性**的整合示範。

與系列另兩個模組對照：

| 模組 | 定位 |
|---|---|
| `ccResourceClient` | happy-path baseline |
| `ccErrorClient` | 教學版：把錯誤手動分成 4 stage / 21 code，凸顯失敗點 |
| **`ccProductionClient`** | **實務版：ProblemDetail + Resilience4j + 401-evict-retry + OpenTelemetry** |

> 三者都用 `:8080`，不要同時啟動。

---

## 涵蓋的實務做法

1. **RFC 7807 ProblemDetail + traceId**
   - 對外回應統一為 `application/problem+json`，欄位含 `type` / `title` / `status` / `detail` / `instance` / `traceId`。
   - traceId 由 **OpenTelemetry starter** 產生並寫入 MDC（key: `traceId`）；若 inbound request 帶 W3C `traceparent`，OTel 會沿用其中的 trace-id。
   - `TraceIdFilter` 已瘦身為只負責「把 MDC 內的 traceId 鏡像到 response header `X-Trace-Id`」，不再自行產生或讀 custom incoming header。MDC 為空時（例如 tracing 關掉）才生 fallback UUID。
   - log pattern 含 `%X{traceId}`，所有 log 自動帶 trace id；與 `traceparent` / `X-Trace-Id` / ProblemDetail body 全部一致。

2. **OpenTelemetry（Spring Boot 4 first-party starter）**
   - 引入 `org.springframework.boot:spring-boot-starter-opentelemetry`。
   - instrumentation 永遠開著（MDC 注入、`RestClient` outbound 自動加 `traceparent`）；OTLP **export 預設關閉**，本機 dev / test 不需要 collector 也不會出 warning。
   - 要開啟 export：把 `management.opentelemetry.tracing.export.otlp.enabled` / `management.otlp.metrics.export.enabled` 設 `true` 並補 endpoint（範例見 application.yaml 註解）。

3. **Resilience4j：retry + circuit breaker（programmatic API）**
   - `ResilienceConfig` 註冊單一 `resourceServer` instance：
     - Retry：`maxAttempts=3`、`waitDuration=200ms`、**只重試 transport / 5xx，不重試 4xx**。
     - CircuitBreaker：`slidingWindow=10`、`minimumCalls=5`、`failureRateThreshold=50%`、open 後 `10s` 才轉 half-open。
   - 透過 `TaggedRetryMetrics` / `TaggedCircuitBreakerMetrics` 自動 bind 到 Micrometer。
   - Token endpoint **刻意不包**——拿不到 token 表示整個依賴鏈斷掉，retry 只會放大延遲並掩蓋 `invalid_client` 等永久錯誤。

4. **401 evict-and-retry-once**
   - 下游回 401 時（cached token 仍未過期但被 resource server 拒絕，例如金鑰輪替）：service 層 evict `OAuth2AuthorizedClient` 重打一次。
   - 重試只做一次，第二次 401 直接視為失敗 → `502 upstream-unauthorized`。

5. **Micrometer + Actuator**
   - `spring-boot-starter-actuator` 暴露 `/actuator/metrics`、`/actuator/health`。
   - Spring Boot 自動為 `RestClient` 產生 `http.client.requests` metric；Resilience4j 註冊 `resilience4j.*` metric；OTel starter 註冊 tracing 相關 metric。

---

## 套件結構

```
tw.elliot.productionclient
├── ProductionClientApplication
├── api/UpdateParameterController              POST /client/UpdateParameter
├── service/UpdateParameterService             Retry+CB 包外、401-evict 包更外
├── config/
│   ├── SecurityConfig                         permitAll
│   ├── RestClientConfig                       2s connect/read timeout, OAuth2 interceptor
│   └── ResilienceConfig                       CircuitBreakerRegistry + RetryRegistry
├── observability/TraceIdFilter                MDC → X-Trace-Id response header 鏡像（OTel starter 負責產生 traceId）
└── error/ProblemDetailExceptionHandler        所有例外 → RFC 7807
```

---

## 啟動

```bash
cd clientCredentials/server/ccOauthServer    && ./mvnw spring-boot:run   # :9000
cd clientCredentials/server/ccResourceServer && ./mvnw spring-boot:run   # :8081
cd clientCredentials/client/ccProductionClient && ./mvnw spring-boot:run # :8080
```

### Smoke test

```bash
# Happy path — response 必含 X-Trace-Id（值為 OTel 產生的 trace-id）
curl -i -X POST 'http://localhost:8080/client/UpdateParameter' \
  --data 'paramType=01&version=v1'

# 帶 W3C traceparent 進來 → server 沿用其中的 trace-id；X-Trace-Id 會反映同一個值
curl -i -H 'traceparent: 00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01' \
  -X POST 'http://localhost:8080/client/UpdateParameter' \
  --data 'paramType=01&version=v1'

# 觀察 Resilience4j / OTel metric
curl 'http://localhost:8080/actuator/metrics' | jq '.names | map(select(startswith("resilience4j") or startswith("http.")))'
```

> 註：舊版曾支援 incoming `X-Trace-Id` header 沿用，已隨 OTel starter 導入而移除——
> 跨服務 trace propagation 統一走 W3C `traceparent`。`X-Trace-Id` 仍保留為 **outbound** response header，
> 方便不講 W3C 的腳本 / support 工具讀取。

---

## 測試

兩個 IT 類別，共 13 個情境 + 1 個 context load unit test。

### `UpdateParameterProductionFlowIT`（MockMvc，12 個情境）

| 情境 | 驗證重點 |
|---|---|
| happy path | 200 + `X-Trace-Id` 存在 |
| missing param | `application/problem+json`、type/title/status/instance/traceId |
| resource 5xx 短暫故障 | retry 後成功（驗證 2 次呼叫） |
| resource 5xx 持續故障 | retry 用盡（驗證 3 次呼叫）→ `502 upstream-server-error` |
| resource 401（先壞後好） | evict token + 重試一次成功；token endpoint 被打 2 次 |
| resource 401（持續） | evict 重試也失敗 → `502 upstream-unauthorized` |
| circuit breaker open | 累積失敗後 CB 進 OPEN；下次呼叫直接 short-circuit，**WireMock 完全沒收到請求** → `503 upstream-circuit-open` |
| **resource read timeout** | `withFixedDelay > 2s` → `ResourceAccessException` → retry 3 次 → `504 upstream-unreachable` |
| **resource connect refused** | WireMock stop 後重打 → `ConnectException` → retry → `504 upstream-unreachable` |
| **token endpoint timeout** | token 路徑刻意不包 Resilience4j，**只被打 1 次** → `504 token-unavailable` |
| **token invalid_client** | auth server 回 `400 {"error":"invalid_client"}` → `502 token-rejected`，不重試 |
| actuator metrics | `/actuator/metrics` 回 200 且 body 含 `resilience4j` |

### `W3CTraceparentPropagationIT`（`RANDOM_PORT` + JDK HttpClient，1 個情境）

| 情境 | 驗證重點 |
|---|---|
| incoming `traceparent` | OTel 沿用其中的 trace-id；response 的 `X-Trace-Id` 與 ProblemDetail body 的 `traceId` 都等於同一個 trace-id |

**為什麼用 `RANDOM_PORT` 而非 MockMvc**：OTel servlet observation filter 註冊在 servlet container 上，MockMvc 的 filter chain 預設不會掛它——用真實 embedded server 才能可信地驗 propagation。

```bash
./mvnw verify          # 含 IT（Failsafe 自動掛 *IT）
./mvnw test            # 僅 unit（context load test）
```

---

## 與 ccErrorClient 的關鍵差異

| 面向 | ccErrorClient | ccProductionClient |
|---|---|---|
| 錯誤分類 | 自訂 `ErrorStage` + 21 `ErrorCode` | Spring 原生例外 + ProblemDetail `type` URI |
| 回應格式 | 自訂 `ErrorResponse` JSON | RFC 7807 `application/problem+json` |
| TraceId | 無 | OpenTelemetry（W3C `traceparent`）+ MDC + `X-Trace-Id` response header + ProblemDetail body |
| Retry | 無 | Resilience4j（transport / 5xx，3 次） |
| Circuit breaker | 無 | Resilience4j（10 滑動窗、50% 失敗率） |
| 401 處理 | 直接回 `RESOURCE_UNAUTHORIZED` | evict cached token + 重試一次 |
| Metric / Tracing | 無 | Micrometer（含 Resilience4j 自動 bind）+ OTel SDK |

**何時用哪一個範本：**

- 想理解「哪些錯誤該被歸到哪一段」→ 讀 ccErrorClient。
- 想理解「production 上實際該怎麼包」→ 讀 ccProductionClient。

兩者並不互斥：實務上的 application 通常會結合「ccProductionClient 的韌性與 envelope」+「ccErrorClient 的細部分類」，差別只在你想對外公開多細的錯誤語意。

---

## 進一步閱讀

- [docs/01_resilience4j-intro.md](docs/01_resilience4j-intro.md) — Resilience4j 在本模組的應用（狀態機、設定理由、與 Retry 的組合順序、Micrometer 整合、測試覆蓋對照）

---

## 進一步可加強

本模組刻意省略以下項目以保持示範清晰；真正 production 系統通常還會加：

- **啟用 OTLP export 到 collector**：本機跑 Jaeger / Tempo / OTel collector，把 `management.opentelemetry.tracing.export.otlp.enabled` 翻成 `true`、補上 endpoint
- **Bulkhead / RateLimiter**（也是 Resilience4j 模組），限制併發與呼叫頻率
- **Idempotency key**：POST 重試前提是 server 端能去重
- **Spring Cloud Contract** 或消費者驅動契約測試
- **Vault / KMS** 管 client secret，application.yaml 不再 hardcode
