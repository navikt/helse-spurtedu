package no.nav.helse.spurte_du

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool
import java.net.URI

fun api(env: Map<String, String>, logg: Logg) {
    val uri = URI(env.getValue("REDIS_URI_OPPSLAG"))
    val config = DefaultJedisClientConfig.builder()
        .user(env.getValue("REDIS_USERNAME_OPPSLAG"))
        .password(env.getValue("REDIS_PASSWORD_OPPSLAG"))
        .ssl(true)
        .hostnameVerifier { hostname, session ->
            val evaluering = hostname == uri.host
            logg.info("verifiserer vertsnavn $hostname: {}", evaluering)
            evaluering
        }
        .build()
    JedisPool(HostAndPort(uri.host, uri.port), config).resource.use { jedis ->
        jedis.set("foo", "100")
        logg.info("Lagret verdi til redis")
    }
}