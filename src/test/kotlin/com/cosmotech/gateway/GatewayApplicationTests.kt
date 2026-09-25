// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway

import com.cosmotech.gateway.config.OpenApiConfig
import io.swagger.v3.oas.models.security.SecurityScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GatewayApplicationTests {

  @Test
  fun `gateway OpenAPI uses the configured identity provider`() {
    val authorizationUrl =
        "https://identity.example.com/realms/cosmotech/protocol/openid-connect/auth"
    val tokenUrl = "https://identity.example.com/realms/cosmotech/protocol/openid-connect/token"
    val openApi =
        OpenApiConfig(
                authorizationUrl = authorizationUrl,
                tokenUrl = tokenUrl,
                configuredScopes = listOf("openid", "profile"),
            )
            .gatewayOpenApi()
    val securityScheme =
        requireNotNull(openApi.components.securitySchemes[OpenApiConfig.SECURITY_SCHEME_NAME])
    val authorizationCodeFlow = securityScheme.flows.authorizationCode

    assertEquals(SecurityScheme.Type.OAUTH2, securityScheme.type)
    assertEquals(
        authorizationUrl,
        authorizationCodeFlow.authorizationUrl,
    )
    assertEquals(
        tokenUrl,
        authorizationCodeFlow.tokenUrl,
    )
    assertEquals(setOf("openid", "profile"), authorizationCodeFlow.scopes.keys)
  }
}
