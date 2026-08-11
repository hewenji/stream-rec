package douyin

import github.hua0512.plugins.douyin.download.cookieHeaderHasSessionId
import github.hua0512.plugins.douyin.download.douyinSessionId
import github.hua0512.plugins.douyin.download.readDouyinCookiesFile
import github.hua0512.plugins.douyin.download.resolveDouyinCookiesRaw
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * 抖音 Cookie 一律以「單一主播」為單位解析，沒有全域後備。
 * 一個帳號無法多處同時登入，全域後備會讓所有主播共用同一組 sessionid 而互踢。
 */
class DouyinCookiesResolveTest : FunSpec({

  fun tempCookies(content: String) = File.createTempFile("dy-cookies", ".txt").apply {
    writeText(content)
    deleteOnExit()
  }

  test("主播的 cookies 字串優先於該主播的 cookiesFile") {
    val f = tempCookies("sessionid=fromfile")
    resolveDouyinCookiesRaw(
      streamerCookies = "sessionid=streamer",
      streamerCookiesFile = f.absolutePath,
    ) shouldBe "sessionid=streamer"
  }

  test("沒有字串時讀該主播的 cookiesFile") {
    val f = tempCookies("sessionid=fromfile")
    resolveDouyinCookiesRaw(
      streamerCookies = null,
      streamerCookiesFile = f.absolutePath,
    ) shouldBe "sessionid=fromfile"
  }

  test("空白字串視同未設定") {
    val f = tempCookies("sessionid=fromfile")
    resolveDouyinCookiesRaw(
      streamerCookies = "   ",
      streamerCookiesFile = f.absolutePath,
    ) shouldBe "sessionid=fromfile"
  }

  test("這位主播沒設定就回空字串，代表匿名連線") {
    resolveDouyinCookiesRaw(streamerCookies = null, streamerCookiesFile = null) shouldBe ""
    resolveDouyinCookiesRaw(streamerCookies = "", streamerCookiesFile = "") shouldBe ""
  }

  test("cookiesFile 指到不存在的路徑時退回匿名，不會拋例外") {
    readDouyinCookiesFile("C:\\no\\such\\douyin_cookies.txt") shouldBe null
    resolveDouyinCookiesRaw(
      streamerCookies = null,
      streamerCookiesFile = "C:\\no\\such\\douyin_cookies.txt",
    ) shouldBe ""
  }

  test("讀檔忽略註解與空行並合併為單行 cookie 字串") {
    val f = tempCookies(
      """
      # comment
      sessionid=abc; ttwid=xyz

      """.trimIndent()
    )
    readDouyinCookiesFile(f.absolutePath) shouldBe "sessionid=abc; ttwid=xyz"
  }

  test("讀取多行且未帶分號的 cookie 時以分號分隔") {
    val f = tempCookies(
      """
      sessionid=abc
      ttwid=xyz
      """.trimIndent()
    )
    readDouyinCookiesFile(f.absolutePath) shouldBe "sessionid=abc; ttwid=xyz"
  }

  test("cookieHeaderHasSessionId 大小寫不敏感") {
    cookieHeaderHasSessionId("ttwid=1; SESSIONID=x") shouldBe true
    cookieHeaderHasSessionId("ttwid=1") shouldBe false
  }

  test("douyinSessionId 取得帳號識別值，用來判斷是否撞帳號") {
    douyinSessionId("ttwid=1; sessionid=abc123") shouldBe "abc123"
    douyinSessionId("ttwid=1; SESSIONID=abc123") shouldBe "abc123"
    douyinSessionId("ttwid=1") shouldBe null
    douyinSessionId("") shouldBe null
  }
})