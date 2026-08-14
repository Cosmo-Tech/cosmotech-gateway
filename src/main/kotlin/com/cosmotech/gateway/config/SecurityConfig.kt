// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

  // Public endpoints
  val endpointSecurityPublic =
      listOf(
          "/about",
      )

  @Bean
  fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain? {
    http
        .csrf { csrfConfigurer -> csrfConfigurer.disable() }
        .authorizeExchange { exchange ->
          exchange
              .matchers(
                  ServerWebExchangeMatchers.pathMatchers(HttpMethod.OPTIONS, "/**"),
              )
              .permitAll()

          endpointSecurityPublic.forEach { path ->
            exchange
                .matchers(
                    ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, path),
                )
                .permitAll()
          }
          exchange.anyExchange().authenticated()
        }
        .oauth2Login(Customizer.withDefaults())
        .oauth2ResourceServer { oauth2 ->
          oauth2!!.jwt(Customizer.withDefaults())
        }

    return http.build()
  }

  @Bean
  fun reactiveJwtDecoder(
      @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") jwkSetUri: String
  ): ReactiveJwtDecoder {
    return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
  }
}
