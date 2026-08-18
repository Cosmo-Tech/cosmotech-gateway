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
  )

  data class CsmIdentityProvider(

      /** Server base Url for identity provider (without / at the end) */
      val serverBaseUrl: String = "",

      /** Identity available during run */
      val identity: CsmIdentity,

      /** Authorization Grant Type */
      val authorizationGrantType: String = "authorization_code",

      /** List of client scope */
      val scopes: List<String> = listOf("openid"),
  ) {
    data class CsmIdentity(

        /** Tenant/realm's identifier: default cosmotech */
        val tenantId: String = "cosmotech",

        /** Client identifier: default cosmotech-api-client */
        val clientId: String = "cosmotech-gateway-client",

        /** Client secret */
        val clientSecret: String,
    )
  }
}
