package tw.elliot.pkceresourceclient.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 與 ccResourceClient 的 permitAll 完全不同：這裡所有路徑都要求使用者登入。
 * oauth2Login() 會自動偵測 client-authentication-method=none 並啟用 PKCE
 * （產生 code_verifier / code_challenge，並在 token request 帶 verifier）。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .oauth2Login(Customizer.withDefaults())
                .logout(logout -> logout.logoutSuccessUrl("/").permitAll());
        return http.build();
    }
}
