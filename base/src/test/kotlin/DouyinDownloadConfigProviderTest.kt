package github.hua0512.plugins.download

import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.config.DouyinConfigGlobal
import github.hua0512.data.stream.StreamingPlatform
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class DouyinDownloadConfigProviderTest : FunSpec({
  test("抖音設定沒有字串 cookies 時讀取 cookiesFile") {
    val cookiesFile = File.createTempFile("douyin-cookies", ".txt").apply {
      writeText("sessionid=from-file")
      deleteOnExit()
    }
    val config = DownloadConfig.DouyinDownloadConfig().fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(douyinConfig = DouyinConfigGlobal(cookiesFile = cookiesFile.absolutePath)),
    )

    config.cookies shouldBe "sessionid=from-file"
  }

  test("主播下載設定的 cookiesFile 優先於全域檔案") {
    val streamerCookiesFile = File.createTempFile("douyin-streamer-cookies", ".txt").apply {
      writeText("sessionid=from-streamer-file")
      deleteOnExit()
    }
    val globalCookiesFile = File.createTempFile("douyin-global-cookies", ".txt").apply {
      writeText("sessionid=from-global-file")
      deleteOnExit()
    }

    val config = DownloadConfig.DouyinDownloadConfig(
      cookiesFile = streamerCookiesFile.absolutePath,
    ).fillDownloadConfig(
      platform = StreamingPlatform.DOUYIN,
      templateConfig = null,
      appConfig = AppConfig(douyinConfig = DouyinConfigGlobal(cookiesFile = globalCookiesFile.absolutePath)),
    )

    config.cookies shouldBe "sessionid=from-streamer-file"
    config.cookiesFile shouldBe streamerCookiesFile.absolutePath
  }
})
