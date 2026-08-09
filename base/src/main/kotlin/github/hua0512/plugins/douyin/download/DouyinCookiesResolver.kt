/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2025 hua0512
 */

package github.hua0512.plugins.douyin.download

import github.hua0512.utils.logger
import io.ktor.http.parseClientCookiesHeader
import java.io.File

private val logger = logger("DouyinCookiesResolver")

fun readDouyinCookiesFile(path: String): String? {
  val file = File(path)
  if (!file.isFile || !file.canRead()) {
    logger.error("Douyin cookies file missing or unreadable: {}", path)
    return null
  }
  return runCatching {
    file.readLines()
      .map { it.trim() }
      .filter { it.isNotEmpty() && !it.startsWith("#") }
      .joinToString("; ") { it.trimEnd(';').trim() }
      .trim()
      .ifEmpty { null }
  }.onFailure {
    logger.error("Failed to read Douyin cookies file: {}", path, it)
  }.getOrNull()
}

fun resolveDouyinCookiesRaw(
  streamerCookies: String?,
  globalCookies: String?,
  cookiesFile: String?,
  envCookiesFile: String? = System.getenv("DOUYIN_COOKIES_FILE"),
): String {
  streamerCookies?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
  globalCookies?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
  cookiesFile?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
    readDouyinCookiesFile(path)?.let { return it }
  }
  envCookiesFile?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
    readDouyinCookiesFile(path)?.let { return it }
  }
  return ""
}

fun cookieHeaderHasSessionId(cookies: String): Boolean {
  if (cookies.isBlank()) return false
  return parseClientCookiesHeader(cookies).keys.any { it.equals("sessionid", ignoreCase = true) }
}
