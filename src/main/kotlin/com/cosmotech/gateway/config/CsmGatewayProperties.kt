// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Configuration Properties for the Cosmo Tech Gateway */
@ConfigurationProperties(prefix = "csm.platform")
class CsmGatewayProperties(

    /** Gateway Configuration */
    val gateway: Gateway,
) {

  data class Gateway(

      /** Gateway path */
      val contextPath: String,

      /** Gateway port */
      val port: Int,

      /** Identity provider configuration */
      val identityProvider: CsmIdentityProvider,

      /** OpenAPI normalization applied to documents exposed by the gateway */
      val openApiAggregation: OpenApiAggregation = OpenApiAggregation(),
  )

  data class OpenApiAggregation(
      /** Enable central OpenAPI normalization */
      val enabled: Boolean = true,

      /** Public Gateway API server URLs keyed by OpenAPI document route */
      val serverUrls: Map<String, String> = emptyMap(),
  )

  data class CsmIdentityProvider(

      /** Server base Url for identity provider (without / at the end) */
      val serverBaseUrl: String = "",

      /** Identity available during run */
      val identity: CsmIdentity,
  ) {
    data class CsmIdentity(

        /** Tenant/realm's identifier: default cosmotech */
        val tenantId: String = "cosmotech",

        /** Client identifier: default cosmotech-api-client */
        val clientId: String = "cosmotech-client-gateway",
    )
  }
}
