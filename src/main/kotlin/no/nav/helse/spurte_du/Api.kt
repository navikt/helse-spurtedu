package no.nav.helse.spurte_du

import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import java.util.*

fun Application.api(logg: Logg, gruppetilganger: Gruppetilganger, maskeringer: Maskeringer) {
    maskeringer.lagTestdata(logg)

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
                val claimsFraToken = listOfNotNull(principal?.get("preferred_username"))
                val gruppemedlemskap = call.bearerToken?.let { gruppetilganger.hentGruppemedlemskap(it, logg) } ?: emptyList()
                val claims = (claimsFraToken + gruppemedlemskap).takeUnless { it.isEmpty() }
                try {
                    maskeringer.visMaskertVerdi(logg, call, uuid, claims)
                } catch (err: Exception) {
                    logg.error("Ukjent feil oppstod: {}", err.message, err)
                    return@get call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
                }
            }
        }
        post("/skjul_meg") {
            val request = call.receive<SkjulMegRequest>()
            val maskertVerdi = request.tilMaskertVerdi() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiFeilmelding(
                """Du må angi en gyldig json-kropp. Eksempel: { "url": "en-url", "påkrevdTilgang": "<en azure gruppe-ID eller NAV-epost>" } eller { "tekst": "en tekst" } """
            ))
            val id = maskeringer.lagre(maskertVerdi)
            val path = "/vis_meg/$id"
            call.respond(SkjulMegRespons(
                id = id,
                url = call.url {
                    set("https")
                    path(path)
               },
                path = path
            ))
        }
    }
}

data class ApiFeilmelding(
    val feilbeskrivelse: String
)
data class SkjulMegRespons(
    val id: UUID,
    val url: String,
    val path: String
)
data class SkjulMegRequest(
    val url: String?,
    val tekst: String?,
    val påkrevdTilgang: String?
) {
    fun tilMaskertVerdi() = when {
        url != null -> MaskertVerdi.Url(UUID.randomUUID(), url, påkrevdTilgang)
        tekst != null -> MaskertVerdi.Tekst(UUID.randomUUID(), tekst, påkrevdTilgang)
        else -> null
    }
}

private val ApplicationCall.bearerToken: String? get() {
    val httpAuthHeader = request.parseAuthorizationHeader() ?: return null
    if (httpAuthHeader !is HttpAuthHeader.Single) return null
    return httpAuthHeader.blob
}

suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}
