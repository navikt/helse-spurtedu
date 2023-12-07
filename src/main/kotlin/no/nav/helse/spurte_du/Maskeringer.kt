package no.nav.helse.spurte_du

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import redis.clients.jedis.*
import redis.clients.jedis.exceptions.JedisException
import java.net.URI
import java.time.Duration
import java.util.*

class Maskeringer(
    private val jedisPool: JedisPool,
    private val objectMapper: ObjectMapper
) {

    suspend fun visMaskertVerdi(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
        try {
            jedisPool.resource.use { jedis ->
                val maskertVerdi = jedis.hget(maskerteVerdier, "$id") ?: return call.`404`(logg, "Finner ikke verdi i Redis")
                val verdi = MaskertVerdi.fraJson(objectMapper, maskertVerdi, logg) ?: return call.`404`(logg, "Kan ikke deserialisere verdi fra Redis")

                verdi.låsOpp(tilganger, call, logg)
            }
        } catch (err: JedisException) {
            logg.error("Feil ved tilkobling til Redis: {}", err.message, err)
            call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
        }
    }

    fun lagTestdata(logg: Logg) {
        jedisPool.resource.use { jedis ->
            createTestData(jedis, objectMapper)
            logg.info("Lagret testverdier til redis")
        }
    }
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

    companion object {
        private const val maskerteVerdier = "maskerte_verdier"

        fun lagMaskeringer(jedisPool: JedisPool, objectMapper: ObjectMapper): Maskeringer {
            return Maskeringer(jedisPool, objectMapper)
        }
    }
}