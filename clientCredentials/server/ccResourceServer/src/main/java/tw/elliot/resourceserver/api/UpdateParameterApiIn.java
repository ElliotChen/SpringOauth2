package tw.elliot.resourceserver.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public class UpdateParameterApiIn {

    @JsonProperty("Check_Valid_Only")
    @NotBlank
    @Size(max = 2)
    private String checkValidOnly;

    @JsonProperty("Request")
    @NotEmpty
    @Valid
    private List<ParamInfo> request;

    public String getCheckValidOnly() {
        return checkValidOnly;
    }

    public void setCheckValidOnly(String checkValidOnly) {
        this.checkValidOnly = checkValidOnly;
    }

    public List<ParamInfo> getRequest() {
        return request;
    }

    public void setRequest(List<ParamInfo> request) {
        this.request = request;
    }
}
