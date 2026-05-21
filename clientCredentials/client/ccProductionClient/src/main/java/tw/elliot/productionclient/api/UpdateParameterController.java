package tw.elliot.productionclient.api;

import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tw.elliot.productionclient.service.UpdateParameterService;

@RestController
@RequestMapping("/client")
public class UpdateParameterController {

    private final UpdateParameterService service;

    public UpdateParameterController(UpdateParameterService service) {
        this.service = service;
    }

    @PostMapping(value = "/UpdateParameter", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateParameter(@RequestParam("paramType") String paramType,
                                               @RequestParam("version") String version) {
        return service.updateParameter(paramType, version);
    }
}
