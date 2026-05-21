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
