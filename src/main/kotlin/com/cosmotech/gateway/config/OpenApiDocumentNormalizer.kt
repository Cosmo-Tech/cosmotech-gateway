// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLFactory

/**
 * Normalizes downstream OpenAPI documents with the Gateway server URL and OAuth2 security scheme.
 */
@Component
@EnableConfigurationProperties(CsmGatewayProperties::class)
class OpenApiDocumentNormalizer(
    private val csmGatewayProperties: CsmGatewayProperties,
    @Qualifier("gatewayOpenApi") gatewayOpenApi: OpenAPI,
) {
  private val yamlMapper = ObjectMapper(YAMLFactory())
  private val jsonMapper = ObjectMapper()
  private val sharedSecurityScheme =
      gatewayOpenApi.components.securitySchemes[OpenApiConfig.SECURITY_SCHEME_NAME]
          .asOpenApiOAuthScheme()

  fun normalize(path: String, content: ByteArray, contentType: MediaType?): ByteArray {
    val document = yamlMapper.readTree(content).asObjectOrNull() ?: return content
    if (!document.path("openapi").isString) return content

    csmGatewayProperties.gateway.openApiAggregation.serverUrls[path]?.let { serverUrl ->
      val servers = yamlMapper.createArrayNode()
      servers.addObject().put("url", serverUrl)
      document.set("servers", servers)
    }

    val components =
        document.get("components").asObjectOrNull()
            ?: yamlMapper.createObjectNode().also { document.set("components", it) }

    val securitySchemes =
        yamlMapper.createObjectNode().also {
          components.set("securitySchemes", it)
        }

    securitySchemes.set(
        OpenApiConfig.SECURITY_SCHEME_NAME,
        sharedSecurityScheme.deepCopy(),
    )

    document.set("security", securityRequirement())
    document.path("paths").properties().forEach { (_, pathItem) ->
      pathItem.asObjectOrNull()?.let { pathItemObject ->
        HttpMethod.values().forEach { method ->
          val operation = pathItemObject.get(method.name().lowercase()).asObjectOrNull()
          operation?.set("security", securityRequirement())
        }
      }
    }

    val outputMapper =
        if (contentType?.subtype?.contains("json", ignoreCase = true) == true) {
          jsonMapper
        } else {
          yamlMapper
        }
    return outputMapper.writeValueAsBytes(document)
  }

  private fun securityRequirement(): ArrayNode {
    val requirement = yamlMapper.createObjectNode()
    requirement.set(OpenApiConfig.SECURITY_SCHEME_NAME, yamlMapper.createArrayNode())
    return yamlMapper.createArrayNode().add(requirement)
  }

  private fun SecurityScheme?.asOpenApiOAuthScheme(): ObjectNode {
    val scheme = requireNotNull(this) { "Gateway OAuth2 security scheme is not configured" }
    require(scheme.type == SecurityScheme.Type.OAUTH2) {
      "OpenAPI aggregation requires an OAuth2 security scheme"
    }
    val authorizationCode =
        requireNotNull(scheme.flows?.authorizationCode) {
          "OpenAPI aggregation requires an authorization-code flow"
        }
    val normalized = yamlMapper.createObjectNode().put("type", "oauth2")
    scheme.description?.let { normalized.put("description", it) }
    val flow = normalized.putObject("flows").putObject("authorizationCode")
    flow.put("authorizationUrl", authorizationCode.authorizationUrl)
    flow.put("tokenUrl", authorizationCode.tokenUrl)
    authorizationCode.refreshUrl?.let { flow.put("refreshUrl", it) }
    val scopes = flow.putObject("scopes")
    authorizationCode.scopes?.forEach { (name, description) -> scopes.put(name, description) }
    return normalized
  }

  private fun JsonNode?.asObjectOrNull(): ObjectNode? = this?.asObjectOpt()?.orElse(null)
}
