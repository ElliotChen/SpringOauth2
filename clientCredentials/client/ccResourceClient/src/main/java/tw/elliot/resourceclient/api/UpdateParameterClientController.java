package tw.elliot.resourceclient.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/client")
public class UpdateParameterClientController {

    private final RestClient resourceServerRestClient;

    public UpdateParameterClientController(RestClient resourceServerRestClient) {
        this.resourceServerRestClient = resourceServerRestClient;
    }

    @PostMapping(value = "/UpdateParameter", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> updateParameter(@RequestParam("paramType") String paramType,
                                               @RequestParam("version") String version) {
        Map<String, String> paramInfo = new LinkedHashMap<>();
        paramInfo.put("Param_Type", paramType);
        paramInfo.put("Version", version);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Check_Valid_Only", "N");
        body.put("Request", List.of(paramInfo));

        return resourceServerRestClient.post()
                .uri("/sEQI/Param/UpdateParameter")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
    }
}