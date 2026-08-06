/*
 * WebcastFansclubMessage 解析（加入粉絲團 / 燈牌升級）
 *
 * 這個訊息類型在 dy.proto 裡沒有定義，而 Dy.java 是預先產生好的（專案停用了
 * protoc plugin），沒辦法加訊息後重新產生。因此改用 protobuf 的低階
 * CodedInputStream 直接掃欄位，做結構無關的解析：
 *
 *   - 任何 varint 欄位都可能是 type（1=加入粉絲團、2=升級）
 *   - 任何字串欄位若含可讀文字，取最長的當作顯示內容
 *   - 任何 length-delimited 欄位嘗試以 Dy.User 解析，取得暱稱與徽章的那個才採用
 *
 * 這樣即使抖音日後調整欄位編號也不會整個壞掉，最差情況是拿不到某個欄位。
 */

package github.hua0512.plugins.douyin.danmu

import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import douyin.Dy

internal data class FansclubInfo(
  val type: Int?,
  val content: String?,
  val user: Dy.User?,
  val createTime: Long?,
)

internal object DouyinFansclubDecoder {

  fun decode(payload: ByteString): FansclubInfo {
    val input = CodedInputStream.newInstance(payload.toByteArray())
    var type: Int? = null
    var content: String? = null
    var user: Dy.User? = null
    var createTime: Long? = null

    while (true) {
      val tag = input.readTag()
      if (tag == 0) break
      val field = tag ushr 3
      when (tag and 0x7) {
        0 -> {                                   // varint
          val v = input.readInt64()
          // type 通常是小整數；createTime 是 10 或 13 位數的時間戳
          if (v in 1..20 && type == null) type = v.toInt()
          else if (v > 1_000_000_000L) createTime = v
        }

        2 -> {                                   // length-delimited：字串或子訊息
          val bytes = input.readBytes()
          // 先試著當 User 解析：能拿到暱稱才算命中
          if (user == null) {
            runCatching { Dy.User.parseFrom(bytes) }.getOrNull()?.let { u ->
              if (u.nickNameBytes.size() > 0) user = u
            }
          }
          // 再看是不是可讀字串（例如「XXX 加入了粉丝团」）
          val s = runCatching { bytes.toStringUtf8() }.getOrNull()
          if (s != null && s.isNotBlank() && isReadable(s) && (content == null || s.length > content!!.length)) {
            content = s
          }
          // Common 子訊息裡也可能藏著 createTime
          if (createTime == null) {
            runCatching { Dy.Common.parseFrom(bytes) }.getOrNull()?.let { c ->
              if (c.createTime > 0) createTime = c.createTime
            }
          }
        }

        1 -> input.readFixed64()
        5 -> input.readFixed32()
        else -> if (!input.skipField(tag)) break
      }
    }
    return FansclubInfo(type, content?.trim(), user, createTime)
  }

  /**
   * 判斷一段 bytes 是否為「人類可讀的文字」而非二進位子訊息。
   * 子訊息被當成 UTF-8 解讀時會出現大量控制字元或替代字元。
   */
  private fun isReadable(s: String): Boolean {
    if (s.length > 200) return false
    var bad = 0
    for (ch in s) {
      if (ch == '�' || (ch.code < 0x20 && ch != '\n' && ch != '\t')) bad++
    }
    return bad == 0
  }
}
