package tw.elliot.productionclient.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRetryMetrics;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * Programmatic Resilience4j wiring. Two independent instances:
 *
 *   resourceServer  — wraps every outbound call to the resource API.
 *                     Retries transport-level failures (connect / read timeout),
 *                     does NOT retry 4xx (deterministic business errors).
 *                     Circuit breaker opens after sustained 5xx / transport storms.
 *
 *   (token endpoint is intentionally NOT wrapped — failing to obtain a token
 *    means total dependency failure; retrying it can mask credential/scope
 *    misconfiguration and add latency to an already-broken request path.
 *    A short connect/read timeout is enough.)
 */
@Configuration
public class ResilienceConfig {

    public static final String RESOURCE = "resourceServer";

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry(MeterRegistry meterRegistry) {
        CircuitBreakerConfig resource = CircuitBreakerConfig.custom()
                .slidingWindowSize(10)
                .minimumNumberOfCalls(5)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(2)
                .recordExceptions(
                        ResourceAccessException.class,
                        HttpServerErrorException.class,
                        ConnectException.class,
                        HttpTimeoutException.class)
                .build();
        CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
        registry.circuitBreaker(RESOURCE, resource);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public RetryRegistry retryRegistry(MeterRegistry meterRegistry) {
        RetryConfig resource = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(200))
                // Only retry transport-level failures; never retry deterministic 4xx.
                .retryExceptions(
                        ResourceAccessException.class,
                        HttpServerErrorException.class,
                        ConnectException.class,
                        HttpTimeoutException.class)
                .build();
        RetryRegistry registry = RetryRegistry.ofDefaults();
        registry.retry(RESOURCE, resource);
        TaggedRetryMetrics.ofRetryRegistry(registry).bindTo(meterRegistry);
        return registry;
    }

    @Bean
    public CircuitBreaker resourceCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker(RESOURCE);
    }

    @Bean
    public Retry resourceRetry(RetryRegistry registry) {
        return registry.retry(RESOURCE);
    }
}
