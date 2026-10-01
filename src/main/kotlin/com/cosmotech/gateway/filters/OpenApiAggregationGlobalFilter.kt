// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
package com.cosmotech.gateway.filters

import com.cosmotech.gateway.config.CsmGatewayProperties
import com.cosmotech.gateway.config.OpenApiDocumentNormalizer
import org.reactivestreams.Publisher
import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.cloud.gateway.filter.GlobalFilter
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpResponseDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import tools.jackson.core.JacksonException

/** Normalizes successful, uncompressed OpenAPI responses served under `/openapi/` routes. */
@Component
class OpenApiAggregationGlobalFilter(
    private val properties: CsmGatewayProperties,
    private val normalizer: OpenApiDocumentNormalizer,
) : GlobalFilter, Ordered {

  private val logger = LoggerFactory.getLogger(OpenApiAggregationGlobalFilter::class.java)

  override fun getOrder(): Int = NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1

  override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> {
    if (!properties.gateway.openApiAggregation.enabled) return chain.filter(exchange)

    val path = exchange.request.path.pathWithinApplication().value()
    if (!path.startsWith("/openapi/")) return chain.filter(exchange)

    val response =
        object : ServerHttpResponseDecorator(exchange.response) {
          override fun writeWith(body: Publisher<out DataBuffer>): Mono<Void> {
            if (
                statusCode?.is2xxSuccessful != true || headers[HttpHeaders.CONTENT_ENCODING] != null
            ) {
              return super.writeWith(body)
            }

            return DataBufferUtils.join(Flux.from(body)).flatMap { buffer ->
              val content = ByteArray(buffer.readableByteCount())
              buffer.read(content)
              DataBufferUtils.release(buffer)

              val normalized =
                  try {
                    normalizer.normalize(path, content, headers.contentType)
                  } catch (exception: JacksonException) {
                    logger.warn("Could not normalize OpenAPI response for {}", path, exception)
                    content
                  }

              if (!normalized.contentEquals(content)) {
                headers.remove(HttpHeaders.CONTENT_LENGTH)
                headers.remove(HttpHeaders.ETAG)
              }
              super.writeWith(Mono.just(bufferFactory().wrap(normalized)))
            }
          }
        }

    return chain.filter(exchange.mutate().response(response).build())
  }
}
