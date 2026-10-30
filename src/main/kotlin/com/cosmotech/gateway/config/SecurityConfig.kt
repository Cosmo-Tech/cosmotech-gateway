// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.web.server.DelegatingServerAuthenticationEntryPoint
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.ServerAuthenticationEntryPoint
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationEntryPoint
import org.springframework.security.web.server.util.matcher.MediaTypeServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.NegatedServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsConfigurationSource
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebFluxSecurity
class SecurityConfig(private val properties: CsmGatewayProperties) {

  // Public endpoints
  val endpointSecurityPublic =
      listOf(
          "/about",
          "/actuator/prometheus",
          "/actuator/health/**",
          "/actuator/info",
          "/swagger-ui.html",
          "/swagger-ui/**",
          "/v3/api-docs",
          "/v3/api-docs/**",
          "/openapi/**",
          "/*/.well-known/oauth-protected-resource/**",
      )

  // API clients get 401 with WWW-Authenticate: Bearer; browsers keep the login redirect.
  // A wildcard Accept (*/*) is not a browser navigation, so it is ignored when matching text/html.
  private val browserNavigationMatcher =
      MediaTypeServerWebExchangeMatcher(MediaType.TEXT_HTML).apply {
        setIgnoredMediaTypes(setOf(MediaType.ALL))
      }

  private val authenticationEntryPoint =
      DelegatingServerAuthenticationEntryPoint(
              DelegatingServerAuthenticationEntryPoint.DelegateEntry(
                  NegatedServerWebExchangeMatcher(browserNavigationMatcher),
                  ServerAuthenticationEntryPoint { exchange, _ ->
                    exchange.response.statusCode = HttpStatus.UNAUTHORIZED
                    exchange.response.headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
                    exchange.response.setComplete()
                  },
              ),
          )
          .apply {
            setDefaultEntryPoint(
                RedirectServerAuthenticationEntryPoint("/oauth2/authorization/keycloak-client"),
            )
          }

  // CSRF only applies to unsafe requests that authenticated by the session cookie. Requests with
  // an Authorization header are exempt, even when a session cookie is also sent (e.g. Swagger UI).
  private val sessionCookieCsrfMatcher = ServerWebExchangeMatcher { exchange ->
    val unsafeMethod =
        exchange.request.method !in
            listOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE)
    val hasBearer =
        exchange.request.headers.getFirst(HttpHeaders.AUTHORIZATION)?.startsWith("Bearer ", true) ==
            true
    if (
        unsafeMethod &&
            !hasBearer &&
            exchange.request.cookies.containsKey(properties.gateway.authorization.cookieName)
    ) {
      ServerWebExchangeMatcher.MatchResult.match()
    } else {
      ServerWebExchangeMatcher.MatchResult.notMatch()
    }
  }

  @Bean
  fun springSecurityFilterChain(
      http: ServerHttpSecurity,
      corsConfigurationSource: CorsConfigurationSource,
  ): SecurityWebFilterChain? {
    http
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
        .cors { cors -> cors.configurationSource(corsConfigurationSource) }
        .csrf { csrf -> csrf.requireCsrfProtectionMatcher(sessionCookieCsrfMatcher) }
        .exceptionHandling { exceptions ->
          exceptions.authenticationEntryPoint(authenticationEntryPoint)
        }
        .oauth2Login(Customizer.withDefaults())
        .oauth2ResourceServer { oauth2 ->
          oauth2!!.jwt(Customizer.withDefaults())
        }

    return http.build()
  }

  // Explicit origins only: credentials are allowed, so a wildcard origin is not an option.
  @Bean
  fun corsConfigurationSource(properties: CsmGatewayProperties): CorsConfigurationSource {
    val cors = properties.gateway.cors
    val configuration =
        CorsConfiguration().apply {
          allowedOrigins = cors.allowedOrigins
          allowedMethods = cors.allowedMethods
          allowedHeaders = cors.allowedHeaders
          exposedHeaders = cors.exposedHeaders
          allowCredentials = cors.allowCredentials
          maxAge = cors.maxAge
        }
    return UrlBasedCorsConfigurationSource().apply {
      registerCorsConfiguration("/**", configuration)
    }
  }

  @Bean
  fun reactiveJwtDecoder(
      @Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") jwkSetUri: String,
  ): ReactiveJwtDecoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
}
