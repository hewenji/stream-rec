/*
 * 抖音公屏訊息解析的單元測試（add-on，非上游 stream-rec 內容）
 *
 * 用合成的 protobuf payload 驗證各訊息類型 -> DanmuData 的欄位對應，
 * 不需要有直播間正在開播（房間下播後 status_str=4，一則彈幕都收不到）。
 */

package douyin

import douyin.Dy
import github.hua0512.app.App
import github.hua0512.app.HttpClientFactory
import github.hua0512.data.config.AppConfig
import github.hua0512.data.media.DanmuDataWrapper.DanmuData
import github.hua0512.plugins.douyin.danmu.DouyinDanmu
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class DouyinDanmuDecodeTest : FunSpec({

  val app = App(Json, HttpClientFactory().getClient(Json)).apply { updateConfig(AppConfig()) }
  val danmu = DouyinDanmu(app)

  /** 造一個帶榮譽等級、粉絲團燈牌、房管勳章的使用者 */
  fun user(
    nick: String = "測試用戶",
    uid: Long = 12345L,
    payGrade: Long = 0,
    fansClubLevel: Int = 0,
    anchorId: Long = 0,
    admin: Boolean = false,
  ): Dy.User {
    val b = Dy.User.newBuilder().setId(uid).setNickName(nick)
    if (payGrade > 0) {
      b.setPayGrade(Dy.PayGrade.newBuilder().setLevel(payGrade).build())
      b.addBadgeImageList(
        Dy.Image.newBuilder().setContent(
          Dy.Image.Content.newBuilder().setAlternativeText("荣誉等级${payGrade}级勋章").build()
        ).addUrlList("https://example.invalid/grade.png").build()
      )
    }
    if (fansClubLevel > 0) {
      b.setFansClub(
        Dy.FansClub.newBuilder().setData(
          Dy.FansClubData.newBuilder().setLevel(fansClubLevel).setAnchorId(anchorId).build()
        ).build()
      )
    }
    if (admin) {
      b.addBadgeImageList(
        Dy.Image.newBuilder().setContent(
          Dy.Image.Content.newBuilder().setAlternativeText("房管勋章").build()
        ).addUrlList("https://example.invalid/admin.png").build()
      )
    }
    b.setAvatarThumb(Dy.Image.newBuilder().addUrlList("https://example.invalid/avatar.jpg").build())
    return b.build()
  }

  fun decode(method: String, payload: com.google.protobuf.ByteString): DanmuData? {
    val msg = Dy.Message.newBuilder().setMethod(method).setPayload(payload).build()
    return danmu.decodeOne(DouyinWebcastMessages.fromClassName(method), msg) as? DanmuData
  }

  test("聊天訊息帶出等級、燈牌、房管與頭像") {
    val payload = Dy.ChatMessage.newBuilder()
      .setUser(user(nick = "椰·双姝", payGrade = 42, fansClubLevel = 10, anchorId = 999, admin = true))
      .setContent("谢谢红红姐姐[打call]")
      .setEventTime(1785999992L)
      .build().toByteString()
    val d = decode("WebcastChatMessage", payload)
    d!!.kind shouldBe "chat"
    d.sender shouldBe "椰·双姝"
    d.content shouldBe "谢谢红红姐姐[打call]"
    d.payGradeLevel shouldBe 42
    d.fansClubLevel shouldBe 10
    d.anchorId shouldBe 999L
    d.isAdmin shouldBe true
    d.badges shouldBe "荣誉等级42级勋章|房管勋章"
    d.serverTime shouldBe 1785999992000L
  }

  test("加入直播間訊息") {
    val payload = Dy.MemberMessage.newBuilder()
      .setUser(user(nick = "骄子", payGrade = 32))
      .setActionDescription("来了")
      .setMemberCount(156)
      .setCommon(Dy.Common.newBuilder().setCreateTime(1785999992L).build())
      .build().toByteString()
    val d = decode("WebcastMemberMessage", payload)
    d!!.kind shouldBe "member"
    d.sender shouldBe "骄子"
    d.content shouldBe "来了"
    d.memberCount shouldBe 156L
    d.payGradeLevel shouldBe 32
  }

  test("送禮訊息：非連擊禮物每則都算") {
    val payload = Dy.GiftMessage.newBuilder()
      .setUser(user(nick = "司辰", payGrade = 24))
      .setToUser(Dy.User.newBuilder().setNickName("豆芽·双姝").build())
      .setGiftId(1234)
      .setGift(Dy.GiftStruct.newBuilder().setName("小心心").setCombo(false).build())
      .setTotalCount("3")
      .setCommon(Dy.Common.newBuilder().setCreateTime(1785999992L).build())
      .build().toByteString()
    val d = decode("WebcastGiftMessage", payload)
    d!!.kind shouldBe "gift"
    d.giftName shouldBe "小心心"
    d.giftCount shouldBe 3L
    d.giftReceiver shouldBe "豆芽·双姝"
    d.giftId shouldBe 1234L
    d.content shouldBe "送出了 小心心 x3 给 豆芽·双姝"
  }

  test("送禮訊息：連擊中的不記，只記連擊結束那筆") {
    fun combo(repeatEnd: Int) = Dy.GiftMessage.newBuilder()
      .setUser(user(nick = "橘了白"))
      .setGift(Dy.GiftStruct.newBuilder().setName("玫瑰").setCombo(true).build())
      .setRepeatEnd(repeatEnd)
      .setTotalCount("9")
      .build().toByteString()
    decode("WebcastGiftMessage", combo(0)) shouldBe null
    decode("WebcastGiftMessage", combo(1))!!.giftCount shouldBe 9L
  }

  test("點讚訊息") {
    val payload = Dy.LikeMessage.newBuilder()
      .setUser(user(nick = "魅魅蝟蝟", payGrade = 30))
      .setCount(5)
      .setCommon(Dy.Common.newBuilder().setCreateTime(1785999992L).build())
      .build().toByteString()
    val d = decode("WebcastChatLikeMessage", payload)
    d!!.kind shouldBe "like"
    d.likeCount shouldBe 5L
    d.content shouldBe "为主播点赞了"
  }

  test("關注與分享訊息") {
    fun social(action: Long) = Dy.SocialMessage.newBuilder()
      .setUser(user(nick = "路人甲"))
      .setAction(action)
      .setCommon(Dy.Common.newBuilder().setCreateTime(1785999992L).build())
      .build().toByteString()
    decode("WebcastSocialMessage", social(1))!!.content shouldBe "关注了主播"
    decode("WebcastSocialMessage", social(3))!!.content shouldBe "分享了直播间"
  }

  test("createTime 已是毫秒時不再乘 1000") {
    val payload = Dy.MemberMessage.newBuilder()
      .setUser(user())
      .setCommon(Dy.Common.newBuilder().setCreateTime(1785999992000L).build())
      .build().toByteString()
    decode("WebcastMemberMessage", payload)!!.serverTime shouldBe 1785999992000L
  }

  test("未支援的訊息類型回傳 null，不影響同批其他訊息") {
    decode("WebcastRoomStatsMessage", Dy.Common.newBuilder().build().toByteString()) shouldBe null
  }
})
