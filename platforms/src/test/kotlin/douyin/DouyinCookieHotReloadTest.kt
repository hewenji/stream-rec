/*
 * 抖音 Cookie 熱重載的單元測試（add-on，非上游 stream-rec 內容）
 *
 * 這條路徑錯了會靜默丟資料：Cookie 沒換成功不會有任何錯誤，只有禮物默默是 0。
 */

package douyin

import github.hua0512.app.App
import github.hua0512.app.HttpClientFactory
import github.hua0512.data.config.AppConfig
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import github.hua0512.plugins.douyin.download.extractGeneratedCookieParams
import github.hua0512.plugins.douyin.download.mergeDouyinCookies
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import java.io.File

class DouyinCookieHotReloadTest : FunSpec({

  fun newDanmu() = DouyinDanmu(
    App(Json, HttpClientFactory().getClient(Json)).apply { updateConfig(AppConfig()) }
  )

  fun tempCookieFile(content: String): File =
    File.createTempFile("douyin_cookies", ".txt").apply {
      deleteOnExit()
      writeText(content)
    }

  test("mergeDouyinCookies：新檔案的值優先，只補缺少的參數") {
    val merged = mergeDouyinCookies(
      fresh = "sessionid=new; ttwid=fresh-ttwid",
      generated = mapOf("ttwid" to "old-ttwid", "msToken" to "tok", "odin_tt" to "odin"),
    )
    merged shouldContain "sessionid=new"
    merged shouldContain "ttwid=fresh-ttwid"
    merged shouldNotContain "old-ttwid"
    merged shouldContain "msToken=tok"
    merged shouldContain "odin_tt=odin"
  }

  test("extractGeneratedCookieParams：只取出程式自己補上的欄位") {
    val generated = extractGeneratedCookieParams(
      raw = "sessionid=s",
      full = "sessionid=s; ttwid=t; msToken=m",
    )
    generated.keys shouldBe setOf("ttwid", "msToken")
  }

  test("Cookie 檔更新後重連會換上新的登入態") {
    val d = newDanmu()
    val file = tempCookieFile("sessionid=first; ttwid=t")
    d.cookiesFilePath = file.absolutePath
    d.cookiesFileMtime = file.lastModified()
    d.generatedParams = mapOf("msToken" to "generated-token")

    // 內容沒變就不該重讀
    d.reloadCookiesIfChanged() shouldBe false

    file.writeText("sessionid=second; ttwid=t")
    file.setLastModified(file.lastModified() + 10_000)

    d.reloadCookiesIfChanged() shouldBe true
    val header = d.currentCookieHeader()!!
    header shouldContain "sessionid=second"
    header shouldContain "msToken=generated-token"
  }

  test("更新後的檔案沒有 sessionid 就不套用，維持原本的登入態") {
    val d = newDanmu()
    val file = tempCookieFile("sessionid=good; ttwid=t")
    d.cookiesFilePath = file.absolutePath
    d.cookiesFileMtime = file.lastModified()

    file.writeText("ttwid=only")
    file.setLastModified(file.lastModified() + 10_000)

    d.reloadCookiesIfChanged() shouldBe false
    d.currentCookieHeader() shouldBe null
    // mtime 仍要更新，否則同一份壞檔案每次重連都會再警告一次
    d.cookiesFileChangedSinceLoad() shouldBe false
  }

  test("沒有設定 Cookie 檔時，熱重載與變更偵測都安靜地不作用") {
    val d = newDanmu()
    d.cookiesFilePath shouldBe null
    d.reloadCookiesIfChanged() shouldBe false
    d.cookiesFileChangedSinceLoad() shouldBe false
  }

  test("禮物疑似失效但 Cookie 檔沒更新時，不要求重連") {
    // 冷清的房間本來就整場零禮物；不看檔案就重連會反覆踢自己下線，
    // 白白燒掉 Danmu 有限的重試額度
    val d = newDanmu()
    val file = tempCookieFile("sessionid=s")
    d.cookiesFilePath = file.absolutePath
    d.cookiesFileMtime = file.lastModified()

    repeat(25) { d.trackGiftHealth("WebcastGiftSortMessage") }
    d.giftExpirySuspected() shouldBe true
    d.reconnectRequested.get() shouldBe false
  }

  test("禮物疑似失效且 Cookie 檔已更新時，要求重連") {
    val d = newDanmu()
    val file = tempCookieFile("sessionid=s")
    d.cookiesFilePath = file.absolutePath
    d.cookiesFileMtime = file.lastModified() - 10_000

    repeat(25) { d.trackGiftHealth("WebcastGiftSortMessage") }
    d.reconnectRequested.get() shouldBe true
  }

  test("重設禮物健康度之後可以重新判定") {
    val d = newDanmu()
    repeat(25) { d.trackGiftHealth("WebcastGiftSortMessage") }
    d.giftExpirySuspected() shouldBe true
    d.resetGiftHealth()
    d.giftExpirySuspected() shouldBe false
  }
})
