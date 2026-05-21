package tw.elliot.errorclient.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
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

        if (ex instanceof IllegalArgumentException iae
                && iae.getMessage() != null
                && iae.getMessage().contains("ClientRegistration")) {
            return ErrorCode.TOKEN_REGISTRATION_NOT_FOUND;
        }
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
}
