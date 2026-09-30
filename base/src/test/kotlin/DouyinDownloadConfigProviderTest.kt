package github.hua0512.plugins.download

import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.config.DouyinConfigGlobal
import github.hua0512.data.stream.StreamingPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 抖音登入態一律以主播為單位：一個帳號無法多處同時登入，
 * 全域後備會讓所有主播共用同一組 sessionid，併發錄製時互踢。
 */
class DouyinDownloadConfigProviderTest : FunSpec({

  fun tempCookies(name: String, content: String) = File.createTempFile(name, ".txt").apply {
    writeText(content)
    deleteOnExit()
  }

  test("讀取該主播自己的 cookiesFile") {
    val cookiesFile = tempCookies("douyin-streamer", "sessionid=from-streamer-file")
    val config = DownloadConfig.DouyinDownloadConfig(
      cookiesFile = cookiesFile.absolutePath,
    ).fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(),
    )

    config.cookies shouldBe "sessionid=from-streamer-file"
    config.cookiesFile shouldBe cookiesFile.absolutePath
  }

  test("全域 cookiesFile 不再被套用，未設定的主播維持匿名") {
    val globalCookiesFile = tempCookies("douyin-global", "sessionid=from-global-file")
    val config = DownloadConfig.DouyinDownloadConfig().fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(douyinConfig = DouyinConfigGlobal(cookiesFile = globalCookiesFile.absolutePath)),
    )

    config.cookies shouldBe null
  }

  test("全域 cookies 字串同樣不再被套用") {
    val config = DownloadConfig.DouyinDownloadConfig().fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(douyinConfig = DouyinConfigGlobal(cookies = "sessionid=from-global-string")),
    )

    config.cookies shouldBe null
    config.cookiesFile shouldBe null
  }

  test("主播自己的 cookies 字串優先於自己的 cookiesFile") {
    val cookiesFile = tempCookies("douyin-streamer2", "sessionid=from-file")
    val config = DownloadConfig.DouyinDownloadConfig(
      cookiesFile = cookiesFile.absolutePath,
    ).apply { cookies = "sessionid=from-string" }.fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(),
    )

    config.cookies shouldBe "sessionid=from-string"
  }
})