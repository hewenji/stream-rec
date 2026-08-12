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

import github.hua0512.utils.replacePlaceholders
import github.hua0512.utils.substringBeforePlaceholders
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.equals.shouldBeEqual
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * replacePlaceholders() 本身就是用 TimeZone.currentSystemDefault() 把 Instant
 * 換算成本地時間（這是故意的：使用者要的是錄影機器當地時間的檔名），所以
 * 測試不能把某一台機器當時換算出來的本地時間字串寫死當期望值——換一個時區
 * 跑就會炸。這裡用同一個時區換算方式產生期望值，兩邊用的是同一套換算規則，
 * 仍然能抓出 replacePlaceholders() 本身格式化錯誤（補零、欄位對錯位置等）。
 */
private fun Instant.toFileNameTimeString(): String {
  val dt = this.toLocalDateTime(TimeZone.currentSystemDefault())
  return "%04d-%02d-%02d %02d-%02d-%02d".format(
    dt.year, dt.month.number, dt.day, dt.hour, dt.minute, dt.second
  )
}

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

class StringTest : FunSpec({

  test("testPlaceholderReplace") {
    val streamer = "雪乃荔荔枝"
    val title = "新人第一天开播"
    val platform = "huya"
    val time = 1708461712L
    val instant = Instant.fromEpochSeconds(time)

    val fileFormat = "{streamer} - {title} - {platform} - %Y-%m-%d %H-%M-%S"

    val formatted = fileFormat.replacePlaceholders(streamer, title, platform, instant)

    formatted shouldBeEqual "雪乃荔荔枝 - 新人第一天开播 - huya - ${instant.toFileNameTimeString()}"
  }

  test("testPlaceholderReplaceWithoutTime") {
    val streamer = "雪乃荔荔枝"
    val title = "新人第一天开播"
    val platform = "huya"

    val fileFormat = "{streamer} - {title} - {platform}"

    val formatted = fileFormat.replacePlaceholders(streamer, title, platform)

    formatted shouldBeEqual "雪乃荔荔枝 - 新人第一天开播 - huya"
  }

  test("testPlaceholderReplaceWithEmptyString") {
    val streamer = ""
    val title = ""
    val platform = ""
    val time = 1708461712L
    val instant = Instant.fromEpochSeconds(time)

    val fileFormat = "{streamer} - {title} - {platform} - %Y-%m-%d %H-%M-%S"

    val formatted = fileFormat.replacePlaceholders(streamer, title, platform, instant)

    formatted shouldBeEqual " -  -  - ${instant.toFileNameTimeString()}"
  }

  test("testPlaceholderReplaceWithoutPlatform") {
    val streamer = "雪乃荔荔枝"
    val title = "新人第一天开播"

    val fileFormat = "{streamer} - {title} - {platform}"

    val formatted = fileFormat.replacePlaceholders(streamer, title)

    formatted shouldBeEqual "雪乃荔荔枝 - 新人第一天开播 - {platform}"
  }

  test("testSubstringBeforePlaceholder") {

    val fileFormat = "/opt/records/{streamer}/%m/%d"

    val formatted = fileFormat.substringBeforePlaceholders()

    formatted shouldBeEqual "/opt/records/"
  }

  test("testSubstringBeforePlaceholderWithoutPlaceholder") {

    val fileFormat = "/opt/records/aaa/bbb"

    val formatted = fileFormat.substringBeforePlaceholders()

    formatted shouldBeEqual "/opt/records/aaa/bbb"
  }

  test("testSubstringBeforePlaceholderWithEmptyString") {

    val fileFormat = ""

    val formatted = fileFormat.substringBeforePlaceholders()

    formatted shouldBeEqual ""
  }

  test("testSubstringBeforePlaceholderWithOnlyPlaceholder") {

    val fileFormat = "{streamer}"

    val formatted = fileFormat.substringBeforePlaceholders()

    formatted shouldBeEqual ""
  }


})