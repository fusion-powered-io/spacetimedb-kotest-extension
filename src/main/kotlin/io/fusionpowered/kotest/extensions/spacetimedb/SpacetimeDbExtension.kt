package io.fusionpowered.kotest.extensions.spacetimedb

import com.clockworklabs.spacetimedb.DbConnection
import io.jsonwebtoken.Jwts
import io.kotest.core.listeners.AfterProjectListener
import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.listeners.BeforeTestListener
import io.kotest.core.test.TestCase
import io.kotest.engine.test.TestResult
import java.io.File
import java.net.ServerSocket
import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.uuid.Uuid

/**
 * A Kotest extension that automates the lifecycle of a local SpacetimeDB instance for integration testing.
 *
 * This extension:
 * 1. Starts a standalone, in-memory SpacetimeDB server bound to a local port before the project runs.
 * 2. Builds and publishes the designated database module to that local server.
 * 3. Establishes and exposes a [DbConnection] to the published database before each test.
 * 4. Disconnects and stops the SpacetimeDB server after the tests complete.
 *
 * Register it inside your Kotest ProjectConfig using `extensions = listOf(spacetimeDbExtension)`.
 *
 * @property moduleName The name of the SpacetimeDB database module to publish and interact with.
 * @property modulePath The system path (relative or absolute) to the module directory (e.g. containing Cargo.toml).
 * @property defaultIssuer The default token issuer used when generating JWT authentication tokens.
 * @property defaultClaims The default JWT claims map containing user profile information.
 * @property port The local port number the SpacetimeDB server should listen on. Defaults to a randomly allocated free port.
 */
class SpacetimeDbExtension(
    val moduleName: String,
    private val modulePath: String,
    private val defaultIssuer: String,
    private val defaultClaims: Map<String, String> = mapOf("name" to "SpacetimeDB User", "email" to "user@spacetimedb"),
    val port: Int = ServerSocket(0).use { it.localPort },
) : BeforeTestListener, AfterTestListener, AfterProjectListener {

    /**
     * The local server connection URL.
     */
    val url = "http://localhost:$port"

    /**
     * The active [DbConnection] instance connected to the published SpacetimeDB module.
     * This is initialized in the [beforeTest] lifecycle hook.
     */
    lateinit var connection: DbConnection

    private lateinit var process: Process

    init {
        cli("start")
        // Expose database connection details as system properties so frameworks (e.g. Spring) can pick them up
        System.setProperty("spacetime.url", url)
        System.setProperty("spacetime.module", moduleName)
        System.setProperty("spacetime.token", createToken())
    }

    /**
     * Generates a signed ES256 JWT auth token using the ECDSA private key located at `~/.config/spacetime/id_ecdsa`.
     *
     * @param issuer The JWT token issuer name. Defaults to [defaultIssuer].
     * @param claims A map of claims to include in the token payload. Defaults to [defaultClaims].
     * @return The compact, signed JWT token string.
     */
    fun createToken(
        issuer: String = defaultIssuer,
        claims: Map<String, String> = defaultClaims
    ): String {
        val privateKey = File("${System.getProperty("user.home")}/.config/spacetime/id_ecdsa")
            .readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
            .let { Base64.decode(it) }
            .let { PKCS8EncodedKeySpec(it) }
            .let { KeyFactory.getInstance("EC").generatePrivate(it) }
        return Jwts.builder()
            .issuer(issuer)
            .issuedAt(java.util.Date())
            .expiration(java.util.Date(System.currentTimeMillis() + 3_600_000))
            .subject(Random.nextBytes(ByteArray(64)).toHexString())
            .claims(claims)
            .signWith(privateKey, Jwts.SIG.ES256)
            .compact()
    }

    /**
     * Kotest before-test hook. Establishes the type-safe client connection before each test.
     */
    override suspend fun beforeTest(testCase: TestCase) {
        cli("publish", moduleName, "--module-path", modulePath, "-s", url, "-c", "--yes")
        connection = DbConnection.builder()
            .withUri(url)
            .withModuleName(moduleName)
            .withToken(createToken())
            .build()
    }

    /**
     * Kotest after-test hook. Disconnects the database client session.
     */
    override suspend fun afterTest(testCase: TestCase, result: TestResult) {
        connection.disconnect()
    }

    /**
     * Kotest after-project hook. Cleans up and forcefully terminates the local SpacetimeDB server process and descendants.
     */
    override suspend fun afterProject() {
        cli("stop")
    }

    /**
     * Executes a SpacetimeDB CLI command as a subprocess.
     *
     * Supported special commands:
     * - `"start"`: Spawns an in-memory SpacetimeDB standalone server and polls until the ping check succeeds or times out.
     * - `"stop"`: Forcefully kills the SpacetimeDB server subprocess and all its descendants.
     * - Any other arguments are passed directly to the `spacetime` executable.
     *
     * @param args The command line arguments to pass or execute.
     */
    fun cli(vararg args: String) {
        when (args.first()) {
            "start" -> {
                process = execute(
                    "spacetime", "start", "--in-memory", "--non-interactive",
                    "--listen-addr", "0.0.0.0:$port",
                    "--data-dir", "build/tmp/spacetime/${Uuid.random()}"
                )

                var retries = 0
                val maxRetries = 50 // 50 * 200ms = 10 second timeout
                do {
                    Thread.sleep(200)
                    println("Waiting for server (attempt ${retries + 1})...")
                    retries++
                    if (retries > maxRetries) {
                        throw IllegalStateException("SpacetimeDB server failed to start within 10 seconds")
                    }
                } while (execute("spacetime", "server", "ping", url).waitFor() != 0)
            }

            "stop" -> {
                if (::process.isInitialized) {
                    process.descendants().forEach { it.destroyForcibly() }
                    process.destroyForcibly().waitFor()
                }
            }

            else -> execute("spacetime", *args).waitFor()
        }
    }

    private fun execute(vararg args: String) =
        ProcessBuilder(*args)
            .start()

}