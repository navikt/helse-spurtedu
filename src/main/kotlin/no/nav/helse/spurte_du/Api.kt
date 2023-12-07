package no.nav.helse.spurte_du

import io.ktor.http.*
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.util.*

fun Application.api(env: Map<String, String>, logg: Logg, gruppetilganger: Gruppetilganger, maskeringer: Maskeringer) {
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
                val gruppemedlemskap = call.bearerToken?.let { gruppetilganger.hentGruppemedlemskap(it, logg) }

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

private val ApplicationCall.bearerToken: String? get() {
    val httpAuthHeader = request.parseAuthorizationHeader() ?: return null
    if (httpAuthHeader !is HttpAuthHeader.Single) return null
    return httpAuthHeader.blob
}

suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}
