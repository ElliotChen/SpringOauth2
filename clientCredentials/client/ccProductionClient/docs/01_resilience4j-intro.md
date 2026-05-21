# Resilience4j 在 ccProductionClient 的應用

> 範圍：聚焦本模組實際用到的 `Retry` + `CircuitBreaker` 兩個元件。
> 其他 module（`Bulkhead`、`RateLimiter`、`TimeLimiter`、`Cache`）會點到為止，方便日後擴展時銜接。

---

## 1. Resilience4j 是什麼

Resilience4j 是 Java 的**輕量級韌性函式庫**（Netflix Hystrix 停止維護後的事實標準），主要特色：

- **以 Functional Interface 為核心**：所有元件都把目標包成 `Supplier` / `Function` / `Runnable`，可以自由組合，不必綁特定框架。
- **零相依**：core 只依賴 `vavr-collections`，沒有反射、沒有 AOP、沒有強制 Spring。
- **可選的整合層**：另有 `resilience4j-spring-boot3`（annotation 驅動）、`resilience4j-micrometer`（metric）、`resilience4j-reactor`（Mono/Flux）等模組。

對應 Hystrix 概念：

| Hystrix | Resilience4j |
|---|---|
| `@HystrixCommand` | `@Retry` / `@CircuitBreaker` / `@Bulkhead` / `@RateLimiter` 多個獨立 annotation |
| Command pattern + 內建 thread pool | 純函式包裝，預設不啟新 thread（要的話另外用 `Bulkhead` 或 `CompletableFuture`） |

---

## 2. 本模組用到的兩個元件

```
[外部呼叫進來]
   ↓
Controller
   ↓
UpdateParameterService
   ↓ (401-evict-and-retry-once 包外)
   ↓
[ Retry ]                    ← Resilience4j 重試 transport / 5xx，最多 3 次
   ↓
[ CircuitBreaker ]           ← Resilience4j 累計失敗率 → OPEN 後 fail-fast
   ↓
RestClient (2s connect/read timeout)
   ↓
ccResourceServer
```

**Token endpoint 路徑刻意不包**——理由：拿不到 token 表示整段依賴都已不可用，retry 只會放大延遲、掩蓋 `invalid_client` 之類永久錯誤。

---

## 3. CircuitBreaker（熔斷器）

### 狀態機

```
        失敗率 ≥ threshold
CLOSED ───────────────────► OPEN
   ▲                          │
   │ 半開測試成功               │ 等 wait-duration
   │                          ▼
   └──────────────────── HALF_OPEN
        permittedCalls 內測試
```

| 狀態 | 行為 |
|---|---|
| `CLOSED` | 放行所有請求，統計成功/失敗率 |
| `OPEN` | **直接拒絕**所有請求（拋 `CallNotPermittedException`），完全不打下游 |
| `HALF_OPEN` | 放行少量「探測」請求；若成功率達標 → CLOSED；否則 → OPEN |

額外狀態：`DISABLED`（永遠 CLOSED）、`FORCED_OPEN`（永遠 OPEN）、`METRICS_ONLY`（只計算不開斷）。

### 本模組設定（`ResilienceConfig`）

```java
CircuitBreakerConfig.custom()
    .slidingWindowSize(10)              // 看最近 10 次呼叫
    .minimumNumberOfCalls(5)            // 至少 5 次才開始計算
    .failureRateThreshold(50f)          // 失敗率 ≥ 50% → OPEN
    .waitDurationInOpenState(Duration.ofSeconds(10))
    .permittedNumberOfCallsInHalfOpenState(2)
    .recordExceptions(
        ResourceAccessException.class,   // transport 失敗
        HttpServerErrorException.class,  // 5xx
        ConnectException.class,
        HttpTimeoutException.class)
    .build();
```

**為什麼這些參數？**

- `slidingWindowSize=10`、`minimumCalls=5`：避免少量呼叫就因偶發失敗開斷（噪音抑制）。
- `failureRateThreshold=50%`：對外服務 1/2 以上失敗已可視為「服務中」而非「偶發」。
- `waitDurationInOpenState=10s`：給下游一段恢復時間；太短會 thrashing，太長使用者體感差。
- `permittedNumberOfCallsInHalfOpenState=2`：半開時不能一次衝太多請求進去再壓爆它。

