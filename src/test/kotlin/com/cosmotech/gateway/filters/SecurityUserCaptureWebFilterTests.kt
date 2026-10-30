// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.filters

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.context.SecurityContextImpl
import reactor.core.publisher.Mono

class SecurityUserCaptureWebFilterTests {

  private val filter = SecurityUserCaptureWebFilter()

  @Test
  fun `authenticated user name is stored in the exchange attributes`() {
    val authentication =
        UsernamePasswordAuthenticationToken.authenticated("alice", null, emptyList())
    val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"))

    filter
        .filter(exchange) { Mono.empty() }
        .contextWrite(
            ReactiveSecurityContextHolder.withSecurityContext(
                Mono.just(SecurityContextImpl(authentication)),
            ),
        )
        .block()

    assertEquals("alice", exchange.attributes["accessLogUser"])
  }

  @Test
  fun `anonymous request is stored as anonymous`() {
    val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/about"))

    filter.filter(exchange) { Mono.empty() }.block()

    assertEquals("anonymous", exchange.attributes["accessLogUser"])
  }
}
