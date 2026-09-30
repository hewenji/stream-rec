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

/**
 * 解析某一位主播要用的抖音登入 Cookie。
 *
 * **刻意只看該主播自己的設定，沒有全域後備。** 一個抖音帳號無法多處同時登入：
 * 若多位主播共用同一組 sessionid，併發錄製時會互相踢掉，結果是大家都收不到禮物。
 * 全域 cookies／全域 cookiesFile／`DOUYIN_COOKIES_FILE` 這類後備會讓每位主播
 * 都自動套上同一個帳號，正是造成互踢的原因，因此一律不提供。
 *
 * 沒有替這位主播設定 Cookie 就回傳空字串，以匿名連線——聊天、進場、點讚照常，
 * 只有禮物收不到（抖音不對匿名連線下發 `WebcastGiftMessage`）。
 */
fun resolveDouyinCookiesRaw(
  streamerCookies: String?,
  streamerCookiesFile: String?,
): String {
  streamerCookies?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
  streamerCookiesFile?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
    readDouyinCookiesFile(path)?.let { return it }
  }
  return ""
}

/**
 * 把 Cookie 檔重新讀進來時，補回先前已經產生過的非登入態參數。
 *
 * 為什麼不直接呼叫 `populateDouyinCookieMissedParams`：那支是 `suspend`（`ttwid`
 * 要打一次 API），而重連的回呼 [github.hua0512.plugins.danmu.base.Danmu.onDanmuRetry]
 * 不是 suspend，而且重連當下正在退避重試，最不該再多一次可能逾時的網路往返。
 * 這些參數（ttwid / odin_tt / __ac_nonce / msToken）在同一場錄影裡沿用即可。
 *
 * 新檔案裡有的欄位一律優先，[generated] 只補缺。
 */
fun mergeDouyinCookies(fresh: String, generated: Map<String, String>): String {
  val merged = LinkedHashMap<String, String>()
  parseClientCookiesHeader(fresh).forEach { (k, v) -> merged[k] = v }
  generated.forEach { (k, v) -> merged.putIfAbsent(k, v) }
  return merged.entries.joinToString("; ") { "${it.key}=${it.value}" } + ";"
}

/**
 * 取出 [full] 裡有、但 [raw] 裡沒有的欄位，也就是程式自己補上的那幾個參數。
 */
fun extractGeneratedCookieParams(raw: String, full: String): Map<String, String> {
  val rawKeys = parseClientCookiesHeader(raw).keys
  return parseClientCookiesHeader(full).filterKeys { it !in rawKeys }
}

/** 取出 Cookie 裡的 sessionid 值，用來判斷是否有登入態、以及是否與別的主播撞帳號 */
fun douyinSessionId(cookies: String): String? {
  if (cookies.isBlank()) return null
  return parseClientCookiesHeader(cookies).entries
    .firstOrNull { it.key.equals("sessionid", ignoreCase = true) }
    ?.value?.takeIf { it.isNotEmpty() }
}

fun cookieHeaderHasSessionId(cookies: String): Boolean = douyinSessionId(cookies) != null
