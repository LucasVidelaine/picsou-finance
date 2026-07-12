package com.picsou.config;

import com.picsou.dto.ClientRegistrationRequest;
import com.picsou.dto.ClientRegistrationResponse;
import com.picsou.mcp.Scopes;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * RFC 7591 Dynamic Client Registration: {@code POST /oauth2/register}.
 *
 * <p>Unauthenticated by design — this is how a remote-MCP client (claude.ai) self-registers before
 * the OAuth handshake starts, exactly like {@link ProtectedResourceMetadataController}. Spring
 * Authorization Server 1.4.5 has no built-in (non-OIDC) client-registration endpoint, so this is a
 * plain controller writing directly to the shared {@link RegisteredClientRepository} (the same Jdbc
 * repository {@link AuthorizationServerConfig} seeds {@code picsou-ios} into) rather than a
 * configured {@code clientRegistrationEndpoint()} — which would fold registration into the AS's own
 * securityMatcher chain and require its own authentication story.
 *
 * <p>Every client created here is deliberately narrow:
 * <ul>
 *   <li><b>Public, PKCE-only.</b> {@code client_authentication_method} is always coerced to
 *       {@code none} — no client secret is ever generated, regardless of what the request asks for.</li>
 *   <li><b>Fixed grant types.</b> Always {@code authorization_code} + {@code refresh_token}; the
 *       request's {@code grant_types} (if any) is accepted for RFC shape but ignored.</li>
 *   <li><b>Consent required.</b> Unlike {@code picsou-ios} (first-party, no consent), every
 *       DCR-registered client requires the interactive consent screen (Task 10/11).</li>
 *   <li><b>Flagged MCP.</b> Carries the {@link AuthorizationServerConfig#MCP_CLIENT_SETTING} setting
 *       so {@link AuthorizationServerConfig#jwtTokenCustomizer()} mints the MCP claim shape
 *       ({@code type=mcp}, {@code aud=picsou-mcp}) instead of the first-party one.</li>
 *   <li><b>Scope-limited.</b> Requested scopes must be a subset of {@link Scopes#ALL}; an unknown
 *       scope is rejected outright rather than silently dropped.</li>
 * </ul>
 */
@RestController
public class DynamicClientRegistrationController {

    private static final List<String> GRANT_TYPES = List.of("authorization_code", "refresh_token");

    private final RegisteredClientRepository registeredClientRepository;

    public DynamicClientRegistrationController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @PostMapping("/oauth2/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ClientRegistrationResponse register(@RequestBody ClientRegistrationRequest request) {
        List<String> redirectUris = validateRedirectUris(request.redirectUris());
        Set<String> scopes = resolveScopes(request.scope());

        String clientId = UUID.randomUUID().toString();
        Instant issuedAt = Instant.now();
        String clientName = (request.clientName() == null || request.clientName().isBlank())
            ? "Remote MCP client" : request.clientName();

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(clientId)
            .clientIdIssuedAt(issuedAt)
            .clientName(clientName)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
            .scopes(s -> s.addAll(scopes))
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)                // PKCE (S256) required — no client secret exists
                .requireAuthorizationConsent(true)     // third-party client → interactive consent
                .setting(AuthorizationServerConfig.MCP_CLIENT_SETTING, true)
                .build());
        redirectUris.forEach(builder::redirectUri);

        registeredClientRepository.save(builder.build());

        return new ClientRegistrationResponse(
            clientId,
            issuedAt,
            redirectUris,
            "none",
            GRANT_TYPES,
            String.join(" ", scopes)
        );
    }

    /**
     * RFC 7591 requires at least one redirect URI for the {@code authorization_code} grant.
     * {@link RegisteredClient.Builder}'s own validation silently accepts an empty/absent set (only
     * per-element checks run, and an empty collection short-circuits before the loop), so emptiness
     * must be rejected here. Each URI must be absolute (has a scheme — matches the existing
     * {@code picsou://callback} custom-scheme convention) and fragment-free, mirroring the
     * framework's own {@code RegisteredClient.Builder} redirect-URI rule.
     */
    private List<String> validateRedirectUris(List<String> redirectUris) {
        if (redirectUris == null || redirectUris.isEmpty()) {
            throw new IllegalArgumentException("redirect_uris must contain at least one URI");
        }
        for (String uri : redirectUris) {
            if (!isWellFormedAbsoluteUri(uri)) {
                throw new IllegalArgumentException("Malformed redirect_uris entry: " + uri);
            }
        }
        return redirectUris;
    }

    private boolean isWellFormedAbsoluteUri(String uri) {
        if (uri == null || uri.isBlank()) {
            return false;
        }
        try {
            URI parsed = new URI(uri);
            return parsed.isAbsolute() && parsed.getFragment() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * No {@code scope} in the request → default to every read-only scope in {@link Scopes#ALL}
     * (the {@code *:read} / {@code *-read} entries only — deliberately excludes the two
     * {@code oauth2:*} meta-scopes, which are about the session itself, not app data). A requested
     * scope outside {@link Scopes#ALL} is rejected rather than silently dropped, matching
     * {@code AccessKeyService#validateScopes}'s convention for the same allowlist.
     */
    private Set<String> resolveScopes(String requestedScope) {
        if (requestedScope == null || requestedScope.isBlank()) {
            return Scopes.ALL.stream()
                .filter(s -> s.endsWith(":read") || s.endsWith("-read"))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        }
        Set<String> requested = new LinkedHashSet<>(List.of(requestedScope.trim().split("\\s+")));
        for (String scope : requested) {
            if (!Scopes.ALL.contains(scope)) {
                throw new IllegalArgumentException("Unknown scope: " + scope);
            }
        }
        return requested;
    }
}
