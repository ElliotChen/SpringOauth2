# ccErrorClient 錯誤處理設計

- 日期：2026-05-21
- 模組：`clientCredentials/client/ccErrorClient`
- 目的：在 OAuth2 `client_credentials` + Resource Server 串接情境下，**列舉可能遇到的所有錯誤**、**示範如何用 unit test 觸發每一種**、並**用統一 schema 把它們翻譯成乾淨的 API 回應**。

## 1. 範圍

ccErrorClient 既有架構：

```
caller → POST /client/UpdateParameter
       → OAuth2ClientHttpRequestInterceptor
           ├─ (若 token 缺/過期) → ccOauthServer  /oauth2/token
           └─ 將 access_token 加上 Authorization header
       → RestClient → ccResourceServer /sEQI/Param/UpdateParameter
       → 將 ccResourceServer JSON 回傳 caller
```

本設計**只新增錯誤分類與處理**，不變更上述主流程。業務語意錯誤（resource server 回 200 但 body `code: -1`）**不視為例外**，由 caller 自行解讀 body。

## 2. 模組結構

```
ccErrorClient/src/main/java/tw/elliot/errorclient/
├── api/UpdateParameterClientController.java         # 既有，不改
├── config/                                          # 既有，不改
└── error/
    ├── ErrorStage.java                              # enum: INPUT, TOKEN, RESOURCE, UNKNOWN
    ├── ErrorCode.java                               # enum: code 字串 + stage + httpStatus + 預設訊息
    ├── ErrorResponse.java                           # record: 對外 JSON schema
    ├── ClientFlowException.java                    # 自訂 RuntimeException，封裝 ErrorCode + detail
    └── GlobalExceptionHandler.java                  # @RestControllerAdvice
```

### 職責邊界

- **`ErrorCode`**：所有錯誤碼的 single source of truth。任何要新增/修改錯誤分類都改這裡，handler 與測試自然跟著。
- **`GlobalExceptionHandler`**：只做「Java exception → ErrorCode」的對應；對 `ClientAuthorizationException` 這種「同型別、不同子原因」的情況，handler 內部依 `getError().getErrorCode()` 字串或 cause 型別再細分。
- **`ClientFlowException`**：給未來在 service / controller 內主動丟可分類錯誤使用；本期不在 controller 內使用（業務碼不處理）。先建立但僅保留 minimal 介面。
- **Controller**：保持原樣，不負責錯誤翻譯。

## 3. 回應 schema

```json
{
  "code": "TOKEN_INVALID_CLIENT",
  "stage": "TOKEN",
  "message": "取得 token 失敗：invalid_client",
  "httpStatus": 502,
  "path": "/client/UpdateParameter",
  "timestamp": "2026-05-21T10:00:00Z"
}
```

- `code` / `stage` / `httpStatus`：來自 `ErrorCode` enum。
- `message`：handler 在對應時，可附加從原始 exception 取得的 detail（例如 OAuth `errorDescription`、上游 status code、I/O 訊息）。
- `path`：從 `HttpServletRequest.getRequestURI()` 取得。
- `timestamp`：`Instant.now()`，ISO-8601 UTC。

## 4. 錯誤分類表

