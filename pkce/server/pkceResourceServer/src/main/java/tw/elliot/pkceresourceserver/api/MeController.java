package tw.elliot.pkceresourceserver.api;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    @GetMapping(value = "/me", produces = MediaType.APPLICATION_JSON_VALUE)
    public MeResponse me(@AuthenticationPrincipal Jwt jwt) {
        List<String> scopes = jwt.getClaimAsStringList("scope");
        List<String> authorities = jwt.getClaimAsStringList("authorities");
        return new MeResponse(
                jwt.getSubject(),
                scopes,
                jwt.getClaimAsString("role"),
                authorities,
                jwt.getClaimAsString("client_id"),
                jwt.getIssuer() != null ? jwt.getIssuer().toString() : null,
                jwt.getExpiresAt()
        );
    }
}
