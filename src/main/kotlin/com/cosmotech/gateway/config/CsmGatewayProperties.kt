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

      /** Authorization configuration */
      val authorization: CsmAuthorization = CsmAuthorization(),

      /** CORS configuration */
      val cors: Cors = Cors(),
  ) {
    data class CsmAuthorization(
        val principal: String = "preferred_username",

        /** Name of the session cookie, also used as server.reactive.session.cookie.name */
        val cookieName: String = "SESSION",
    )

    data class Cors(

        /** Origins allowed to make credentialed cross-origin requests (no wildcard) */
        val allowedOrigins: List<String> =
            listOf(
                "http://localhost:3000",
                "http://localhost:8060",
                "http://localhost:8080",
            ),

        /** HTTP methods allowed for cross-origin requests */
        val allowedMethods: List<String> =
            listOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),

        /** Request headers allowed for cross-origin requests */
        val allowedHeaders: List<String> = listOf("*"),

        /** Response headers exposed to the browser */
        val exposedHeaders: List<String> = listOf("Location", "WWW-Authenticate"),

        /** Whether credentials (cookies, Authorization) are allowed cross-origin */
        val allowCredentials: Boolean = true,

        /** Preflight response cache duration, in seconds */
        val maxAge: Long = 3600,
    )
  }

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
