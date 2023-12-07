package no.nav.helse.spurte_du

import com.auth0.jwk.JwkProviderBuilder
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import java.net.URL
import java.util.*

fun Application.api(env: Map<String, String>, logg: Logg, objectMapper: ObjectMapper, maskeringer: Maskeringer) {
    maskeringer.lagTestdata(logg)

    authentication {
        konfigurerJwtAuth(env)
    }

    routing {
        authenticate(optional = true) {
            get("/vis_meg/{maskertId?}") {
                val id = call.parameters["maskertId"] ?: return@get call.respondText("Maksert id mangler fra url", status = HttpStatusCode.BadRequest)
                val uuid = try {
                    UUID.fromString(id)
                } catch (err: IllegalArgumentException) {
                    return@get call.respondText("Maksert id er jo ikke gyldig uuid", status = HttpStatusCode.BadRequest)
                }

                val principal = call.principal<JWTPrincipal>()
                logg.info("requested er ${principal?.let { "authenticated: ${principal["name"]} (${principal["preferred_username"]})" } ?: "ikke autentisert"}")
                val gruppemedlemskap = call.hentGruppemedlemskap(env, logg, objectMapper)

                try {
                    maskeringer.visMaskertVerdi(logg, call, uuid, gruppemedlemskap)
                } catch (err: Exception) {
                    logg.error("Ukjent feil oppstod: {}", err.message, err)
                    return@get call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
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
            withClaimPresence("preferred_username")
            withClaimPresence("name")
        }
        validate { credentials -> JWTPrincipal(credentials.payload) }
    }
}

suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}

private fun ApplicationCall.hentGruppemedlemskap(env: Map<String, String>, logg: Logg, objectMapper: ObjectMapper): List<String> {
    val httpAuthHeader = request.parseAuthorizationHeader() ?: return emptyList()
    if (httpAuthHeader !is HttpAuthHeader.Single) return emptyList()
    val bearerToken = httpAuthHeader.blob

    logg.info("Henter gruppemedlemskap fra microsoft graph")
    val httpClient = HttpClient(CIO)

    // bytte access token mot et scopet for bruk mot graph api
    val exchanged = try {
        runBlocking {
            val response = httpClient.post(env.getValue("AZURE_OPENID_CONFIG_TOKEN_ENDPOINT")) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("client_id", env.getValue("AZURE_APP_CLIENT_ID"))
                    append("client_secret", env.getValue("AZURE_APP_CLIENT_SECRET"))
                    append("assertion", bearerToken)
                    append("scope", "https://graph.microsoft.com/.default")
                    append("requested_token_use", "on_behalf_of")
                }))
            }
            val body = response.bodyAsText()
            val json = objectMapper.readTree(body)
            json.path("access_token").asText()
        }
    } catch (err: Exception) {
        logg.info("fikk problemer ved bytting av token")
        return emptyList()
    }

    val body = runBlocking {
        val response = httpClient.get("https://graph.microsoft.com/v1.0/me/memberOf?\$select=id,displayName") {
            header(HttpHeaders.Authorization, "Bearer $exchanged")
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
