package no.nav.helse.spurte_du

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import redis.clients.jedis.JedisPool
import redis.clients.jedis.exceptions.JedisException
import java.util.*

interface Maskeringtjeneste {
    suspend fun visMaskertVerdi(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?)
    suspend fun visMetadata(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?)
    fun lagre(maskertVerdi: MaskertVerdi): UUID
    fun lagre(id: UUID, data: String): UUID
}

class Maskeringer(
    private val jedisPool: JedisPool,
    private val objectMapper: ObjectMapper
) : Maskeringtjeneste {

    override suspend fun visMaskertVerdi(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
        val verdi = hentMaskertVerdi(call, logg, id) ?: return
        verdi.låsOpp(tilganger, call, logg)
    }

    override suspend fun visMetadata(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
        val verdi = hentMaskertVerdi(call, logg, id) ?: return
        verdi.visMetadata(tilganger, call, logg)
    }

    private suspend fun hentMaskertVerdi(call: ApplicationCall, logg: Logg, id: UUID): MaskertVerdi? {
        try {
            jedisPool.resource.use { jedis ->
                val maskertVerdi = jedis.hget(maskerteVerdier, "$id")
                if (maskertVerdi == null) {
                    call.`404`(logg, "Finner ikke verdi i Valkey")
                    return null
                }
                val verdi = MaskertVerdi.fraJson(objectMapper, maskertVerdi, logg)
                if (verdi == null) call.`404`(logg, "Kan ikke deserialisere verdi fra Valkey")
                return verdi
            }
        } catch (err: JedisException) {
            logg.error("Feil ved tilkobling til Valkey: {}", err.message, err)
            call.respondText("Nå røyk vi på en smell her. Vi får håpe det er forbigående!", status = HttpStatusCode.InternalServerError)
            return null
        }
    }

    override fun lagre(maskertVerdi: MaskertVerdi): UUID {
        return maskertVerdi.lagre(this, objectMapper)
    }

    override fun lagre(id: UUID, data: String): UUID {
        return jedisPool.resource.use { jedis ->
            jedis.hset(maskerteVerdier, "$id", data)
            id
        }
    }

    companion object {
        private const val maskerteVerdier = "maskerte_verdier"

        fun lagMaskeringer(jedisPool: JedisPool, objectMapper: ObjectMapper): Maskeringer {
            return Maskeringer(jedisPool, objectMapper)
        }
    }
}
