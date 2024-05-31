package no.nav.helse.spurte_du

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import no.nav.helse.spurte_du.LokalBruker.Companion.håndterAutentisering
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*


fun main() {
    // "logg på" med de lokale brukere ved å slenge på query parameter 'bruker' i url:
    // http://0.0.0.0:8080/vis_meg/<uuid>?bruker=david
    val lokaleBrukere = listOf(
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
    val lokaleMaskeringer = listOf(
        MaskertVerdi.Tekst(UUID.fromString("1575977c-9b3b-4703-ac6a-7db5e14cd9df"), "Hei, Verden"),
        MaskertVerdi.Tekst(UUID.fromString("ec0d49ab-b42a-4626-a7de-3951d9ed5038"), "Hei, Gruppe1", listOf("gruppe1")),
        MaskertVerdi.Tekst(UUID.fromString("06299de5-b51a-4ff1-a83b-7f3db621791a"), "Hei, Bømlo!", listOf("bomlo@nav.no")),
    )

    val logg = Logg(LoggerFactory.getLogger("åpenLogg"), LoggerFactory.getLogger("sikkerLogg"))
    val objectMapper = jacksonObjectMapper()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .registerModule(JavaTimeModule())
        .setDefaultPrettyPrinter(DefaultPrettyPrinter().apply {
            indentArraysWith(DefaultPrettyPrinter.FixedSpaceIndenter.instance)
            indentObjectsWith(DefaultIndenter("  ", "\n"))
        })
    val maskeringer = LokaleMaskeringer(lokaleMaskeringer, objectMapper)
    val prinsipaler = LokalePrinsipaler(logg, lokaleBrukere)

    embeddedServer(CIO, 8080, module = {
        authentication {
            provider {
                authenticate { context ->
                    prinsipaler.håndterAutentisering(logg, context)
                }
            }
        }
        lagApplikasjonsmodul(logg, objectMapper, maskeringer)
    }).start(wait = true)
}

class LokalBruker(
    private val navn: String,
    private val claims: Map<String, String>,
    private val grupper: List<String>
) {
    private val jwtPrinsipal = JWTPrincipal(LokalePrinsipaler.LokalePayload(claims))

    private fun somPrinsipal() = when {
        claims.containsKey("azp_name") -> SpurteDuPrinsipal.MaskinPrincipal(jwtPrincipal = jwtPrinsipal)
        else -> SpurteDuPrinsipal.BrukerPrincipal(
            jwtPrincipal = jwtPrinsipal,
            gruppetilganger = grupper
        )
    }
    companion object {
        fun List<LokalBruker>.håndterAutentisering(context: AuthenticationContext, logg: Logg, bruker: String) {
            val lokalBruker = somPrinsipal(logg, bruker) ?: return logg.info("logger ikke på fordi <bruker> er ikke satt i query string")
            logg.info("setter en forfalsket principal i application-konteksten for: $bruker")
            context.principal(lokalBruker)
        }
        private fun List<LokalBruker>.somPrinsipal(logg: Logg, bruker: String): SpurteDuPrinsipal? {
            val lokalBruker = firstOrNull { it.navn == bruker } ?: return null
            logg.info("logger på lokal bruker $bruker")
            return lokalBruker.somPrinsipal()
        }
    }
}

private class LokalePrinsipaler(private val logg: Logg, private val lokaleBrukere: List<LokalBruker>) {
    fun håndterAutentisering(logg: Logg, context: AuthenticationContext) {
        val bruker = context.call.parameters["bruker"] ?: return
        lokaleBrukere.håndterAutentisering(context, logg, bruker)
    }

    class LokalePayload(claims: Map<String, String>) : Payload {
        private val claims = claims.mapValues { LokaleClaim(it.value) }
        override fun getIssuer(): String {
            return "lokal utsteder"
        }

        override fun getSubject(): String {
            return "lokal subjekt"
        }

        override fun getAudience(): List<String> {
            return listOf("lokal publikum")
        }

        override fun getExpiresAt(): Date {
            return Date.from(Instant.MAX)
        }

        override fun getNotBefore(): Date {
            return Date.from(Instant.EPOCH)
        }

        override fun getIssuedAt(): Date {
            return Date.from(Instant.now())
        }

        override fun getId(): String {
            return "lokal id"
        }

        override fun getClaim(name: String): Claim {
            return claims.getValue(name)
        }

        override fun getClaims(): Map<String, Claim> {
            return claims
        }
    }

    private class LokaleClaim(private val verdi: String) : Claim {
        override fun isNull() = false
        override fun isMissing() = false
        override fun asBoolean() = true
        override fun asInt() = 0
        override fun asLong() = 0L
        override fun asDouble() = 0.0
        override fun asString() = verdi
        override fun asDate() = Date.from(Instant.EPOCH)
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> asArray(clazz: Class<T>?) = emptyArray<Any>() as Array<T>
        @Suppress("UNCHECKED_CAST")
        override fun <T : Any?> asList(clazz: Class<T>?) = emptyList<Any>() as List<T>
        override fun asMap() = emptyMap<String, Any>()
        override fun <T : Any?> `as`(clazz: Class<T>?) = throw NotImplementedError()
    }
}

private class LokaleMaskeringer(lokaleMaskeringer: List<MaskertVerdi>, private val objectMapper: ObjectMapper) : Maskeringtjeneste {
    private val maskeringer = mutableMapOf<UUID, String>()

    init {
        lokaleMaskeringer.forEach { it.lagre(this, objectMapper) }
    }

    override suspend fun visMaskertVerdi(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
        val data = maskeringer[id] ?: return call.`404`(logg, "Fant ikke $id i lokale maskeringer")
        val maskertVerdi = MaskertVerdi.fraJson(objectMapper, data, logg) ?: return call.`404`(logg, "Kan ikke deserialisere lokal maskert verdi")
        maskertVerdi.låsOpp(tilganger, call, logg)
    }

    override suspend fun visMetadata(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
        val data = maskeringer[id] ?: return call.`404`(logg, "Fant ikke $id i lokale maskeringer")
        val maskertVerdi = MaskertVerdi.fraJson(objectMapper, data, logg) ?: return call.`404`(logg, "Kan ikke deserialisere lokal maskert verdi")
        maskertVerdi.visMetadata(tilganger, call, logg)
    }

    override fun lagre(id: UUID, data: String): UUID {
        maskeringer[id] = data
        return id
    }

    override fun lagre(maskertVerdi: MaskertVerdi): UUID {
        return maskertVerdi.lagre(this, objectMapper)
    }
}