/*
 * Probe helper (add-on, not part of upstream stream-rec)
 *
 * 目的：把抖音 WebcastChatMessage 的完整內容 dump 出來，用來確認
 *       等級 (PayGrade)、粉絲團燈牌 (FansClub)、管理員/其他徽章 (BadgeImageList)
 *       以及專屬表情圖 (rtfContent.pieces[].imagevalue) 是否真的有回傳。
 *
 * 啟用方式（環境變數）：
 *   DOUYIN_DANMU_PROBE=/path/to/douyin_probe.txt   # 必填，指定輸出檔
 *   DOUYIN_DANMU_PROBE_LIMIT=40                    # 選填，最多 dump 幾筆，預設 40
 *   DOUYIN_DANMU_PROBE_RAW=1                       # 選填，附上 protobuf 原始文字格式
 *
 * 未設定 DOUYIN_DANMU_PROBE 時完全不做事，可安全留在正式版程式碼裡。
 */

package github.hua0512.plugins.douyin.danmu

import douyin.Dy
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

object DouyinDanmuProbe {

  private val outPath: String? = System.getenv("DOUYIN_DANMU_PROBE")?.takeIf { it.isNotBlank() }
  private val limit: Int = System.getenv("DOUYIN_DANMU_PROBE_LIMIT")?.toIntOrNull() ?: 40
  private val withRaw: Boolean = System.getenv("DOUYIN_DANMU_PROBE_RAW")?.isNotBlank() == true
  private val count = AtomicInteger(0)
  private val lock = Any()

  val enabled: Boolean get() = outPath != null

  fun dump(msg: Dy.ChatMessage) {
    val path = outPath ?: return
    val n = count.incrementAndGet()
    if (n > limit) return
    val text = buildString {
      append("========== CHAT #").append(n).append(" ==========\n")
      append(summary(msg))
      if (withRaw) {
        append("--- RAW protobuf ---\n")
        append(msg.toString())
        append('\n')
      }
    }
    synchronized(lock) {
      runCatching { File(path).appendText(text) }
    }
  }

  // ---------------------------------------------------------------- helpers

  private fun img(label: String, i: Dy.Image?): String {
    if (i == null) return ""
    val urls = i.urlListList
    if (urls.isEmpty() && i.uri.isNullOrEmpty()) return ""
    return buildString {
      append("    ").append(label).append(": ")
      append("w=").append(i.width).append(" h=").append(i.height)
      append(" animated=").append(i.isAnimated)
      if (!i.uri.isNullOrEmpty()) append(" uri=").append(i.uri)
      append('\n')
      urls.forEachIndexed { k, u -> append("      url[").append(k).append("]=").append(u).append('\n') }
      val c = i.content
      if (c != null && (c.name.isNotEmpty() || c.level.isNotEmpty() || c.alternativeText.isNotEmpty() || c.fontColor.isNotEmpty())) {
        append("      content: name=").append(c.name)
          .append(" level=").append(c.level)
          .append(" fontColor=").append(c.fontColor)
          .append(" alt=").append(c.alternativeText).append('\n')
      }
    }
  }

