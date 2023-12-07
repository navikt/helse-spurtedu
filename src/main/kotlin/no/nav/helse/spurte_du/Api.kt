package no.nav.helse.spurte_du

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.util.*
import no.nav.helse.spurte_du.SpurteDuPrinsipal.Companion.logg
import java.util.*

fun Route.api(logg: Logg, maskeringer: Maskeringtjeneste) {
    get("/vis_meg/{maskertId?}") {
        val id = call.parameters["maskertId"] ?: return@get call.respondText("Maksert id mangler fra url", status = HttpStatusCode.BadRequest)
        val uuid = try {
            UUID.fromString(id)
        } catch (err: IllegalArgumentException) {
            return@get call.respondText("Maksert id er jo ikke gyldig uuid", status = HttpStatusCode.BadRequest)
        }

        val principal = call.principal<SpurteDuPrinsipal>()
        principal.logg(logg)
        try {
            maskeringer.visMaskertVerdi(logg, call, uuid, principal?.claims)
        } catch (err: Exception) {
            logg.error("Ukjent feil oppstod: {}", err.message, err)
            return@get call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
        }
    }
    get("/skjul_meg") {
        call.respondText("""<!doctype html>
            <head>
                <meta charset="utf-8" />
                <title>Skjul meg!</title>
            </head>
           <body>
            <h1>Skjul meg</h1>
            <form action="/skjul_meg" method="post">
                <fieldset>
                    <legend>Url</legend>
                    <input type="text" name="url" />
                 </fieldset>
                <fieldset>
                    <legend>Tekst</legend>
                    <input type="text" name="tekst" />
                 </fieldset>
                <fieldset>
                    <legend>Påkrevd tilgang</legend>
                    <input type="text" name="påkrevdTilgang" />
                    <p>
                        Kan enten være en Azure AD-gruppeID eller en Nav-epost.
                    </p>
                 </fieldset>
                 <fieldset>
                    <button type="submit">Send inn rakker'n</button>
                 </fieldset>
            </form>
            <h2>API-bruk</h2>
            
            <p>Du kan sende en POST-request til samme url:</p>
            <pre>curl -X POST -d '{ "url": "http://min-app.intern.nav.no/hemmelig", "påkrevdTilgang": "en gruppe" }' -H 'Content-type: application/json' /skjul_meg</pre>
           </body>
           </html>
        """, ContentType.Text.Html)
    }
    post("/skjul_meg") {
        val request = call.receiveNullable<SkjulMegRequest>()
        val maskertVerdi = request?.tilMaskertVerdi() ?: return@post call.respond(HttpStatusCode.BadRequest, ApiFeilmelding(
            """Du må angi en gyldig json-kropp. Eksempel: { "url": "en-url", "påkrevdTilgang": "<en azure gruppe-ID eller NAV-epost>" } eller { "tekst": "en tekst" } """
        ))
        val id = maskeringer.lagre(maskertVerdi)
        val path = "/vis_meg/$id"
        call.respond(SkjulMegRespons(
            id = id,
            url = call.url {
                set("https", port = 443)
                path(path)
           },
            path = path
        ))
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
    fun tilMaskertVerdi(): MaskertVerdi? {
        val tilgang = påkrevdTilgang.takeUnless { it.isNullOrBlank() }
        return when {
            url?.takeUnless { it.isBlank() } != null -> MaskertVerdi.Url(UUID.randomUUID(), url, tilgang)
            tekst?.takeUnless { it.isBlank() } != null -> MaskertVerdi.Tekst(UUID.randomUUID(), tekst, tilgang)
            else -> null
        }
    }
}

suspend fun ApplicationCall.`404`(logg: Logg, hjelpetekst: String) {
    logg.info(hjelpetekst)
    respondText("Finner ikke noe spesielt til deg", status = HttpStatusCode.NotFound)
}
