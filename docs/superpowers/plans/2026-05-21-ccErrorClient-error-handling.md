# ccErrorClient Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add stage-aware error classification and a unified JSON error response to `ccErrorClient`, with exhaustive handler-mapping unit tests plus MockWebServer-driven end-to-end wiring tests.

**Architecture:** A new `error/` package introduces an `ErrorCode` enum as the single source of truth (code + stage + HTTP status + default message), an `ErrorResponse` record as the on-wire schema, and a `@RestControllerAdvice` that maps Java exceptions thrown anywhere along the `controller → OAuth2 interceptor → ccOauthServer → RestClient → ccResourceServer` path. Tests are two layers: pure JUnit handler tests cover every entry of the mapping table; `@SpringBootTest` with MockMvc + two `okhttp3.mockwebserver.MockWebServer` instances cover representative end-to-end flows.

**Tech Stack:** Java 25, Spring Boot 4.0.6, Spring Security OAuth2 Client 7.x, JUnit 5, `okhttp3:mockwebserver` (version managed by Spring Boot BOM).

**Spec:** `docs/superpowers/specs/2026-05-21-ccErrorClient-error-handling-design.md`

---

## File Map

```
clientCredentials/client/ccErrorClient/
├── pom.xml                                                            # MODIFY: add mockwebserver test dep
├── src/main/java/tw/elliot/errorclient/
│   ├── config/
│   │   └── RestClientConfig.java                                      # MODIFY: add connect/read timeout to both RestClient + token response client
│   └── error/
│       ├── ErrorStage.java                                            # CREATE: enum INPUT / TOKEN / RESOURCE / UNKNOWN
│       ├── ErrorCode.java                                             # CREATE: enum, single source of truth
│       ├── ErrorResponse.java                                         # CREATE: record (JSON schema)
│       ├── ClientFlowException.java                                   # CREATE: minimal scaffolding
│       └── GlobalExceptionHandler.java                                # CREATE: @RestControllerAdvice
└── src/test/java/tw/elliot/errorclient/
    ├── error/GlobalExceptionHandlerTest.java                          # CREATE: Layer 1, 21 cases
    └── UpdateParameterClientFlowTest.java                             # CREATE: Layer 2, 6 cases
```

---

## Task 1: Add MockWebServer dep + RestClient timeouts

**Files:**
- Modify: `clientCredentials/client/ccErrorClient/pom.xml`
- Modify: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/config/RestClientConfig.java`

**Why:** Layer 2 needs MockWebServer (test scope). Both the resource-server `RestClient` and the OAuth2 token client need short read/connect timeouts so the `SocketPolicy.NO_RESPONSE` test cases trigger `SocketTimeoutException` quickly.

- [ ] **Step 1: Add mockwebserver dependency to pom.xml**

In `<dependencies>` (after the existing test starter), add:

```xml
<dependency>
  <groupId>com.squareup.okhttp3</groupId>
  <artifactId>mockwebserver</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify dependency resolves**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q dependency:resolve | grep mockwebserver
```
Expected: a line containing `com.squareup.okhttp3:mockwebserver:jar:...:test`.

- [ ] **Step 3: Add timeout-configured ClientHttpRequestFactory + apply to RestClient + token response client**

Replace the contents of `RestClientConfig.java` with:

```java
package tw.elliot.errorclient.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(2);

    private static ClientHttpRequestFactory timeoutFactory() {
        JdkClientHttpRequestFactory f = new JdkClientHttpRequestFactory();
        f.setReadTimeout(READ_TIMEOUT);
        return f;
    }

    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest>
            clientCredentialsTokenResponseClient() {
        RestClient tokenRestClient = RestClient.builder()
                .requestFactory(timeoutFactory())
                .build();
        RestClientClientCredentialsTokenResponseClient client =
                new RestClientClientCredentialsTokenResponseClient();
        client.setRestClient(tokenRestClient);
        return client;
    }

    @Bean
    public OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenResponseClient) {

        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(cc -> cc.accessTokenResponseClient(tokenResponseClient))
                .build();

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                        clientRegistrationRepository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    @Bean
    public RestClient resourceServerRestClient(
            OAuth2AuthorizedClientManager authorizedClientManager,
            @Value("${app.resource-server.base-url}") String baseUrl) {

        OAuth2ClientHttpRequestInterceptor interceptor =
                new OAuth2ClientHttpRequestInterceptor(authorizedClientManager);
        interceptor.setClientRegistrationIdResolver(request -> "resource-server");

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(timeoutFactory())
                .requestInterceptor(interceptor)
                .build();
    }
}
```

- [ ] **Step 4: Verify compile**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -DskipTests compile
```
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 5: Commit**

