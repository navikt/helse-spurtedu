package no.nav.helse.spurte_du

import com.auth0.jwk.JwkProvider
import io.ktor.http.auth.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

class AzureApp(
    private val jwkProvider: JwkProvider,
    private val issuer: String,
    private val clientId: String,
) {
    fun konfigurerJwtAuth(logg: Logg, config: AuthenticationConfig, gruppetilganger: Gruppetilgangtjeneste) {
        config.jwt {
            verifier(jwkProvider, issuer) {
                withAudience(clientId)
                withClaimPresence("preferred_username")
                withClaimPresence("name")
            }
            validate { credentials ->
                // TODO: må få tak i bearer token på en annen måte vel? hvorfor får vi det ikke fra validate{} !?
                val gruppemedlemskap = bearerToken?.let { token -> gruppetilganger.hentGruppemedlemskap(token, logg) } ?: emptyList()
                SpurteDuPrinsipal(JWTPrincipal(credentials.payload), gruppemedlemskap)
            }
        }
    }

    private val ApplicationCall.bearerToken: String? get() {
        val httpAuthHeader = request.parseAuthorizationHeader() ?: return null
        if (httpAuthHeader !is HttpAuthHeader.Single) return null
        return httpAuthHeader.blob
    }
}