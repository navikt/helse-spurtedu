package no.nav.helse.spurte_du

import org.slf4j.Logger
import org.slf4j.LoggerFactory

class Logg(
    private val åpenLogger: Logger,
    private val sikkerLogger: Logger
) : Logger by (åpenLogger) {
    fun sikker() = sikkerLogger
    fun nyLogg(navn: String) = LoggerFactory.getLogger(navn)
}