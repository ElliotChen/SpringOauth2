package tw.elliot.errorclient.error;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.UnknownContentTypeException;
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
        if (ex instanceof ClientFlowException cfe)                 return cfe.errorCode();
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

        if (ex instanceof ResourceAccessException rae) {
            Throwable root = rootCause(rae);
            if (root instanceof java.net.SocketTimeoutException
                    || root instanceof java.net.http.HttpTimeoutException) {
                return ErrorCode.RESOURCE_TIMEOUT;
            }
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

        return ErrorCode.INTERNAL_UNEXPECTED;
    }

    private ErrorCode classifyTokenError(ClientAuthorizationException ex) {
        Throwable root = rootCause(ex);
        if (root instanceof java.net.ConnectException) {
            return ErrorCode.TOKEN_ENDPOINT_UNREACHABLE;
        }
        if (root instanceof java.net.SocketTimeoutException
                || root instanceof java.net.http.HttpTimeoutException) {
            return ErrorCode.TOKEN_ENDPOINT_TIMEOUT;
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
