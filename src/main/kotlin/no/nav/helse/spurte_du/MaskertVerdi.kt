package no.nav.helse.spurte_du

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import java.net.URI
import java.util.*

sealed class MaskertVerdi {
    companion object {
        fun fraJson(objectMapper: ObjectMapper, json: String, logg: Logg): MaskertVerdi? {
            val implementasjoner: List<(UUID, String, String?, JsonNode) -> MaskertVerdi?> = listOf(
                MaskertVerdi.Tekst::fraJson,
                MaskertVerdi.Url::fraJson
            )
            return try {
                logg.sikker().info("forsøker å deserialisere: $json")
                val node = objectMapper.readTree(json)
                if (!node.hasNonNull("id")) return null
                val id = UUID.fromString(node.path("id").asText())
                val type = node.path("type").takeIf(JsonNode::isTextual)?.asText() ?: return null
                val påkrevdTilgang = node.path("påkrevdTilgang").takeIf(JsonNode::isTextual)?.asText()
                return implementasjoner.firstNotNullOfOrNull { deserialiser ->
                    deserialiser(id, type, påkrevdTilgang, node.path("data"))
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
    protected open val påkrevdTilgang: String? = null

    suspend fun låsOpp(tilganger: List<String>?, call: ApplicationCall, logg: Logg) {
        if (påkrevdTilgang != null) {
            if (tilganger == null) return call.respondRedirect(
                url = "/oauth2/login?redirect=/vis_meg/$id",
                permanent = false
            )
            if (påkrevdTilgang?.lowercase() !in tilganger.map(String::lowercase)) return call.`404`(
                logg,
                "Uautorisert tilgang kan ikke innfris. Pålogget bruker har ${tilganger.joinToString()}"
            )
        }
        håndterRespons(call)
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
            "påkrevdTilgang" to påkrevdTilgang,
            "data" to data
        )
    )

    class Tekst(override val id: UUID, private val tekst: String, override val påkrevdTilgang: String? = null) :
        MaskertVerdi() {
        override val data = mapOf("tekst" to tekst)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Teksttype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            call.respondText("Jeg har fått beskjed om å vise deg <$tekst>")
        }

        companion object {
            private const val Teksttype = "tekst"
            fun fraJson(id: UUID, type: String, påkrevdTilgang: String?, data: JsonNode): Tekst? {
                if (type != Teksttype) return null
                return Tekst(id, data.path("tekst").asText(), påkrevdTilgang)
            }
        }
    }

    class Url(override val id: UUID, private val url: String, override val påkrevdTilgang: String? = null) :
        MaskertVerdi() {
        override val data = mapOf("url" to url)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Urltype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            try {
                val uri = URI(url)
                check(uri.host.lowercase().endsWith("nav.no"))
                call.respondRedirect(url, permanent = true)
            } catch (err: Exception) {
                call.respondText(text = "URL er ikke gyldig, eller peker til en ikke-tillatt tjener: <$url>", status = HttpStatusCode.PreconditionFailed)
            }
        }

        companion object {
            private const val Urltype = "url"
            fun fraJson(id: UUID, type: String, påkrevdTilgang: String?, data: JsonNode): Url? {
                if (type != Urltype) return null
                return Url(id, data.path("url").asText(), påkrevdTilgang)
            }
        }
    }
}