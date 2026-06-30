package io.github.hypercopy.clipboard.handling

import io.github.hypercopy.HyperLog
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

object OneRedirectResolver {
    private const val TAG = "HyperCopy"
    private const val TIMEOUT_MILLIS = 2_000
    private const val MAX_REDIRECTS = 5

    /** 功能①：多跳重定向解析（最多 MAX_REDIRECTS 跳，HEAD 优先逐跳，失败 GET 兜底） */
    fun resolve(url: String): String {
        val normalized = normalizeUrl(url)
        return runCatching {
            var current = normalized
            for (hop in 1..MAX_REDIRECTS) {
                val headResult = request(current, "HEAD")
                val next = if (headResult != current) headResult else request(current, "GET")
                if (next == current) break
                current = next
            }
            current
        }
            .getOrElse { error ->
                HyperLog.d(TAG, "redirect resolve failed after retries: ${error.message}")
                normalized
            }
    }

    private fun request(url: String, method: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("User-Agent", "HyperCopy")
        }
        return try {
            val code = connection.responseCode
            val location = connection.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) resolveLocation(url, location) else url
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveLocation(baseUrl: String, location: String): String {
        return runCatching { URI(baseUrl).resolve(location).toString() }.getOrDefault(location)
    }

    private fun normalizeUrl(text: String): String {
        val value = text.trim()
        return if (value.startsWith("http://", true) || value.startsWith("https://", true)) value else "https://$value"
    }
}
