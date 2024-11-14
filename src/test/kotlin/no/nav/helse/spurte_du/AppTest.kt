package no.nav.helse.spurte_du

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.navikt.tbd_libs.naisful.test.TestContext
import com.github.navikt.tbd_libs.naisful.test.naisfulTestApp
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.server.auth.authentication
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.slf4j.LoggerFactory
import java.util.*

private val objectmapper get() = jacksonObjectMapper()
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .registerModule(JavaTimeModule())
    .setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
        indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
        indentObjectsWith(DefaultIndenter("  ", "\n"))
    })

class AppTest {

    private val testlogg = Logg(
        åpenLogger = LoggerFactory.getLogger("åpenLogg"),
        sikkerLogger = LoggerFactory.getLogger("secureLogs"),
    )

    @Test
    fun `unauthenticated - create secret`() = spurteDuE2E {
        client.post("/skjul_meg") {
            contentType(ContentType.Application.Json)
            setBody(objectmapper.writeValueAsString(mapOf(
                "tekst" to "Hemmelig!",
                "påkrevdTilgang" to "gruppe1"
            )))
        }.let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertDoesNotThrow {
                response.body<ExpectedSkjulMegResponse>()
            }
        }
    }

    @Test
    fun `unauthenticated - access to public value`() = spurteDuE2E {
        client.get("/vis_meg/1575977c-9b3b-4703-ac6a-7db5e14cd9df").let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Hei, Verden", response.body<ExpectedTextResponse>().text)
        }
    }

    @Test
    fun `authenticated - access to public value`() = spurteDuE2E {
        client.get("/vis_meg/1575977c-9b3b-4703-ac6a-7db5e14cd9df?bruker=stord").let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Hei, Verden", response.body<ExpectedTextResponse>().text)
        }
    }

    @Test
    fun `unauthenticated - no access to private value`() = spurteDuE2E {
        client.get("/vis_meg/ec0d49ab-b42a-4626-a7de-3951d9ed5038").let { response ->
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun unauthorized() = spurteDuE2E {
        client.get("/vis_meg/ec0d49ab-b42a-4626-a7de-3951d9ed5038?bruker=stord").let { response ->
            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun authorized() = spurteDuE2E {
        client.get("/vis_meg/ec0d49ab-b42a-4626-a7de-3951d9ed5038?bruker=bømlo").let { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Hei, Gruppe1", response.body<ExpectedTextResponse>().text)
        }
    }

    private data class ExpectedSkjulMegResponse(
        val id: UUID,
        val url: String,
        val path: String
    )
    private data class ExpectedTextResponse(val text: String)

    fun spurteDuE2E(testblokk: suspend TestContext.() -> Unit) {
        naisfulTestApp(
            testApplicationModule = {
                val lokaleMaskeringer = listOf(
                    MaskertVerdi.Tekst(UUID.fromString("1575977c-9b3b-4703-ac6a-7db5e14cd9df"), "Hei, Verden"),
                    MaskertVerdi.Tekst(UUID.fromString("ec0d49ab-b42a-4626-a7de-3951d9ed5038"), "Hei, Gruppe1", listOf("gruppe1")),
                    MaskertVerdi.Tekst(UUID.fromString("06299de5-b51a-4ff1-a83b-7f3db621791a"), "Hei, Bømlo!", listOf("bomlo@nav.no")),
                )
                val maskeringer = LokaleMaskeringer(lokaleMaskeringer, objectmapper)

                authentication {
                    provider {
                        authenticate { context ->
                            prinsipaler.håndterAutentisering(testlogg, context)
                        }
                    }
                }
                lagApplikasjonsmodul(testlogg, objectmapper, maskeringer)
            },
            objectMapper = objectmapper,
            meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT),
            testblokk = testblokk
        )
    }

    private val lokaleBrukere = listOf(
        LokalBruker(
            navn = "bømlo",
            claims = mapOf(
                "name" to "Bømlosen, Bømlo",
                "preferred_username" to "bomlo@nav.no"
            ),
            grupper = listOf("gruppe1")
        ),
        LokalBruker(
            navn = "stord",
            claims = mapOf(
                "name" to "Storden, Stord",
                "preferred_username" to "stord@nav.no"
            ),
            grupper = listOf("gruppe2")
        ),
        LokalBruker(
            navn = "maskinbruker",
            claims = mapOf(
                "azp_name" to "spleis-api"
            ),
            grupper = emptyList()
        )
    )
    private val prinsipaler = LokalePrinsipaler(testlogg, lokaleBrukere)
}