```bash
git add clientCredentials/client/ccErrorClient/pom.xml \
        clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/config/RestClientConfig.java
git commit -m "ccErrorClient: add mockwebserver dep and short RestClient/token timeouts"
```

---

## Task 2: Error data structures (no logic)

**Files:**
- Create: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/ErrorStage.java`
- Create: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/ErrorCode.java`
- Create: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/ErrorResponse.java`
- Create: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/ClientFlowException.java`

These have no behavior to TDD — they are pure data. Tests come in Task 3+ when handler consumes them.

- [ ] **Step 1: Create ErrorStage**

```java
package tw.elliot.errorclient.error;

public enum ErrorStage {
    INPUT,
    TOKEN,
    RESOURCE,
    UNKNOWN
}
```

- [ ] **Step 2: Create ErrorCode**

```java
package tw.elliot.errorclient.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INPUT_MISSING_PARAMETER(ErrorStage.INPUT, HttpStatus.BAD_REQUEST,
            "缺少必要參數"),
    INPUT_PARAMETER_TYPE_MISMATCH(ErrorStage.INPUT, HttpStatus.BAD_REQUEST,
            "參數型別不符"),
    INPUT_METHOD_NOT_ALLOWED(ErrorStage.INPUT, HttpStatus.METHOD_NOT_ALLOWED,
            "HTTP method 不支援"),
    INPUT_MEDIA_TYPE_NOT_ACCEPTABLE(ErrorStage.INPUT, HttpStatus.NOT_ACCEPTABLE,
            "Accept media type 不支援"),

    TOKEN_INVALID_CLIENT(ErrorStage.TOKEN, HttpStatus.BAD_GATEWAY,
            "取得 token 失敗：invalid_client"),
    TOKEN_INVALID_SCOPE(ErrorStage.TOKEN, HttpStatus.BAD_GATEWAY,
            "取得 token 失敗：invalid_scope"),
    TOKEN_UNAUTHORIZED_CLIENT(ErrorStage.TOKEN, HttpStatus.BAD_GATEWAY,
            "取得 token 失敗：unauthorized_client"),
    TOKEN_ENDPOINT_UNREACHABLE(ErrorStage.TOKEN, HttpStatus.GATEWAY_TIMEOUT,
            "無法連線到 token endpoint"),
    TOKEN_SERVER_ERROR(ErrorStage.TOKEN, HttpStatus.BAD_GATEWAY,
            "token endpoint 回應錯誤"),
    TOKEN_REGISTRATION_NOT_FOUND(ErrorStage.TOKEN, HttpStatus.INTERNAL_SERVER_ERROR,
            "找不到 OAuth2 client registration"),

    RESOURCE_UNAUTHORIZED(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 拒絕 token (401)"),
    RESOURCE_FORBIDDEN(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 拒絕 scope/role (403)"),
    RESOURCE_BAD_REQUEST(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 退件 (400)"),
    RESOURCE_NOT_FOUND(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 路徑不存在 (404)"),
    RESOURCE_CLIENT_ERROR(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 4xx 錯誤"),
    RESOURCE_SERVER_ERROR(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 5xx 錯誤"),
    RESOURCE_UNREACHABLE(ErrorStage.RESOURCE, HttpStatus.GATEWAY_TIMEOUT,
            "無法連線到 resource server"),
    RESOURCE_TIMEOUT(ErrorStage.RESOURCE, HttpStatus.GATEWAY_TIMEOUT,
            "resource server 回應逾時"),
    RESOURCE_MALFORMED_RESPONSE(ErrorStage.RESOURCE, HttpStatus.BAD_GATEWAY,
            "resource server 回應無法解析"),

    INTERNAL_UNEXPECTED(ErrorStage.UNKNOWN, HttpStatus.INTERNAL_SERVER_ERROR,
            "未預期的內部錯誤");

    private final ErrorStage stage;
    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(ErrorStage stage, HttpStatus httpStatus, String defaultMessage) {
        this.stage = stage;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public ErrorStage stage() { return stage; }
    public HttpStatus httpStatus() { return httpStatus; }
    public String defaultMessage() { return defaultMessage; }
}
```

