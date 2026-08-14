# Cosmotech Gateway

[![Build, Test and Package](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/build_test_package.yml/badge.svg)](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/build_test_package.yml)
[![Lint](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/lint.yml/badge.svg)](https://github.com/Cosmo-Tech/cosmotech-gateway/actions/workflows/lint.yml)

## Description

Cosmotech Gateway is an API Gateway built on [Spring Cloud Gateway (WebFlux)](https://spring.io/projects/spring-cloud-gateway), written in Kotlin and packaged with Spring Boot.

It is responsible for:

- routing incoming requests to the various Cosmo Tech platform micro-services, based on configurable routes and predicates;
- securing these routes via OAuth2/OIDC, relying on an identity provider (Keycloak) for client authentication;
- relaying the access token (`TokenRelay`) to downstream services so they can validate the authenticated user;
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
| `spring.cloud.gateway.server.webflux.routes` | List of routes exposed by the gateway: id, target URI and request matching predicates | `id: test-service`, `uri: http://localhost:8040`, `predicates: Path=/test/**` |
| `spring.security.oauth2.resource-server.jwt.jwk-set-uri` | URL of the JWK endpoint used to validate the signature of incoming JWT tokens | `http://localhost:8080/realms/test/protocol/openid-connect/certs` |
| `spring.security.oauth2.client.provider.keycloak.issuer-uri` | URL of the Keycloak issuer used during the client-side OAuth2 authentication flow | `http://localhost:8080/realms/test` |
| `spring.security.oauth2.client.registration.keycloak-client.provider` | Name of the referenced OAuth2 provider (must match the key declared under `provider`) | `keycloak` |
| `spring.security.oauth2.client.registration.keycloak-client.client-id` | OAuth2 client identifier registered with Keycloak | `gateway-client` |
| `spring.security.oauth2.client.registration.keycloak-client.client-secret` | OAuth2 client secret (never commit a real one; sample value shown here) | `XXXXXXXXXX` |
| `spring.security.oauth2.client.registration.keycloak-client.authorization-grant-type` | OAuth2 flow type used for authentication | `authorization_code` |
| `spring.security.oauth2.client.registration.keycloak-client.scope` | OIDC scopes requested during authentication | `["openid"]` |

> ⚠️ An OIDC-compatible identity provider (e.g. a local Keycloak instance) exposing a realm consistent with `issuer-uri` and `jwk-set-uri` is required for authentication to work.
