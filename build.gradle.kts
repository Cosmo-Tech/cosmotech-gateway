// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
import com.diffplug.gradle.spotless.SpotlessExtension
import com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
import com.google.cloud.tools.jib.gradle.JibExtension
import dev.detekt.gradle.Detekt
import dev.detekt.gradle.extensions.FailOnSeverity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
  val kotlinVersion = "2.4.20"
  id("org.springframework.boot") version "4.1.1"
  id("io.spring.dependency-management") version "1.1.7"
  kotlin("jvm") version kotlinVersion
  kotlin("plugin.spring") version kotlinVersion
  id("dev.detekt") version "2.0.0-alpha.6"
  id("com.google.cloud.tools.jib") version "3.5.4" apply true
  id("com.diffplug.spotless") version "8.9.0" apply true
}

group = "com.cosmotech"

version = "0.0.1-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_25

val kotlinJvmTarget = 25
val kotlinVersion = "2.4"
// Checks
val detektVersion = "2.0.0-alpha.6"
val detektKotlinVersion = "2.4.10"

repositories {
  mavenCentral()
}

buildscript {
  dependencies {
    // This dependency is needed by jib-gradle-plugin to handle correctly
    // zstd compressed layers in docker images (e.g. used by Docker Hardened Images)
    // here is some relative links:
    // issue : https://github.com/GoogleContainerTools/jib/issues/3714
    // PR: https://github.com/GoogleContainerTools/jib/pull/3717
    classpath("com.github.luben:zstd-jni:1.5.7-13")
  }
}

dependencies {
  detekt("dev.detekt:detekt-cli:$detektVersion")
  detekt("dev.detekt:detekt-rules-ktlint-wrapper:$detektVersion")
  detektPlugins("dev.detekt:detekt-rules-libraries:$detektVersion")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
}

configurations
    .matching { it.name in setOf("detekt", "detektPlugins") }
    .configureEach {
      resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
          useVersion(detektKotlinVersion)
        }
      }
    }

extra["springCloudVersion"] = "2025.1.2"

dependencyManagement {
  imports {
    mavenBom(
        "org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}"
    )
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
    ktfmt()
    target("**/*.kt")
    licenseHeader(licenseHeaderComment)
  }
  kotlinGradle {
    ktfmt()
    target("**/*.kts")
    //      licenseHeader(licenseHeaderComment, "import")
  }
}

tasks.withType<Detekt>().configureEach {
  buildUponDefaultConfig = true // preconfigure defaults
  allRules = false // activate all available (even unstable) rules.
  autoCorrect = true
  failOnSeverity = FailOnSeverity.Warning
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
      outputLocation.set(
          file("${layout.buildDirectory.get()}/reports/detekt/${project.name}-detekt.html")
      )
    }
    checkstyle {
      // checkstyle like format mainly for integrations like Jenkins
      required.set(false)
      outputLocation.set(
          file("${layout.buildDirectory.get()}/reports/detekt/${project.name}-detekt.xml")
      )
    }
    markdown {
      // similar to the console output, contains issue signature to manually edit baseline files
      required.set(true)
      outputLocation.set(
          file("${layout.buildDirectory.get()}/reports/detekt/${project.name}-detekt.txt")
      )
    }
    sarif {
      // standardized SARIF format (https://sarifweb.azurewebsites.net/) to support integrations
      // with Github Code Scanning
      required.set(true)
      outputLocation.set(
          file("${layout.buildDirectory.get()}/reports/detekt/${project.name}-detekt.sarif")
      )
    }
  }

  tasks.getByName<BootJar>("bootJar") { enabled = false }
  tasks.getByName<Jar>("jar") { enabled = true }
}

configure<JibExtension> {
  from {
    image = "${project.property("baseimage.name")}"
    auth {
      username =
          project.findProperty("baseimage.repository.user")?.toString()
              ?: System.getenv("BASEIMAGE_REPOSITORY_USER")
      password =
          project.findProperty("baseimage.repository.password")?.toString()
              ?: System.getenv("BASEIMAGE_REPOSITORY_PASSWORD")
    }
  }
  to { image = "${project.group}/${project.name}:${project.version}" }
  container {
    format = OCI
    labels.putAll(mapOf("maintainer" to "Cosmo Tech"))
    environment =
        mapOf(
            "JAVA_TOOL_OPTIONS" to
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:5005"
        )
    jvmFlags =
        listOf(
            // Make sure Spring DevTools is disabled in production as running it is a
            // security risk
            "-Dspring.devtools.restart.enabled=false"
        )
    ports = listOf("5005", "8060")
    // Docker Best Practice : run as non-root.
    // These are the 'nobody' UID and GID inside the image
    user = "65534:65534"
  }
}

kotlin {
  compilerOptions {
    apiVersion.set(KotlinVersion.fromVersion(kotlinVersion))
    freeCompilerArgs = listOf("-Xjsr305=strict")
    jvmTarget.set(JvmTarget.fromTarget(kotlinJvmTarget.toString()))
    java {
      targetCompatibility = JavaVersion.VERSION_25
      sourceCompatibility = JavaVersion.VERSION_25
      toolchain { languageVersion.set(JavaLanguageVersion.of(kotlinJvmTarget)) }
    }
  }
}

tasks.getByName<BootRun>("bootRun") {
  workingDir = rootDir

  if (project.hasProperty("jvmArgs")) {
    jvmArgs = project.property("jvmArgs").toString().split("\\s+".toRegex()).toList()
  }

  args = listOf("--spring.profiles.active=dev")
}

tasks.withType<Test> { useJUnitPlatform() }
