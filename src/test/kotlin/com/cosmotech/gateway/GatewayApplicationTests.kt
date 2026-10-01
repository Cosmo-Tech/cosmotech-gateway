// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway

import com.cosmotech.gateway.config.CsmGatewayProperties
import com.cosmotech.gateway.config.OpenApiConfig
import com.cosmotech.gateway.config.OpenApiDocumentNormalizer
import io.swagger.v3.oas.models.security.SecurityScheme
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ByteArrayResource
import tools.jackson.databind.ObjectMapper
import tools.jackson.dataformat.yaml.YAMLFactory

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

  @Test
  fun `aggregated OpenAPI uses gateway server and OAuth scheme`() {
    val normalized =
        normalizer()
            .normalize(
                "/openapi/api",
                downstreamDocument().toByteArray(),
                org.springframework.http.MediaType.APPLICATION_YAML,
            )
    val result = ObjectMapper(YAMLFactory()).readTree(normalized)

    assertEquals("/tenant/gateway-api", result["servers"][0]["url"].stringValue())
    assertEquals(
        setOf(OpenApiConfig.SECURITY_SCHEME_NAME),
        result["components"]["securitySchemes"].propertyNames().toSet(),
    )
    assertEquals(
        "oauth2",
        result["components"]["securitySchemes"][OpenApiConfig.SECURITY_SCHEME_NAME]["type"]
            .stringValue(),
    )
    assertEquals(
        true,
        result["security"][0].has(OpenApiConfig.SECURITY_SCHEME_NAME),
    )
    assertEquals(
        true,
        result["paths"]["/items"]["get"]["security"][0].has(OpenApiConfig.SECURITY_SCHEME_NAME),
    )
  }

  private fun normalizer(): OpenApiDocumentNormalizer {
    val identityProvider =
        CsmGatewayProperties.CsmIdentityProvider(
            identity =
                CsmGatewayProperties.CsmIdentityProvider.CsmIdentity(
                    tenantId = "tenant",
                    clientId = "gateway-client",
                ),
        )
    val properties =
        CsmGatewayProperties(
            CsmGatewayProperties.Gateway(
                contextPath = "/tenant",
                port = 8060,
                identityProvider = identityProvider,
                openApiAggregation =
                    CsmGatewayProperties.OpenApiAggregation(
                        serverUrls = mapOf("/openapi/api" to "/tenant/gateway-api"),
                    ),
            ),
        )
    val gatewayOpenApi =
        OpenApiConfig(
                authorizationUrl = "https://identity.example.com/auth",
                tokenUrl = "https://identity.example.com/token",
                configuredScopes = listOf("openid", "profile"),
            )
            .gatewayOpenApi()
    return OpenApiDocumentNormalizer(properties, gatewayOpenApi)
  }

  private fun downstreamDocument(): String =
      """
      openapi: 3.0.3
      info:
          title: downstream
          version: 1.0.0
      servers:
          - url: http://api.default.svc.cluster.local:8080/api
      components:
          securitySchemes:
              upstreamBearer:
                  type: http
                  scheme: bearer
      paths:
          /items:
              get:
                  security:
                      - upstreamBearer: []
                  responses:
                      '200':
                          description: OK
      """
          .trimIndent()

  @Test
  fun `OpenAPI server URL map binds route keys containing slashes`() {
    val yaml =
        """
        csm:
            platform:
                gateway:
                    contextPath: /
                    port: 8060
                    identityProvider:
                        identity:
                            clientId: gateway-client
                    openApiAggregation:
                        serverUrls:
                            "[/openapi/api]": /tenant/gateway-api
        """
            .trimIndent()
    val propertySource =
        YamlPropertySourceLoader().load("test", ByteArrayResource(yaml.toByteArray())).first()
    val configurationPropertySources =
        ConfigurationPropertySources.from(propertySource).filterNotNull()
    val properties =
        Binder(configurationPropertySources)
            .bind("csm.platform", Bindable.of(CsmGatewayProperties::class.java))
            .get()

    assertEquals(
        mapOf("/openapi/api" to "/tenant/gateway-api"),
        properties.gateway.openApiAggregation.serverUrls,
    )
  }
}