- [ ] **Step 3: Create ErrorResponse**

```java
package tw.elliot.errorclient.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        String code,
        ErrorStage stage,
        String message,
        int httpStatus,
        String path,
        Instant timestamp
) {
    public static ErrorResponse of(ErrorCode code, String detailMessage, String path) {
        String msg = (detailMessage == null || detailMessage.isBlank())
                ? code.defaultMessage()
                : code.defaultMessage() + "：" + detailMessage;
        return new ErrorResponse(
                code.name(),
                code.stage(),
                msg,
                code.httpStatus().value(),
                path,
                Instant.now()
        );
    }
}
```

- [ ] **Step 4: Create ClientFlowException**

```java
package tw.elliot.errorclient.error;

public class ClientFlowException extends RuntimeException {
    private final ErrorCode errorCode;

    public ClientFlowException(ErrorCode errorCode, String detail) {
        super(detail);
        this.errorCode = errorCode;
    }

    public ClientFlowException(ErrorCode errorCode, String detail, Throwable cause) {
        super(detail, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() { return errorCode; }
}
```

- [ ] **Step 5: Compile**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -DskipTests compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/
git commit -m "ccErrorClient: add error data structures (ErrorStage, ErrorCode, ErrorResponse, ClientFlowException)"
```

---

## Task 3: GlobalExceptionHandler — INPUT mappings (TDD)

**Files:**
- Create: `clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/GlobalExceptionHandler.java`
- Create: `clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/error/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Write failing test for INPUT mappings**

Create `GlobalExceptionHandlerTest.java`:

```java
package tw.elliot.errorclient.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static HttpServletRequest req() {
        HttpServletRequest r = Mockito.mock(HttpServletRequest.class);
        Mockito.when(r.getRequestURI()).thenReturn("/client/UpdateParameter");
        return r;
    }

    static Stream<Arguments> inputCases() {
        return Stream.of(
                Arguments.of(
                        new MissingServletRequestParameterException("paramType", "String"),
                        ErrorCode.INPUT_MISSING_PARAMETER),
                Arguments.of(
                        new MethodArgumentTypeMismatchException("x", String.class, "paramType", null, null),
                        ErrorCode.INPUT_PARAMETER_TYPE_MISMATCH),
                Arguments.of(
                        new HttpRequestMethodNotSupportedException("GET"),
                        ErrorCode.INPUT_METHOD_NOT_ALLOWED),
                Arguments.of(
                        new HttpMediaTypeNotAcceptableException("not acceptable"),
                        ErrorCode.INPUT_MEDIA_TYPE_NOT_ACCEPTABLE)
        );
    }

    @ParameterizedTest
    @MethodSource("inputCases")
    void mapsInputExceptions(Exception ex, ErrorCode expected) {
        ResponseEntity<ErrorResponse> resp = handler.handle(ex, req());

        assertThat(resp.getStatusCode().value()).isEqualTo(expected.httpStatus().value());
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().code()).isEqualTo(expected.name());
        assertThat(resp.getBody().stage()).isEqualTo(expected.stage());
        assertThat(resp.getBody().httpStatus()).isEqualTo(expected.httpStatus().value());
        assertThat(resp.getBody().path()).isEqualTo("/client/UpdateParameter");
        assertThat(resp.getBody().timestamp()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test, verify it fails (no handler class)**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: compile error — `GlobalExceptionHandler` does not exist.

- [ ] **Step 3: Create GlobalExceptionHandler with INPUT branch**

```java
package tw.elliot.errorclient.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handle(Exception ex, HttpServletRequest request) {
        ErrorCode code = classify(ex);
        ErrorResponse body = ErrorResponse.of(code, ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(code.httpStatus()).body(body);
    }

    private ErrorCode classify(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException) return ErrorCode.INPUT_MISSING_PARAMETER;
        if (ex instanceof MethodArgumentTypeMismatchException)     return ErrorCode.INPUT_PARAMETER_TYPE_MISMATCH;
        if (ex instanceof HttpRequestMethodNotSupportedException)  return ErrorCode.INPUT_METHOD_NOT_ALLOWED;
        if (ex instanceof HttpMediaTypeNotAcceptableException)     return ErrorCode.INPUT_MEDIA_TYPE_NOT_ACCEPTABLE;
        return ErrorCode.INTERNAL_UNEXPECTED;
    }
}
```

- [ ] **Step 4: Run test, verify all 4 INPUT cases pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/GlobalExceptionHandler.java \
        clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/error/GlobalExceptionHandlerTest.java
git commit -m "ccErrorClient: handler maps INPUT-stage exceptions"
```

