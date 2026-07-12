package com.picsou.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * RFC 7591 Dynamic Client Registration response ({@code 201 Created} body). Deliberately has no
 * {@code client_secret} / {@code client_secret_expires_at} field — every client registered through
 * this endpoint is a public PKCE client (see {@code com.picsou.config.DynamicClientRegistrationController}),
 * so no secret is ever generated.
 */
public record ClientRegistrationResponse(
    @JsonProperty("client_id") String clientId,
    @JsonProperty("client_id_issued_at") Instant clientIdIssuedAt,
    @JsonProperty("redirect_uris") List<String> redirectUris,
    @JsonProperty("token_endpoint_auth_method") String tokenEndpointAuthMethod,
    @JsonProperty("grant_types") List<String> grantTypes,
    @JsonProperty("scope") String scope
) {}
