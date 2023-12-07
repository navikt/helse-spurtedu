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
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest

class AzureClient(
    private val jwkProvider: JwkProvider,
    private val issuer: String,
    private val jedisPool: JedisPool,
    private val httpClient: HttpClient,
    private val tokenEndpoint: String,
    private val clientId: String,
    private val clientSecret: String,
    private val objectMapper: ObjectMapper
) {

    fun veksleTilOnBehalfOf(logg: Logg, token: String, scope: String): String {
        return hentTokenFraMellomlager(logg, token, scope) ?: hentTokenFraAzure(logg, token, scope)
    }

    private fun hentTokenFraAzure(logg: Logg, token: String, scope: String): String {
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
            val accessToken = json.path("access_token").asText()
            val expiresIn = json.path("expires_in").asLong()
            jedisPool.resource.use { jedis ->
                logg.info("lagrer OBO-token i mellomlager")
                jedis.set(mellomlagringsnøkkel(token, scope), accessToken, SetParams.setParams().ex(expiresIn))
            }
            accessToken
        }
    }

    private fun hentTokenFraMellomlager(logg: Logg, token: String, scope: String): String? {
        return jedisPool.resource.use { jedis ->
            jedis.get(mellomlagringsnøkkel(token, scope))?.also {
                logg.info("hentet OBO-token fra mellomlager")
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun mellomlagringsnøkkel(token: String, scope: String): String {
        val nøkkel = "$OnBehalfOfTokennøkkel$token$scope".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(nøkkel)
        return digest.toHexString()
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

    private companion object {
        private const val OnBehalfOfTokennøkkel = "obo_"
    }
}