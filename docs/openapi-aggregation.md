# OpenAPI aggregation

In addition to routing requests, the gateway aggregates the OpenAPI (Swagger) descriptions exposed by every
Cosmo Tech service it routes to, and serves them behind a single Swagger UI. OpenAPI descriptions are public;
API operations remain protected by the gateway's centralized OAuth2 configuration (see
[`spring.security.oauth2`](../src/main/resources/application.yaml)).

## How it works

- The aggregation is provided by [springdoc-openapi](https://springdoc.org/) (`springdoc-openapi-starter-webflux-ui`), added as a Gradle dependency in [build.gradle.kts](../build.gradle.kts).
- Each description is exposed through a dedicated [Spring Cloud Gateway route](https://spring.io/projects/spring-cloud-gateway) following the `/openapi/{service}` convention.
- `springdoc.swagger-ui.urls` lists these **gateway-relative** paths, not the downstream service addresses.
- The gateway's security configuration ([`SecurityConfig`](../src/main/kotlin/com/cosmotech/gateway/config/SecurityConfig.kt)) permits anonymous `GET` requests under `/openapi/**`. The downstream OpenAPI endpoint must therefore also be publicly readable.
- All other routes fall through to `anyExchange().authenticated()`. The existing `TokenRelay` default filter forwards the authenticated user's access token to downstream API routes.
- The gateway's own OpenAPI description declares the same `oAuth2AuthCode` authorization-code scheme as the platform services, so selecting the gateway also displays the **Authorize** button. `OpenApiConfig` injects the resolved `spring.security.oauth2.client.provider.keycloak` URLs and `springdoc.swagger-ui.oauth.scopes` directly; it does not rebuild Keycloak URLs.
- `OpenApiAggregationGlobalFilter` can normalize successful OpenAPI responses on `/openapi/**`: it replaces upstream security schemes and requirements with the configured Gateway OAuth2 scheme and can replace each document's server URL with its public Gateway API route.

### Gateway-Level Normalization

Normalization is configured under `csm.platform.gateway.openApiAggregation` in `application.yaml` or Helm
values:

```yaml
csm:
  platform:
    gateway:
      openApiAggregation:
        enabled: true
        serverUrls:
          "[/openapi/cosmotech-api]": /tenant/gateway-api
          "[/openapi/cosmotech-modapi]": /tenant/modeling
```

`serverUrls` maps the path used to fetch an OpenAPI document to the public Gateway base path used for its
operations. The value must be a browser-reachable Gateway URL path, not a Kubernetes service address. A
mapping is optional; if none is configured for a document, its upstream `servers` value is preserved. Set
`enabled` to `false` to pass OpenAPI documents through without normalization.

These two URLs have different purposes. `springdoc.swagger-ui.urls` tells the browser where to **download the
OpenAPI document**; `serverUrls` tells Swagger UI where to **send Try it out requests** described by that
document. For example:

```yaml
springdoc:
  swagger-ui:
    urls:
      - name: cosmotech-api
        url: /openapi/cosmotech-api-service

csm:
  platform:
    gateway:
      openApiAggregation:
        serverUrls:
          "[/openapi/cosmotech-api-service]": /tenant-modapi-ci/run-api
```

Here, Swagger downloads the definition from `/openapi/cosmotech-api-service`, then sends its API operations
to `/tenant-modapi-ci/run-api` on the same Gateway origin. The server URL is a base path: an operation such
as `/projects` is called at `/tenant-modapi-ci/run-api/projects`.

The normalizer replaces each downstream document's security schemes and applies the shared `oAuth2AuthCode`
OAuth2 scheme to the document and all HTTP operations. The scheme name is fixed to match the existing Gateway
definition; upstream scheme names do not need to match. All downstream services must accept access tokens from
the shared Keycloak client; this option is unsuitable for services with intentionally public operations or
different auth schemes.

## Adding a new service to the aggregation

For each service to aggregate:

1. Declare the service's protected API route independently, e.g.:

   ```yaml
   spring:
     cloud:
       gateway:
         server:
           webflux:
             routes:
               - id: my-service
                 uri: http://my-service:8080
                 predicates:
                   - Path=/my-service/**
   ```

2. Declare a route exposing its public OpenAPI description under `/openapi/{service}`. Rewrite that path to
  the endpoint exposed by the downstream service:

   ```yaml
               - id: my-service-openapi
                 uri: http://my-service:8080
                 predicates:
                   - Path=/openapi/my-service
                 filters:
                   - RewritePath=/openapi/my-service, /api-docs
   ```

3. Register the service in `springdoc.swagger-ui.urls`:

   ```yaml
   springdoc:
     swagger-ui:
       urls:
        - name: cosmotech-gateway
          url: /v3/api-docs
        - name: my-service
          url: /openapi/my-service
   ```

Set the gateway as the initially selected definition with:

```yaml
springdoc:
  swagger-ui:
    urls-primary-name: cosmotech-gateway
```

The service then appears as a selectable group in the aggregated Swagger UI (`/swagger-ui.html` by default,
or the gateway root if `use-root-path: true`). Only the OpenAPI description is public; API operations still
require a valid access token.

## Available configuration

All properties use the standard Spring Boot configuration mechanism (`application.yaml`, environment
variables, etc.). The full reference is documented by springdoc: <https://springdoc.org/#properties>.

### Core

| Key | Description                                                               | Default |
| --- |---------------------------------------------------------------------------| --- |
| `springdoc.api-docs.enabled` | Enables/disables the local `/v3/api-docs` endpoint of the gateway itself. | `true` |
| `springdoc.api-docs.path` | Path of the gateway's own OpenAPI description in JSON format.             | `/v3/api-docs` |
| `springdoc.cache.disabled` | Disables caching of the computed OpenAPI description.                     | `false` |
| `springdoc.show-actuator` | Includes the gateway's actuator endpoints in its own OpenAPI description. | `false` |

### Swagger UI

| Key | Description | Default |
| --- | --- | --- |
| `springdoc.swagger-ui.enabled` | Enables/disables the Swagger UI. | `true` |
| `springdoc.swagger-ui.path` | Path of the Swagger UI HTML page. | `/swagger-ui.html` |
| `springdoc.swagger-ui.use-root-path` | Serves the Swagger UI from the gateway's root path. | `false` |
| `springdoc.swagger-ui.urls[n].name` | Display name of an aggregated service, shown in the Swagger UI service selector. | — |
| `springdoc.swagger-ui.urls[n].url` | **Gateway-relative** path where the aggregated service's `/v3/api-docs` is exposed. | — |
| `springdoc.swagger-ui.urls-primary-name` | Name of the service selected by default when the Swagger UI loads. | — |
| `springdoc.swagger-ui.disable-swagger-default-url` | Disables the default Swagger Petstore demo entry. | `false` |
| `springdoc.swagger-ui.persist-authorization` | Persists OAuth2 authorization so it can be reused across aggregated definitions and page reloads. | `true` |

### Gateway OpenAPI Normalization

| Key | Description | Default |
| --- | --- | --- |
| `csm.platform.gateway.openApiAggregation.enabled` | Enables rewriting of security schemes and operation requirements on successful `/openapi/**` responses. | `true` |
| `csm.platform.gateway.openApiAggregation.serverUrls` | Map of OpenAPI document route paths to public Gateway operation base paths. Unmapped documents retain their upstream `servers`. | `{}` |

### Centralized OAuth2 authentication for "Try it out"

The Spring Security provider URLs are derived in `application.yaml` from
`csm.platform.gateway.identityProvider`. The gateway OpenAPI scheme reuses the resolved provider URLs rather
than reconstructing them. The gateway server and Swagger UI share the public Keycloak client configured with
`csm.platform.gateway.identityProvider.identity.clientId`. Spring Security uses
`client-authentication-method: none`, and no client secret is configured or sent to the browser. The same
`springdoc.swagger-ui.oauth.scopes` property defines the scopes advertised by the gateway OpenAPI document and
preselected by Swagger UI.

#### User authentication flow

1. The user opens the public Swagger UI and selects an OpenAPI definition.
2. Clicking the top-level **Authorize** button opens a simplified confirmation dialog. The configured client
  ID, client secret field and scopes are hidden because users cannot change them.
3. Clicking **Authorize** in that dialog starts the authorization-code flow with PKCE S256 and redirects the
  browser to Keycloak.
4. The user enters their Keycloak credentials. No application client secret is requested or sent.
5. Keycloak redirects the browser to `/swagger-ui/oauth2-redirect.html`; Swagger UI exchanges the code using
  the PKCE verifier and stores the resulting authorization in the browser's local storage.
6. Swagger UI adds that token as an `Authorization: Bearer ...` header to subsequent **Try it out** requests.
7. When the user selects another aggregated definition, Swagger UI restores the authorization associated with
  `oAuth2AuthCode`; no additional Keycloak login is required.

The gateway and aggregated services use the same `oAuth2AuthCode` security-scheme name and the same Keycloak
client configuration. Each selected OpenAPI document must declare that scheme for Swagger UI to display the
**Authorize** button. Publishing an OpenAPI document under `/openapi/**` does not make the corresponding API
operations public: only the documentation endpoint is anonymous.

Authorization sharing requires every aggregated document to use the exact same `oAuth2AuthCode` scheme name,
Keycloak realm and public client, and every downstream API must accept tokens issued for that client. Because
`persist-authorization` uses browser local storage, users on shared workstations must explicitly log out from
Swagger UI when they finish; disabling the property restores in-memory-only authorization.

#### Configuration

| Key | Description | Project value |
| --- | --- | --- |
| `csm.platform.gateway.identityProvider.identity.clientId` | Public Keycloak client shared by Spring Security and Swagger UI. | — |
| `spring.security.oauth2.client.registration.keycloak-client.client-authentication-method` | Must be `none` for the shared public client. | `none` |
| `springdoc.swagger-ui.oauth.client-id` | OAuth2 client id used by Swagger UI. References the shared identity-provider client id. | `${csm.platform.gateway.identityProvider.identity.clientId}` |
| `springdoc.swagger-ui.oauth.scopes` | Comma-separated scopes advertised by the gateway OpenAPI document and preselected by Swagger UI. | `openid,email,profile` |
| `springdoc.swagger-ui.oauth.use-basic-authentication-with-access-code-grant` | Sends the client credentials to the token endpoint for an authorization code exchange. Keep `false` for Swagger UI. | `false` |
| `springdoc.swagger-ui.oauth.use-pkce-with-authorization-code-grant` | Enables PKCE for the authorization code grant. | `true` |
| `springdoc.swagger-ui.persist-authorization` | Reuses the authorization across definitions, refreshes and browser restarts. | `true` |
| `springdoc.swagger-ui.oauth.client-secret` | Must remain unset: Swagger UI is a browser client and any configured secret would be exposed. | — |

The shared Keycloak client must be public, with the authorization-code flow and PKCE S256 enabled. Its valid
redirect URIs must include `{gateway-base-url}/swagger-ui/oauth2-redirect.html` for Swagger UI and
`{gateway-base-url}/login/oauth2/code/keycloak-client` for Spring Security.

The standard Swagger UI authorization dialog is customized by `SwaggerUiIndexTransformer`: the client id,
client secret and scope controls are hidden because their values are fixed by the gateway configuration. The
dialog keeps the standard **Authorize** action, which starts the authorization-code flow and redirects the
user to Keycloak. No secret is injected into the generated page.

The transformer appends presentation-only CSS to springdoc's generated `swagger-initializer.js`; it does not
replace Swagger UI's OAuth implementation. The selectors target Swagger UI's internal markup and must be
checked when upgrading springdoc or Swagger UI.
