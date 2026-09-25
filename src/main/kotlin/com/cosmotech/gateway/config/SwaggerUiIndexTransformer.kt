// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import java.nio.charset.StandardCharsets
import org.springdoc.core.properties.SwaggerUiConfigProperties
import org.springdoc.core.properties.SwaggerUiOAuthProperties
import org.springdoc.core.providers.ObjectMapperProvider
import org.springdoc.webflux.ui.SwaggerIndexPageTransformer
import org.springdoc.webflux.ui.SwaggerWelcomeCommon
import org.springframework.core.io.Resource
import org.springframework.stereotype.Component
import org.springframework.web.reactive.resource.ResourceTransformerChain
import org.springframework.web.reactive.resource.TransformedResource
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * Customizes the Swagger UI OAuth dialog for the gateway's centrally managed authentication.
 *
 * The OAuth client ID and scopes are fixed by the gateway configuration and are not choices that
 * users should have to review or edit. The client is public and uses authorization code with PKCE,
 * so no client secret is sent to the browser. This transformer therefore hides the preconfigured
 * client fields and scope controls while keeping Swagger UI's standard Authorize action intact.
 *
 * The customization is appended to springdoc's generated `swagger-initializer.js` instead of
 * replacing Swagger UI's OAuth implementation.
 */
@Component
class SwaggerUiIndexTransformer(
    swaggerUiConfig: SwaggerUiConfigProperties,
    swaggerUiOAuthProperties: SwaggerUiOAuthProperties,
    swaggerWelcomeCommon: SwaggerWelcomeCommon,
    objectMapperProvider: ObjectMapperProvider,
) :
    SwaggerIndexPageTransformer(
        swaggerUiConfig,
        swaggerUiOAuthProperties,
        swaggerWelcomeCommon,
        objectMapperProvider,
    ) {

  override fun transform(
      exchange: ServerWebExchange,
      resource: Resource,
      resourceTransformerChain: ResourceTransformerChain,
  ): Mono<Resource> =
      super.transform(exchange, resource, resourceTransformerChain).map { transformedResource ->
        if (transformedResource.filename != SWAGGER_INITIALIZER) {
          transformedResource
        } else {
          val initializer =
              transformedResource.inputStream.bufferedReader(StandardCharsets.UTF_8).use {
                it.readText()
              }
          TransformedResource(
              transformedResource,
              "$initializer\n$OAUTH_DIALOG_STYLE_SCRIPT".toByteArray(StandardCharsets.UTF_8),
          )
        }
      }

  companion object {
    private const val SWAGGER_INITIALIZER = "swagger-initializer.js"
    private val OAUTH_DIALOG_STYLE_SCRIPT =
        """
        (() => {
          const style = document.createElement("style");
          style.textContent = `
            .auth-container .scope-def,
            .auth-container .wrapper:has(input[data-name="clientId"]),
            .auth-container .wrapper:has(input[data-name="clientSecret"]),
            .auth-container .scopes {
              display: none;
            }
          `;
          document.head.appendChild(style);
        })();
        """
            .trimIndent()
  }
}
