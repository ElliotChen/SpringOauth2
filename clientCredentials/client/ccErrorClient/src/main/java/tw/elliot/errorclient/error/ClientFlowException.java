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
