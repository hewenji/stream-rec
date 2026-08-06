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

package github.hua0512.plugins.douyin.danmu

/**
 * @author hua0512
 * @date : 2024/10/6 21:43
 */
enum class DouyinWebcastMessages(val className: String) {

  CHAT_MESSAGE("WebcastChatMessage"),
  CONTROL_MESSAGE("WebcastControlMessage"),

  // ---- 公屏完整還原用：實測某團播房 80 則訊息的類型分佈為
  // MemberMessage 17、ChatMessage 15、InRoomBanner 11、RoomUserSeq 8、
  // CommonCardArea 7、RoomStats 6、RoomRank 3 ... 其中會出現在公屏的是以下幾種
  MEMBER_MESSAGE("WebcastMemberMessage"),        // XXX 加入了直播間
  GIFT_MESSAGE("WebcastGiftMessage"),            // 送出禮物
  LIKE_MESSAGE("WebcastLikeMessage"),            // 為主播點讚
  CHAT_LIKE_MESSAGE("WebcastChatLikeMessage"),   // 公屏版點讚（payload 同 LikeMessage）
  SOCIAL_MESSAGE("WebcastSocialMessage"),        // 關注 / 分享
  EMOJI_CHAT_MESSAGE("WebcastEmojiChatMessage"); // 大表情訊息

  companion object {
    fun fromClassName(className: String): DouyinWebcastMessages? {
      return DouyinWebcastMessages.entries.firstOrNull { it.className == className }
    }
  }
}