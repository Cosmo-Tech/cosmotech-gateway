// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.micrometer.common.KeyValue
import io.micrometer.common.KeyValues
import io.micrometer.core.instrument.MeterRegistry
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.server.reactive.observation.DefaultServerRequestObservationConvention
import org.springframework.http.server.reactive.observation.ServerRequestObservationContext

/**
 * OpenTelemetry / Micrometer wiring for the gateway.
 *
 * Traces, metrics and logs are all produced through Micrometer Observation and exported over OTLP
 * (see the `management.opentelemetry.*` and `management.otlp.*` properties). Nothing is exported
 * until an OTLP endpoint is configured, so the defaults are safe for local runs.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfig {

  /** Tags applied to every meter, so metrics can be sliced per instance/environment. */
  @Bean
  fun observabilityCommonTags(): MeterRegistryCustomizer<MeterRegistry> =
      MeterRegistryCustomizer { registry ->
        registry.config().commonTags("application.instance", instanceId())
      }

  @Bean
  fun gatewayServerRequestObservationConvention(): GatewayServerRequestObservationConvention =
      GatewayServerRequestObservationConvention()

  /**
   * Routes application logs to the OpenTelemetry log pipeline (OTLP), keeping the existing console
   * appenders untouched. Log records are automatically correlated with the current trace/span.
   */
  @Bean
  fun openTelemetryAppenderInitializer(openTelemetry: OpenTelemetry) =
      OpenTelemetryLogbackAppenderInitializer(openTelemetry)

  private fun instanceId(): String =
      System.getenv("HOSTNAME") ?: System.getenv("POD_NAME") ?: "local"
}

/**
 * Enriches the `http.server.requests` observation (and therefore both the HTTP server metrics and
 * the OpenTelemetry server spans) with gateway and audit oriented attributes.
 *
 * Only low cardinality values end up as metric tags; everything user/route specific is exposed as
 * high cardinality data, which is attached to spans only.
 */
class GatewayServerRequestObservationConvention : DefaultServerRequestObservationConvention() {

  override fun getName() = "http.server.requests"

  override fun getLowCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues =
      super.getLowCardinalityKeyValues(context).and(KeyValue.of(ROUTE_ID, routeId(context)))

  override fun getHighCardinalityKeyValues(context: ServerRequestObservationContext): KeyValues {
    val request = context.carrier
    return super.getHighCardinalityKeyValues(context)
        .and(
            KeyValue.of(ENDUSER_ID, context.attributes[ACCESS_LOG_USER] as? String ?: "anonymous"),
            KeyValue.of(URL_QUERY, request?.uri?.query ?: ""),
            KeyValue.of(CLIENT_ADDRESS, request?.remoteAddress?.address?.hostAddress ?: ""),
            KeyValue.of(USER_AGENT, request?.headers?.getFirst("User-Agent") ?: ""),
        )
  }

  private fun routeId(context: ServerRequestObservationContext): String =
      (context.attributes[ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR] as? Route)?.id ?: "none"

  companion object {
    /** Exchange attribute filled in by `SecurityUserCaptureWebFilter`. */
    const val ACCESS_LOG_USER = "accessLogUser"

    private const val ROUTE_ID = "gateway.route.id"
    private const val ENDUSER_ID = "enduser.id"
    private const val URL_QUERY = "url.query"
    private const val CLIENT_ADDRESS = "client.address"
    private const val USER_AGENT = "user_agent.original"
  }
}

/**
 * Installs the OpenTelemetry Logback appender programmatically, which avoids having to provide a
 * full `logback-spring.xml` and therefore keeps Spring Boot's console/structured logging defaults.
 */
class OpenTelemetryLogbackAppenderInitializer(private val openTelemetry: OpenTelemetry) :
    InitializingBean {

  override fun afterPropertiesSet() {
    val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
    val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
    if (rootLogger.getAppender(APPENDER_NAME) != null) {
      OpenTelemetryAppender.install(openTelemetry)
      return
    }
    val appender =
        OpenTelemetryAppender().apply {
          name = APPENDER_NAME
          context = loggerContext
          setCaptureExperimentalAttributes(true)
          setCaptureCodeAttributes(true)
          setCaptureMarkerAttribute(true)
          setCaptureKeyValuePairAttributes(true)
          setCaptureLoggerContext(true)
          setCaptureMdcAttributes("*")
          start()
        }
    OpenTelemetryAppender.install(openTelemetry)
    rootLogger.addAppender(appender)
  }

  private companion object {
    const val APPENDER_NAME = "OTEL"
  }
}
