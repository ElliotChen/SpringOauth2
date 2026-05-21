package tw.elliot.errorclient.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 本服務角色為「OAuth2 Client」：對外只負責接收使用者請求，
 * 再用 client_credentials 向 oauthserver 取得 token、轉打 resourceServer。
 * 因此自己不需要要求使用者登入，全部端點 permitAll。
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}