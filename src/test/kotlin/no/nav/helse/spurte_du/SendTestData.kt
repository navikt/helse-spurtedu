package no.nav.helse.spurte_du

import com.google.gson.JsonParseException
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.module.kotlin.jacksonMapperBuilder
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun main() {
    val objectMapper =
        jacksonMapperBuilder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()

    println(sendRequest(objectMapper, mapOf("tekst" to "skjul denne meldingen")))
}

private fun sendRequest(
    objectMapper: ObjectMapper,
    data: Map<String, String>,
): String? {
    val httpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()

    val jsonInputString = objectMapper.writeValueAsString(data)

    val request =
        HttpRequest
            .newBuilder()
            .uri(URI("http://localhost:8080/skjul_meg"))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonInputString))
            .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

    return try {
        objectMapper.readTree(response.body()).path("path").asString()
    } catch (err: JsonParseException) {
        null
    }
}
