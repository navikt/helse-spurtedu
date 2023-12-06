package no.nav.helse.spurte_du

import redis.clients.jedis.DefaultJedisClientConfig
import redis.clients.jedis.HostAndPort
import redis.clients.jedis.JedisPool

fun api(env: Map<String, String>, logg: Logg) {
    val host = env.getValue("REDIS_HOST")
    val config = DefaultJedisClientConfig.builder()
        .user(env.getValue("REDIS_USER"))
        .password(env.getValue("REDIS_PASSWORD"))
        .ssl(true)
        .hostnameVerifier { hostname, session ->
            val evaluering = hostname == host
            logg.info("verifiserer vertsnavn $hostname: {}", evaluering)
            evaluering
        }
        .build()
    JedisPool(HostAndPort(host, env.getValue("REDIS_PORT").toInt()), config).resource.use { jedis ->
        jedis.set("foo", "100")
        logg.info("Lagret verdi til redis")
    }
}