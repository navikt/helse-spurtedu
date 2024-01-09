package no.nav.helse.spurte_du

import com.github.navikt.tbd_libs.azure.AzureToken
import com.github.navikt.tbd_libs.azure.AzureTokenProvider
import redis.clients.jedis.JedisPool
import redis.clients.jedis.params.SetParams
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

class JedisTokenCache(
    private val jedisPool: JedisPool,
    private val other: AzureTokenProvider,
    private val logg: Logg
) : AzureTokenProvider by (other) {
    override fun onBehalfOfToken(scope: String, token: String): AzureToken {
        return hentTokenFraMellomlager(logg, token, scope) ?: other.onBehalfOfToken(scope, token).also {
            val expiresIn = ChronoUnit.SECONDS.between(LocalDateTime.now(), it.expirationTime)
            jedisPool.resource.use { jedis ->
                logg.info("lagrer OBO-token i mellomlager")
                jedis.set(mellomlagringsnøkkel(token, scope), it.token, SetParams.setParams().ex(expiresIn))
            }
        }
    }

    private fun hentTokenFraMellomlager(logg: Logg, token: String, scope: String): AzureToken? {
        return jedisPool.resource.use { jedis ->
            jedis.get(mellomlagringsnøkkel(token, scope))?.also {
                logg.info("hentet OBO-token fra mellomlager")
            }?.let { AzureToken(it, LocalDateTime.MAX) }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun mellomlagringsnøkkel(token: String, scope: String): String {
        val nøkkel = "$OnBehalfOfTokennøkkel$token$scope".toByteArray()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(nøkkel)
        return digest.toHexString()
    }

    private companion object {
        private const val OnBehalfOfTokennøkkel = "obo_"
    }
}