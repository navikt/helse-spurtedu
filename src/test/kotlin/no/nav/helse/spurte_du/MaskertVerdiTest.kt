package no.nav.helse.spurte_du

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.server.application.ApplicationCall
import io.ktor.server.testing.TestApplicationCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.slf4j.LoggerFactory
import java.util.UUID

class MaskertVerdiTest {

    @Test
    fun `serialisering`() {
        val testMaskeringstjeneste = TestMaskeringstjeneste()
        val objectMapper = jacksonObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val verdi = MaskertVerdi.Tekst(UUID.fromString("ec0d49ab-b42a-4626-a7de-3951d9ed5038"), "Hei, Gruppe1", listOf("gruppe1"))

        verdi.lagre(testMaskeringstjeneste, objectMapper)
        assertTrue(testMaskeringstjeneste.lagrede.isNotEmpty())
        val serialisert = testMaskeringstjeneste.lagrede.single()
        val deserialisert = MaskertVerdi.fraJson(objectMapper, serialisert, testlogg) ?: fail { "forventet å kunne deserialisere" }

        deserialisert.lagre(testMaskeringstjeneste, objectMapper)
        assertEquals(2, testMaskeringstjeneste.lagrede.size)

        val serialisert2 = testMaskeringstjeneste.lagrede.last()

        assertEquals(serialisert, serialisert2)
    }

    private val testlogg = Logg(
        åpenLogger = LoggerFactory.getLogger("åpenLogg"),
        sikkerLogger = LoggerFactory.getLogger("secureLogs"),
    )

    private class TestMaskeringstjeneste() : Maskeringtjeneste {
        val lagrede = mutableListOf<String>()

        override suspend fun visMaskertVerdi(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
            TODO("Not yet implemented")
        }
        override suspend fun visMetadata(logg: Logg, call: ApplicationCall, id: UUID, tilganger: List<String>?) {
            TODO("Not yet implemented")
        }

        override fun lagre(maskertVerdi: MaskertVerdi): UUID {
            TODO("Not yet implemented")
        }

        override fun lagre(id: UUID, data: String): UUID {
            lagrede.add(data)
            return UUID.randomUUID()
        }
    }
}