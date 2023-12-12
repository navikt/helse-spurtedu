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
import kotlin.math.log

fun Route.api(logg: Logg, maskeringer: Maskeringtjeneste) {
    route("/vis_meg") {
        get("{maskertId?}") {
            val (uuid, principal) = call.håndterVisMeg(logg) ?: return@get
            try {
                maskeringer.visMaskertVerdi(logg, call, uuid, principal?.claims)
            } catch (err: Exception) {
                logg.error("Ukjent feil oppstod: {}", err.message, err)
                return@get call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
            }
        }
        get("{maskertId}/metadata") {
            val (uuid, principal) = call.håndterVisMeg(logg) ?: return@get
            try {
                maskeringer.visMetadata(logg, call, uuid, principal?.claims)
            } catch (err: Exception) {
                logg.error("Ukjent feil oppstod: {}", err.message, err)
                return@get call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
            }
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
            <p>Om <code>påkrevdTilgang</code> utelates vil verdien være åpen for alle</p>   
            <p><code>påkrevdTilgang</code> kan være en Azure AD-gruppe ID eller en NAV epost-adresse (f.eks. om du vil sende til én mottaker)</p>   
            <h3>Skjule url</h3>
            <pre>curl -X POST -d '{ "url": "http://min-app.intern.nav.no/hemmelig", "påkrevdTilgang": "en gruppe" }' -H 'Content-type: application/json' /skjul_meg</pre>
            <pre>curl -X POST -d '{ "url": "http://min-app.intern.nav.no/hemmelig", "påkrevdTilgang": "en-epost-adresse@nav.no" }' -H 'Content-type: application/json' /skjul_meg</pre>
           
            <h3>Skjule tekst</h3>
            <pre>curl -X POST -d '{ "tekst": "Superhemmelig melding", "påkrevdTilgang": "en gruppe" }' -H 'Content-type: application/json' /skjul_meg</pre>
            
            <h3>Respons</h3>
            <p>Responsen inneholder felt for ID-en til den skjulte verdien, en absolutt-URL til visning, og path til visning</p>
            <pre>
{
    "id": "&lt;en uuid&gt;",
    "url": "https://spurte-du.intern.nav.no/vis_meg/&lt;uuid-en&gt;",
    "path": "/vis_meg/&lt;uuid-en&gt;"
}
            </pre>
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

private suspend fun ApplicationCall.håndterVisMeg(logg: Logg): Pair<UUID, SpurteDuPrinsipal?>? {
    val id = parameters["maskertId"]
    if (id == null) {
        respondText("Maksert id mangler fra url", status = HttpStatusCode.BadRequest)
        return null
    }
    val uuid = try {
        UUID.fromString(id)
    } catch (err: IllegalArgumentException) {
        respondText("Maksert id er jo ikke gyldig uuid", status = HttpStatusCode.BadRequest)
        return null
    }

    val principal = principal<SpurteDuPrinsipal>()
    principal.logg(logg)
    return uuid to principal
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
