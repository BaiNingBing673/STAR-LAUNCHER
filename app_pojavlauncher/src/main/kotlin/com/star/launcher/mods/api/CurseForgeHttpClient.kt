package com.star.launcher.mods.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException

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

    class Forbidden(val isDistributionRestriction: Boolean = false) : CurseForgeApiException(
        if (isDistributionRestriction) {
            "This file's author disabled third-party API distribution on CurseForge."
        } else {
            "CurseForge API access was denied (HTTP 403)."
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

    class ServerError(val statusCode: Int) : CurseForgeApiException(
        "CurseForge service returned HTTP $statusCode."
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
    private val readTimeoutMs: Int = 30_000
) : CurseForgeTransport {
    override suspend fun get(
        url: String,
        headers: Map<String, String>
    ): CurseForgeHttpResponse = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "STARLauncher/1.0")
            for ((k, v) in headers) {
                setRequestProperty(k, v)
            }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val responseHeaders = LinkedHashMap<String, String>()
            conn.headerFields.forEach { (key, values) ->
                if (key != null && !values.isNullOrEmpty()) {
                    responseHeaders[key.lowercase()] = values.first()
                }
            }
            CurseForgeHttpResponse(statusCode = code, body = body, headers = responseHeaders)
        } catch (e: UnknownHostException) {
            throw CurseForgeApiException.NetworkOffline()
        } catch (e: SocketTimeoutException) {
            throw CurseForgeApiException.Timeout()
        } catch (e: IOException) {
            if (e is CurseForgeApiException) throw e
            throw CurseForgeApiException.NetworkOffline()
        } finally {
            conn.disconnect()
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
