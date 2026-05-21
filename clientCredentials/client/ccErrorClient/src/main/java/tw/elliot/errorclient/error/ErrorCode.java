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
    TOKEN_ENDPOINT_TIMEOUT(ErrorStage.TOKEN, HttpStatus.GATEWAY_TIMEOUT,
            "token endpoint 回應逾時"),
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
