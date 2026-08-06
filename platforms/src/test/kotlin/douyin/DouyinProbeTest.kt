/*
 * Probe test (add-on, not part of upstream stream-rec)
 *
 * 連上指定抖音直播間，把前 N 筆公屏訊息的完整欄位 dump 到檔案。
 */

package douyin

import BaseTest
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import github.hua0512.data.config.AppConfig
import github.hua0512.data.config.DouyinConfigGlobal
import github.hua0512.data.config.DownloadConfig
import github.hua0512.data.stream.Streamer
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import github.hua0512.plugins.douyin.download.DouyinCombinedApiExtractor
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.withTimeoutOrNull

class DouyinProbeTest : BaseTest<DouyinCombinedApiExtractor>({

  test("probe") {
    val probeOut = System.getenv("DOUYIN_DANMU_PROBE")
    if (probeOut.isNullOrBlank() || System.getenv("DOUYIN_PROBE_URL").isNullOrBlank()) {
      // 這是需要連上正在開播的直播間的偵察工具，不是常規單元測試。
      // 沒設環境變數就直接跳過，不要讓整批測試失敗。
      println("跳過 probe：未設定 DOUYIN_PROBE_URL / DOUYIN_DANMU_PROBE")
      return@test
    }

    // cookie 建議放檔案，避免出現在指令列或環境變數被別的程序看到
    val cookies = System.getenv("DOUYIN_PROBE_COOKIES_FILE")
      ?.takeIf { it.isNotBlank() }
      ?.let { java.io.File(it).readText().trim() }
      ?: System.getenv("DOUYIN_PROBE_COOKIES")
    app.updateConfig(AppConfig(douyinConfig = DouyinConfigGlobal(cookies = cookies)))

    val timeoutSec = System.getenv("DOUYIN_PROBE_TIMEOUT")?.toLongOrNull() ?: 180L

    // 直接給 idStr 就跳過 extract()：抖音的 /webcast/room/web/enter 需要 a_bogus 反爬簽章，
    // 上游是靠外部 strev 執行檔算的（Windows 沒有這支）。而 idStr 從直播間 HTML 就抓得到，
    // 彈幕 WebSocket 只需要 idStr，不需要 extract 的其他結果。
    val forcedIdStr = System.getenv("DOUYIN_PROBE_IDSTR")?.takeIf { it.isNotBlank() }
    val roomIdStr: String
    if (forcedIdStr != null) {
        println("IDSTR=" + forcedIdStr + " (from DOUYIN_PROBE_IDSTR)")
        roomIdStr = forcedIdStr
    } else {
        val extractor = extractor
        val info = extractor.extract()
        println("EXTRACT_OK=" + info.isOk)
        if (!info.isOk) println("EXTRACT_ERROR=" + info.getError()) else println("EXTRACT_INFO=" + info.get())
        info.isOk shouldBe true
        roomIdStr = extractor.idStr
        println("IDSTR=" + roomIdStr)
    }

    // 實際寫出加料 XML，用來驗證等級/燈牌/房管/送禮/加入直播間等欄位真的落地
    val xmlOut = System.getenv("DOUYIN_PROBE_XML")
    val danmu = DouyinDanmu(app).apply {
      idStr = roomIdStr
      if (!xmlOut.isNullOrBlank()) {
        filePath = xmlOut
        enableWrite = true
      } else {
        enableWrite = false
      }
    }
    val ok = danmu.init(
      Streamer(0, "probe", testUrl, downloadConfig = DownloadConfig.DouyinDownloadConfig())
    )
    ok shouldBe true

    withTimeoutOrNull(timeoutSec * 1000) { danmu.fetchDanmu() }
    println("probe 完成，輸出：" + probeOut)
  }

}) {

  // 不可以在這裡 error()：kotest 會先實例化 spec，沒設環境變數時會變成
  // SpecInstantiationException 讓整批測試爆掉。改成給佔位值，由測試本體檢查。
  override val testUrl: String =
    System.getenv("DOUYIN_PROBE_URL")?.takeIf { it.isNotBlank() }
      ?: "https://live.douyin.com/0"

  override fun createExtractor(url: String) = DouyinCombinedApiExtractor(app.client, app.json, url)
}
