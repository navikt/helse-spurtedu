package no.nav.helse.spurte_du

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.jackson.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.callid.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.micrometer.core.instrument.binder.jvm.ClassLoaderMetrics
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.Clock
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import net.logstash.logback.argument.StructuredArguments.keyValue
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.CharArrayWriter
import java.util.*

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
    val app = embeddedServer(
        factory = CIO,
        environment = applicationEngineEnvironment {
            log = logg
            connectors.add(EngineConnectorBuilder().apply {
                this.port = 8080
            })
            module {
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
                install(ContentNegotiation) { register(ContentType.Application.Json, JacksonConverter(objectmapper)) }
                requestResponseTracing(logg.nyLogg("no.nav.helse.spurte_du.api.Tracing"))
                nais()
                api(env, logg, objectmapper)
            }
        }
    )
    app.start(wait = true)
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
            logger.error("exception thrown during processing: ${err.message} callId=${call.callId}")
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