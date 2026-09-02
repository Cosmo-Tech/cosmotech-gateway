// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.filters

import com.cosmotech.gateway.config.GatewayServerRequestObservationConvention.Companion.ACCESS_LOG_USER
import io.micrometer.observation.Observation
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor
import io.micrometer.tracing.handler.TracingObservationHandler
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * Logs one structured line per request (user access, called URL, matched gateway route, status and
 * duration), readable both by humans and by log aggregation tools.
 *
 * Ordered first so it wraps the whole chain, including requests blocked/redirected by Spring
 * Security before routing (e.g. a login redirect) and requests handled by regular controllers, not
 * only ones matched by a Gateway route. The authenticated user is filled in by
 * [SecurityUserCaptureWebFilter], which runs right after Security and tags the exchange.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class AccessLogGlobalFilter : WebFilter {

  private val logger = LoggerFactory.getLogger(AccessLogGlobalFilter::class.java)

  override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
    val startTime = System.currentTimeMillis()
    val request = exchange.request

    return chain.filter(exchange).doFinally { logAccess(exchange, request, startTime) }
  }

  // MDC entries are populated and cleared synchronously around the log call so they are
  // captured as top-level JSON fields when structured logging is enabled (logging.structured.*).
  private fun logAccess(
      exchange: ServerWebExchange,
      request: ServerHttpRequest,
      startTime: Long,
  ) {
    val durationMs = System.currentTimeMillis() - startTime
    val route = exchange.getAttribute<Route>(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR)
    val user = exchange.attributes[ACCESS_LOG_USER] as? String ?: "anonymous"
    val remoteAddr = request.remoteAddress?.address?.hostAddress ?: "-"
    val status = exchange.response.statusCode?.value() ?: 0
    val method = request.method.name()
    val path = request.uri.path
    val routeId = route?.id ?: "-"
    val traceId = exchange.attributes[TRACE_ID] as? String ?: "-"
    val spanId = exchange.attributes[SPAN_ID] as? String ?: "-"

    MDC.put("method", method)
    MDC.put("path", path)
    MDC.put("query", request.uri.query ?: "")
    MDC.put("routeId", routeId)
    MDC.put("user", user)
    MDC.put("remoteAddr", remoteAddr)
    MDC.put("status", status.toString())
    MDC.put("durationMs", durationMs.toString())
    MDC.put("traceId", traceId)
    MDC.put("spanId", spanId)
    try {
      logger.info(
          "gateway_access method={} path={} routeId={} user={} remoteAddr={} status={} durationMs={} traceId={} spanId={}",
          method,
          path,
          routeId,
          user,
          remoteAddr,
          status,
          durationMs,
          traceId,
          spanId,
      )
    } finally {
      MDC.clear()
    }
  }

  companion object {
    const val TRACE_ID = "accessLogTraceId"
    const val SPAN_ID = "accessLogSpanId"
  }
}

/**
 * Copies the current trace/span ids onto the exchange so [AccessLogGlobalFilter], which completes
 * outside of the observation scope, can log them.
 *
 * Ordered right after Spring Boot's observation filter (`HIGHEST_PRECEDENCE + 1`) and before Spring
 * Security, so the ids are also available for requests that Security rejects or redirects.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
class TracingContextCaptureWebFilter : WebFilter {

  override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
      Mono.deferContextual { contextView ->
        val observation =
            contextView.getOrEmpty<Observation>(ObservationThreadLocalAccessor.KEY).orElse(null)
        val tracingContext: TracingObservationHandler.TracingContext? =
            observation?.context?.get(TracingObservationHandler.TracingContext::class.java)
        tracingContext?.span?.context()?.let {
          exchange.attributes[AccessLogGlobalFilter.TRACE_ID] = it.traceId()
          exchange.attributes[AccessLogGlobalFilter.SPAN_ID] = it.spanId()
        }
        chain.filter(exchange)
      }
}

/**
 * Tags the exchange with the authenticated username, right after Spring Security's filter chain
 * (where the security context is actually available), so [AccessLogGlobalFilter] can read it back
 * once the request completes. Runs with the default (lowest precedence) order, i.e. after Security.
 */
@Component
class SecurityUserCaptureWebFilter : WebFilter {

  override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
    return ReactiveSecurityContextHolder.getContext()
        .map { it.authentication?.name ?: "anonymous" }
        .defaultIfEmpty("anonymous")
        .flatMap { user ->
          exchange.attributes[ACCESS_LOG_USER] = user
          chain.filter(exchange)
        }
  }
}