---

## Task 4: Handler — TOKEN mappings (TDD)

**Files:**
- Modify: `GlobalExceptionHandlerTest.java` (add TOKEN cases)
- Modify: `GlobalExceptionHandler.java` (add TOKEN branches)

- [ ] **Step 1: Add TOKEN test cases to GlobalExceptionHandlerTest**

Append to the test class (before the closing brace):

```java
    private static ClientAuthorizationException clientAuthEx(String oauthErrorCode) {
        OAuth2Error err = new OAuth2Error(oauthErrorCode, oauthErrorCode + " description", null);
        return new ClientAuthorizationException(err, "resource-server");
    }

    private static ClientAuthorizationException clientAuthExWithCause(Throwable cause) {
        OAuth2Error err = new OAuth2Error("io_error");
        return new ClientAuthorizationException(err, "resource-server", "io", cause);
    }

    static Stream<Arguments> tokenCases() {
        return Stream.of(
                Arguments.of(clientAuthEx("invalid_client"),       ErrorCode.TOKEN_INVALID_CLIENT),
                Arguments.of(clientAuthEx("invalid_scope"),        ErrorCode.TOKEN_INVALID_SCOPE),
                Arguments.of(clientAuthEx("unauthorized_client"),  ErrorCode.TOKEN_UNAUTHORIZED_CLIENT),
                Arguments.of(
                        clientAuthExWithCause(new ResourceAccessException("io", new ConnectException("refused"))),
                        ErrorCode.TOKEN_ENDPOINT_UNREACHABLE),
                Arguments.of(
                        clientAuthExWithCause(new ResourceAccessException("io", new SocketTimeoutException("timeout"))),
                        ErrorCode.TOKEN_ENDPOINT_UNREACHABLE),
                Arguments.of(clientAuthEx("server_error"),         ErrorCode.TOKEN_SERVER_ERROR),
                Arguments.of(
                        new ClientRegistrationException("no registration: nope"),
                        ErrorCode.TOKEN_REGISTRATION_NOT_FOUND)
        );
    }

    @ParameterizedTest
    @MethodSource("tokenCases")
    void mapsTokenExceptions(Exception ex, ErrorCode expected) {
        ResponseEntity<ErrorResponse> resp = handler.handle(ex, req());
        assertThat(resp.getStatusCode().value()).isEqualTo(expected.httpStatus().value());
        assertThat(resp.getBody().code()).isEqualTo(expected.name());
        assertThat(resp.getBody().stage()).isEqualTo(ErrorStage.TOKEN);
    }
```

Add imports:

```java
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.ResourceAccessException;
```

- [ ] **Step 2: Run, verify TOKEN cases fail (fall through to INTERNAL_UNEXPECTED)**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: 7 TOKEN cases fail; 4 INPUT cases still pass.

- [ ] **Step 3: Extend handler with TOKEN branches**

Replace the `classify` method (and add a helper for OAuth error-code sub-dispatch):

