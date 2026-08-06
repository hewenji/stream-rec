/*
 * WebcastFansclubMessage 解析測試（add-on）
 *
 * 這個訊息在 dy.proto 沒有定義，解析器是靠掃欄位而非固定欄位編號。
 * 測試刻意用「不同欄位編號」組出兩份 payload，驗證兩者都能解出來。
 */

package douyin

import com.google.protobuf.CodedOutputStream
import douyin.Dy
import github.hua0512.plugins.douyin.danmu.DouyinFansclubDecoder
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream

class DouyinFansclubDecoderTest : FunSpec({

  fun user(nick: String, level: Int = 0) = Dy.User.newBuilder()
    .setId(4242)
    .setNickName(nick)
    .apply {
      if (level > 0) setPayGrade(Dy.PayGrade.newBuilder().setLevel(level.toLong()).build())
    }
    .build()

  /** 依指定欄位編號手工組 payload */
  fun build(commonField: Int, typeField: Int, contentField: Int, userField: Int,
            type: Int, content: String, u: Dy.User): com.google.protobuf.ByteString {
    val bos = ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(bos)
    out.writeMessage(commonField, Dy.Common.newBuilder().setCreateTime(1786000000L).build())
    out.writeInt32(typeField, type)
    out.writeString(contentField, content)
    out.writeMessage(userField, u)
    out.flush()
    return com.google.protobuf.ByteString.copyFrom(bos.toByteArray())
  }

  test("標準欄位編號 1/2/3/4") {
    val u = user("小熊炸毛了", 18)
    val r = DouyinFansclubDecoder.decode(build(1, 2, 3, 4, 1, "小熊炸毛了 加入了粉丝团", u))
    r.user?.nickNameBytes?.toStringUtf8() shouldBe "小熊炸毛了"
    r.content shouldBe "小熊炸毛了 加入了粉丝团"
    r.type shouldBe 1
    r.createTime shouldBe 1786000000L
  }

  test("欄位編號被改動也照樣解得出來") {
    val u = user("日日安")
    val r = DouyinFansclubDecoder.decode(build(7, 11, 9, 5, 2, "日日安 粉丝团升到 3 级", u))
    r.user?.nickNameBytes?.toStringUtf8() shouldBe "日日安"
    r.content shouldBe "日日安 粉丝团升到 3 级"
    r.type shouldBe 2
  }

  test("沒有 User 子訊息時回傳 null user，呼叫端會跳過該筆") {
    val bos = ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(bos)
    out.writeInt32(2, 1)
    out.writeString(3, "某人 加入了粉丝团")
    out.flush()
    val r = DouyinFansclubDecoder.decode(com.google.protobuf.ByteString.copyFrom(bos.toByteArray()))
    r.user shouldBe null
    r.content shouldBe "某人 加入了粉丝团"
  }
})
