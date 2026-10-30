// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Date
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockUser
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.RouterFunctions
import org.springframework.web.reactive.function.server.ServerResponse

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityConfigTests {

  private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
  private val untrustedKey: RSAKey = RSAKeyGenerator(2048).keyID("test-key").generate()
  private lateinit var jwksServer: HttpServer
  private val serverUrl = "http://localhost:8080"

  @BeforeAll
  fun startJwksServer() {
    val jwks = JWKSet(signingKey.toPublicJWK()).toString().toByteArray()
    jwksServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
          createContext("/certs") { exchange ->
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, jwks.size.toLong())
            exchange.responseBody.use { it.write(jwks) }
          }
          start()
        }
  }

  @AfterAll
  fun stopJwksServer() {
    jwksServer.stop(0)
  }

  @Test
  fun `unsafe request authenticated by session cookie requires CSRF token`() {
    withClient { client ->
      client
          .call(HttpMethod.POST, "/api/items", session = true, authenticated = true)
          .expectStatus()
          .isForbidden
      client
          .call(HttpMethod.PUT, "/api/items", session = true, authenticated = true)
          .expectStatus()
          .isForbidden
      client
          .call(HttpMethod.DELETE, "/api/items", session = true, authenticated = true)
          .expectStatus()
          .isForbidden
    }
  }

  @Test
  fun `valid bearer token is accepted alongside session cookie without CSRF token`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = bearer(validToken()),
              session = true,
          )
          .expectStatus()
          .isOk
    }
  }

  @Test
  fun `bearer scheme is matched case-insensitively and still exempt from CSRF`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = "bearer ${validToken()}",
              session = true,
          )
          .expectStatus()
          .isOk
    }
  }

  @Test
  fun `invalid bearer token alongside session cookie is rejected with 401 not 403`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = bearer(validToken(key = untrustedKey)),
              session = true,
          )
          .expectStatus()
          .isUnauthorized
    }
  }

  @Test
  fun `expired bearer token alongside session cookie is rejected with 401`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = bearer(validToken(expiresAt = Date(0))),
              session = true,
          )
          .expectStatus()
          .isUnauthorized
    }
  }

  @Test
  fun `empty bearer token alongside session cookie is rejected with 401`() {
    withClient { client ->
      client
          .call(HttpMethod.POST, "/api/items", authorization = "Bearer ", session = true)
          .expectStatus()
          .isUnauthorized
    }
  }

  @Test
  fun `non-bearer authorization header does not bypass CSRF`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = "Basic dXNlcjpwYXNzd29yZA==",
              session = true,
              authenticated = true,
          )
          .expectStatus()
          .isForbidden
    }
  }

  @Test
  fun `authorization value without a space after the scheme does not bypass CSRF`() {
    withClient { client ->
      client
          .call(
              HttpMethod.POST,
              "/api/items",
              authorization = "BearerX${validToken()}",
              session = true,
              authenticated = true,
          )
          .expectStatus()
          .isForbidden
    }
  }

  @Test
  fun `anonymous unsafe request gets 401 with Bearer challenge for API clients`() {
    withClient { client ->
      client
          .call(HttpMethod.POST, "/api/items")
          .expectStatus()
          .isUnauthorized
          .expectHeader()
          .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
    }
  }

  @Test
  fun `anonymous request accepting any media type gets 401 not a login redirect`() {
    withClient { client ->
      client
          .call(HttpMethod.GET, "/api/items", accept = MediaType.ALL)
          .expectStatus()
          .isUnauthorized
          .expectHeader()
          .valueEquals(HttpHeaders.WWW_AUTHENTICATE, "Bearer")
    }
  }

  @Test
  fun `anonymous browser request is redirected to the login page`() {
    withClient { client ->
      client
          .call(HttpMethod.GET, "/api/items", accept = MediaType.TEXT_HTML)
          .expectStatus()
          .isFound
          .expectHeader()
          .valueEquals(HttpHeaders.LOCATION, "/oauth2/authorization/keycloak-client")
    }
  }

  @Test
  fun `safe request authenticated by session cookie does not require CSRF token`() {
    withClient { client ->
      client
          .call(HttpMethod.GET, "/api/items", session = true, authenticated = true)
          .expectStatus()
          .isOk
    }
  }

  @Test
  fun `CORS preflight from an allowed origin is answered without authentication`() {
    withClient { client ->
      client
          .options()
          .uri("$serverUrl/api/items")
          .header(HttpHeaders.ORIGIN, "http://localhost:3000")
          .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
          .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type")
          .exchange()
          .expectStatus()
          .isOk
          .expectHeader()
          .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
          .expectHeader()
          .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
    }
  }

  @Test
  fun `CORS response from an unknown origin carries no allow-origin header`() {
    withClient { client ->
      client
          .options()
          .uri("$serverUrl/api/items")
          .header(HttpHeaders.ORIGIN, "https://evil.example")
          .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
          .exchange()
          .expectHeader()
          .doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
    }
  }

  @Test
  fun `actual cross-origin request with credentials gets the allowed origin back`() {
    withClient { client ->
      client
          .call(
              HttpMethod.GET,
              "/api/items",
              session = true,
              authenticated = true,
              origin = "http://localhost:3000",
          )
          .expectStatus()
          .isOk
          .expectHeader()
          .valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000")
    }
  }

  @Test
  fun `public endpoint stays reachable anonymously`() {
    withClient { client -> client.call(HttpMethod.GET, "/about").expectStatus().isOk }
  }

  @Test
  fun `protected resource metadata of a proxied service stays reachable anonymously`() {
    withClient { client ->
      client
          .call(HttpMethod.GET, "/run-api/.well-known/oauth-protected-resource/mcp")
          .expectStatus()
          .isOk
    }
  }

  private fun withClient(block: (WebTestClient) -> Unit) {
    runner().run { context ->
      block(
          WebTestClient.bindToApplicationContext(context.sourceApplicationContext)
              .apply(springSecurity())
              .build(),
      )
    }
  }

  private fun runner() =
      ReactiveWebApplicationContextRunner()
          .withUserConfiguration(SecurityConfig::class.java, TestRoutesConfig::class.java)
          .withBean(CsmGatewayProperties::class.java, { gatewayProperties() })
          .withPropertyValues(
              "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=" +
                  "http://127.0.0.1:${jwksServer.address.port}/certs",
          )

  private fun gatewayProperties() =
      CsmGatewayProperties(
          CsmGatewayProperties.Gateway(
              contextPath = "/",
              port = 8080,
              identityProvider =
                  CsmGatewayProperties.CsmIdentityProvider(
                      identity = CsmGatewayProperties.CsmIdentityProvider.CsmIdentity(),
                  ),
          ),
      )

  private fun WebTestClient.call(
      method: HttpMethod,
      path: String,
      authorization: String? = null,
      session: Boolean = false,
      authenticated: Boolean = false,
      accept: MediaType = MediaType.APPLICATION_JSON,
      origin: String? = null,
  ): WebTestClient.ResponseSpec {
    val client = if (authenticated) mutateWith(mockUser()) else this
    return client
        .method(method)
        .uri("$serverUrl$path")
        .accept(accept)
        .headers { headers ->
          authorization?.let { headers.set(HttpHeaders.AUTHORIZATION, it) }
          origin?.let { headers.set(HttpHeaders.ORIGIN, it) }
        }
        .apply { if (session) cookie("SESSION", "fake-session-id") }
        .exchange()
  }

  private fun bearer(token: String) = "Bearer $token"

  private fun validToken(
      key: RSAKey = signingKey,
      expiresAt: Date = Date(System.currentTimeMillis() + 10 * 60 * 1000),
  ): String {
    val claims =
        JWTClaimsSet.Builder().subject("alice").issueTime(Date()).expirationTime(expiresAt).build()
    val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).build()
    return SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
  }

  @Configuration
  @EnableWebFlux
  class TestRoutesConfig {

    @Bean
    fun clientRegistrations(): ReactiveClientRegistrationRepository =
        InMemoryReactiveClientRegistrationRepository(
            ClientRegistration.withRegistrationId("keycloak-client")
                .clientId("test-client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .authorizationUri("http://127.0.0.1/auth")
                .tokenUri("http://127.0.0.1/token")
                .build(),
        )

    @Bean
    fun testRoutes(): RouterFunction<ServerResponse> =
        RouterFunctions.route()
            .GET("/about") { ServerResponse.ok().build() }
            .GET("/run-api/.well-known/oauth-protected-resource/mcp") {
              ServerResponse.ok().build()
            }
            .GET("/api/items") { ServerResponse.ok().build() }
            .POST("/api/items") { ServerResponse.ok().build() }
            .PUT("/api/items") { ServerResponse.ok().build() }
            .DELETE("/api/items") { ServerResponse.ok().build() }
            .build()
  }
}
