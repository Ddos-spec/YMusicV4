package kmphttp

import kmpcommon.log.Logger
import kmphttp.internal.HttpCallLog
import kmphttp.internal.withCallLog
import kotlin.coroutines.cancellation.CancellationException

/**
 * Logs each request at INFO: `http#<id> start` and then `http#<id> <code> (<protocol>) +<ms>ms`,
 * `failed` or `canceled`, each line suffixed with method, host and path. The `start` line also
 * lists the names of the non-empty cookies in the request's `Cookie` header — never their values.
 * No query, other headers or bodies are logged. On JVM, OkHttp's network stages (connecting, TLS,
 * sent) join the same id at DEBUG, so a hung request shows how far it got.
 */
class HttpLogInterceptor(private val logger: Logger) : AsyncInterceptor {
    override suspend fun intercept(chain: AsyncInterceptor.Chain): Response {
        val request = chain.request
        val call = HttpCallLog(logger, "${request.method} ${request.url.host}${request.url.encodedPath}")
        val cookieNames = cookieNames(request.headers.values("Cookie"))
        call.log(Logger.Level.INFO, if (cookieNames.isEmpty()) "start" else "start cookies=$cookieNames")
        val response = try {
            chain.proceed(request.withCallLog(call))
        } catch (e: CancellationException) {
            call.log(Logger.Level.INFO, "canceled")
            throw e
        } catch (e: Throwable) {
            call.log(Logger.Level.INFO, "failed", e)
            throw e
        }
        call.log(Logger.Level.INFO, "${response.code} (${response.protocol})")
        return response
    }

    private fun cookieNames(headers: List<String>): String = headers
        .asSequence()
        .flatMap { it.splitToSequence(';') }
        .mapNotNull { pair ->
            val eq = pair.indexOf('=')
            if (eq <= 0 || pair.substring(eq + 1).isBlank()) null else pair.substring(0, eq).trim()
        }
        .filter(String::isNotEmpty)
        .joinToString(",")
}
