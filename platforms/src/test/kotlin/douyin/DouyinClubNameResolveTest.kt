/*
 * 粉絲團團名查表的單元測試（add-on，非上游 stream-rec 內容）
 *
 * 抖音多數訊息不填 clubName，靠 anchorId 在同場錄影內查表補齊。
 */

package douyin

import github.hua0512.app.App
import github.hua0512.app.HttpClientFactory
import github.hua0512.data.config.AppConfig
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DouyinClubNameResolveTest : FunSpec({

  fun newDanmu() = DouyinDanmu(
    App(Json, HttpClientFactory().getClient(Json)).apply { updateConfig(AppConfig()) }
  )

  test("看過一次團名之後，同 anchorId 的空團名都補得回來") {
    val d = newDanmu()
    d.resolveClubName(98279941470L, "龙浩天") shouldBe "龙浩天"
    d.resolveClubName(98279941470L, "") shouldBe "龙浩天"
    d.resolveClubName(98279941470L, null) shouldBe "龙浩天"
  }

  test("沒看過的 anchorId 查不到就回 null") {
    val d = newDanmu()
    d.resolveClubName(123L, null) shouldBe null
    d.resolveClubName(123L, "") shouldBe null
  }

  test("不同 anchorId 各自獨立，不會互相汙染") {
    val d = newDanmu()
    d.resolveClubName(1L, "甲團") shouldBe "甲團"
    d.resolveClubName(2L, "乙團") shouldBe "乙團"
    d.resolveClubName(1L, null) shouldBe "甲團"
    d.resolveClubName(2L, null) shouldBe "乙團"
  }

  test("anchorId 缺失或為 0 時只看當下的團名") {
    val d = newDanmu()
    d.resolveClubName(null, "丙團") shouldBe "丙團"
    d.resolveClubName(null, null) shouldBe null
    d.resolveClubName(0L, "丁團") shouldBe "丁團"
    d.resolveClubName(0L, null) shouldBe null
  }

  test("當下有團名就用當下的，查表只在缺值時介入") {
    val d = newDanmu()
    d.resolveClubName(9L, "原團名") shouldBe "原團名"
    d.resolveClubName(9L, "改名後") shouldBe "改名後"
    d.resolveClubName(9L, null) shouldBe "原團名"
  }

  test("每場錄影各自一份表，不同實例互不共用") {
    val a = newDanmu()
    val b = newDanmu()
    a.resolveClubName(7L, "甲團") shouldBe "甲團"
    b.resolveClubName(7L, null) shouldBe null
  }
})