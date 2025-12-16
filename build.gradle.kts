plugins {
    kotlin("jvm") version "2.2.21"
}

val mainClass = "no.nav.helse.spurte_du.AppKt"

group = "no.nav.helse"
version = properties["version"] ?: "local-build"

val ktorVersion = "3.2.3"
val junitJupiterVersion = "5.12.1"
val logbackClassicVersion = "1.5.18"
val logbackEncoderVersion = "8.0"
val tbdLibsVersion = "2025.11.04-10.12-3a167132"

repositories {
    val githubPassword: String? by project
    mavenCentral()
    /* ihht. https://github.com/navikt/utvikling/blob/main/docs/teknisk/Konsumere%20biblioteker%20fra%20Github%20Package%20Registry.md
        så plasseres github-maven-repo (med autentisering) før nav-mirror slik at github actions kan anvende førstnevnte.
        Det er fordi nav-mirroret kjører i Google Cloud og da ville man ellers fått unødvendige utgifter til datatrafikk mellom Google Cloud og GitHub
     */
    maven {
        url = uri("https://maven.pkg.github.com/navikt/maven-release")
        credentials {
            username = "x-access-token"
            password = githubPassword
        }
    }
    maven("https://github-package-registry-mirror.gc.nav.no/cached/maven-release")
}

dependencies {
    api("ch.qos.logback:logback-classic:$logbackClassicVersion")
    api("net.logstash.logback:logstash-logback-encoder:$logbackEncoderVersion")

    implementation("com.github.navikt.tbd-libs:naisful-app:$tbdLibsVersion")

    api("io.ktor:ktor-server-auth:$ktorVersion")
    api("io.ktor:ktor-server-auth-jwt:$ktorVersion") {
        exclude(group = "junit")
    }

    api("com.github.navikt.tbd-libs:azure-token-client:$tbdLibsVersion")

    api("redis.clients:jedis:5.1.0")

    implementation("io.ktor:ktor-client-auth:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")

    testImplementation("com.github.navikt.tbd-libs:naisful-test-app:$tbdLibsVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitJupiterVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of("21"))
    }
}

tasks {
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
