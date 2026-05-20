package tw.elliot.pkceresourceserver.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ParamInfo {

    @JsonProperty("Param_Type")
    @NotBlank
    @Size(max = 2)
    private String paramType;

    @JsonProperty("Version")
    @NotBlank
    @Size(max = 15)
    private String version;

    public String getParamType() { return paramType; }
    public void setParamType(String paramType) { this.paramType = paramType; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
}
