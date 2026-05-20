package tw.elliot.pkceresourceclient.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ResourceServerClient {

    private final RestClient restClient;

    public ResourceServerClient(RestClient resourceServerRestClient) {
        this.restClient = resourceServerRestClient;
    }

    public Map<String, Object> me() {
        return restClient.get()
                .uri("/me")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    public Map<String, Object> updateParameter(String paramType, String version) {
        Map<String, String> paramInfo = new LinkedHashMap<>();
        paramInfo.put("Param_Type", paramType);
        paramInfo.put("Version", version);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Check_Valid_Only", "N");
        body.put("Request", List.of(paramInfo));

        return restClient.post()
                .uri("/sEQI/Param/UpdateParameter")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }
}
