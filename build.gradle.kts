group = "no.nav.helse"

plugins {
    alias(libs.plugins.sas.deployable)
}

sasDeployable {
    mainClass = "no.nav.helse.spurte_du.AppKt"
    imageName = "spurtedu"
}

dependencies {
    implementation(libs.bundles.logback)
    implementation(libs.bundles.ktor.client)
    implementation(libs.jedis)
    implementation(libs.tbdLibs.naisfulApp)
    implementation(libs.tbdLibs.azureTokenClient)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt) {
        exclude(group = "junit")
    }

    testImplementation(libs.tbdLibs.naisfulTestApp)
}
