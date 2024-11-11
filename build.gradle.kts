import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "1.9.21"
}

val mainClass = "no.nav.helse.spurte_du.AppKt"

group = "no.nav.helse"
version = properties["version"] ?: "local-build"

val ktorVersion = "2.3.12"
val micrometerRegistryPrometheusVersion = "1.12.3"
val junitJupiterVersion = "5.10.2"
val jacksonVersion = "2.16.0"
val logbackClassicVersion = "1.4.14"
val logbackEncoderVersion = "7.4"
val tbdLibsVersion = "2024.01.09-10.01-864ddafa"

repositories {
    mavenCentral()
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
    maven("https://jitpack.io")
}

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    api("io.ktor:ktor-client-cio:$ktorVersion")
    api("io.ktor:ktor-server-cio:$ktorVersion")
    api("io.ktor:ktor-server-call-id:$ktorVersion")
    api("io.ktor:ktor-server-content-negotiation:$ktorVersion")
    api("io.ktor:ktor-serialization-jackson:$ktorVersion")
    api("io.ktor:ktor-server-auth:$ktorVersion")
    api("io.ktor:ktor-server-auth-jwt:$ktorVersion") {
        exclude(group = "junit")
    }

    api("com.github.navikt.tbd-libs:azure-token-client:$tbdLibsVersion")

    api("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")

    api("io.ktor:ktor-server-metrics-micrometer:$ktorVersion")
    api("io.micrometer:micrometer-registry-prometheus:$micrometerRegistryPrometheusVersion")

    api("redis.clients:jedis:5.1.0")
    implementation("io.ktor:ktor-client-auth:2.3.6")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")
}

kotlin {
    compilerOptions {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}
java {
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    processResources {
        doLast {
            exec {
                executable = "npm"
                args = listOf("install")
                workingDir = File("frontend")
            }
            exec {
                executable = "npm"
                args = listOf("run", "build")
                workingDir = File("frontend")
            }
        }
    }
    withType<Jar> {
        archiveBaseName.set("app")

        manifest {
            attributes["Main-Class"] = mainClass
            attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") {
                it.name
            }
        }

        doLast {
            configurations.runtimeClasspath.get().forEach {
                val file = File("${layout.buildDirectory.get()}/libs/${it.name}")
                if (!file.exists()) it.copyTo(file)
            }
        }
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("skipped", "failed")
        }
    }
}
