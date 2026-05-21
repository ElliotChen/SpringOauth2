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
