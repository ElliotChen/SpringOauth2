# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository layout

The repo is organized by OAuth2 grant type. Each grant type has its own top-level directory containing the relevant Spring Boot services.

```
oauth/
├── parent/                              # shared BOM / Maven wrapper
├── clientCredentials/                   # client_credentials grant demo
│   ├── server/
│   │   ├── ccOauthServer/               # :9000 — Spring Authorization Server
│   │   └── ccResourceServer/            # :8081 — JWT-protected resource API
│   └── client/
│       └── ccResourceClient/            # :8080 — public edge / token consumer
├── pkce/                                # authorization_code + PKCE grant demo
│   ├── server/
│   │   ├── pkceOauthServer/             # :9000 — Spring Authorization Server (PKCE)
│   │   └── pkceResourceServer/          # :8081 — JWT-protected resource API (/me + UpdateParameter)
│   └── client/
│       └── pkceResourceClient/          # :8080 — Thymeleaf web (oauth2Login + PKCE)
└── docs/                                # cross-cutting notes (e.g. 09_oauth-2.1-overview.md)
```

Root `pom.xml` aggregates `parent`, the three `clientCredentials/**` modules, and the three `pkce/**` modules. Both demos reuse the same ports (9000 / 8081 / 8080) — do not run them simultaneously.

## Build & Run

Multi-module Maven build (Java 25, Spring Boot 4.0.6). There is no root `mvnw`; use any module's wrapper.

```bash
# Build everything from repo root
./parent/mvnw -f pom.xml clean install

# Run a single Spring Boot service (in its module directory)
cd clientCredentials/server/ccOauthServer    && ./mvnw spring-boot:run   # → :9000
cd clientCredentials/server/ccResourceServer && ./mvnw spring-boot:run   # → :8081
cd clientCredentials/client/ccResourceClient && ./mvnw spring-boot:run   # → :8080

# Tests
./mvnw test                                                              # all tests in a module
./parent/mvnw -pl clientCredentials/server/ccOauthServer test            # one module from root
./mvnw -Dtest=ccOauthServerApplicationTests test                         # single test class
```

Services must start in order **ccOauthServer → ccResourceServer → ccResourceClient** because ccResourceServer fetches JWKS from `http://localhost:9000` at startup, and ccResourceClient fetches tokens from there per request.

## End-to-end smoke tests

- `clientCredentials/client/ccResourceClient/shell/update-parameter.sh` — drives the full flow via ccResourceClient's `/client/UpdateParameter`.
- `clientCredentials/server/ccResourceServer/shell/call-update-parameter.sh` — hits the authorization server directly for a token, decodes the JWT, then calls ccResourceServer's `/sEQI/Param/UpdateParameter` with both a success (`Param_Type=01` → returns `0`) and an error case (`Param_Type=00` → returns `-1`).

## Architecture

Three Spring Boot services demonstrating an OAuth2 `client_credentials` flow:

- **ccOauthServer** (`:9000`) — Spring Authorization Server. Issues JWTs for two registered clients defined in `application.yaml`: `admin-client` (scope `admin`) and `member-client` (scope `member`). Both use `client_secret_basic` + `client_credentials`, 30-minute self-contained tokens.
- **ccResourceServer** (`:8081`) — Pure JWT-protected API. Stateless, no sessions/cookies, no CSRF. Validates Bearer tokens against `issuer-uri: http://localhost:9000` (auto-fetches JWKS).
- **ccResourceClient** (`:8080`) — Public-facing edge. All endpoints `permitAll`; it does not authenticate end users. Uses `OAuth2AuthorizedClientManager` + `client_credentials` provider to acquire tokens, then a `RestClient` with `OAuth2ClientHttpRequestInterceptor` (registration id `resource-server`) to call ccResourceServer.

### Two configuration classes in ccOauthServer — keep them separate

- `WebSecurityConfig` owns **all three** `SecurityFilterChain` beans (actuator @Order 0, authorization server @Order 1, default formLogin @Order 2).
- `AuthorizationServerConfig` owns OAuth domain beans (e.g. `OAuth2TokenCustomizer` that injects `role` / `authorities` claims based on `clientId`).