```java
    private ErrorCode classify(Exception ex) {
        if (ex instanceof MissingServletRequestParameterException) return ErrorCode.INPUT_MISSING_PARAMETER;
        if (ex instanceof MethodArgumentTypeMismatchException)     return ErrorCode.INPUT_PARAMETER_TYPE_MISMATCH;
        if (ex instanceof HttpRequestMethodNotSupportedException)  return ErrorCode.INPUT_METHOD_NOT_ALLOWED;
        if (ex instanceof HttpMediaTypeNotAcceptableException)     return ErrorCode.INPUT_MEDIA_TYPE_NOT_ACCEPTABLE;

        if (ex instanceof ClientRegistrationException)             return ErrorCode.TOKEN_REGISTRATION_NOT_FOUND;
        if (ex instanceof ClientAuthorizationException cae)        return classifyTokenError(cae);

        return ErrorCode.INTERNAL_UNEXPECTED;
    }

    private ErrorCode classifyTokenError(ClientAuthorizationException ex) {
        Throwable root = rootCause(ex);
        if (root instanceof java.net.ConnectException
                || root instanceof java.net.SocketTimeoutException) {
            return ErrorCode.TOKEN_ENDPOINT_UNREACHABLE;
        }
        String oauthCode = ex.getError() == null ? "" : ex.getError().getErrorCode();
        return switch (oauthCode) {
            case "invalid_client"      -> ErrorCode.TOKEN_INVALID_CLIENT;
            case "invalid_scope"       -> ErrorCode.TOKEN_INVALID_SCOPE;
            case "unauthorized_client" -> ErrorCode.TOKEN_UNAUTHORIZED_CLIENT;
            default                    -> ErrorCode.TOKEN_SERVER_ERROR;
        };
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) c = c.getCause();
        return c;
    }
```

Add imports at the top of `GlobalExceptionHandler.java`:

```java
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationException;
```

- [ ] **Step 4: Run, verify all 11 cases pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/GlobalExceptionHandler.java \
        clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/error/GlobalExceptionHandlerTest.java
