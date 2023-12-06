package no.nav.helse.spurte_du

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import redis.clients.jedis.*
import redis.clients.jedis.exceptions.JedisException
import java.net.URI
import java.net.URL
import java.time.Duration
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
    val poolConfig = JedisPoolConfig().apply {
        minIdle = 1 // minimum antall ledige tilkoblinger
        setMaxWait(Duration.ofSeconds(3)) // maksimal ventetid på tilkobling
        testOnBorrow = true // tester tilkoblingen før lån
        testWhileIdle = true // tester ledige tilkoblinger periodisk

    }
    val pool = JedisPool(poolConfig, HostAndPort(uri.host, uri.port), config)
    pool.resource.use { jedis ->
        createTestData(jedis, objectMapper)
        logg.info("Lagret testverdier til redis")
    }

    authentication {
        konfigurerJwtAuth(env)
    }

    routing {
        authenticate(optional = true) {
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

                try {
                    pool.resource.use { jedis ->
                        val maskertVerdi = jedis.hget(maskerteVerdier, "$uuid") ?: return@get call.`404`(
                            logg,
                            "Finner ikke verdi i Redis"
                        )
                        val verdi = MaskertVerdi.fraJson(objectMapper, maskertVerdi, logg) ?: return@get call.`404`(
                            logg,
                            "Kan ikke deserialisere verdi fra Redis"
                        )

                        val principal = call.principal<JWTPrincipal>()
                        logg.info("requested er ${principal?.let { "authenticated: ${principal.subject}" } ?: "ikke autentisert"}")
                        val gruppeFraClaims = call.hentGruppemedlemskap(logg, objectMapper)
                        verdi.låsOpp(gruppeFraClaims, call, logg)
                    }
                } catch (err: JedisException) {
                    logg.error("Feil ved tilkobling til Redis: {}", err.message, err)
                    return@get call.respondText(
                        "Nå røyk vi på en smell her. Vi får håpe det er forbigående!",
                        status = HttpStatusCode.InternalServerError
                    )
                } catch (err: Exception) {
                    logg.error("Ukjent feil oppstod: {}", err.message, err)
                    return@get call.respondText(
                        "Nå røyk vi på en smell her. Vi får håpe det er forbigående!",
                        status = HttpStatusCode.InternalServerError
                    )
                }
            }
        }
    }
}

private fun AuthenticationConfig.konfigurerJwtAuth(env: Map<String, String>) {
    jwt {
        val jwkProvider = JwkProviderBuilder(URL(env.getValue("AZURE_OPENID_CONFIG_JWKS_URI"))).build()
        verifier(jwkProvider, env.getValue("AZURE_OPENID_CONFIG_ISSUER")) {
            withAudience(env.getValue("AZURE_APP_CLIENT_ID"))
            withClaimPresence("groups")
        }
        validate { credentials -> JWTPrincipal(credentials.payload) }
    }
}

private suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}

private fun ApplicationCall.hentGruppemedlemskap(logg: Logg, objectMapper: ObjectMapper): List<String> {
    val httpAuthHeader = request.parseAuthorizationHeader() ?: return emptyList()
    if (httpAuthHeader !is HttpAuthHeader.Single) return emptyList()
    val bearerToken = httpAuthHeader.blob

    logg.info("Henter gruppemedlemskap fra microsoft graph")
    val httpClient = HttpClient(CIO)
    val body = runBlocking {
        val response = httpClient.get("https://graph.microsoft.com/v1.0/me/memberOf?\$select=id,displayName") {
            header(HttpHeaders.Authorization, "Bearer $bearerToken")
        }
        response.bodyAsText()
    }
    logg.sikker().info("respons fra microsoft graph:\n$body")
    return try {
        val json = objectMapper.readTree(body)
        json.path("value").map { medlemskap ->
            medlemskap.path("id").asText()
        }
    } catch (err: Exception) {
        emptyList()
    }
}

private const val maskerteVerdier = "maskerte_verdier"
private fun createTestData(jedis: Jedis, objectMapper: ObjectMapper) {
    val tbdgruppe = "f787f900-6697-440d-a086-d5bb56e26a9c"
    val testdata = listOf(
        MaskertVerdi.Url(
            UUID.fromString("e5d9ccfa-9197-4b1c-8a09-a36b94d38226"),
            "https://sporing.intern.dev.nav.no/tilstandsmaskin/a19560bf-f025-4b15-8bf1-f6cc716794ea"
        ),
        MaskertVerdi.Url(
            UUID.fromString("4d1db5bf-dfdf-48cb-8c77-c5742590da1c"),
            "https://sporing.intern.dev.nav.no/tilstandsmaskin/384b160c-c95e-48fc-a872-17c9361abe50",
            tbdgruppe
        ),
        MaskertVerdi.Tekst(UUID.fromString("c8a8cfc1-3d9c-4e98-a3d3-813b53a2177e"), "Hei, Verden!"),
        MaskertVerdi.Tekst(UUID.fromString("a56d4623-c376-41b7-9307-12623acae0e7"), "Hei, Tbd!", tbdgruppe)
    )
    testdata.forEach { it.lagre(jedis, maskerteVerdier, objectMapper) }
}

private sealed class MaskertVerdi {
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
            if (påkrevdTilgang !in tilganger) return call.`404`(
                logg,
                "Uautorisert tilgang kan ikke innfris. Pålogget bruker har ${tilganger.joinToString()}"
            )
        }
        håndterRespons(call)
    }

    fun lagre(jedis: Jedis, nøkkel: String, objectMapper: ObjectMapper) {
        jedis.hset(nøkkel, "$id", json(objectMapper))
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
            call.respondText("Jeg ville ha videresendt deg til $url, men lar være akkurat nå!")
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