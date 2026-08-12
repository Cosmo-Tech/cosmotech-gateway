package com.cosmotech.gateway.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.util.CollectionUtils
import org.springframework.util.StringUtils
import reactor.core.publisher.Mono
import java.util.Objects
import java.util.stream.Collectors

@Configuration
@EnableWebFluxSecurity
class SecurityConfig {

    @Bean
    fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain? {
        http.authorizeExchange { auth ->
            auth!!.anyExchange().authenticated()
        }
            .oauth2Login(Customizer.withDefaults())
            .oauth2ResourceServer { oauth2 ->
                oauth2!!.jwt { jwt ->
                    run {
                        jwt.jwtAuthenticationConverter {
                            jwt ->
                            val authorities: Collection<GrantedAuthority> = KeycloakJwtGrantedAuthoritiesConverter().convert(jwt)
                            val principalClaimValue: String = jwt.getClaimAsString("preferred_username")!!
                            Mono.just(JwtAuthenticationToken(jwt, authorities, principalClaimValue))
                        }
                    }
                }
            }
        http.csrf { csrfConfigurer -> csrfConfigurer.disable() }
        return http.build()
    }

    @Bean
    fun reactiveJwtDecoder(@Value("\${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") jwkSetUri: String): ReactiveJwtDecoder {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build()
    }
}

class KeycloakJwtGrantedAuthoritiesConverter() : Converter<Jwt, Collection<GrantedAuthority>> {

    override fun convert(jwt: Jwt): Collection<GrantedAuthority> {
        val extractAuthorities = mutableListOf<GrantedAuthority>()
        extractAuthorities.addAll(
            convertRolesToAuthorities(jwt.claims, "userRoles")
        )
        return extractAuthorities
    }

    private fun convertRolesToAuthorities(
        attributes: Map<String, Any>,
        claimKey: String,
    ): MutableCollection<GrantedAuthority> {
        if (!CollectionUtils.isEmpty(attributes) && StringUtils.hasText(claimKey)) {
            val rawRoleClaim = attributes[claimKey]
            if (rawRoleClaim is Collection<*>) {
                return rawRoleClaim
                    .stream()
                    .filter(Objects::nonNull)
                    .map { role -> SimpleGrantedAuthority((role as String)) }
                    .collect(Collectors.toList())
            } else if (rawRoleClaim != null) {
                println(
                    "Could not extract authorities from claim '${claimKey}', value was not a collection"
                )
            }
        }
        return mutableSetOf()
    }
}