| Stage | Java Exception | 條件 / 子判斷 | ErrorCode | HTTP out |
|---|---|---|---|---|
| —（透傳）| `ClientFlowException` | handler 直接取 `cfe.errorCode()` 回傳 | （載運的任意 ErrorCode） | （依載運） |
| INPUT | `MissingServletRequestParameterException` | — | `INPUT_MISSING_PARAMETER` | 400 |
| INPUT | `MethodArgumentTypeMismatchException` | — | `INPUT_PARAMETER_TYPE_MISMATCH` | 400 |
| INPUT | `HttpRequestMethodNotSupportedException` | — | `INPUT_METHOD_NOT_ALLOWED` | 405 |
| INPUT | `HttpMediaTypeNotAcceptableException` | — | `INPUT_MEDIA_TYPE_NOT_ACCEPTABLE` | 406 |
| TOKEN | `ClientAuthorizationException` | `getError().getErrorCode() == "invalid_client"` | `TOKEN_INVALID_CLIENT` | 502 |
| TOKEN | `ClientAuthorizationException` | `"invalid_scope"` | `TOKEN_INVALID_SCOPE` | 502 |
| TOKEN | `ClientAuthorizationException` | `"unauthorized_client"` | `TOKEN_UNAUTHORIZED_CLIENT` | 502 |
| TOKEN | `ClientAuthorizationException` | root cause 為 `ConnectException` | `TOKEN_ENDPOINT_UNREACHABLE` | 504 |
| TOKEN | `ClientAuthorizationException` | root cause 為 `SocketTimeoutException` / `java.net.http.HttpTimeoutException` | `TOKEN_ENDPOINT_TIMEOUT` | 504 |
| TOKEN | `ClientAuthorizationException` | 其他（含 5xx、未分類 OAuth error code） | `TOKEN_SERVER_ERROR` | 502 |
| TOKEN | `IllegalArgumentException` | `message` 含 `"ClientRegistration"`（Spring Security 7 已移除 `ClientRegistrationException`） | `TOKEN_REGISTRATION_NOT_FOUND` | 500 |
| RESOURCE | `HttpClientErrorException.Unauthorized` (401) | — | `RESOURCE_UNAUTHORIZED` | 502 |
| RESOURCE | `HttpClientErrorException.Forbidden` (403) | — | `RESOURCE_FORBIDDEN` | 502 |
| RESOURCE | `HttpClientErrorException.BadRequest` (400) | — | `RESOURCE_BAD_REQUEST` | 502 |
| RESOURCE | `HttpClientErrorException.NotFound` (404) | — | `RESOURCE_NOT_FOUND` | 502 |
| RESOURCE | 其他 `HttpClientErrorException` (4xx) | — | `RESOURCE_CLIENT_ERROR` | 502 |
| RESOURCE | `HttpServerErrorException` (5xx) | — | `RESOURCE_SERVER_ERROR` | 502 |
| RESOURCE | `ResourceAccessException` | root cause 為 `ConnectException` | `RESOURCE_UNREACHABLE` | 504 |
| RESOURCE | `ResourceAccessException` | root cause 為 `SocketTimeoutException` / `java.net.http.HttpTimeoutException` | `RESOURCE_TIMEOUT` | 504 |
| RESOURCE | `UnknownContentTypeException` / `RestClientResponseException` / `HttpMessageNotReadableException`（非 INPUT 來源） | — | `RESOURCE_MALFORMED_RESPONSE` | 502 |
| UNKNOWN | `Exception`（fallback） | — | `INTERNAL_UNEXPECTED` | 500 |

### 設計取捨

1. **Token / Resource 階段一律對外回 502 / 504**（不透傳上游 401/403/400）。
   理由：caller 並未直接跟 ccOauthServer / ccResourceServer 互動，上游 status 對它無語意。`stage` + `code` 仍可區分根因。
2. **`ClientAuthorizationException` 細分依賴字串比對**：`invalid_client` / `invalid_scope` / `unauthorized_client` 為 OAuth2 (RFC 6749) 規範定義，穩定可比對。
3. **`HttpMessageNotReadableException` 一律歸 RESOURCE**：controller 使用 `@RequestParam`（非 `@RequestBody`），因此 INPUT 層不會產生此例外。若未來 controller 增加 `@RequestBody` 入口需重新評估。
4. **TOKEN 階段區分 UNREACHABLE vs TIMEOUT**：和 RESOURCE 階段對稱。on-call 透過 `code` 即可分辨「AS 沒起」vs「AS 慢」。
5. **JDK HTTP client 的 timeout 例外型別**：實際使用 `JdkClientHttpRequestFactory` 時，read timeout 會丟 `java.net.http.HttpTimeoutException`（不是 `SocketTimeoutException`）。handler 對兩者都接受，以容納未來換 request factory 的可能性。
6. **`ClientRegistrationException` 替代方案**：Spring Security 7 已移除此型別，改用 `IllegalArgumentException` + message 子字串 `"ClientRegistration"` 比對。比對來源是 Spring Security 內部 `Assert.notNull("Could not find ClientRegistration with id ...")`。脆弱但目前是唯一可行的對應。

## 5. 測試策略

雙層 unit test，分工互補：

### Layer 1 — Handler 對應表測試

