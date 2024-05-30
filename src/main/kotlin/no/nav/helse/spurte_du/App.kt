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
import io.ktor.client.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.http.content.*
import io.ktor.serialization.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.reflect.*
import io.ktor.utils.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.jvm.javaio.*
import io.micrometer.core.instrument.Clock
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.nav.helse.spurte_du.Maskeringer.Companion.lagMaskeringer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import redis.clients.jedis.JedisPoolConfig
import java.io.CharArrayWriter
import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import java.util.*
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
    val app = embeddedServer(
        factory = CIO,
        environment = applicationEngineEnvironment {
            log = logg
            connectors.add(EngineConnectorBuilder().apply {
                this.port = 8080
            })
            module {
                authentication { azureApp.konfigurerJwtAuth(logg, this, gruppetilganger) }
                lagApplikasjonsmodul(logg, objectmapper, maskeringer)
            }
        }
    )
    app.start(wait = true)
}

fun Application.lagApplikasjonsmodul(logg: Logg, objectMapper: ObjectMapper, maskeringer: Maskeringtjeneste) {
    install(CallId) {
        header("callId")
        verify { it.isNotEmpty() }
        generate { UUID.randomUUID().toString() }
    }
    install(CallLogging) {
        logger = logg.nyLogg("no.nav.helse.spurte_du.api.CallLogging")
        level = Level.INFO
        callIdMdc("callId")
        disableDefaultColors()
        filter { call -> call.request.path().startsWith("/api/") }
    }
    install(ContentNegotiation) {
        register(ContentType.Application.Json, JacksonConverter(objectMapper))
        register(ContentType.Application.FormUrlEncoded, FormConverter(objectMapper))
    }
    requestResponseTracing(logg.nyLogg("no.nav.helse.spurte_du.api.Tracing"))
    nais()
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

class SpurteDuPrinsipal(
    private val jwtPrincipal: JWTPrincipal,
    private val gruppetilganger: List<String>
) : Principal {

    val claims = (listOfNotNull(jwtPrincipal["preferred_username"]) + gruppetilganger).takeUnless(List<String>::isEmpty)
    companion object {

        fun SpurteDuPrinsipal?.logg(logg: Logg) {
            logg.info("requested er ${this?.let { "authenticated: ${jwtPrincipal["name"]} (${jwtPrincipal["preferred_username"]})" } ?: "ikke autentisert"}")
        }
    }
}

private class FormConverter (private val objectMapper: ObjectMapper): ContentConverter {
    override suspend fun deserialize(
        charset: Charset,
        typeInfo: TypeInfo,
        content: ByteReadChannel
    ): Any? {
        return withContext(Dispatchers.IO) {
            val reader = content
                .toInputStream()
                .reader(charset)
                .readText()
                .let { URLDecoder.decode(it, charset) }
                .split("&")
                .associate { verdi ->
                    val (key, value) = verdi.split("=", limit = 2)
                    key to value
                }
            objectMapper.convertValue(reader, objectMapper.constructType(typeInfo.reifiedType))
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

private const val isaliveEndpoint = "/isalive"
private const val isreadyEndpoint = "/isready"
private const val metricsEndpoint = "/metrics"

private val ignoredPaths = listOf(metricsEndpoint, isaliveEndpoint, isreadyEndpoint)

private fun Application.requestResponseTracing(logger: Logger) {
    intercept(ApplicationCallPipeline.Monitoring) {
        try {
            if (call.request.uri in ignoredPaths) return@intercept proceed()
            val headers = call.request.headers.toMap()
                .filterNot { (key, _) -> key.lowercase() in listOf("authorization") }
                .map { (key, values) ->
                    keyValue("req_header_$key", values.joinToString(separator = ";"))
                }.toTypedArray()
            logger.info("incoming callId=${call.callId} method=${call.request.httpMethod.value} uri=${call.request.uri}", *headers)
            proceed()
        } catch (err: Throwable) {
            logger.error("exception thrown during processing: ${err.message} callId=${call.callId}", err)
            throw err
        }
    }

    sendPipeline.intercept(ApplicationSendPipeline.After) { message ->
        val status = call.response.status() ?: (when (message) {
            is OutgoingContent -> message.status
            is HttpStatusCode -> message
            else -> null
        } ?: HttpStatusCode.OK).also { status ->
            call.response.status(status)
        }

        if (call.request.uri in ignoredPaths) return@intercept
        logger.info("responding with status=${status.value} callId=${call.callId} ")
    }
}

private fun Application.nais() {
    install(MicrometerMetrics) {
        registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, CollectorRegistry.defaultRegistry, Clock.SYSTEM)
        meterBinders = listOf(
            ClassLoaderMetrics(),
            JvmMemoryMetrics(),
            JvmGcMetrics(),
            ProcessorMetrics(),
            JvmThreadMetrics(),
        )
    }

    routing {
        get(isaliveEndpoint) {
            call.respondText("ALIVE", ContentType.Text.Plain)
        }

        get(isreadyEndpoint) {
            call.respondText("READY", ContentType.Text.Plain)
        }

        get(metricsEndpoint) {
            val names = call.request.queryParameters.getAll("name[]")?.toSet() ?: emptySet()
            val formatted = CharArrayWriter(1024)
                .also { TextFormat.write004(it, CollectorRegistry.defaultRegistry.filteredMetricFamilySamples(names)) }
                .use { it.toString() }

            call.respondText(
                contentType = ContentType.parse(TextFormat.CONTENT_TYPE_004),
                text = formatted
            )
        }
    }
}