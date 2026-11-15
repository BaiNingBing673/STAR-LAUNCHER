package com.star.launcher.mods.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class CurseForgeHttpResponse(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

interface CurseForgeTransport {
    suspend fun get(
        url: String,
        headers: Map<String, String>
    ): CurseForgeHttpResponse
}

sealed class CurseForgeApiException(
    override val message: String
) : IOException(message) {
    class NotConfigured : CurseForgeApiException(
        "CurseForge API credentials are not configured. Configure a STAR backend proxy or a developer API key in Vault."
    )

    class Unauthorized : CurseForgeApiException(
        "CurseForge API authentication failed (HTTP 401). Verify your configured credentials."
    )

    class Forbidden(
        val isDistributionRestriction: Boolean = false,
        val serverReason: String? = null
    ) : CurseForgeApiException(
        if (isDistributionRestriction) {
            "This file's author disabled third-party API distribution on CurseForge."
        } else if (!serverReason.isNullOrBlank()) {
            "CurseForge API access was denied (HTTP 403: $serverReason). Update your CurseForge API key in Vault or switch to a Backend Proxy."
        } else {
            "CurseForge API access was denied (HTTP 403). Verify your CurseForge API key in Vault or switch to a Backend Proxy."
        }
    )

    class RateLimited(val retryAfterSeconds: Int?) : CurseForgeApiException(
        if (retryAfterSeconds != null) {
            "CurseForge API rate limit reached (HTTP 429). Retry after $retryAfterSeconds seconds."
        } else {
            "CurseForge API rate limit reached (HTTP 429). Please wait and retry."
        }
    )

    class NotFound(resource: String) : CurseForgeApiException(
        "CurseForge resource not found ($resource)."
    )

    class NetworkOffline : CurseForgeApiException(
        "Unable to reach CurseForge. Check your internet connection."
    )

    class Timeout : CurseForgeApiException(
        "CurseForge request timed out. Please try again."
    )

    class ServerError(val statusCode: Int, val detail: String? = null) : CurseForgeApiException(
        when {
            statusCode > 0 && !detail.isNullOrBlank() -> "CurseForge service returned HTTP $statusCode: $detail"
            statusCode > 0 -> "CurseForge service returned HTTP $statusCode."
            !detail.isNullOrBlank() -> "CurseForge request failed: $detail"
            else -> "CurseForge request failed."
        }
    )

    class ParseError(detail: String) : CurseForgeApiException(
        "Unable to parse CurseForge API response: $detail"
    )
}

/**
 * Production [CurseForgeTransport] using [HttpURLConnection] on [Dispatchers.IO].
 * Never logs headers, x-api-key values, or raw authorization secrets.
 */
class UrlConnectionCurseForgeTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val maxRetries: Int = 2
) : CurseForgeTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): CurseForgeHttpResponse = withContext(Dispatchers.IO) {
        var attempt = 0
        var backoffMs = 350L
        while (true) {
            try {
                val response = executeSingleGet(url, headers)
                val isTransient = response.statusCode in setOf(408, 502, 503, 504) ||
                    (response.statusCode == 429 && (response.headers["retry-after"]?.trim()?.toIntOrNull() ?: 99) <= 2)
                if (!isTransient || attempt >= maxRetries) {
                    return@withContext response
                }
                val retryAfterSec = response.headers["retry-after"]?.trim()?.toIntOrNull()
                val sleepMs = if (retryAfterSec != null && retryAfterSec in 1..2) retryAfterSec * 1000L else backoffMs
                attempt++
                backoffMs = (backoffMs * 2L).coerceAtMost(2_000L)
                delay(sleepMs)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: CurseForgeApiException.Timeout) {
                if (attempt >= maxRetries) throw e
                attempt++
                delay(backoffMs)
                backoffMs = (backoffMs * 2L).coerceAtMost(2_000L)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw CurseForgeApiException.NetworkOffline()
    }

    private fun executeSingleGet(
        initialUrl: String,
        headers: Map<String, String>
    ): CurseForgeHttpResponse {
        var currentUrl = initialUrl
        val originHost = runCatching { URL(initialUrl).host }.getOrNull()
        var redirects = 0
        while (true) {
            val targetUrl = try {
                URL(currentUrl)
            } catch (_: Exception) {
                throw CurseForgeApiException.ServerError(0, "Invalid CurseForge endpoint URL")
            }
            val sameHost = originHost != null && targetUrl.host.equals(originHost, ignoreCase = true)
            val conn = (targetUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "STARLauncher/1.0")
                for ((k, v) in headers) {
                    val isSensitive = k.equals("x-api-key", ignoreCase = true) ||
                        k.equals("Authorization", ignoreCase = true)
                    if (sameHost || !isSensitive) {
                        setRequestProperty(k, v)
                    }
                }
            }
            try {
                val code = conn.responseCode
                if (code in 300..399 && redirects < 4) {
                    val location = conn.getHeaderField("Location")?.trim()
                    if (!location.isNullOrEmpty()) {
                        currentUrl = URL(targetUrl, location).toString()
                        redirects++
                        continue
                    }
                }
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val responseHeaders = LinkedHashMap<String, String>()
                conn.headerFields.forEach { (key, values) ->
                    if (key != null && !values.isNullOrEmpty()) {
                        responseHeaders[key.lowercase()] = values.first()
                    }
                }
                return CurseForgeHttpResponse(statusCode = code, body = body, headers = responseHeaders)
            } catch (_: UnknownHostException) {
                throw CurseForgeApiException.NetworkOffline()
            } catch (_: ConnectException) {
                throw CurseForgeApiException.NetworkOffline()
            } catch (_: NoRouteToHostException) {
                throw CurseForgeApiException.NetworkOffline()
            } catch (_: SocketTimeoutException) {
                throw CurseForgeApiException.Timeout()
            } catch (e: SSLException) {
                throw CurseForgeApiException.ServerError(0, "TLS handshake failed (${e::class.java.simpleName})")
            } catch (e: IOException) {
                if (e is CurseForgeApiException) throw e
                throw CurseForgeApiException.ServerError(0, e.message ?: e::class.java.simpleName)
            } finally {
                conn.disconnect()
            }
        }
    }
}

object CurseForgeUrlBuilder {
    fun buildUrl(baseUrl: String, path: String, queryParams: Map<String, String> = emptyMap()): String {
        val cleanBase = baseUrl.trimEnd('/')
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        if (queryParams.isEmpty()) return "$cleanBase$cleanPath"
        val queryString = queryParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$cleanBase$cleanPath?$queryString"
    }

    fun redactSecrets(raw: String, vararg secrets: String?): String {
        var out = raw
        for (secret in secrets) {
            if (!secret.isNullOrBlank()) {
                out = out.replace(secret, "[REDACTED]")
            }
        }
        out = out.replace(Regex("""(?i)(x-api-key\s*[:=]\s*)(\S+)"""), "$1[REDACTED]")
        out = out.replace(Regex("""(?i)(Authorization\s*:\s*Bearer\s+)(\S+)"""), "$1[REDACTED]")
        return out
    }
}
