package no.nav.helse.spurte_du

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisPool
import java.net.URI
import java.util.*

fun Application.api(env: Map<String, String>, logg: Logg, objectMapper: ObjectMapper) {
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
    val pool = JedisPool(HostAndPort(uri.host, uri.port), config)
    pool.resource.use { jedis ->
        createTestData(jedis, objectMapper)
        logg.info("Lagret testverdier til redis")
    }

    routing {
        get("/vis_meg/{maskertId?}") {
            val id = call.parameters["maskertId"] ?: return@get call.respondText(
                "Maksert id mangler fra url",
                status = HttpStatusCode.BadRequest
            )
            val uuid = try {
                UUID.fromString(id)
            } catch (err: IllegalArgumentException) {
                return@get call.respondText(
                    "Maksert id er jo ikke gyldig uuid",
                    status = HttpStatusCode.BadRequest
                )
            }

            pool.resource.use { jedis ->
                val maskertVerdi = jedis.hget(maskerteVerdier, "$uuid") ?: return@get call.`404`(logg, "Finner ikke verdi i Redis")
                val verdi = MaskertVerdi.fraJson(objectMapper, maskertVerdi) ?: return@get call.`404`(logg, "Kan ikke deserialisere verdi fra Redis")

                val gruppeFraClaims = "<hent fra claims>"
                verdi.låsOpp(gruppeFraClaims, call, logg)
            }
        }
    }
}

private suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}

private const val maskerteVerdier = "maskerte_verdier"
private fun createTestData(jedis: Jedis, objectMapper: ObjectMapper) {
    val tbdgruppe = "f787f900-6697-440d-a086-d5bb56e26a9c"
    val testdata = listOf(
        MaskertVerdi.Url(UUID.fromString("e5d9ccfa-9197-4b1c-8a09-a36b94d38226"), "https://sporing.intern.dev.nav.no/tilstandsmaskin/a19560bf-f025-4b15-8bf1-f6cc716794ea"),
        MaskertVerdi.Url(UUID.fromString("4d1db5bf-dfdf-48cb-8c77-c5742590da1c"), "https://sporing.intern.dev.nav.no/tilstandsmaskin/384b160c-c95e-48fc-a872-17c9361abe50", tbdgruppe),
        MaskertVerdi.Tekst(UUID.fromString("c8a8cfc1-3d9c-4e98-a3d3-813b53a2177e"), "Hei, Verden!"),
        MaskertVerdi.Tekst(UUID.fromString("a56d4623-c376-41b7-9307-12623acae0e7"), "Hei, Tbd!", tbdgruppe)
    )
    testdata.forEach { it.lagre(jedis, maskerteVerdier, objectMapper) }
}

private sealed class MaskertVerdi {
    companion object {
        fun fraJson(objectMapper: ObjectMapper, json: String): MaskertVerdi? {
            val implementasjoner: List<(UUID, String, String?, JsonNode) -> MaskertVerdi?> = listOf(
                MaskertVerdi.Url::fraJson
            )
            return try {
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

    suspend fun låsOpp(tilgang: String?, call: ApplicationCall, logg: Logg) {
        if (påkrevdTilgang != null && tilgang != påkrevdTilgang) return call.`404`(logg, "Uautorisert tilgang kan ikke innfris")
        håndterRespons(call)
    }
    fun lagre(jedis: Jedis, nøkkel: String, objectMapper: ObjectMapper) {
        jedis.hset(nøkkel, "$id", json(objectMapper))
    }

    protected abstract suspend fun håndterRespons(call: ApplicationCall)
    protected abstract fun json(objectMapper: ObjectMapper): String

    protected fun json(objectMapper: ObjectMapper, type: String) = objectMapper.writeValueAsString(mapOf(
        "id" to id,
        "type" to type,
        "påkrevdTilgang" to påkrevdTilgang,
        "data" to data
    ))

    class Tekst(override val id: UUID, private val tekst: String, override val påkrevdTilgang: String? = null) : MaskertVerdi() {
        override val data = mapOf("tekst" to tekst)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Teksttype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            call.respondText("Jeg har fått beskjed om å vise deg <$tekst>")
        }
        companion object {
            private const val Teksttype = "tekst"
            fun fraJson(id: UUID, type: String, påkrevdTilgang: String?, node: JsonNode): Url? {
                if (type != Teksttype) return null
                return Url(id, node.path("data").path("tekst").asText(), påkrevdTilgang)
            }
        }
    }

    class Url(override val id: UUID, private val url: String, override val påkrevdTilgang: String? = null) : MaskertVerdi() {
        override val data = mapOf("url" to url)
        override fun json(objectMapper: ObjectMapper) = json(objectMapper, Urltype)
        override suspend fun håndterRespons(call: ApplicationCall) {
            call.respondText("Jeg ville ha videresendt deg til $url, men lar være akkurat nå!")
        }

        companion object {
            private const val Urltype = "url"
            fun fraJson(id: UUID, type: String, påkrevdTilgang: String?, node: JsonNode): Url? {
                if (type != Urltype) return null
                return Url(id, node.path("data").path("url").asText(), påkrevdTilgang)
            }
        }
    }
}