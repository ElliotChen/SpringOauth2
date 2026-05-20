package tw.elliot.resourceserver.api;

import jakarta.validation.Valid;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/sEQI/Param")
public class UpdateParameterController {

    private static final Logger log = LoggerFactory.getLogger(UpdateParameterController.class);

    @PostMapping(value = "/UpdateParameter",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse updateParameter(@Valid @RequestBody UpdateParameterApiIn in,
                                       @AuthenticationPrincipal Jwt jwt) {
        List<ParamInfo> infos = in.getRequest();
        if (0 == infos.size()) {
            return ApiResponse.ERROR;
        }

        ParamInfo paramInfo = infos.get(0);

        if (paramInfo.getParamType().equals("00")) {
            return ApiResponse.ERROR;
        }
        log.info("UpdateParameter called by client={} scopes={} checkValidOnly={} count={}",
                jwt.getSubject(),
                jwt.getClaimAsStringList("scope"),
                in.getCheckValidOnly(),
                in.getRequest().size());

        return ApiResponse.SUCCESS;
    }
}
