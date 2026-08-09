/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2025 hua0512 (https://github.com/hua0512)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package github.hua0512.plugins.douyin.danmu


import com.github.michaelbull.result.get
import com.github.michaelbull.result.getError
import com.google.protobuf.ByteString
import douyin.Dy
import douyin.Dy.PushFrame
import github.hua0512.app.App
import github.hua0512.data.config.DownloadConfig.DouyinDownloadConfig
import github.hua0512.data.media.DanmuDataWrapper
import github.hua0512.data.media.DanmuDataWrapper.DanmuData
import github.hua0512.data.media.DanmuDataWrapper.EndOfDanmu
import github.hua0512.data.stream.Streamer
import github.hua0512.plugins.danmu.base.Danmu
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.CHAT_LIKE_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.CHAT_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.CONTROL_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.EMOJI_CHAT_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.FANSCLUB_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.GIFT_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.LIKE_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.MEMBER_MESSAGE
import github.hua0512.plugins.douyin.danmu.DouyinWebcastMessages.SOCIAL_MESSAGE
import github.hua0512.plugins.douyin.download.*
import github.hua0512.plugins.douyin.download.DouyinRequestParams.Companion.ROOM_ID_KEY
import github.hua0512.plugins.douyin.download.DouyinRequestParams.Companion.SIGNATURE_KEY
import github.hua0512.plugins.douyin.download.DouyinRequestParams.Companion.USER_UNIQUE_KEY
import github.hua0512.utils.decompressGzip
import github.hua0512.utils.withIOContext
import io.ktor.http.*
import io.ktor.websocket.*
import kotlin.time.Instant


/**
 * Douyin danmu client
 * @author hua0512
 * @date : 2024/2/9 13:48
 */
open class DouyinDanmu(app: App) : Danmu(app, enablePing = false) {

  companion object {
    init {
      // load webmssdk js
      loadWebmssdk()
    }
  }


  override var websocketUrl: String = DouyinApi.randomWebSocketUrl

  override val heartBeatDelay: Long = 15000

  override val heartBeatPack: ByteArray = run {
    val heartbeatPack = Dy.PushFrame.newBuilder()
      .setPayloadType("hb")
      .build()
    heartbeatPack.toByteArray()
  }

  private var userUniqueId: String? = null

  internal var idStr = ""

  override suspend fun initDanmu(streamer: Streamer, startTime: Instant): Boolean {
    // get room id
    val webRid = extractDouyinWebRid(streamer.url) ?: return false

    val config: DouyinDownloadConfig = streamer.downloadConfig as DouyinDownloadConfig

    var cookies = resolveDouyinCookiesRaw(
      streamerCookies = config.cookies,
      globalCookies = app.config.douyinConfig.cookies,
      cookiesFile = app.config.douyinConfig.cookiesFile,
    )

    try {
      cookies = populateDouyinCookieMissedParams(cookies, app.client)
    } catch (e: Exception) {
      logger.error("{} Failed to populate douyin cookie missed params", webRid, e)
      return false
    }

    if (!cookieHeaderHasSessionId(cookies)) {
      logger.warn(
        "{} Douyin cookies have no sessionid; WebcastGiftMessage may be missing. Set douyinConfig.cookiesFile or DOUYIN_COOKIES_FILE.",
        webRid,
      )
    }

    if (idStr.isEmpty()) {
      logger.error("{} Failed to get douyin room id_str", webRid)
      return false
    }
    logger.info("${streamer.name} douyin room id_str: $idStr")

    with(requestParams) {
      fillDouyinCommonParams()
      fillDouyinWsParams()

      this[ROOM_ID_KEY] = idStr
      updateSignature()
    }
    headersMap[HttpHeaders.Cookie] = cookies
    return true
  }

  override fun onDanmuRetry(retryCount: Int) {
    // update signature
    updateSignature()
  }

  private fun updateSignature() {
    // update ws url
    websocketUrl = DouyinApi.randomWebSocketUrl
    assert(requestParams[ROOM_ID_KEY] != null) { "$ROOM_ID_KEY is null" }
    // user unique id may be expired, get a new one
    userUniqueId = getValidUserId().toString()
    requestParams[USER_UNIQUE_KEY] = userUniqueId!!
    // update signature
    val signatureResult = getSignature(requestParams[ROOM_ID_KEY]!!, userUniqueId!!)
    if (signatureResult.isErr) {
      logger.error("{} Failed to get douyin signature: {}", idStr, signatureResult.getError())
      return
    }
    requestParams[SIGNATURE_KEY] = signatureResult.get()!!
  }

  override fun oneHello(): ByteArray {
    // Douyin does not use hello
    return byteArrayOf()
  }

  override suspend fun decodeDanmu(session: WebSocketSession, data: ByteArray): List<DanmuDataWrapper?> {
    val pushFrame = PushFrame.parseFrom(data)
    val logId = pushFrame.logId

    // flag to indicate whether the payload is compressed (gzipped)
    var isGzipped = pushFrame.headersListList.find { it.key == "compress_type" }?.let {
      it.value == "gzip"
    } == true

    // payload may be compressed or not
    val payload = pushFrame.payload.toByteArray()
    // decompress payload, may be gzip or not
    val decompressed = if (isGzipped) {
      try {
        withIOContext {
          decompressGzip(payload)
        }
      } catch (e: Exception) {
        logger.error("douyin: failed to decompress payload: $pushFrame", e)
        return emptyList()
      }
    } else {
      payload
    }

    val payloadPackage = Dy.Response.parseFrom(decompressed)
    val internalExt = payloadPackage.internalExtBytes
    if (payloadPackage.needAck) {
      sendAck(session, logId, internalExt)
    }
    val msgList = payloadPackage.messagesListList
    // each frame may contain multiple messages
    return msgList.mapNotNull { msg ->
      logger.trace("msg: {}", msg)
      if (DouyinDanmuProbe.enabled) DouyinDanmuProbe.method(msg.method)
      val msgType = DouyinWebcastMessages.fromClassName(msg.method)
      // 逐則隔離解析錯誤：一個 frame 內含多則訊息，若任何一則 parseFrom 拋例外，
      // 上游的 catch 會把「整個 frame」丟掉，連同批的聊天訊息一起消失。
      // 實測就是 MemberMessage 解析失敗導致 35 則聊天只寫入 8 則。
      runCatching {
        decodeOne(msgType, msg)
      }.getOrElse { e ->
        logger.debug("douyin: failed to decode {}: {}", msg.method, e.toString())
        null
      }
    }
  }

  // internal 而非 private：讓單元測試能用合成 protobuf 直接驗證各訊息類型的欄位對應，
  // 不必等到有直播間正在開播（實測過房間下播後 status_str=4，完全收不到彈幕）
  internal fun decodeOne(msgType: DouyinWebcastMessages?, msg: Dy.Message): DanmuDataWrapper? {
    return when (msgType) {
        CHAT_MESSAGE -> {
          val chatMessage = Dy.ChatMessage.parseFrom(msg.payload)
          // [probe] dump 完整聊天訊息以確認等級/燈牌/徽章/表情欄位，未設 DOUYIN_DANMU_PROBE 時不做事
          if (DouyinDanmuProbe.enabled) DouyinDanmuProbe.dump(chatMessage)
          val textColor = chatMessage.rtfContent.defaultFormat.color.run {
            if (this.isNullOrEmpty()) -1 else this.toInt(16)
          }
          DanmuData(
            chatMessage.user.id,
            chatMessage.user.nickNameBytes.toStringUtf8(),
            textColor,
            chatMessage.contentBytes.toStringUtf8(),
            chatMessage.rtfContent.defaultFormat.fontSize,
            eventTimeMs(chatMessage.eventTime),
          ).withUser(chatMessage.user, "chat")
        }

        MEMBER_MESSAGE -> {
          val m = Dy.MemberMessage.parseFrom(msg.payload)
          val desc = m.actionDescription.ifEmpty { "来了" }
          DanmuData(
            m.user.id,
            m.user.nickNameBytes.toStringUtf8(),
            -1,
            desc,
            0,
            eventTimeMs(m.common.createTime),
            memberCount = m.memberCount.takeIf { it > 0 },
          ).withUser(m.user, "member")
        }

        GIFT_MESSAGE -> {
          val g = Dy.GiftMessage.parseFrom(msg.payload)
          // 連擊禮物在連擊期間會持續推送同一筆，只有最後一則帶 repeatEnd = 1。
          // 因此連擊禮物只在結束時記一筆（帶最終數量），非連擊禮物則每則都算一次。
          if (g.gift.combo && g.repeatEnd != 1) {
            null
          } else {
            val giftName = g.gift.name.ifEmpty { g.gift.describe }
            val count = g.totalCount.toLongOrNull() ?: g.repeatCount.toLongOrNull()
            val receiver = g.toUser.nickNameBytes.toStringUtf8()
            DanmuData(
              g.user.id,
              g.user.nickNameBytes.toStringUtf8(),
              -1,
              buildString {
                append("送出了 ").append(giftName.ifEmpty { "礼物" })
                if (count != null && count > 1) append(" x").append(count)
                if (receiver.isNotEmpty()) append(" 给 ").append(receiver)
              },
              0,
              eventTimeMs(g.sendTime.toLongOrNull() ?: g.common.createTime),
              giftId = g.giftId.takeIf { it > 0 },
              giftName = giftName.ifEmpty { null },
              giftCount = count,
              giftComboCount = g.comboCount.toLongOrNull(),
              giftReceiver = receiver.ifEmpty { null },
            ).withUser(g.user, "gift")
          }
        }

        LIKE_MESSAGE, CHAT_LIKE_MESSAGE -> {
          // 兩個 method 的 payload 都是 LikeMessage；若日後結構不同，parse 失敗就當作沒這筆
          val l = runCatching { Dy.LikeMessage.parseFrom(msg.payload) }.getOrNull()
          if (l == null || !l.hasUser()) null
          else DanmuData(
            l.user.id,
            l.user.nickNameBytes.toStringUtf8(),
            -1,
            "为主播点赞了",
            0,
            eventTimeMs(l.common.createTime),
            likeCount = l.count.takeIf { it > 0 },
          ).withUser(l.user, "like")
        }

        SOCIAL_MESSAGE -> {
          val s = Dy.SocialMessage.parseFrom(msg.payload)
          val desc = when (s.action) {
            1L -> "关注了主播"
            3L -> "分享了直播间"
            else -> "与主播互动"
          }
          DanmuData(
            s.user.id,
            s.user.nickNameBytes.toStringUtf8(),
            -1,
            desc,
            0,
            eventTimeMs(s.common.createTime),
          ).withUser(s.user, "social")
        }

        EMOJI_CHAT_MESSAGE -> {
          val e = Dy.EmojiChatMessage.parseFrom(msg.payload)
          val text = e.emojiContent.defaultPattern.ifEmpty { e.defaultContent }
          DanmuData(
            e.user.id,
            e.user.nickNameBytes.toStringUtf8(),
            -1,
            text.ifEmpty { "[表情]" },
            0,
            eventTimeMs(e.common.createTime),
          ).withUser(e.user, "emoji")
        }

        FANSCLUB_MESSAGE -> {
        // dy.proto 沒有這個訊息的定義，改用低階欄位掃描解析，見 DouyinFansclubDecoder
        val fc = DouyinFansclubDecoder.decode(msg.payload)
        val u = fc.user
        if (u == null) null
        else DanmuData(
          u.id,
          u.nickNameBytes.toStringUtf8(),
          -1,
          fc.content ?: if (fc.type == 2) "粉丝团升级了" else "加入了粉丝团",
          0,
          eventTimeMs(fc.createTime ?: 0L),
        ).withUser(u, "fansclub")
      }

      CONTROL_MESSAGE -> {
          val controlMessage = Dy.ControlMessage.parseFrom(msg.payload)
          val status = controlMessage.status
          if (status == 3) {
            EndOfDanmu
          } else null
        }

        else -> null
      }
  }

  /**
   * 抖音的 eventTime / createTime 單位不一致，統一轉成毫秒，缺值時用本機時間。
   */
  private fun eventTimeMs(value: Long): Long = when {
    value <= 0L -> System.currentTimeMillis()
    // ChatMessage.eventTime 是「秒」（實測 10 位數 1785999992），
    // 但 Common.createTime 有些訊息給的是毫秒，所以依位數判斷，避免乘錯 1000 倍
    value < 100_000_000_000L -> value * 1000
    else -> value
  }

  /**
   * 把使用者身上的等級、粉絲團燈牌、房管等徽章資訊補進 [DanmuData]。
   *
   * 實測（20 則樣本，命中率 20/20）：
   * - `PayGrade.level` 就是榮譽等級，對應素材 `new_user_grade_level_v1_<level>.png`
   * - `FansClub.data.level` 是粉絲團等級，對應 `fansclub_level_v6_<level>.png`；
   *   但 `clubName` 一律為空，團名要靠 `anchorId` 事後另查，所以這裡把 anchorId 一併寫出
   * - 房管與其他勳章都在 `BadgeImageList`，靠 `content.alternativeText`
   *   （如「房管勋章」「荣誉等级42级勋章」）判斷，比用檔名猜可靠
   */
  private fun DanmuData.withUser(user: Dy.User, kind: String): DanmuData {
    val fansClub = user.fansClub.data
    val badgeTexts = user.badgeImageListList.mapNotNull { it.content?.alternativeText?.takeIf(String::isNotEmpty) }
    return copy(
      kind = kind,
      payGradeLevel = user.payGrade.level.toInt().takeIf { it > 0 },
      fansClubLevel = fansClub.level.takeIf { it > 0 },
      fansClubName = fansClub.clubName.takeIf { it.isNotEmpty() },
      anchorId = fansClub.anchorId.takeIf { it > 0 },
      isAdmin = badgeTexts.any { it.contains("房管") }.takeIf { it },
      badges = badgeTexts.takeIf { it.isNotEmpty() }?.joinToString("|"),
      avatarUrl = user.avatarThumb.urlListList.firstOrNull(),
    )
  }

  private suspend fun sendAck(session: WebSocketSession, logId: Long, internalExt: ByteString) {
    val pushFrame = PushFrame.newBuilder()
      .setPayloadType("ack")
      .setLogId(logId)
      .setPayload(internalExt)
      .build()
    val byteArray = pushFrame.toByteArray()
    session.send(byteArray)
    logger.trace("sent ack : {}", byteArray.decodeToString())
  }
}