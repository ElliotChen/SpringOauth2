package tw.elliot.errorclient.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class UpdateParameterService {

    private final RestClient resourceServerRestClient;

    public UpdateParameterService(RestClient resourceServerRestClient) {
        this.resourceServerRestClient = resourceServerRestClient;
    }

    public Map<String, Object> updateParameter(String paramType, String version) {
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
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
