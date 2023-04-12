// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
import com.diffplug.gradle.spotless.SpotlessExtension
import com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
import com.google.cloud.tools.jib.gradle.JibExtension
import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("org.springframework.boot") version "3.0.6-SNAPSHOT"
  id("io.spring.dependency-management") version "1.1.0"
  kotlin("jvm") version "1.7.22"
  kotlin("plugin.spring") version "1.7.22"
  id("com.google.cloud.tools.jib") version "3.3.1" apply true
  id("com.diffplug.spotless") version "6.18.0" apply true
  id("io.gitlab.arturbosch.detekt") version "1.22.0" apply true
}

group = "com.cosmotech"

version = "0.0.1-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_17

val kotlinJvmTarget = 17

repositories {
  mavenCentral()
  maven { url = uri("https://repo.spring.io/milestone") }
  maven { url = uri("https://repo.spring.io/snapshot") }
}

extra["springCloudVersion"] = "2022.0.2"

dependencies {
  // Workaround until Detekt adds support for JVM Target 17
  // See https://github.com/detekt/detekt/issues/4287
  detekt("io.gitlab.arturbosch.detekt:detekt-cli:1.22.0")
  detekt("io.gitlab.arturbosch.detekt:detekt-formatting:1.22.0")
  detekt("org.jetbrains.kotlin:kotlin-compiler-embeddable:1.7.22")

  // implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
  implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
  implementation(
      "org.springframework.cloud:spring-cloud-starter-circuitbreaker-reactor-resilience4j")
  implementation("org.springframework.cloud:spring-cloud-starter-gateway")
  // implementation("org.springframework.session:spring-session-data-redis")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("io.projectreactor:reactor-test")
}

dependencyManagement {
  imports {
    mavenBom(
        "org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
  }
}

configure<SpotlessExtension> {
  isEnforceCheck = false

  val licenseHeaderComment =
      """
        // Copyright (c) Cosmo Tech.
        // Licensed under the MIT license.
      """
          .trimIndent()

  java {
    googleJavaFormat()
    target("**/*.java")
    licenseHeader(licenseHeaderComment)
  }
  kotlin {
    ktfmt("0.43")
    target("**/*.kt")
    licenseHeader(licenseHeaderComment)
  }
  kotlinGradle {
    ktfmt("0.43")
    target("**/*.kts")
    //      licenseHeader(licenseHeaderComment, "import")
  }
}

tasks.withType<Detekt>().configureEach {
  buildUponDefaultConfig = true // preconfigure defaults
  allRules = false // activate all available (even unstable) rules.
  config.from(file("$rootDir/.detekt/detekt.yaml"))
  jvmTarget = kotlinJvmTarget.toString()
  ignoreFailures = project.findProperty("detekt.ignoreFailures")?.toString()?.toBoolean() ?: false
  // Specify the base path for file paths in the formatted reports.
  // If not set, all file paths reported will be absolute file path.
  // This is so we can easily map results onto their source files in tools like GitHub Code
  // Scanning
  basePath = rootDir.absolutePath
  reports {
    html {
      // observe findings in your browser with structure and code snippets
      required.set(true)
      outputLocation.set(file("$buildDir/reports/detekt/${project.name}-detekt.html"))
    }
    xml {
      // checkstyle like format mainly for integrations like Jenkins
      required.set(false)
      outputLocation.set(file("$buildDir/reports/detekt/${project.name}-detekt.xml"))
    }
    txt {
      // similar to the console output, contains issue signature to manually edit baseline files
      required.set(true)
      outputLocation.set(file("$buildDir/reports/detekt/${project.name}-detekt.txt"))
    }
    sarif {
      // standardized SARIF format (https://sarifweb.azurewebsites.net/) to support integrations
      // with Github Code Scanning
      required.set(true)
      outputLocation.set(file("$buildDir/reports/detekt/${project.name}-detekt.sarif"))
    }
  }
  tasks.getByName<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
  }
  tasks.getByName<Jar>("jar") { enabled = true }
}

configure<JibExtension> {
  from { image = "eclipse-temurin:17-alpine" }
  to { image = "${project.group}/${project.name}:${project.version}" }
  container {
    format = OCI
    labels.putAll(mapOf("maintainer" to "Cosmo Tech"))
    environment =
        mapOf(
            "JAVA_TOOL_OPTIONS" to
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005")
    jvmFlags =
        listOf(
            // Make sure Spring DevTools is disabled in production as running it is a
            // security risk
            "-Dspring.devtools.restart.enabled=false")
    ports = listOf("5005", "8080", "8081")
    // Docker Best Practice : run as non-root.
    // These are the 'nobody' UID and GID inside the image
    user = "65534:65534"
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    freeCompilerArgs = listOf("-Xjsr305=strict")
    jvmTarget = "17"
  }
}

tasks.withType<Test> { useJUnitPlatform() }