- **檔案**：`tw.elliot.errorclient.error.GlobalExceptionHandlerTest`
- **形式**：純 JUnit，不啟動 Spring context。
- **做法**：`@ParameterizedTest` + `MethodSource`，造出第 4 節表格每一列對應的 exception 實例，呼叫 handler，斷言回傳的 `ErrorResponse` 的 `code / stage / httpStatus`。
- **覆蓋**：表格全部分支（INPUT 4 + TOKEN 8 + RESOURCE 10 + UNKNOWN 1 + `ClientFlowException` 1 = 24 case）。
- **價值**：每新增一個 `ErrorCode` 必加一行測試；對應表的「規格」即此測試。

### Layer 2 — 端到端串接測試

- **檔案**：`tw.elliot.errorclient.UpdateParameterClientFlowTest`
- **形式**：`@SpringBootTest(webEnvironment = MOCK)` + `MockMvc` + 兩個 `okhttp3.mockwebserver.MockWebServer`（一個假裝 ccOauthServer、一個假裝 ccResourceServer）。
- **連線**：`@DynamicPropertySource` 將下列 property 指向 MockWebServer 隨機 port：
  - `spring.security.oauth2.client.provider.resource-server.token-uri`
  - `app.resource-server.base-url`
- **覆蓋場景**（最少集合，證明 wiring 正確）：

  | 場景 | AS mock 回應 | RS mock 回應 | 期望輸出 |
  |---|---|---|---|
  | happy path | 200 + access_token | 200 + `{"code":0,...}` | 200，body 透傳 |
  | INPUT 缺參數 | — | — | 400 `INPUT_MISSING_PARAMETER` |
  | TOKEN invalid_client | **400** `{"error":"invalid_client"}`（Spring Security 的 `OAuth2ErrorResponseErrorHandler` 只在 HTTP 400 解析 OAuth2 error body；RFC 6749 §5.2 允許 400） | — | 502 `TOKEN_INVALID_CLIENT` |
  | TOKEN timeout | `SocketPolicy.NO_RESPONSE` | — | 504 `TOKEN_ENDPOINT_TIMEOUT` |
  | RESOURCE 401 | 200 + token | 401 | 502 `RESOURCE_UNAUTHORIZED` |
  | RESOURCE timeout | 200 + token | `SocketPolicy.NO_RESPONSE` | 504 `RESOURCE_TIMEOUT` |

### 工具依賴

- 新增 test scope dependency：`com.squareup.okhttp3:mockwebserver`。
- Layer 2 為避免 timeout 場景測試過慢，需在 `RestClient` builder 設定較短的 connect / read timeout（或在測試 profile 中覆寫）。建議於 `RestClientConfig` 加上 timeout 設定（read timeout ~2s、connect timeout ~2s），既符合正式環境合理值，也讓測試可行。

## 6. 工作項目（給後續 plan）

1. 新增 `error/` 套件下五個檔案（`ErrorStage`、`ErrorCode`、`ErrorResponse`、`ClientFlowException`、`GlobalExceptionHandler`）。
2. `RestClientConfig`：
   - connect timeout 透過 `java.net.http.HttpClient.newBuilder().connectTimeout(...)` 設定後傳入 `JdkClientHttpRequestFactory`（`JdkClientHttpRequestFactory` 本身沒有 connect timeout setter）；read timeout 用 `setReadTimeout`。
   - 為 token RestClient 顯式註冊 `OAuth2AccessTokenResponseHttpMessageConverter` 與 `OAuth2ErrorResponseErrorHandler`（用 `configureMessageConverters(...).disableDefaults().addCustomConverter(...)` + `defaultStatusHandler(...)`）；少了這兩者，bare RestClient 會丟 `accessToken cannot be null` 或無法解析 OAuth2 error body。
3. `pom.xml` 增加 `mockwebserver` test dependency。**Spring Boot 4.0.6 BOM 未管理此 artifact，需顯式指定 `<version>4.12.0</version>`。**
4. 撰寫 `GlobalExceptionHandlerTest`（Layer 1，24 case）。
5. 撰寫 `UpdateParameterClientFlowTest`（Layer 2，6 case）。

## 7. 非目標

- 不做整合測試（不真的啟動 ccOauthServer / ccResourceServer）。
- 不導入 distributed tracing / `traceId`。
- 不處理業務層 `code: -1`；交由 caller。
- 不對 pkce demo 做任何變更。
