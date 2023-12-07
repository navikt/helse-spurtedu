package no.nav.helse.spurte_du

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest
import java.time.Duration

interface Gruppetilgangtjeneste {
    fun hentGruppemedlemskap(bearerToken: String, logg: Logg): List<String>
}

class Gruppetilganger(
    private val jedisPool: JedisPool,
    private val azureClient: AzureClient,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) : Gruppetilgangtjeneste {
    override fun hentGruppemedlemskap(bearerToken: String, logg: Logg): List<String> {
        return hentMedlemskapFraMellomlager(bearerToken, logg) ?: hentMedlemskapFraAzure(bearerToken, logg)
    }

    private fun hentMedlemskapFraAzure(bearerToken: String, logg: Logg): List<String> {
        logg.info("Henter gruppemedlemskap fra microsoft graph")
        val exchanged = bytteToken(logg, bearerToken) ?: return emptyList()
        return try {
            hentDirekteMedlemskap(logg, exchanged).also {
                jedisPool.resource.use { jedis ->
                    logg.info("Lagrer gruppemedlemskap til mellomlager")
                    jedis.set(mellomlagringsnøkkel(bearerToken), objectMapper.writeValueAsString(mapOf(
                        "grupper" to it
                    )), SetParams.setParams().ex(Duration.ofMinutes(30).toSeconds()))
                }
            }
        } catch (err: Exception) {
            emptyList()
        }
    }

    private fun hentMedlemskapFraMellomlager(token: String, logg: Logg): List<String>? {
        return try {
            jedisPool.resource.use { jedis ->
                jedis.get(mellomlagringsnøkkel(token))?.let { mellomlagretVerdi ->
                    logg.info("Henter gruppemedlemskap fra mellomlager")
                    objectMapper.readTree(mellomlagretVerdi).path("grupper").takeIf(JsonNode::isArray)?.map { it.asText() }
                }
            }
        } catch (err: Exception) {
            logg.info("Fikk en feil ved henting av verdi fra Redis")
            return null
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun mellomlagringsnøkkel(token: String): String {
        val nøkkel = "${Gruppetilgangnøkkel}$token".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(nøkkel)
        return digest.toHexString()
    }

    private fun bytteToken(logg: Logg, bearerToken: String): String? {
        // bytte access token mot et scopet for bruk mot graph api
        return try {
            azureClient.veksleTilOnBehalfOf(logg, bearerToken, "https://graph.microsoft.com/.default")
        } catch (err: Exception) {
            logg.info("fikk problemer ved bytting av token")
            null
        }
    }

    private fun hentDirekteMedlemskap(logg: Logg, token: String): List<String> {
        val body = runBlocking {
            val response = httpClient.get("https://graph.microsoft.com/v1.0/me/memberOf?\$select=id,displayName") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            response.bodyAsText()
        }
        logg.sikker().info("respons fra microsoft graph:\n$body")
        val json = objectMapper.readTree(body)
        return json.path("value").map { medlemskap ->
            medlemskap.path("id").asText()
        }
    }

    private companion object {
        private const val Gruppetilgangnøkkel = "grupper_"
    }
}