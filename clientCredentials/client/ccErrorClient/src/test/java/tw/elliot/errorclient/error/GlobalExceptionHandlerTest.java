package tw.elliot.errorclient.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.UnknownContentTypeException;
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
                        new IllegalArgumentException("No ClientRegistration with id: nope"),
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
                        new UnknownContentTypeException(String.class, MediaType.APPLICATION_OCTET_STREAM,
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

    @org.junit.jupiter.api.Test
    void fallsBackToInternalUnexpectedForUnknownException() {
        ResponseEntity<ErrorResponse> resp = handler.handle(new IllegalStateException("boom"), req());
        assertThat(resp.getStatusCode().value()).isEqualTo(500);
        assertThat(resp.getBody().code()).isEqualTo(ErrorCode.INTERNAL_UNEXPECTED.name());
        assertThat(resp.getBody().stage()).isEqualTo(ErrorStage.UNKNOWN);
    }
}
