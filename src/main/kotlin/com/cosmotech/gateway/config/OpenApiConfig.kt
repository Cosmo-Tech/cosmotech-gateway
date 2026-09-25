// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.OAuthFlow
import io.swagger.v3.oas.models.security.OAuthFlows
import io.swagger.v3.oas.models.security.Scopes
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Defines the OAuth2 security scheme exposed by the gateway's OpenAPI description.
 *
 * Swagger UI displays its Authorize action only when the selected OpenAPI document declares a
 * security scheme. The gateway therefore publishes the same authorization-code scheme name used by
 * the aggregated platform services, providing a consistent authentication experience when users
 * switch between definitions.
 *
 * Authorization and token endpoints are injected from Spring Security's resolved OAuth2 provider
 * properties, while scopes come from the Swagger UI OAuth configuration. This keeps OpenAPI aligned
 * with the runtime configuration without rebuilding or duplicating Keycloak URLs. The shared public
 * client uses PKCE without requiring or exposing a client secret.
 */
@Configuration
class OpenApiConfig(
    @Value("\${spring.security.oauth2.client.provider.keycloak.authorization-uri}")
    private val authorizationUrl: String,
    @Value("\${spring.security.oauth2.client.provider.keycloak.token-uri}")
    private val tokenUrl: String,
    @Value("\${springdoc.swagger-ui.oauth.scopes}") private val configuredScopes: List<String>,
) {

  @Bean
  fun gatewayOpenApi(): OpenAPI {
    val scopes = Scopes()
    configuredScopes.forEach { scope -> scopes.addString(scope, "$scope scope") }

    val authorizationCodeFlow =
        OAuthFlow().authorizationUrl(authorizationUrl).tokenUrl(tokenUrl).scopes(scopes)
    val securityScheme =
        SecurityScheme()
            .type(SecurityScheme.Type.OAUTH2)
            .description("OAuth2 authentication")
            .flows(OAuthFlows().authorizationCode(authorizationCodeFlow))

    return OpenAPI()
        .components(Components().addSecuritySchemes(SECURITY_SCHEME_NAME, securityScheme))
  }

  companion object {
    const val SECURITY_SCHEME_NAME = "oAuth2AuthCode"
  }
}
