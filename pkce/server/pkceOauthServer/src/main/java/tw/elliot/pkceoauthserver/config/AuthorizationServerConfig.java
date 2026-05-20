package tw.elliot.pkceoauthserver.config;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * 與 ccOauthServer.AuthorizationServerConfig 對照：
 * cc 的 claims 來源是 clientId（機器對機器），這裡來源是「當前登入使用者」的 authorities。
 * 同一個 `/me` 端點在兩種 grant 下會回傳不同 subject / role，是 demo 的核心對照點。
 */
@Configuration
public class AuthorizationServerConfig {

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtTokenCustomizer() {
        return context -> {
            if (!"access_token".equals(context.getTokenType().getValue())) {
                return;
            }
            // authorization_code grant 下，context.getPrincipal() 是使用者 Authentication。
            // 取出 authorities，剝掉 "ROLE_" 前綴後作為 role，原值放進 authorities claim。
            List<String> authorities = context.getPrincipal().getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors.toList());

            String role = authorities.stream()
                    .filter(a -> a.startsWith("ROLE_"))
                    .findFirst()
                    .map(a -> a.substring("ROLE_".length()).toLowerCase())
                    .orElse("none");

            context.getClaims().claim("role", role);
            context.getClaims().claim("authorities", authorities);
        };
    }
}
