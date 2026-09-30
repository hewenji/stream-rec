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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Instant


/**
 * Douyin danmu client
 * @author hua0512
 * @date : 2024/2/9 13:48
 */
open class DouyinDanmu(app: App) : Danmu(app, enablePing = false) {

  companion object {
    /** 禮物排行榜更新幾次後仍零禮物就提醒。實測失效時 4 分鐘可累積 125 次 */
    private const val GIFT_EXPIRY_THRESHOLD = 20

    /**
     * sessionid -> 目前正在使用該登入態的主播名稱。
     *
     * 一個抖音帳號無法多處同時登入，兩位主播同時掛同一組 sessionid 會互踢，
     * 結果雙方都收不到禮物。這裡在連線時登記、[clean] 時移除，只有「同時」
     * 使用才會示警；先後輪流用同一個帳號是正常的，不該誤報。
     */
    private val sessionOwners = ConcurrentHashMap<String, String>()

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

  // 以下幾個成員用 internal 而非 private：與 decodeOne 相同的理由，讓單元測試能直接
  // 驗證 Cookie 熱重載，不必真的連上一個正在開播的房間。

  /** 主播名稱，log 與 sessionOwners 登記用 */
  internal var streamerName: String = ""

  /**
   * 這位主播的 Cookie 檔路徑；null 代表不支援熱重載
   * （沒設定檔案，或主播直接貼了 cookies 字串——那個優先序更高，重讀檔案也蓋不過）。
   */
  internal var cookiesFilePath: String? = null

  /** 上次載入 Cookie 檔時的 mtime。內容沒變時 dycookie 不會覆寫檔案，所以 mtime 就是可靠的判準 */
  internal var cookiesFileMtime: Long = 0L

  /** 初次補齊的 ttwid / odin_tt / __ac_nonce / msToken，重載時沿用，避免在非 suspend 的重試回呼裡連網 */
  internal var generatedParams: Map<String, String> = emptyMap()

  /**
   * 要求主動斷線重連。由 [trackGiftHealth] 在「禮物疑似失效 **且** Cookie 檔已更新」時設起，
   * 由 [decodeDanmu] 在收完當前這批訊息後關閉連線；關閉會讓 Danmu 的重試迴圈拋出
   * IOException，走進退避重連，再由 [onDanmuRetry] 換上新的 Cookie。
   */
  internal val reconnectRequested = AtomicBoolean(false)

  internal var idStr = ""

  override suspend fun initDanmu(streamer: Streamer, startTime: Instant): Boolean {
    // get room id
    val webRid = extractDouyinWebRid(streamer.url) ?: return false

    val config: DouyinDownloadConfig = streamer.downloadConfig as DouyinDownloadConfig

    streamerName = streamer.name

    // 只認這位主播自己的設定：一個抖音帳號無法多處同時登入，共用會互踢
    var cookies = resolveDouyinCookiesRaw(
      streamerCookies = config.cookies,
      streamerCookiesFile = config.cookiesFile,
    )
    val loggedIn = cookies.isNotEmpty()
    val rawCookies = cookies

    // 只有「從檔案讀」的來源才支援熱重載：主播設定裡直接貼的 cookies 字串優先序更高，
    // 重讀檔案也蓋不過它，那種情況硬要熱重載只會製造「改了檔卻沒生效」的困惑。
    cookiesFilePath = if (!config.cookies.isNullOrBlank()) null
    else config.cookiesFile?.trim()?.takeIf { it.isNotEmpty() }
    cookiesFileMtime = cookiesFilePath?.let { File(it).lastModified() } ?: 0L

    try {
      cookies = populateDouyinCookieMissedParams(cookies, app.client)
    } catch (e: Exception) {
      logger.error("{} Failed to populate douyin cookie missed params", webRid, e)
      return false
    }
    generatedParams = extractGeneratedCookieParams(rawCookies, cookies)

    when {
      // 沒設定就是刻意不登入，不是錯誤：聊天／進場／點讚照收，只有禮物收不到
      !loggedIn -> logger.info(
        "{} 未設定此主播的抖音 Cookie，以匿名連線錄製（收不到禮物）",
        streamer.name,
      )

      !cookieHeaderHasSessionId(cookies) -> logger.warn(
        "{} 此主播的 Cookie 沒有 sessionid，禮物仍然收不到。請貼上含 sessionid 的完整 Cookie。",
        streamer.name,
      )

      else -> registerSession(streamer.name, cookies)
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
    // 重連是唯一能換掉 Cookie 的時機：headersMap 每次連線都會重新套用
    // （見 Danmu.fillRequest），所以在這裡換就會生效，不必重啟整場錄製。
    reloadCookiesIfChanged()
    // update signature
    updateSignature()
  }

  /**
   * 若 Cookie 檔在上次讀取之後有更新，就重新載入並套進 [headersMap]。
   *
   * 以 mtime 當判準而不是「每次重連都重讀」：dycookie 內容沒變時不會覆寫檔案，
   * 所以 mtime 沒動就代表沒有新東西可換，重讀只是白費工。
   */
  internal fun reloadCookiesIfChanged(): Boolean {
    val path = cookiesFilePath ?: return false
    val file = File(path)
    if (!file.isFile) {
      logger.warn("{} Cookie 檔不見了：{}", streamerName, path)
      return false
    }
    val mtime = file.lastModified()
    if (mtime == cookiesFileMtime) return false

    val fresh = readDouyinCookiesFile(path)
    if (fresh == null || !cookieHeaderHasSessionId(fresh)) {
      // 記下 mtime，避免同一份壞檔案每次重連都重讀、重印一次警告
      cookiesFileMtime = mtime
      logger.warn("{} Cookie 檔已更新但沒有 sessionid，維持原本的登入態：{}", streamerName, path)
      return false
    }

    val merged = mergeDouyinCookies(fresh, generatedParams)
    headersMap[HttpHeaders.Cookie] = merged
    cookiesFileMtime = mtime

    // 換成另一組登入態時要把舊的登記歸還，否則 sessionOwners 會留著已經不用的 sessionid
    ownedSessionId?.let { sessionOwners.remove(it) }
    ownedSessionId = null
    registerSession(streamerName, merged)

    // 換了 Cookie 就重新觀察禮物健康度，否則舊的計數會讓判斷永遠停在「已失效」
    resetGiftHealth()
    logger.info("{} 已套用更新後的抖音 Cookie（{}）", streamerName, path)
    return true
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
    val decoded = msgList.mapNotNull { msg ->
      logger.trace("msg: {}", msg)
      if (DouyinDanmuProbe.enabled) DouyinDanmuProbe.method(msg.method)
      trackGiftHealth(msg.method)
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

    // 先把這批訊息解析完再斷線，不要為了重連而丟掉已經收到的彈幕
    if (reconnectRequested.compareAndSet(true, false)) {
      logger.warn("{} 偵測到禮物失效且 Cookie 檔已更新，主動斷線以套用新的登入態", streamerName)
      runCatching {
        session.close(CloseReason(CloseReason.Codes.NORMAL, "cookie updated"))
      }.onFailure { logger.debug("關閉 ws session 失敗：{}", it.toString()) }
    }

    return decoded
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
          val dropCombo = g.gift.combo && g.repeatEnd != 1
          if (DouyinDanmuProbe.enabled) DouyinDanmuProbe.gift(g, kept = !dropCombo)
          if (dropCombo) {
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
        // 加入訊息「恭喜 XXX 成为第1584214名龙浩天成员」帶團名，實測與 protobuf
        // clubName 一致，拿來餵查表就能替整場補齊（多數訊息的 clubName 是空的）
        extractClubName(fc.content)?.let {
          resolveClubName(u?.fansClub?.data?.anchorId?.takeIf { id -> id > 0 }, it)
        }
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
   *   但 `clubName` 多數為空（實測 496 則帶燈牌只有 2 則有團名），靠 [resolveClubName]
   *   依 nchorId 在同場錄影內查表補齊，並一併把 anchorId 寫出
   * - 房管與其他勳章都在 `BadgeImageList`，靠 `content.alternativeText`
   *   （如「房管勋章」「荣誉等级42级勋章」）判斷，比用檔名猜可靠
   */
  /**
   * anchorId -> 粉絲團名稱。抖音只在少數訊息填 `FansClub.data.clubName`
   * （實測某場 496 則帶燈牌的訊息裡只有 2 則有團名），但每則都帶 `anchorId`。
   *
   * 團名是「每位主播一個」，所以第一次看到團名就以 anchorId 為鍵記進表裡，
   * 之後同 anchorId 的訊息一律補上，寫進 XML 的就是完整資料，不必事後回填。
   *
   * 注意不能假設一個房間只有一位主播：單人直播間實測整場 670 則燈牌都是同一個
   * anchorId，但**團播**房間會同時出現多位主播的燈牌（實測某場 400 則有 7 個
   * anchorId）。以 anchorId 為鍵就能各自對應，不會把甲主播的團名套到乙主播的粉絲身上。
   *
   * 用 ConcurrentHashMap：decodeDanmu 會在多個 dispatcher worker 上並行解析。
   */
  private val clubNames = ConcurrentHashMap<Long, String>()

  /**
   * 「加入粉絲團」訊息裡的團名。實測句型：`恭喜 西瓜肠Zz 成为第1584214名龙浩天成员`，
   * 抓出來的「龙浩天」與同房 protobuf `clubName` 的值一致，可信。
   *
   * 升級訊息的 `刚刚升级至【龙浩天🐲(9号...】粉丝团 Lv6` 不用：那串會被抖音自己截斷，
   * 且與 clubName 不是同一個字串。
   */
  private val joinClubRegex = Regex("成为第\\d+名(.+?)成员")

  /** 粉絲團登入態健康度：禮物排行榜在跳但收不到禮物，代表 Cookie 過期 */
  private val giftSortSeen = AtomicInteger(0)
  private val giftSeen = AtomicInteger(0)
  private val giftExpiryWarned = AtomicBoolean(false)

  internal fun extractClubName(content: String?): String? =
    content?.let { joinClubRegex.find(it)?.groupValues?.getOrNull(1)?.trim() }
      ?.takeIf { it.isNotEmpty() }

  /**
   * 抓「Cookie 過期」這個無聲失效：連線正常、聊天照收、也不會缺 sessionid，
   * 只有 `WebcastGiftMessage` 默默變 0。實測 30 小時前的 Cookie 就已失效。
   *
   * 判準是 `WebcastGiftSortMessage`（禮物排行榜）：它在更新代表房間有禮物活動，
   * 此時若一則禮物都收不到，就不是「沒人送禮」而是登入態掉了。
   * 只是警告不是錯誤——冷門房間仍可能兩者皆低，所以措辭保留餘地，且整場只提醒一次。
   */
  /** 目前是否研判登入態可能已過期：排行榜持續更新卻一則禮物都沒有 */
  /** 目前這條連線佔用的 sessionid，[clean] 時用來歸還登記 */
  private var ownedSessionId: String? = null

  /**
   * 登記本主播佔用的登入態；若同一組 sessionid 已被別的主播佔著就示警。
   */
  internal fun registerSession(streamerName: String, cookies: String) {
    val sid = douyinSessionId(cookies) ?: return
    val owner = sessionOwners.putIfAbsent(sid, streamerName)
    if (owner == null || owner == streamerName) {
      ownedSessionId = sid
      return
    }
    logger.warn(
      "{} 與 {} 正在共用同一個抖音帳號。一個帳號無法多處同時登入，併發錄製會互相踢掉，" +
        "可能導致兩邊的禮物都收不到。請改用不同帳號的 Cookie，或只讓其中一位登入。",
      streamerName,
      owner,
    )
  }

  override fun clean() {
    ownedSessionId?.let { sid ->
      sessionOwners.remove(sid)
      ownedSessionId = null
    }
    super.clean()
  }

  internal fun giftExpirySuspected(): Boolean =
    giftSeen.get() == 0 && giftSortSeen.get() >= GIFT_EXPIRY_THRESHOLD

  internal fun trackGiftHealth(method: String) {
    when (method) {
      "WebcastGiftMessage" -> {
        giftSeen.incrementAndGet()
        return
      }

      "WebcastGiftSortMessage" -> giftSortSeen.incrementAndGet()
      else -> return
    }
    if (giftSeen.get() == 0 && giftSortSeen.get() >= GIFT_EXPIRY_THRESHOLD) {
      // 有新的 Cookie 可用才值得斷線重連。冷清的房間本來就整場零禮物，
      // 若不看檔案有沒有更新就重連，那種房間會被反覆踢下線，
      // 而 Danmu 的重試次數是有上限的，白白燒掉重試額度會讓真正的斷線救不回來。
      if (cookiesFileChangedSinceLoad()) {
        reconnectRequested.set(true)
      }
      if (giftExpiryWarned.compareAndSet(false, true)) {
        logger.warn(
          "{} 禮物排行榜已更新 {} 次卻收不到任何禮物訊息，抖音登入 Cookie 可能已過期。" +
            "請為此主播重新匯出含 sessionid 的 Cookie。",
          idStr,
          giftSortSeen.get(),
        )
      }
    }
  }

  /** 目前實際要送出的 Cookie 標頭。headersMap 在基底類別是 protected，測試需要一個讀取點。 */
  internal fun currentCookieHeader(): String? = headersMap[HttpHeaders.Cookie]

  /** Cookie 檔在載入之後是否被改過。沒有設定檔案來源時一律回 false。 */
  internal fun cookiesFileChangedSinceLoad(): Boolean {
    val path = cookiesFilePath ?: return false
    val file = File(path)
    return file.isFile && file.lastModified() != cookiesFileMtime
  }

  /** 換過 Cookie 之後重新觀察禮物健康度，否則舊計數會讓判斷永遠停在「已失效」。 */
  internal fun resetGiftHealth() {
    giftSeen.set(0)
    giftSortSeen.set(0)
    giftExpiryWarned.set(false)
  }

  /**
   * 有團名就記錄並回傳；沒有就用 anchorId 查先前記下的團名，查不到回傳 null。
   */
  internal fun resolveClubName(anchorId: Long?, clubName: String?): String? {
    val name = clubName?.takeIf { it.isNotEmpty() }
    if (anchorId == null || anchorId <= 0L) return name
    if (name != null) {
      clubNames.putIfAbsent(anchorId, name)
      return name
    }
    return clubNames[anchorId]
  }
  private fun DanmuData.withUser(user: Dy.User, kind: String): DanmuData {
    val fansClub = user.fansClub.data
    val badgeTexts = user.badgeImageListList.mapNotNull { it.content?.alternativeText?.takeIf(String::isNotEmpty) }
    return copy(
      kind = kind,
      payGradeLevel = user.payGrade.level.toInt().takeIf { it > 0 },
      fansClubLevel = fansClub.level.takeIf { it > 0 },
      fansClubName = resolveClubName(fansClub.anchorId.takeIf { it > 0 }, fansClub.clubName),
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