### 哪些例外算「失敗」

`recordExceptions` 是白名單——**只有列出的例外**會計入失敗率。
4xx（`HttpClientErrorException`）**不在**清單裡——它們是 client 的鍋（送錯參數），下游本身仍然健康，不應該因此熔斷。

對應反例：`ignoreExceptions` 可以用來排除某些例外（例如業務驗證錯誤）。

### 在 ProblemDetailExceptionHandler 對接

當 CB OPEN 時，Resilience4j 丟 `CallNotPermittedException`。我們捕捉它並回傳：

```java
@ExceptionHandler(CallNotPermittedException.class)
public ResponseEntity<ProblemDetail> onCircuitOpen(...) {
    // 503 + type=upstream-circuit-open
}
```

`503 Service Unavailable` 是合適的——它告訴上游 caller「現在不可用，請稍候再試」，並可搭 `Retry-After` header（本範例還沒加）。

---

## 4. Retry（重試）

### 本模組設定

```java
RetryConfig.custom()
    .maxAttempts(3)                     // 總嘗試次數（含首發）
    .waitDuration(Duration.ofMillis(200))
    .retryExceptions(
        ResourceAccessException.class,
        HttpServerErrorException.class,
        ConnectException.class,
        HttpTimeoutException.class)
    .build();
```

**重要區別**：
- `maxAttempts=3` 表示**總共最多 3 次**（首次 + 重試 2 次），不是「重試 3 次」。
- `waitDuration` 是兩次嘗試之間的固定間隔。要 backoff 改用 `IntervalFunction.ofExponentialBackoff(...)`。

### 重試的紅線

**只重試「無狀態 / 冪等」的失敗**：

| 情境 | 該重試嗎？ | 理由 |
|---|---|---|
| `ConnectException`（連不上） | ✅ | 連線都沒建立，下游一定沒收到請求，安全 |
| Read timeout | ⚠️ 視操作而定 | 下游可能**已經**處理了——POST 重試會導致重複建立資源 |
| 5xx | ⚠️ 視操作而定 | 同上 |
| 4xx | ❌ | 重試會得到一樣的答案，純浪費 |
| `invalid_client` / 認證失敗 | ❌ | 結構性錯誤，重試 100 次也不會好 |

本範例為了示範把 transport / 5xx 都納入 retry，但**真正 production 上 POST 重試前提是接 idempotency-key**（server 端能去重）。GET 通常安全。

### 與 CircuitBreaker 的組合順序

```java
Supplier<Map<String, Object>> cbWrapped =
        CircuitBreaker.decorateSupplier(circuitBreaker, () -> invoke(body));
Supplier<Map<String, Object>> resilientCall =
        Retry.decorateSupplier(retry, cbWrapped);
```

**Retry 包外、CB 包內**——意思是：每次重試嘗試都會經過 CB。若中途 CB 開啟，剩下的重試會立刻被 fast-fail，不會傻傻打到底。

反過來「CB 包外、Retry 包內」也有人這樣寫，意思是「CB 看的是已經重試完的結果」——通常不是想要的行為，因為重試本身的雜訊會被 CB 隱藏，CB 反而不敏感。

---

## 5. 為什麼不用 Spring Boot 內建 / Spring Retry

常見的疑問：「Spring Boot 不是已經有 HTTP timeout 與 retry 嗎，為什麼還要 Resilience4j？」
答案要把 **timeout** 與 **retry / circuit breaker** 分開講。

### 5.1 Timeout — 我們**就是**在用 Spring Boot 的機制

Spring Boot 對 HTTP timeout 的「支援」其實就是把 `ClientHttpRequestFactory` 上的 connect/read timeout 包成 properties：

```yaml
spring:
  http:
    client:
      connect-timeout: 2s
      read-timeout: 2s
```

底層就是 `factory.setConnectTimeout(...)` / `factory.setReadTimeout(...)`，本模組 `RestClientConfig` 直接用程式碼設定，**機制完全一樣**。
之所以沒用 properties 寫法，是因為本模組有兩個獨立 RestClient（token / resource）共用一份 timeout，且 builder 需要客製，沒吃 Boot 自動裝配的那個 builder。要外部化也可以，把 constant 改成 `@Value("${app.timeout.*}")` 即可，與選不選 Resilience4j 無關。

