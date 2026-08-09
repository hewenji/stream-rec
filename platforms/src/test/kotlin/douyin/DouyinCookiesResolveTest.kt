package douyin

import github.hua0512.plugins.douyin.download.cookieHeaderHasSessionId
import github.hua0512.plugins.douyin.download.readDouyinCookiesFile
import github.hua0512.plugins.douyin.download.resolveDouyinCookiesRaw
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class DouyinCookiesResolveTest : FunSpec({

  test("streamer cookies 優先於全域與檔案") {
    val f = File.createTempFile("dy-cookies", ".txt").apply {
      writeText("sessionid=fromfile")
      deleteOnExit()
    }
    resolveDouyinCookiesRaw(
      streamerCookies = "sessionid=streamer",
      globalCookies = "sessionid=global",
      cookiesFile = f.absolutePath,
      envCookiesFile = null,
    ) shouldBe "sessionid=streamer"
  }

  test("全域字串優先於檔案") {
    val f = File.createTempFile("dy-cookies", ".txt").apply {
      writeText("sessionid=fromfile")
      deleteOnExit()
    }
    resolveDouyinCookiesRaw(
      streamerCookies = null,
      globalCookies = "sessionid=global",
      cookiesFile = f.absolutePath,
      envCookiesFile = null,
    ) shouldBe "sessionid=global"
  }

  test("cookiesFile 優先於環境變數路徑") {
    val fileA = File.createTempFile("dy-a", ".txt").apply {
      writeText("sessionid=fileA")
      deleteOnExit()
    }
    val fileB = File.createTempFile("dy-b", ".txt").apply {
      writeText("sessionid=fileB")
      deleteOnExit()
    }
    resolveDouyinCookiesRaw(
      streamerCookies = "  ",
      globalCookies = "",
      cookiesFile = fileA.absolutePath,
      envCookiesFile = fileB.absolutePath,
    ) shouldBe "sessionid=fileA"
  }

  test("讀檔忽略註解與空行並合併為單行 cookie 字串") {
    val f = File.createTempFile("dy-c", ".txt").apply {
      writeText(
        """
        # comment
        sessionid=abc; ttwid=xyz

        """.trimIndent()
      )
      deleteOnExit()
    }
    readDouyinCookiesFile(f.absolutePath) shouldBe "sessionid=abc; ttwid=xyz"
  }

  test("讀取多行且未帶分號的 cookie 時以分號分隔") {
    val f = File.createTempFile("dy-c", ".txt").apply {
      writeText(
        """
        sessionid=abc
        ttwid=xyz
        """.trimIndent()
      )
      deleteOnExit()
    }

    readDouyinCookiesFile(f.absolutePath) shouldBe "sessionid=abc; ttwid=xyz"
  }

  test("缺檔回傳 null，resolve 落到下一層") {
    readDouyinCookiesFile("C:\\no\\such\\douyin_cookies.txt") shouldBe null
    resolveDouyinCookiesRaw(
      streamerCookies = null,
      globalCookies = null,
      cookiesFile = "C:\\no\\such\\douyin_cookies.txt",
      envCookiesFile = null,
    ) shouldBe ""
  }

  test("cookieHeaderHasSessionId 大小寫不敏感") {
    cookieHeaderHasSessionId("ttwid=1; SESSIONID=x") shouldBe true
    cookieHeaderHasSessionId("ttwid=1") shouldBe false
  }
})
