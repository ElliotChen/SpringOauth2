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