### 5.2 Retry / CB — 「Spring Boot 內建」其實是 Spring Retry，而它**不夠**

Spring Boot **本身沒有 retry 框架**。能補上這塊的是 [Spring Retry](https://github.com/spring-projects/spring-retry)（Spring 維護的獨立 library，不在 Boot core）。

| 面向 | Spring Retry | Resilience4j |
|---|---|---|
| 重試 | ✅ `@Retryable` / `RetryTemplate` | ✅ `Retry` |
| Backoff DSL | ✅ 簡單（fixed / exponential） | ✅ 同等 + `IntervalFunction` 可自訂 |
| **Circuit breaker** | ⚠️ 有 `@CircuitBreaker`，但**極度原始**：只是「一段時間內失敗 N 次後跳過，過了 reset 時間再試」 | ✅ 完整狀態機（CLOSED / OPEN / HALF\_OPEN / DISABLED / FORCED\_OPEN）、滑動窗、失敗率、可調 half-open 探測數 |
| 滑動窗 / 失敗率 | ❌ | ✅ `slidingWindowSize` + `failureRateThreshold` |
| `recordExceptions` 白名單 | ❌（只能 `retryFor` 控制重試清單，無 CB 對應） | ✅ CB 可獨立指定哪些例外計入失敗率 |
| Bulkhead（併發限制） | ❌ | ✅ |
| RateLimiter | ❌ | ✅ |
| TimeLimiter（端到端 deadline） | ❌ | ✅ |
| Metric 細度 | ⚠️ 基本 | ✅ 細到 successful_without_retry / successful_with_retry / failed_with_retry / failed_without_retry / not_permitted 等分類 |
| API 風格 | AOP / annotation 為主 | 純函式（`Supplier`）組合，可程式化決定 |
| 對 Spring Boot 4 的相容性 | 仰賴 Spring AOP autoconfig，與 Boot 大版本耦合 | core jar 零相依，programmatic 用法無 AOP |

**真正的差距在 circuit breaker**。Spring Retry 的 `@CircuitBreaker` 不是業界認知的熔斷器——它沒有：

- 滑動窗 / 失敗率計算（Resilience4j：`slidingWindow=10`、`failureRateThreshold=50%`）
- HALF\_OPEN 狀態與 `permittedNumberOfCallsInHalfOpenState`
- 失敗率 metric / state transition event

本模組 IT `circuitOpens_afterSustainedFailures_shortCircuitsNextCall` 驗證的行為——「累積失敗 → CB OPEN → 下次呼叫 WireMock **完全沒收到** → `503`」——**Spring Retry 做不到**，它只會在 retry 用盡後丟例外，下一個 fresh request 仍然會打到下游。

### 5.3 選型結論

| 需求 | 選什麼 | 理由 |
|---|---|---|
| Connect / read timeout | **Spring Boot HTTP client（透過 factory 或 `spring.http.client.*`）** | 標準機制，我們已經這樣做 |
| 只要 retry，不需要真 CB | Spring Retry | 較輕量，`@Retryable` 一個 annotation 解決 |
| **要真正的 circuit breaker** | **Resilience4j（沒有對等選擇）** | Spring Retry 的 CB 太陽春 |
| Bulkhead / RateLimiter / TimeLimiter | Resilience4j | Spring 生態沒有對等元件 |
| 想避免 AOP / annotation 對 Boot 大版本綁定 | Resilience4j programmatic API | core jar 零相依 |

ccProductionClient 的核心示範之一就是「真正的 circuit breaker 行為」，因此用 Resilience4j。
若改用 Spring Retry，必須再混入第二個 library 才能做 CB，混合使用反而更亂——同模組內用一套統一資源最直觀。

---

## 6. 為什麼用 programmatic API 而非 `@Retry` annotation

Resilience4j 有 `resilience4j-spring-boot3` autoconfig 模組，提供 annotation 驅動：

```java
@Retry(name="resourceServer", fallbackMethod="fallback")
@CircuitBreaker(name="resourceServer")
public Map<String, Object> call() { ... }
```

本模組**故意不用**，理由：

- **Spring Boot 4 相容性未驗證**：annotation 模組依賴 Spring AOP autoconfig，新 Boot 大版本初期常見 incompatibility。programmatic API 只依賴 core jar，跨版本最穩。
- **行為更明確**：`Retry.decorateSupplier(retry, ...)` 直接看出包裝順序；annotation 的順序依賴 AOP advice precedence，不直觀。
- **方便動態組合**：實務上常需要在同一個方法針對不同分支套不同 policy，annotation 做不到。

代價：annotation 一行解決的事，programmatic 要寫 3–5 行。本範例 1 個 service 1 個 call，這代價可接受。

---

## 7. 觀測性（Micrometer 整合）

```java
TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
```

註冊後可在 `/actuator/metrics` 看到：

| Metric | 意義 |
|---|---|
| `resilience4j.circuitbreaker.calls{kind="successful"}` | 成功呼叫數 |
| `resilience4j.circuitbreaker.calls{kind="failed"}` | 失敗呼叫數 |
| `resilience4j.circuitbreaker.calls{kind="not_permitted"}` | OPEN 期間被擋掉的呼叫數 |
| `resilience4j.circuitbreaker.state` | 當前狀態（gauge） |
| `resilience4j.circuitbreaker.failure.rate` | 當前失敗率 |
| `resilience4j.retry.calls{kind="successful_without_retry"}` | 首發即成功 |
| `resilience4j.retry.calls{kind="successful_with_retry"}` | 重試後成功 |
| `resilience4j.retry.calls{kind="failed_with_retry"}` | 重試後仍失敗 |
| `resilience4j.retry.calls{kind="failed_without_retry"}` | 不可重試直接失敗 |

production 上建議在 Grafana 拉這幾條 dashboard，並針對「CB OPEN > 5 分鐘」「failure.rate > 30% 持續 1 分鐘」設 alert。

---

## 8. 本模組測試對 Resilience4j 行為的覆蓋

`UpdateParameterProductionFlowIT` 已涵蓋：

- **Retry 觸發後成功**：`resource5xx_isRetriedAndEventuallySucceeds`（驗 2 次呼叫）
- **Retry 用盡**：`resourcePersistent5xx_exhaustsRetriesAndReturnsBadGateway`（驗 3 次呼叫）
- **Retry 不重試 4xx**：`resourceRepeated401_returnsBadGatewayAfterSecondAttempt`（401 不會被 retry）
- **CB OPEN 後 fast-fail**：`circuitOpens_afterSustainedFailures_shortCircuitsNextCall`（驗 WireMock **沒**收到請求）
- **Transport timeout 觸發 retry**：`resourceReadTimeout_isRetriedThenReturnsGatewayTimeout`
- **Connect refused 觸發 retry**：`resourceConnectRefused_isRetriedThenReturnsGatewayTimeout`
- **Token 路徑刻意不 retry**：`tokenEndpointTimeout_returnsGatewayTimeoutAndDoesNotRetry`（驗 token endpoint **只**被打 1 次）

---

## 9. 進一步可加

| 元件 | 用途 | 何時需要 |
|---|---|---|
| `Bulkhead` | 限制併發呼叫數（semaphore 或 thread pool 兩種） | 當一個慢下游可能耗盡 worker thread |
| `RateLimiter` | 限制單位時間內的呼叫數 | 對方 API 有 quota / RPS 限制 |
| `TimeLimiter` | 整體 deadline（含 retry 的時間） | 需要「整個流程不能超過 X 秒」的端到端保證 |
| `Cache` | 結果快取（pluggable，例如接 Caffeine） | 同一參數短時間內被打多次 |

最常一起出現的是 `Bulkhead + Retry + CircuitBreaker`，包裝順序：

```
TimeLimiter > Retry > CircuitBreaker > Bulkhead > Actual call
```

Retry 在 CB 外、CB 在 Bulkhead 外、TimeLimiter 在最外層管整體時間。

---

## 參考

- 官網：https://resilience4j.readme.io/
- GitHub：https://github.com/resilience4j/resilience4j
- 本模組設定：`config/ResilienceConfig.java`
- 對接點：`service/UpdateParameterService.java`、`error/ProblemDetailExceptionHandler#onCircuitOpen`
