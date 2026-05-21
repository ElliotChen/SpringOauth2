package tw.elliot.productionclient.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Public-facing edge: no end-user auth, no CSRF (the controller is JSON-only).
 * Auth happens machine-to-machine via the client_credentials access token added
 * by {@code OAuth2ClientHttpRequestInterceptor} when calling the resource server.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a.anyRequest().permitAll())
            .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
