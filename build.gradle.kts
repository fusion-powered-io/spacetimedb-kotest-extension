plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotest)
    id("maven-publish")
    id("signing")
}

group = "io.fusionpowered"
version = System.getenv("BACKEND_CLIENT_VERSION")?.takeIf { it.isNotBlank() } ?: "0.0.1-SNAPSHOT"

kotlin {
    compilerOptions {
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
    }
}

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    implementation(libs.spacetimedb.sdk)
    implementation(libs.kotest.runner.junit5)
    implementation(libs.jjwt)
    runtimeOnly(libs.jjwtImpl)
    runtimeOnly(libs.jjwtJackson)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        register<MavenPublication>("mavenJava") {
            from(components["java"])
            pom {
                name.set("SpacetimeDB Kotest Extension")
                description.set("Kotest extension for testing with SpacetimeDB")
                url.set("https://github.com/fusion-powered-io/kotest-extensions-spacetimedb")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("fusion-powered")
                        name.set("Fusion Powered IO")
                        email.set("jesse.brandao@fusionpowered.io")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/fusion-powered-io/kotest-extensions-spacetimedb.git")
                    developerConnection.set("scm:git:ssh://github.com/fusion-powered-io/kotest-extensions-spacetimedb.git")
                    url.set("https://github.com/fusion-powered-io/kotest-extensions-spacetimedb")
                }
            }
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("GPG_PRIVATE_KEY").orNull
        ?: findProperty("signingKey") as? String
    val signingPassphrase = providers.environmentVariable("GPG_PASSPHRASE").orNull
        ?: findProperty("signingPassword") as? String
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(publishing.publications["mavenJava"])
    }
}

