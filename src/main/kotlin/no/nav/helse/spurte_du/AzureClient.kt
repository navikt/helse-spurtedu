package no.nav.helse.spurte_du

import com.auth0.jwk.JwkProvider
import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import kotlinx.coroutines.runBlocking

class AzureClient(
    private val jwkProvider: JwkProvider,
    private val issuer: String,
    private val httpClient: HttpClient,
    private val tokenEndpoint: String,
    private val clientId: String,
    private val clientSecret: String,
    private val objectMapper: ObjectMapper
) {

    fun veksleTilOnBehalfOf(token: String, scope: String): String {
        return runBlocking {
            val response = httpClient.post(tokenEndpoint) {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(FormDataContent(parameters {
                    append("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer")
                    append("client_id", clientId)
                    append("client_secret", clientSecret)
                    append("assertion", token)
                    append("scope", scope)
                    append("requested_token_use", "on_behalf_of")
                }))
            }
            val body = response.bodyAsText()
            val json = objectMapper.readTree(body)
            json.path("access_token").asText()
        }
    }

    fun konfigurerJwtAuth(config: AuthenticationConfig) {
        config.jwt {
            verifier(jwkProvider, issuer) {
                withAudience(clientId)
                withClaimPresence("preferred_username")
                withClaimPresence("name")
            }
            validate { credentials -> JWTPrincipal(credentials.payload) }
        }
    }
}