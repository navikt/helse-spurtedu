package no.nav.helse.spurte_du

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.azure.AzureAuthMethod
import com.github.navikt.tbd_libs.azure.AzureTokenClient
import com.github.navikt.tbd_libs.naisful.naisApp
import io.ktor.client.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.prometheus.metrics.model.registry.PrometheusRegistry
import no.nav.helse.spurte_du.Maskeringer.Companion.lagMaskeringer
import org.slf4j.LoggerFactory
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.net.URI
import java.time.Duration
import io.ktor.client.engine.cio.CIO as ClientEngineCioCIO

private val logg = LoggerFactory.getLogger("no.nav.helse.spurte_du.App")
private val sikkerlogg = LoggerFactory.getLogger("tjenestekall")

private val objectmapper get() = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())
    .setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
        indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
        indentObjectsWith(DefaultIndenter("  ", "\n"))
    })


fun main() {
    val logg = Logg(logg, sikkerlogg)
    val env = System.getenv()
    Thread.currentThread().setUncaughtExceptionHandler { _, e ->
        logg.error("Ufanget exception: {}", e.message, e)
        sikkerlogg.error("Ufanget exception: {}", e.message, e)
    }
    launchApp(env, logg)
}

fun launchApp(env: Map<String, String>, logg: Logg) {
    val jedisPool = lagJedistilkobling(env, logg)
    val httpClient = HttpClient(ClientEngineCioCIO)
    val azureClient = JedisTokenCache(
        jedisPool = jedisPool,
        other = AzureTokenClient(
            tokenEndpoint = URI(env.getValue("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT")),
            clientId = env.getValue("AZURE_APP_CLIENT_ID"),
            authMethod = AzureAuthMethod.Secret(env.getValue("AZURE_APP_CLIENT_SECRET")),
            objectMapper = objectmapper
        ),
        logg = logg
    )
    val azureApp = AzureApp(
        jwkProvider = JwkProviderBuilder(URI(env.getValue("AZURE_OPENID_CONFIG_JWKS_URI")).toURL()).build(),
        issuer = env.getValue("AZURE_OPENID_CONFIG_ISSUER"),
        clientId = env.getValue("AZURE_APP_CLIENT_ID"),
    )
    val gruppetilganger = Gruppetilganger(jedisPool, azureClient, httpClient, objectmapper)
    val maskeringer = lagMaskeringer(jedisPool, objectmapper)
    val app = naisApp(
        meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, PrometheusRegistry.defaultRegistry, Clock.SYSTEM),
        objectMapper = objectmapper,
        applicationLogger = logg,
        callLogger = LoggerFactory.getLogger("no.nav.helse.spurte_du.api.CallLogging"),
        timersConfig = { call, _ -> tag("azp_name", call.principal<JWTPrincipal>()?.get("azp_name") ?: "n/a") },
        mdcEntries = mapOf(
            "azp_name" to { call: ApplicationCall -> call.principal<JWTPrincipal>()?.get("azp_name") },
            "preferred_username" to { call: ApplicationCall -> call.principal<JWTPrincipal>()?.get("preferred_username") }
        )
    ) {
        authentication { azureApp.konfigurerJwtAuth(logg, this, gruppetilganger) }
        lagApplikasjonsmodul(logg, objectmapper, maskeringer)
    }
    app.start(wait = true)
}

fun Application.lagApplikasjonsmodul(logg: Logg, objectMapper: ObjectMapper, maskeringer: Maskeringtjeneste) {
    routing {
        frontend()
        authenticate(optional = true) {
            api(logg, maskeringer)
        }
    }
}

private fun Route.frontend() {
    staticResources("/", "/public/")
}

sealed class SpurteDuPrinsipal(
    private val jwtPrincipal: JWTPrincipal,
    private val gruppetilganger: List<String>,
    extraClaims: List<String>
) {
    val claims = (extraClaims + gruppetilganger).takeUnless(List<String>::isEmpty)
    abstract val name: String

    class BrukerPrincipal(jwtPrincipal: JWTPrincipal, gruppetilganger: List<String>) : SpurteDuPrinsipal(jwtPrincipal, gruppetilganger, listOfNotNull(jwtPrincipal["preferred_username"])) {
        override val name: String = "${jwtPrincipal["name"]} (${jwtPrincipal["preferred_username"]})"
    }

    class MaskinPrincipal(jwtPrincipal: JWTPrincipal) : SpurteDuPrinsipal(jwtPrincipal, emptyList(), listOfNotNull(jwtPrincipal["azp_name"])) {
        override val name: String = "${jwtPrincipal["azp_name"]}"
    }

    companion object {

        fun SpurteDuPrinsipal?.logg(logg: Logg) {
            logg.info("requested er ${this?.let { "authenticated: $name" } ?: "ikke autentisert"}")
        }
    }
}

private fun lagJedistilkobling(env: Map<String, String>, logg: Logg): JedisPool {
    val uri = URI(env.getValue("REDIS_URI_OPPSLAG"))
    val config = DefaultJedisClientConfig.builder()
        .user(env.getValue("REDIS_USERNAME_OPPSLAG"))
        .password(env.getValue("REDIS_PASSWORD_OPPSLAG"))
        .ssl(true)
        .hostnameVerifier { hostname, session ->
            val evaluering = hostname == uri.host
            logg.info("verifiserer vertsnavn $hostname: {}", evaluering)
            evaluering
        }
        .build()
    val poolConfig = JedisPoolConfig().apply {
        minIdle = 1 // minimum antall ledige tilkoblinger
        setMaxWait(Duration.ofSeconds(3)) // maksimal ventetid på tilkobling
        testOnBorrow = true // tester tilkoblingen før lån
        testWhileIdle = true // tester ledige tilkoblinger periodisk

    }
    return JedisPool(poolConfig, HostAndPort(uri.host, uri.port), config)
}
