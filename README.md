# Cosmotech Gateway

[![Build, Test and Package](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/build_test_package.yml/badge.svg)](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/build_test_package.yml)
[![Lint](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/lint.yml/badge.svg)](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/lint.yml)

## Description

Cosmotech Gateway is an API Gateway built on [Spring Cloud Gateway (WebFlux)](https://spring.io/projects/spring-cloud-gateway), written in Kotlin and packaged with Spring Boot.

It is responsible for:

- routing incoming requests to the various Cosmo Tech platform services, based on configurable routes and predicates;
- securing these routes via OAuth2/OIDC, relying on an identity provider (Keycloak) for client authentication;
- relaying the access token (`TokenRelay`) to downstream services so they can validate the authenticated user;
- aggregating the OpenAPI descriptions exposed by downstream services behind a single Swagger UI, with a centralized Keycloak authorization-code flow secured by PKCE (see [docs/openapi-aggregation.md](docs/openapi-aggregation.md));
- being packaged as an OCI container image using [Jib](https://github.com/GoogleContainerTools/jib), without requiring a Dockerfile.

## Technical prerequisites

| Tool | Version | Role |
| --- | --- | --- |
| JDK | 25 (Eclipse Temurin recommended) | Compiling and running the application (Kotlin/JVM) |
| Git | - | Retrieving the source code |
| Docker | - | Optional, only needed to build a container image locally (`jibDockerBuild`) |

> The Gradle wrapper (`./gradlew`) is provided with the project: there is no need to install Gradle manually, the required version (9.7.0) is downloaded automatically.

### Installing the prerequisites

**JDK 25** via [SDKMAN!](https://sdkman.io/) (recommended, cross-platform):

```bash
curl -s "https://get.sdkman.io" | bash
sdk install java 25-tem
```

Or via a package manager:

```bash
# Ubuntu / Debian
sudo apt install openjdk-25-jdk
```

**Git**:

```bash
# Ubuntu / Debian
sudo apt install git
```

**Docker** (optional): follow the [official Docker documentation](https://docs.docker.com/get-docker/) for your operating system.

## Useful commands

All commands should be run from the project root, via the Gradle wrapper.

| Command | Description |
| --- | --- |
| `./gradlew build` | Compiles the project, runs the tests and builds the artifact |
| `./gradlew clean` | Removes build outputs (the `build/` directory) |
| `./gradlew bootRun` | Starts the application locally with the Spring `dev` profile |
| `./gradlew test` | Runs the unit tests |
| `./gradlew detekt` | Runs Kotlin static analysis with Detekt |
| `./gradlew spotlessCheck` | Checks code formatting and the presence of the license header |
| `./gradlew spotlessApply` | Automatically fixes code formatting issues |
| `./gradlew jibDockerBuild` | Builds an OCI container image in the local Docker registry |
| `./gradlew tasks` | Lists all available Gradle tasks |

## Running the application locally

The [config/application-dev-sample.yaml](config/application-dev-sample.yaml) file contains a sample configuration required to run the application locally (gateway routes, OAuth2/OIDC security, etc.).

To start the application locally:

1. Duplicate the [config/application-dev-sample.yaml](config/application-dev-sample.yaml) file.
2. Rename the copy to `application-dev.yaml`, in the same `config/` directory.
3. Adapt the configuration values to your local environment (service URLs, Keycloak, etc.).
4. Start the application with the `dev` profile:

   ```bash
   ./gradlew bootRun
   ```

`application-dev.yaml` is automatically picked up by Spring Boot at startup, as the `dev` profile is enabled by default by the `bootRun` task, and the `config/` directory is scanned by Spring Boot for a configuration file matching the active profile.

### Configuration available in `application-dev-sample.yaml`

| Key | Description | Sample value |
| --- | --- | --- |
| `spring.cloud.gateway.server.webflux.default-filters` | Filters applied by default to all routes (here, relaying the OAuth2 token to downstream services) | `TokenRelay=` |
| `spring.cloud.gateway.server.webflux.routes` | Routes added or overridden for local development | `Path=/openapi/test-service` |
| `springdoc.swagger-ui.urls` | OpenAPI descriptions displayed in the Swagger UI selector | `url: /openapi/test-service` |
| `csm.platform.gateway.contextPath` | Gateway WebFlux base path | `/` |
| `csm.platform.gateway.port` | Gateway HTTP port | `8060` |
| `csm.platform.gateway.identityProvider.serverBaseUrl` | Keycloak base URL, without a trailing slash | `http://localhost:8080` |
| `csm.platform.gateway.identityProvider.identity.tenantId` | Keycloak realm | `changeme` |
| `csm.platform.gateway.identityProvider.identity.clientId` | Public Keycloak client shared by the gateway and Swagger UI | `idp-gateway-client` |

The main [application.yaml](src/main/resources/application.yaml) derives Spring Security's issuer, JWK,
authorization and token endpoints from these identity-provider values. The shared client must be public,
allow the authorization-code flow with PKCE S256, and accept these redirect URIs for local development:

- `http://localhost:8060/swagger-ui/oauth2-redirect.html` for Swagger UI;
- `http://localhost:8060/login/oauth2/code/keycloak-client` for Spring Security.

## OpenAPI aggregation

The gateway aggregates the OpenAPI descriptions of the services it routes to and exposes them through
a single Swagger UI. OpenAPI documents are public, while API operations remain protected by OAuth2. See
[docs/openapi-aggregation.md](docs/openapi-aggregation.md) for the full configuration reference and the
Swagger UI authentication flow. Users authorize with their Keycloak credentials; the shared public client,
scopes and PKCE settings are fixed by the gateway and require no user input. The resulting authorization is
reused when switching between aggregated services that declare the common `oAuth2AuthCode` scheme.