git commit -m "ccErrorClient: handler maps TOKEN-stage exceptions"
```

---

## Task 5: Handler — RESOURCE mappings (TDD)

**Files:**
- Modify: `GlobalExceptionHandlerTest.java`
- Modify: `GlobalExceptionHandler.java`

- [ ] **Step 1: Add RESOURCE test cases**

Append to the test class:

```java
    static Stream<Arguments> resourceCases() {
        return Stream.of(
                Arguments.of(
                        HttpClientErrorException.create(HttpStatus.UNAUTHORIZED,
                                "Unauthorized", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_UNAUTHORIZED),
                Arguments.of(
                        HttpClientErrorException.create(HttpStatus.FORBIDDEN,
                                "Forbidden", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_FORBIDDEN),
                Arguments.of(
                        HttpClientErrorException.create(HttpStatus.BAD_REQUEST,
                                "Bad Request", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_BAD_REQUEST),
                Arguments.of(
                        HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                                "Not Found", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_NOT_FOUND),
                Arguments.of(
                        HttpClientErrorException.create(HttpStatus.CONFLICT,
                                "Conflict", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_CLIENT_ERROR),
                Arguments.of(
                        HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR,
                                "Server", HttpHeaders.EMPTY, new byte[0], null),
                        ErrorCode.RESOURCE_SERVER_ERROR),
                Arguments.of(
                        new ResourceAccessException("io", new ConnectException("refused")),
                        ErrorCode.RESOURCE_UNREACHABLE),
                Arguments.of(
                        new ResourceAccessException("io", new SocketTimeoutException("timeout")),
                        ErrorCode.RESOURCE_TIMEOUT),
                Arguments.of(
                        new UnknownContentTypeException(String.class, HttpHeaders.EMPTY.getContentType(),
                                200, "OK", HttpHeaders.EMPTY, new byte[0]),
                        ErrorCode.RESOURCE_MALFORMED_RESPONSE)
        );
    }

    @ParameterizedTest
    @MethodSource("resourceCases")
    void mapsResourceExceptions(Exception ex, ErrorCode expected) {
        ResponseEntity<ErrorResponse> resp = handler.handle(ex, req());
        assertThat(resp.getStatusCode().value()).isEqualTo(expected.httpStatus().value());
        assertThat(resp.getBody().code()).isEqualTo(expected.name());
        assertThat(resp.getBody().stage()).isEqualTo(ErrorStage.RESOURCE);
    }
```

Add imports:

```java
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.UnknownContentTypeException;
```

- [ ] **Step 2: Run, verify 9 RESOURCE cases fail**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: 9 RESOURCE cases fail.

- [ ] **Step 3: Extend handler with RESOURCE branches**

Insert above the final `return ErrorCode.INTERNAL_UNEXPECTED;` in `classify`:

```java
        if (ex instanceof ResourceAccessException rae) {
            Throwable root = rootCause(rae);
            if (root instanceof java.net.SocketTimeoutException) return ErrorCode.RESOURCE_TIMEOUT;
            return ErrorCode.RESOURCE_UNREACHABLE;
        }
        if (ex instanceof HttpClientErrorException hcee) {
            return switch (hcee.getStatusCode().value()) {
                case 401 -> ErrorCode.RESOURCE_UNAUTHORIZED;
                case 403 -> ErrorCode.RESOURCE_FORBIDDEN;
                case 400 -> ErrorCode.RESOURCE_BAD_REQUEST;
                case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
                default  -> ErrorCode.RESOURCE_CLIENT_ERROR;
            };
        }
        if (ex instanceof HttpServerErrorException)           return ErrorCode.RESOURCE_SERVER_ERROR;
        if (ex instanceof UnknownContentTypeException
                || ex instanceof RestClientResponseException
                || ex instanceof HttpMessageNotReadableException) {
            return ErrorCode.RESOURCE_MALFORMED_RESPONSE;
        }
```

Add imports at the top of `GlobalExceptionHandler.java`:

```java
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
```

- [ ] **Step 4: Run, verify all 20 cases pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: all tests pass.

- [ ] **Step 5: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/main/java/tw/elliot/errorclient/error/GlobalExceptionHandler.java \
        clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/error/GlobalExceptionHandlerTest.java
git commit -m "ccErrorClient: handler maps RESOURCE-stage exceptions"
```

---

## Task 6: Handler — UNKNOWN fallback test (TDD)

**Files:**
- Modify: `GlobalExceptionHandlerTest.java`

- [ ] **Step 1: Add fallback test**

Append to the test class:

```java
    @org.junit.jupiter.api.Test
    void fallsBackToInternalUnexpectedForUnknownException() {
        ResponseEntity<ErrorResponse> resp = handler.handle(new IllegalStateException("boom"), req());
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().code()).isEqualTo(ErrorCode.INTERNAL_UNEXPECTED.name());
        assertThat(resp.getBody().stage()).isEqualTo(ErrorStage.UNKNOWN);
    }
```

- [ ] **Step 2: Run, verify all 21 cases pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=GlobalExceptionHandlerTest test
```
Expected: 21 tests pass (fallback already works because of the default branch).

- [ ] **Step 3: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/error/GlobalExceptionHandlerTest.java
git commit -m "ccErrorClient: handler test covers UNKNOWN fallback"
```

---

## Task 7: Layer 2 — MockWebServer-driven end-to-end test

**Files:**
- Create: `clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/UpdateParameterClientFlowTest.java`

Single test class, six scenarios. Each scenario gets its own `@Test` method (no parameterization — MockWebServer enqueue scripts differ enough that flat methods are clearer).

- [ ] **Step 1: Write the test class skeleton with happy path + INPUT_MISSING_PARAMETER**

```java
package tw.elliot.errorclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tw.elliot.errorclient.error.ErrorCode;
import tw.elliot.errorclient.error.ErrorStage;

@SpringBootTest
class UpdateParameterClientFlowTest {

    private static MockWebServer authServer;
    private static MockWebServer resourceServer;

    @Autowired private WebApplicationContext webContext;
    @Autowired private OAuth2AuthorizedClientService authorizedClientService;

    private MockMvc mockMvc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    static void startMocks() throws IOException {
        authServer = new MockWebServer();
        resourceServer = new MockWebServer();
        authServer.start();
        resourceServer.start();
    }

    @AfterAll
    static void stopMocks() throws IOException {
        authServer.shutdown();
        resourceServer.shutdown();
    }

    @DynamicPropertySource
    static void wireMocks(DynamicPropertyRegistry r) {
        r.add("spring.security.oauth2.client.provider.resource-server.token-uri",
                () -> "http://localhost:" + authServer.getPort() + "/oauth2/token");
        r.add("app.resource-server.base-url",
                () -> "http://localhost:" + resourceServer.getPort());
    }

    @BeforeEach
    void resetState() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        // Drop any cached access token from previous test
        authorizedClientService.removeAuthorizedClient("resource-server", "errorClient");
        authorizedClientService.removeAuthorizedClient("resource-server", "anonymousUser");
    }

    private static MockResponse tokenOk() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"access_token":"fake-token","token_type":"Bearer","expires_in":3600,"scope":"admin"}
                        """);
    }

    @Test
    void happyPath_returnsResourceBody() throws Exception {
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"code\":0,\"message\":\"OK\"}"));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(0);
    }

    @Test
    void missingParameter_returnsInputError() throws Exception {
        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.INPUT_MISSING_PARAMETER.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.INPUT.name());
    }
}
```

- [ ] **Step 2: Run, verify happy path + missing param pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=UpdateParameterClientFlowTest test
```
Expected: 2 tests pass. If the principal-name guess in `resetState` is wrong, the happy-path test may reuse a cached token across runs — but the first run on a clean JVM should pass.

- [ ] **Step 3: Add TOKEN scenarios**

Append:

```java
    @Test
    void tokenInvalidClient_returnsTokenError() throws Exception {
        authServer.enqueue(new MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"error\":\"invalid_client\"}"));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.TOKEN_INVALID_CLIENT.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.TOKEN.name());
    }

    @Test
    void tokenEndpointTimeout_returnsGatewayTimeout() throws Exception {
        authServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.TOKEN_ENDPOINT_UNREACHABLE.name());
    }
