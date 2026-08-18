// Copyright (c) Cosmo Tech.
// Licensed under the MIT license.
import com.diffplug.gradle.spotless.SpotlessExtension
import com.google.cloud.tools.jib.api.buildplan.ImageFormat.OCI
import com.google.cloud.tools.jib.gradle.JibExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
  val kotlinVersion = "2.3.21"
  id("org.springframework.boot") version "4.1.0"
  id("io.spring.dependency-management") version "1.1.7"
  kotlin("jvm") version kotlinVersion
  kotlin("plugin.spring") version kotlinVersion
  id("com.google.cloud.tools.jib") version "3.5.4" apply true
  id("com.diffplug.spotless") version "8.9.0" apply true
}

group = "com.cosmotech"

version = "0.0.1-SNAPSHOT"

java.sourceCompatibility = JavaVersion.VERSION_25

val kotlinJvmTarget = 25
val kotlinVersion = "2.3"

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
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
  testImplementation("org.springframework.boot:spring-boot-starter-test")
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
    freeCompilerArgs = listOf("-Xjsr305=strict", "-Xannotation-default-target=param-property")
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
