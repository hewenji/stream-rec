/*
 * MIT License
 *
 * Stream-rec  https://github.com/hua0512/stream-rec
 *
 * Copyright (c) 2024 hua0512 (https://github.com/hua0512)
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

package github.hua0512.data.media

import kotlin.time.Instant

/**
 * This is a sealed class that represents a wrapper for Danmu data.
 * A sealed class is used here to represent a restricted class hierarchy.
 *
 * @author hua0512
 * @date : 2024/2/11 1:21
 */
sealed class DanmuDataWrapper {

  /**
   * This data class represents the actual Danmu data.
   * It contains information about the sender, color, content, font size, server time, and client time.
   *
   * @property uid The uid of the sender of the danmu.
   * @property sender The name of the sender of the Danmu.
   * @property color The color of the Danmu.
   * @property content The content of the Danmu.
   * @property fontSize The font size of the Danmu.
   * @property serverTime The time when the server received the Danmu, in epoch milliseconds.
   * @property clientTime The time when the client sent the Danmu. This is optional.
   */
  data class DanmuData(
    val uid: Long,
    val sender: String,
    val color: Int,
    val content: String,
    val fontSize: Int,
    val serverTime: Long,
    /**
     * 以下為「公屏完整還原」用的擴充欄位，只有抖音會填，其他平台一律 null。
     * 全部有預設值，因此不影響既有平台的建構呼叫。
     *
     * @property kind 訊息種類：chat / member / gift / like / social / emoji
     * @property payGradeLevel 榮譽等級（PayGrade.level）
     * @property fansClubLevel 粉絲團等級（FansClub.data.level）
     * @property fansClubName 粉絲團名稱，抖音彈幕通常為空，需靠 anchorId 另查
     * @property anchorId 粉絲團所屬主播 id，用來事後解析團名
     * @property isAdmin 是否有房管勳章
     * @property badges 徽章說明清單，取自 BadgeImageList[].content.alternativeText，以 | 分隔
     * @property avatarUrl 頭像 URL
     * @property giftId 禮物 id
     * @property giftName 禮物名稱
     * @property giftCount 禮物總數（total_count）
     * @property giftComboCount 連擊數（combo_count）
     * @property giftReceiver 收禮人暱稱（團播會送給特定主播）
     * @property likeCount 本次點讚數
     * @property memberCount 當前房間人數（加入直播間訊息附帶）
     */
    val kind: String? = null,
    val payGradeLevel: Int? = null,
    val fansClubLevel: Int? = null,
    val fansClubName: String? = null,
    val anchorId: Long? = null,
    val isAdmin: Boolean? = null,
    val badges: String? = null,
    val avatarUrl: String? = null,
    val giftId: Long? = null,
    val giftName: String? = null,
    val giftCount: Long? = null,
    val giftComboCount: Long? = null,
    val giftReceiver: String? = null,
    val likeCount: Long? = null,
    val memberCount: Long? = null,
  ) : DanmuDataWrapper()


  /**
   * This object represents the end of the Danmu data.
   */
  data object EndOfDanmu : DanmuDataWrapper()

}


data class ClientDanmuData(
  val danmu: DanmuDataWrapper,
  val videoStartTime: Instant,
  val clientTime: Double,
)