**Critical Boot 4 pitfall**: `OAuth2AuthorizationServerWebSecurityConfiguration` is guarded by `@ConditionalOnDefaultWebSecurity`. As soon as **any** user-defined `SecurityFilterChain` exists, Boot's auto-configured chains (both the AS chain and the default chain) are dropped as a set. That is why all three chains must be declared explicitly here — see `clientCredentials/server/ccOauthServer/docs/05_why-three-security-filter-chains.md` and `02_security-filter-chain-pitfall.md` for the full reasoning. Do not refactor any of these chains away without re-reading those notes.

### Token customization

`AuthorizationServerConfig#jwtTokenCustomizer` adds `role` and `authorities` claims keyed off `clientId` (`admin-client` → admin, `member-client` → member). Downstream services that need role-based authorization should consume these claims rather than relying on `scope` alone.

### Existing reference docs (read before changing related code)

- `clientCredentials/server/ccOauthServer/docs/01_..08_*.md` — deep-dive notes on AS client config, the three-chain pattern, actuator chain rationale, OIDC configurer internals, and Boot 4 AS autoconfig. Written in Traditional Chinese.
- `clientCredentials/client/ccResourceClient/docs/OAuth2AuthorizedClientManager-with-RestClient.md` — explains the client-side manager + interceptor wiring used in `RestClientConfig`.
- `clientCredentials/server/ccResourceServer/docs/API_UpdateParameter_V2.md` — API contract for the sample endpoint.
- `docs/09_oauth-2.1-overview.md` — repo-wide OAuth 2.1 overview shared across grant-type demos.

## PKCE demo (authorization_code + PKCE)

The `pkce/` tree mirrors `clientCredentials/`. Differences worth knowing:

- **pkceOauthServer** has an extra `UserConfig` (InMemoryUserDetailsManager with `user/password` and `admin/admin`), because authorization_code requires a real end-user login. Its `AuthorizationServerConfig#jwtTokenCustomizer` derives `role` / `authorities` from the **user's** `GrantedAuthority`, not the `clientId`.
- The registered client `web-client` is a **public client**: `client-authentication-methods: none`, `require-proof-key: true`, `require-authorization-consent: true`.
- **pkceResourceServer** adds a `GET /me` endpoint that returns the JWT's `subject / scopes / role / authorities / clientId / issuer / expiresAt`. This is the core comparison point with cc demo: PKCE subject is a user; cc subject is a client id.
- **pkceResourceClient** uses Thymeleaf + `oauth2Login()`. Spring Security automatically performs PKCE because the client registration sets `client-authentication-method: none`. The `RestClient` uses `DefaultOAuth2AuthorizedClientManager` + `authorizationCode()` provider so calls to the resource server carry the **logged-in user's** access token.

### Running pkce demo

Start order (same reasons as cc):
```bash
cd pkce/server/pkceOauthServer    && ./mvnw spring-boot:run    # → :9000
cd pkce/server/pkceResourceServer && ./mvnw spring-boot:run    # → :8081
cd pkce/client/pkceResourceClient && ./mvnw spring-boot:run    # → :8080
```

Manual smoke test (browser):
1. Open http://localhost:8080/ → redirects to login on :9000
2. Log in (`user / password` or `admin / admin`) → consent → back to /
3. Click `/me`: confirm `subject` is the username, `role` matches
4. Click `UpdateParameter`: `Param_Type=01` → `code: 0`; `00` → `code: -1`

No shell-based smoke test for pkce (the browser interaction makes pure-curl tedious).

### PKCE reference docs

- `pkce/server/pkceOauthServer/docs/01_pkce-client-config.md`
- `pkce/server/pkceOauthServer/docs/02_user-store-and-formlogin.md`
- `pkce/server/pkceOauthServer/docs/03_token-customizer-user-vs-client.md`
- `pkce/server/pkceResourceServer/docs/01_me-endpoint.md`
- `pkce/client/pkceResourceClient/docs/01_oauth2-login-and-pkce.md`
- `pkce/client/pkceResourceClient/docs/02_authorized-client-manager-with-user-token.md`