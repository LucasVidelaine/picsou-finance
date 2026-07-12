package com.picsou.config;

import com.picsou.mcp.Scopes;
import com.picsou.model.AppSetting;
import com.picsou.model.SetupState;
import com.picsou.repository.AppSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Task 8: RFC 7591 Dynamic Client Registration at {@code POST /oauth2/register}, unauthenticated.
 * Boots the full application context + real Postgres via Testcontainers (mirroring
 * {@code AuthorizationServerConfigTest}) since a real {@code JdbcRegisteredClientRepository} against
 * the V54 schema is the point — an in-memory stand-in would not exercise the same write path a
 * dynamically-registered claude.ai client relies on.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DynamicClientRegistrationControllerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void secrets(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "test-jwt-secret-test-jwt-secret-0123456789");
        registry.add("app.crypto.encryption-key", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Autowired MockMvc mockMvc;
    @Autowired AppSettingRepository appSettingRepository;
    @Autowired RegisteredClientRepository registeredClientRepository;

    /** Same trap Task 6 hit: SetupFilter 503s every request until setup is COMPLETE. */
    @BeforeEach
    void completeSetup() {
        appSettingRepository.save(AppSetting.builder()
            .key("setup.state")
            .value(SetupState.COMPLETE.name())
            .build());
    }

    @Test
    void happyPath_persistsAPublicPkceMcpFlaggedClient_andReturns201() throws Exception {
        String body = """
            {"client_name":"claude.ai","redirect_uris":["https://claude.ai/api/mcp/auth_callback"],
             "scope":"accounts:read goals:read"}
            """;

        Instant before = Instant.now();
        String response = mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.client_id").exists())
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
            .andExpect(jsonPath("$.redirect_uris[0]").value("https://claude.ai/api/mcp/auth_callback"))
            .andExpect(jsonPath("$.grant_types", org.hamcrest.Matchers.containsInAnyOrder(
                "authorization_code", "refresh_token")))
            // RFC 7591 §3.2.1: client_id_issued_at is a JSON number of epoch seconds, not an
            // ISO-8601 string — assert both the JSON type and that it's a sane recent timestamp.
            .andExpect(jsonPath("$.client_id_issued_at").isNumber())
            .andReturn().getResponse().getContentAsString();
        Instant after = Instant.now();

        Number issuedAtEpochSecond = com.jayway.jsonpath.JsonPath.read(response, "$.client_id_issued_at");
        assertThat(issuedAtEpochSecond.longValue())
            .isGreaterThanOrEqualTo(before.getEpochSecond())
            .isLessThanOrEqualTo(after.getEpochSecond());

        String clientId = com.jayway.jsonpath.JsonPath.read(response, "$.client_id");
        RegisteredClient persisted = registeredClientRepository.findByClientId(clientId);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(persisted.getScopes()).containsExactlyInAnyOrder("accounts:read", "goals:read");
        assertThat(persisted.getRedirectUris()).containsExactly("https://claude.ai/api/mcp/auth_callback");
        assertThat(persisted.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(persisted.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(persisted.getClientSettings().<Boolean>getSetting(AuthorizationServerConfig.MCP_CLIENT_SETTING))
            .isTrue();
    }

    @Test
    void noScopeRequested_defaultsToEveryReadScope() throws Exception {
        String body = """
            {"redirect_uris":["https://claude.ai/api/mcp/auth_callback"]}
            """;

        String response = mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        String clientId = com.jayway.jsonpath.JsonPath.read(response, "$.client_id");
        RegisteredClient persisted = registeredClientRepository.findByClientId(clientId);
        java.util.Set<String> expectedDefaults = Scopes.ALL.stream()
            .filter(s -> s.endsWith(":read") || s.endsWith("-read"))
            .collect(java.util.stream.Collectors.toSet());
        assertThat(persisted.getScopes()).containsExactlyInAnyOrderElementsOf(expectedDefaults);
    }

    @Test
    void emptyRedirectUris_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":[]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void malformedRedirectUri_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["not a valid uri with spaces and no scheme"]}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void scopeOutsideAllowlist_returns400() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["https://claude.ai/callback"],"scope":"admin:god-mode"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void anyRequestedAuthMethod_isCoercedToNone() throws Exception {
        mockMvc.perform(post("/oauth2/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"redirect_uris":["https://claude.ai/callback"],
                     "token_endpoint_auth_method":"client_secret_basic"}
                    """))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"));
    }
}
