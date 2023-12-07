package no.nav.helse.spurte_du

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

class Gruppetilganger(
    private val azureClient: AzureClient,
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper
) {
    fun hentGruppemedlemskap(bearerToken: String, logg: Logg): List<String> {
        logg.info("Henter gruppemedlemskap fra microsoft graph")
        val exchanged = bytteToken(logg, bearerToken) ?: return emptyList()
        return try {
            hentDirekteMedlemskap(logg, exchanged)
        } catch (err: Exception) {
            emptyList()
        }
    }


    private fun bytteToken(logg: Logg, bearerToken: String): String? {
        // bytte access token mot et scopet for bruk mot graph api
        return try {
            azureClient.veksleTilOnBehalfOf(bearerToken, "https://graph.microsoft.com/.default")
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
}