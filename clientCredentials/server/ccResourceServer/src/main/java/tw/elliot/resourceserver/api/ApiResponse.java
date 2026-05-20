package tw.elliot.resourceserver.api;

public class ApiResponse {

    public static final ApiResponse SUCCESS = new ApiResponse(0, "successful");
    public static final ApiResponse ERROR = new ApiResponse(-1, "something get wrong....");

    private final int code;
    private final String message;

    public ApiResponse(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
