// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.filters.telemetry

import com.cosmotech.gateway.config.CsmGatewayProperties
import com.cosmotech.gateway.constants.ACCESS_LOG_USER
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.WebFilterChainProxy
import org.springframework.web.server.WebHandler
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import reactor.core.publisher.Mono

class SecurityUserCaptureWebFilterTests {

  @Test
  fun `default principal claim is preferred_username`() {
    assertEquals("alice", capturedUser(mapOf("preferred_username" to "alice")))
  }

  @Test
  fun `configured principal claim is used instead of the default`() {
    assertEquals(
        "alice@example.com",
        capturedUser(
            mapOf("email" to "alice@example.com", "preferred_username" to "alice"),
            CsmGatewayProperties.Gateway.CsmAuthorization(principal = "email"),
        ),
    )
  }

  @Test
  fun `token without the configured principal claim is stored as anonymous`() {
    assertEquals("anonymous", capturedUser(mapOf("sub" to "user-123")))
  }

  @Test
  fun `public request without credentials stays anonymous`() {
    assertEquals("anonymous", capturedUser(null))
  }

  private fun capturedUser(
      claims: Map<String, Any>?,
      authorization: CsmGatewayProperties.Gateway.CsmAuthorization =
          CsmGatewayProperties.Gateway.CsmAuthorization(),
  ): String? {
    val properties =
        CsmGatewayProperties(
            CsmGatewayProperties.Gateway(
                contextPath = "/",
                port = 8060,
                identityProvider =
                    CsmGatewayProperties.CsmIdentityProvider(
                        identity = CsmGatewayProperties.CsmIdentityProvider.CsmIdentity(),
                    ),
                authorization = authorization,
            ),
        )
    val decoder = ReactiveJwtDecoder {
      Mono.just(
          Jwt.withTokenValue("test-token")
              .header("alg", "RS256")
              .claims { it.putAll(requireNotNull(claims)) }
              .build(),
      )
    }
    val security =
        ServerHttpSecurity.http()
            .authorizeExchange { it.anyExchange().permitAll() }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtDecoder(decoder) } }
            .build()
    var user: String? = null
    val handler =
        WebHttpHandlerBuilder.webHandler(
                WebHandler {
                  user = it.getAttribute(ACCESS_LOG_USER)
                  Mono.empty()
                },
            )
            .filter(WebFilterChainProxy(security), SecurityUserCaptureWebFilter(properties))
            .build()
    val request = MockServerHttpRequest.get("/api/items")
    if (claims != null) request.header("Authorization", "Bearer test-token")
    val exchange = MockServerWebExchange.from(request)
    handler.handle(exchange.request, exchange.response).block()
    return user
  }
}