```

- [ ] **Step 4: Run, verify TOKEN scenarios pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=UpdateParameterClientFlowTest test
```
Expected: 4 tests pass. The timeout test will take ~2s (RestClient read timeout).

- [ ] **Step 5: Add RESOURCE scenarios**

Append:

```java
    @Test
    void resourceUnauthorized_returnsBadGateway() throws Exception {
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse().setResponseCode(401));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(502);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.RESOURCE_UNAUTHORIZED.name());
        assertThat(body.get("stage")).isEqualTo(ErrorStage.RESOURCE.name());
    }

    @Test
    void resourceTimeout_returnsGatewayTimeout() throws Exception {
        authServer.enqueue(tokenOk());
        resourceServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        MvcResult result = mockMvc.perform(post("/client/UpdateParameter")
                        .param("paramType", "01")
                        .param("version", "v1"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(504);
        Map<?, ?> body = json.readValue(result.getResponse().getContentAsString(), Map.class);
        assertThat(body.get("code")).isEqualTo(ErrorCode.RESOURCE_TIMEOUT.name());
    }
```

- [ ] **Step 6: Run full test, verify 6 scenarios pass**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q -Dtest=UpdateParameterClientFlowTest test
```
Expected: 6 tests pass. Total runtime ~5-8s (two timeout cases at ~2s each).

- [ ] **Step 7: Run full module test suite as final check**

```bash
cd clientCredentials/client/ccErrorClient && ./mvnw -q test
```
Expected: BUILD SUCCESS, 22 tests passing (21 Layer 1 + 6 Layer 2 + existing `ErrorClientApplicationTests` if present — adjust count by what's already there).

- [ ] **Step 8: Commit**

```bash
git add clientCredentials/client/ccErrorClient/src/test/java/tw/elliot/errorclient/UpdateParameterClientFlowTest.java
git commit -m "ccErrorClient: end-to-end MockWebServer tests for token + resource flows"
```

---

## Self-Review Notes

**Spec coverage check:**
- §2 module structure → Tasks 2, 3 (all five new files created).
- §3 response schema → Task 2 step 3 (`ErrorResponse.of` shape matches spec).
- §4 error table (21 rows) → Tasks 3, 4, 5, 6 collectively cover all rows in Layer 1.
- §5 Layer 1 testing → Tasks 3-6.
- §5 Layer 2 testing (6 scenarios) → Task 7.
- §5 timeout config + mockwebserver dep → Task 1.
- §6 work item list → Tasks 1-7 each map.

**Known fragilities:**
- `AuthorizedClientServiceOAuth2AuthorizedClientManager` uses a principal-name internally; the `resetState` clears two candidates (`errorClient`, `anonymousUser`). If neither matches at runtime, second test in a class may reuse a cached token and the second-token-related test could see an unexpected enqueued response replay. Mitigation if hit: add `authorizedClientService.removeAuthorizedClient(...)` for the actually-observed principal name, or re-create the application context per test (`@DirtiesContext`).
- `ClientAuthorizationException` constructor signatures vary across Spring Security versions; the test helpers use the public 4-arg `(OAuth2Error, clientRegistrationId, message, cause)` constructor available in Spring Security 6+. If the compile fails on this signature under Boot 4.0.6's Security version, adjust to whichever public constructor is available.
