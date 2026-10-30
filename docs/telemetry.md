# Telemetry

The gateway produces three kinds of telemetry: structured **access logs**, **traces** and **metrics**. Traces and
metrics are created through Micrometer Observation and exported over OTLP. Application logs are also routed to the
OpenTelemetry log pipeline. Nothing is exported unless it is explicitly enabled (see [Configuration](#configuration)).

## Components

| Component | Role |
| --- | --- |
| [`AccessLogGlobalFilter`](../src/main/kotlin/com/cosmotech/gateway/filters/telemetry/AccessLogGlobalFilter.kt) | Writes one `gateway_access` log line per request, after the response is complete. |
| [`SecurityUserCaptureWebFilter`](../src/main/kotlin/com/cosmotech/gateway/filters/telemetry/AccessLogGlobalFilter.kt) | Stores the authenticated user on the exchange, right after Spring Security. |
| [`TracingContextCaptureWebFilter`](../src/main/kotlin/com/cosmotech/gateway/filters/telemetry/AccessLogGlobalFilter.kt) | Copies the trace and span ids onto the exchange, so the access log can include them. |
| [`GatewayServerRequestObservationConvention`](../src/main/kotlin/com/cosmotech/gateway/config/telemetry/ObservabilityConfig.kt) | Adds gateway attributes (route, user, query, client address, user agent) to `http.server.requests`. |
| [`ObservabilityConfig`](../src/main/kotlin/com/cosmotech/gateway/config/telemetry/ObservabilityConfig.kt) | Common metric tags, and the OpenTelemetry Logback appender. |

The filters run in this order:

1. `AccessLogGlobalFilter` (`HIGHEST_PRECEDENCE`): wraps the whole chain, including requests rejected or redirected
   by Spring Security.
2. `TracingContextCaptureWebFilter` (`HIGHEST_PRECEDENCE + 2`): runs after Spring Boot's observation filter and
   before Spring Security.
3. Spring Security's filter chain.
4. `SecurityUserCaptureWebFilter` (default order): runs after Security, where the security context is available.

## Access logs

Each request produces one line at `INFO` level on the `AccessLogGlobalFilter` logger:

```
gateway_access method=GET path=/run-api/organizations routeId=run-api user=jdoe remoteAddr=127.0.0.1 status=200 durationMs=42 traceId=... spanId=...
```

| Field | Content |
| --- | --- |
| `method` | HTTP method |
| `path` | Request path, without the query string |
| `routeId` | Matched Spring Cloud Gateway route id, or `-` when no route matched |
| `user` | Authenticated user, see below |
| `remoteAddr` | Client IP address |
| `status` | Response status code (`0` if none was set) |
| `durationMs` | Time between the start of the filter and the end of the response |
| `traceId`, `spanId` | Trace context, or `-` when there is no active trace |

The query string is not part of the message. It is only put in the MDC (`query`), so it appears as a top-level field
in structured logs.

**Log format.** In the `dev` profile, logs use the console pattern with the `correlationId` (`[application,traceId,spanId]`).
Other profiles (`prod`, `docker`, ...) switch to JSON logs (`logging.structured.format.console: logstash`). The MDC
fields (`method`, `path`, `routeId`, `user`, `status`, ...) then become top-level JSON keys, which log aggregators can index.

## User attribution

`SecurityUserCaptureWebFilter` determines the value of `user` from the security context:

| Situation | `user` value |
| --- | --- |
| No security context, unauthenticated, or anonymous request | `anonymous` |
| JWT (Bearer token) | Value of the claim set in `csm.platform.gateway.authorization.principal` (default `email`) |
| Other authenticated principal (OAuth2 login session) | Same claim, read from the OAuth2 principal attributes |
| Claim missing or blank, or principal of another type | `anonymous` |

The same user is set as `enduser.id` on the request observation (see below).

## Traces and metrics

Every request is recorded as an `http.server.requests` observation. It is exported as a server span and as HTTP server
metrics (with histograms and SLO buckets: `50ms,100ms,250ms,500ms,1s,2s,5s`).

Attributes are split by cardinality, to keep metric series bounded:

| Attribute | Cardinality | Available on |
| --- | --- | --- |
| `gateway.route.id` (`none` when no route matched) | Low | Metrics and spans |
| `enduser.id` | High | Spans only |
| `url.query` | High | Spans only |
| `client.address` | High | Spans only |
| `user_agent.original` | High | Spans only |

Every meter and span/log resource also carries the tags `deployment.environment.name` and `application.instance`.
`application.instance` is taken from the `HOSTNAME` or `POD_NAME` environment variable, and defaults to `local`.

Logs are also sent through an OpenTelemetry Logback appender (`OTEL`). Records are correlated with the active trace and
span.

## Configuration

Telemetry settings live in [`application.yaml`](../src/main/resources/application.yaml), under `management.*` and
`management.opentelemetry.*`. The environment variables below override the defaults.

| Environment variable | Default | Effect |
| --- | --- | --- |
| `OTEL_EXPORT_ENABLED` | `false` | Enables export of metrics and logs over OTLP. Also enables trace export through `management.tracing.export.enabled`. |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318` | Base OTLP/HTTP endpoint. `/v1/traces`, `/v1/logs` and `/v1/metrics` are appended. |
| `OTEL_TRACES_SAMPLER_ARG` | `1.0` | Trace sampling probability (`management.tracing.sampling.probability`). |
| `DEPLOYMENT_ENVIRONMENT` | `local` | Value of `deployment.environment.name`, on all meters, spans and logs. |
| `HOSTNAME`, `POD_NAME` | `local` | Value of `application.instance`. |

Nothing is shipped by default. Spans, metrics and logs are still collected in-process and are visible through the
actuator endpoints.

The `dev` profile sets `management.tracing.export.enabled: true`, so spans are exported as soon as an OTLP endpoint is
reachable. Metrics and logs stay local unless `OTEL_EXPORT_ENABLED=true`.

Actuator endpoints are served on the management port `8061` (`management.server.port`). `/actuator/prometheus`,
`/actuator/health/**` and `/actuator/info` are public (see the [README](../README.md#security)).

## Local development stack

`./gradlew bootRun` starts the services in [`compose.yaml`](../compose.yaml) through Spring Boot's Docker Compose
support:

| Service | Address | Purpose |
| --- | --- | --- |
| Jaeger | UI: <http://localhost:16686>, OTLP: `4317` (gRPC), `4318` (HTTP) | Trace storage and UI. The **Monitor** tab shows service performance metrics. |
| Prometheus | <http://localhost:9090> | Scrapes the RED metrics derived from spans by Jaeger's `spanmetrics` connector, and Jaeger's own metrics. |

The Jaeger configuration is in [`docker/jaeger/config-spm.yaml`](../docker/jaeger/config-spm.yaml). The Prometheus
scrape configuration is in [`docker/jaeger/prometheus.yml`](../docker/jaeger/prometheus.yml).

The fixed OTLP ports also let other services started locally, such as `cosmotech-api`, send their traces to the same
Jaeger with `OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4318`.

The gateway itself discovers the OTLP endpoint through Spring Boot's Docker Compose service connection. The fixed
port mapping does not change this.

## Limitations

- High-cardinality attributes (`enduser.id`, `url.query`, `client.address`, `user_agent.original`) are kept on spans only.
  They are not metric tags.
