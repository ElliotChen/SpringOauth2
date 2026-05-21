package tw.elliot.productionclient.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Wraps the resource call in two layers:
 *   1) 401 evict-and-retry-once  — handles tokens revoked by the resource server
 *      (the cached AuthorizedClient may still be unexpired but no longer accepted).
 *      Done OUTSIDE Resilience4j so that the evict step happens deterministically
 *      and the retry is bounded to exactly one attempt for 401.
 *   2) Resilience4j retry + circuit breaker — handles transport / 5xx storms.
 *
 * Layer ordering: 401-evict wraps Resilience4j, so a 401 triggers one full
 * retry through the resilience pipeline (which itself may retry transport errors).
 */
@Service
public class UpdateParameterService {

    private static final Logger log = LoggerFactory.getLogger(UpdateParameterService.class);
    private static final String REGISTRATION_ID = "resource-server";

    private final RestClient resourceServerRestClient;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public UpdateParameterService(RestClient resourceServerRestClient,
                                  OAuth2AuthorizedClientService authorizedClientService,
                                  CircuitBreaker resourceCircuitBreaker,
                                  Retry resourceRetry) {
        this.resourceServerRestClient = resourceServerRestClient;
        this.authorizedClientService = authorizedClientService;
        this.circuitBreaker = resourceCircuitBreaker;
        this.retry = resourceRetry;
    }

    public Map<String, Object> updateParameter(String paramType, String version) {
        Map<String, Object> body = buildRequestBody(paramType, version);

        // Manual decoration order (innermost → outermost):
        //   CircuitBreaker wraps the actual call (so it sees raw upstream errors)
        //   Retry wraps the CB-wrapped call (so each attempt goes through the CB)
        Supplier<Map<String, Object>> cbWrapped =
                CircuitBreaker.decorateSupplier(circuitBreaker, () -> invoke(body));
        Supplier<Map<String, Object>> resilientCall =
                Retry.decorateSupplier(retry, cbWrapped);

        try {
            return resilientCall.get();
        } catch (HttpClientErrorException.Unauthorized e) {
            // Token was accepted by ccOauthServer but rejected by ccResourceServer
            // (revoked / rotated keys / aud mismatch). Evict the cached token and
            // try once more — only ONCE, to avoid hammering a real auth problem.
            log.warn("Resource server returned 401; evicting cached token and retrying once");
            evictCachedToken();
            return resilientCall.get();
        }
    }

    private Map<String, Object> invoke(Map<String, Object> body) {
        return resourceServerRestClient.post()
                .uri("/sEQI/Param/UpdateParameter")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
    }

    private void evictCachedToken() {
        // AuthorizedClientServiceOAuth2AuthorizedClientManager stores under the
        // client registration id as principal name for non-user flows.
        authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, REGISTRATION_ID);
        authorizedClientService.removeAuthorizedClient(REGISTRATION_ID, "anonymousUser");
    }

    private static Map<String, Object> buildRequestBody(String paramType, String version) {
        Map<String, String> paramInfo = new LinkedHashMap<>();
        paramInfo.put("Param_Type", paramType);
        paramInfo.put("Version", version);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Check_Valid_Only", "N");
        body.put("Request", List.of(paramInfo));
        return body;
    }
}