  private fun summary(m: Dy.ChatMessage): String = buildString {
    val u = m.user
    append("nick      : ").append(u.nickNameBytes.toStringUtf8()).append('\n')
    append("uid       : ").append(u.id).append("  idStr=").append(u.idStr).append('\n')
    append("content   : ").append(m.contentBytes.toStringUtf8()).append('\n')
    append("eventTime : ").append(m.eventTime).append('\n')

    // ---- 榮譽等級 ----
    val pg = u.payGrade
    append("PayGrade  : level=").append(pg.level)
      .append(" name=").append(pg.name)
      .append(" score=").append(pg.score).append('\n')
    append(img("payGrade.icon", pg.icon))
    append(img("payGrade.imIcon", pg.imIcon))
    append(img("payGrade.imIconWithLevel", pg.imIconWithLevel))
    append(img("payGrade.newImIconWithLevel", pg.newImIconWithLevel))
    append(img("payGrade.liveIcon", pg.liveIcon))
    append(img("payGrade.newLiveIcon", pg.newLiveIcon))
    pg.gradeIconListList.forEachIndexed { k, gi ->
      append("    gradeIconList[").append(k).append("]: level=").append(gi.level)
        .append(" levelStr=").append(gi.levelStr).append(" diamond=").append(gi.iconDiamond).append('\n')
      append(img("      icon", gi.icon))
    }

    // ---- 粉絲團燈牌 ----
    val fc = u.fansClub
    val d = fc.data
    append("FansClub  : clubName=").append(d.clubName)
      .append(" level=").append(d.level)
      .append(" status=").append(d.userFansClubStatus)
      .append(" anchorId=").append(d.anchorId).append('\n')
    val badge = d.badge
    if (badge != null) {
      append("    badge.title=").append(badge.title).append('\n')
      badge.iconsMap.forEach { (k, v) -> append(img("badge.icons[$k]", v)) }
    }
    fc.preferDataMap.forEach { (k, v) ->
      append("    preferData[").append(k).append("]: clubName=").append(v.clubName)
        .append(" level=").append(v.level).append('\n')
      v.badge?.iconsMap?.forEach { (ik, iv) -> append(img("      preferData[$k].icons[$ik]", iv)) }
    }

    // ---- 管理員 / 其他徽章 ----
    append("BadgeImageList (").append(u.badgeImageListList.size).append(")\n")
    u.badgeImageListList.forEachIndexed { k, b -> append(img("badgeImageList[$k]", b)) }
    append("RealTimeIconsList (").append(u.realTimeIconsListList.size).append(")\n")
    u.realTimeIconsListList.forEachIndexed { k, b -> append(img("realTimeIcons[$k]", b)) }
    append(img("medal", u.medal))
    append(img("avatarThumb", u.avatarThumb))
    append(img("avatarBorder", u.avatarBorder))

    // ---- 專屬表情 / 富文字 ----
    val rtf = m.rtfContent
    append("rtfContent: key=").append(rtf.key)
      .append(" defaultPattern=").append(rtf.defaultPattern).append('\n')
    val df = rtf.defaultFormat
    if (df != null) {
      append("    defaultFormat: color=").append(df.color).append(" bold=").append(df.bold).append('\n')
    }
    append("    pieces (").append(rtf.piecesList.size).append(")\n")
    rtf.piecesList.forEachIndexed { k, p ->
      append("    piece[").append(k).append("]: type=").append(p.type)
        .append(" valueRef=").append(p.valueRef)
        .append(" schemaKey=").append(p.schemaKey).append('\n')
      if (p.stringValue.isNotEmpty()) append("      stringValue=").append(p.stringValue).append('\n')
      p.format?.let { f ->
        if (f.color.isNotEmpty() || f.bold) append("      format: color=").append(f.color).append(" bold=").append(f.bold).append('\n')
      }
      p.imagevalue?.let { iv ->
        append("      imagevalue.scalingRate=").append(iv.scalingRate).append('\n')
        append(img("      imagevalue.image", iv.image))
      }
      p.uservalue?.let { uv ->
        if (uv.hasUser()) {
          append("      uservalue: nick=").append(uv.user.nickNameBytes.toStringUtf8())
            .append(" withColon=").append(uv.withColon)
            .append(" leftAdd=").append(uv.leftAdditionalContent)
            .append(" rightAdd=").append(uv.rightAdditionalContent).append('\n')
        }
      }
      p.patternrefvalue?.let { pr ->
        if (pr.key.isNotEmpty()) append("      patternRef: key=").append(pr.key).append(" default=").append(pr.defaultPattern).append('\n')
      }
    }
    append(img("backgroundImage", m.backgroundImage))
    append(img("backgroundImageV2", m.backgroundImageV2))
    append('\n')
  }

  // ---- 統計這個直播間到底推送了哪些訊息類型（表情訊息是否另有 method）----
  private val methodCounts = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger>()

  fun method(name: String) {
    val path = outPath ?: return
    methodCounts.computeIfAbsent(name) { java.util.concurrent.atomic.AtomicInteger(0) }.incrementAndGet()
    if (methodCounts.values.sumOf { it.get() } % 40 != 0) return
    val text = methodCounts.entries.sortedByDescending { it.value.get() }
      .joinToString("\n") { "  " + it.key + " = " + it.value.get() }
    synchronized(lock) {
      runCatching { java.io.File(path + ".methods.txt").writeText("METHOD COUNTS\n" + text + "\n") }
    }
  }
}
