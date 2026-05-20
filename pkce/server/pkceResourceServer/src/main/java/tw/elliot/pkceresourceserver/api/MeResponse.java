package tw.elliot.pkceresourceserver.api;

import java.time.Instant;
import java.util.List;

public class MeResponse {

    private final String subject;
    private final List<String> scopes;
    private final String role;
    private final List<String> authorities;
    private final String clientId;
    private final String issuer;
    private final Instant expiresAt;

    public MeResponse(String subject, List<String> scopes, String role, List<String> authorities,
                      String clientId, String issuer, Instant expiresAt) {
        this.subject = subject;
        this.scopes = scopes;
        this.role = role;
        this.authorities = authorities;
        this.clientId = clientId;
        this.issuer = issuer;
        this.expiresAt = expiresAt;
    }

    public String getSubject() { return subject; }
    public List<String> getScopes() { return scopes; }
    public String getRole() { return role; }
    public List<String> getAuthorities() { return authorities; }
    public String getClientId() { return clientId; }
    public String getIssuer() { return issuer; }
    public Instant getExpiresAt() { return expiresAt; }
}
