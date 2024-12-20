package no.nav.helse.spurte_du

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.*

sealed class MaskertVerdi {
    companion object {
        fun fraJson(objectMapper: ObjectMapper, json: String, logg: Logg): MaskertVerdi? {
            val implementasjoner: List<(UUID, String, ZonedDateTime, List<String>, JsonNode) -> MaskertVerdi?> = listOf(
                MaskertVerdi.Tekst::fraJson,
                MaskertVerdi.Url::fraJson
            )
            return try {
                logg.sikker().info("forsøker å deserialisere: $json")
                val node = objectMapper.readTree(json)
                if (!node.hasNonNull("id")) return null
                val id = UUID.fromString(node.path("id").asText())
                val type = node.path("type").takeIf(JsonNode::isTextual)?.asText() ?: return null
                val påkrevdTilgangNode = node.path("påkrevdTilgang")
                val påkrevdTilganger = when {
                    påkrevdTilgangNode.isTextual -> påkrevdTilgangNode.asText().split(',').map(String::trim)
                    else -> påkrevdTilgangNode.map { it.asText()}
                }
                val opprettet = node.path("opprettet")
                    .takeIf(JsonNode::isTextual)?.asText()
                    ?.let { ZonedDateTime.parse(it) }
                    ?: ZonedDateTime.ofInstant(Instant.EPOCH, ZoneId.systemDefault())
                return implementasjoner.firstNotNullOfOrNull { deserialiser ->
                    deserialiser(id, type, opprettet, påkrevdTilganger, node.path("data"))
                }
            } catch (err: JsonParseException) {
                null
            } catch (err: IllegalArgumentException) {
                null
            }
        }

    }

    protected abstract val id: UUID
    protected abstract val data: Map<String, String>
    protected open val opprettet: ZonedDateTime = ZonedDateTime.now()
    protected open val påkrevdTilganger: List<String> = emptyList()

    suspend fun låsOpp(tilganger: List<String>?, call: ApplicationCall, logg: Logg) {
        if (!vurderTilgang(call, logg, tilganger)) return
        håndterRespons(call)
    }

    suspend fun visMetadata(tilganger: List<String>?, call: ApplicationCall, logg: Logg) {
        if (!vurderTilgang(call, logg, tilganger)) return
        call.respondText("""{
    "opprettet": "${this.opprettet}"
}""", ContentType.Application.Json)
    }

    private suspend fun vurderTilgang(call: ApplicationCall, logg: Logg, tilganger: List<String>?): Boolean {
        if (påkrevdTilganger.isEmpty()) {
            logg.sikker().info("verdien har ingen påkrevde tilganger, og tilgangen innfris")
            return true
        }
        if (tilganger == null) {
            logg.sikker().info("verdien har påkrevde tilganger, men bruker er ikke innlogget")
            call.respondRedirect(url = "/oauth2/login?redirect=/vis_meg/$id", permanent = false)
            return false
        }

        val harMinstEnTilgang = påkrevdTilganger.any { it.lowercase() in tilganger.map(String::lowercase) }
        if (!harMinstEnTilgang) {
            logg.sikker().info("verdien har påkrevde tilganger (${påkrevdTilganger.joinToString()}), men bruker har ${tilganger.joinToString()}")
            call.`404`(logg, "Uautorisert tilgang kan ikke innfris. Pålogget bruker har ${tilganger.joinToString()}")
            return false
        }
        logg.sikker().info("tilgang innfris for ${påkrevdTilganger.joinToString()} for bruker med ${tilganger.joinToString()}")
        return true
    }

    fun lagre(maskeringer: Maskeringtjeneste, objectMapper: ObjectMapper): UUID {
        return maskeringer.lagre(id, json(objectMapper))
    }

    protected abstract suspend fun håndterRespons(call: ApplicationCall)
    protected abstract fun json(objectMapper: ObjectMapper): String

    protected fun json(objectMapper: ObjectMapper, type: String) = objectMapper.writeValueAsString(
        mapOf(
            "id" to id,
            "type" to type,
            "opprettet" to opprettet,
            "påkrevdTilgang" to påkrevdTilganger,
            "data" to data
        )
    )

    class Tekst(
        override val id: UUID,
        private val tekst: String,
        override val påkrevdTilganger: List<String> = emptyList(),
        override val opprettet: ZonedDateTime = ZonedDateTime.now()
    ) : MaskertVerdi() {
        override val data = mapOf("tekst" to tekst)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Teksttype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            call.respond(Response(tekst))
        }

        private data class Response(val text: String)

        companion object {
            private const val Teksttype = "tekst"
            fun fraJson(id: UUID, type: String, opprettet: ZonedDateTime, påkrevdTilgang: List<String>, data: JsonNode): Tekst? {
                if (type != Teksttype) return null
                return Tekst(id, data.path("tekst").asText(), påkrevdTilgang, opprettet)
            }
        }
    }

    class Url(
        override val id: UUID,
        private val url: String,
        override val påkrevdTilganger: List<String> = emptyList(),
        override val opprettet: ZonedDateTime = ZonedDateTime.now()
    ) : MaskertVerdi() {
        override val data = mapOf("url" to url)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Urltype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            try {
                val uri = URI(url)
                val tjenernavn = uri.host.lowercase()
                check(tjenernavn.endsWith("nav.no")) {
                    "tjeneren $tjenernavn er ikke tillatt"
                }
                call.respondRedirect(url, permanent = true)
            } catch (err: Exception) {
                call.respondText(text = "URL er ikke gyldig, eller peker til en ikke-tillatt tjener: <$url>: ${err.message}", status = HttpStatusCode.PreconditionFailed)
            }
        }

        companion object {
            private const val Urltype = "url"
            fun fraJson(id: UUID, type: String, opprettet: ZonedDateTime, påkrevdTilgang: List<String>, data: JsonNode): Url? {
                if (type != Urltype) return null
                return Url(id, data.path("url").asText(), påkrevdTilgang, opprettet)
            }
        }
    }
}