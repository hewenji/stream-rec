import github.hua0512.data.media.VideoFormat
import github.hua0512.data.stream.FileInfo
import github.hua0512.flv.data.other.FlvMetadataInfo
import github.hua0512.plugins.StreamerContext
import github.hua0512.plugins.download.base.DownloadCallback
import github.hua0512.plugins.download.engines.ffmpeg.FFmpegDownloadEngine
import github.hua0512.plugins.download.engines.ffmpeg.processFFmpegOutputLine
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.writeText

/**
 * 對應 CLAUDE.md 裡「抖音 CDN 斷線重連拿舊網址」那段意外：
 * ffmpeg 在上游連線被切斷時常常仍以 exit code 0 收尾，讓 handleExitCode()
 * 把它當成正常完成，沿用同一個（已經失效）網址立刻重連，
 * 保證會 404。這支測試驗證 STREAM_INTERRUPTION_MARKER 偵測邏輯不誤判、
 * 不誤殺，並且真的偵測到時改回報錯誤、並把正確的（改名後）
 * 路徑傳给 onDownloadError()。
 */

private class RecordingCallback : DownloadCallback {
  val downloaded = mutableListOf<FileInfo>()
  val errors = mutableListOf<Pair<String?, Exception>>()
  var finishedCount = 0

  override fun onInit() {}
  override fun onDownloadStarted(filePath: String, time: Long) {}
  override fun onDownloadProgress(diff: Long, bitrate: Double) {}
  override fun onDownloaded(data: FileInfo, metaInfo: FlvMetadataInfo?) {
    downloaded += data
  }

  override fun onDownloadFinished() {
    finishedCount++
  }

  override fun onDownloadError(filePath: String?, e: Exception) {
    errors += filePath to e
  }

  override fun onDownloadCancelled() {}
  override fun onDestroy() {}
}

/** 把 protected 成員暴露出來供測試控制，不真的跑 ffmpeg 進程。 */
private class TestFFmpegEngine : FFmpegDownloadEngine() {
  fun triggerExitCode(code: Int) = handleExitCode(code)
  fun markInterrupted() {
    streamInterrupted = true
  }

  fun setLastOpeningFile(path: java.nio.file.Path) {
    outputFolder = path.parent!!
    lastOpeningFile = path.name
    lastOpeningFileTime = 0
  }
}

private fun newEngine(callback: DownloadCallback, filePath: String): TestFFmpegEngine {
  val engine = TestFFmpegEngine()
  engine.init(
    downloadUrl = "http://example.com/stream.flv",
    downloadFormat = VideoFormat.flv,
    downloadFilePath = filePath,
    context = StreamerContext("test", "title", "DOUYIN"),
    callback = callback,
  )
  return engine
}

class FFmpegStreamInterruptionTest : FunSpec({

  test("processFFmpegOutputLine 偵測到 demuxing 錯誤時會呼叫 onStreamInterrupted") {
    var triggered = false
    processFFmpegOutputLine(
      line = "[in#0/flv @ 0x1] Error during demuxing: Input/output error",
      streamer = "test",
      lastSize = 0,
      onSegmentStarted = {},
      onStreamInterrupted = { triggered = true },
    ) { _, _, _ -> }
    triggered shouldBe true
  }

  test("單純的 Stream ends prematurely 不誤判為中斷") {
    // 這行幾乎每次直播收尾都會印（宣告長度是 unknown），
    // 不能拿來當偵測依據，否則正常停止錄影也會被當成異常。
    var triggered = false
    processFFmpegOutputLine(
      line = "[http @ 0x1] Stream ends prematurely at 123, should be 18446744073709551615",
      streamer = "test",
      lastSize = 0,
      onSegmentStarted = {},
      onStreamInterrupted = { triggered = true },
    ) { _, _, _ -> }
    triggered shouldBe false
  }

  test("handleExitCode 在偵測到中斷且檔案存在時，收檔並回報錯誤") {
    val tmpDir = Files.createTempDirectory("ffmpeg-engine-test")
    val partFile = tmpDir.resolve("PART_test.flv")
    partFile.writeText("fake video bytes")

    val callback = RecordingCallback()
    val engine = newEngine(callback, partFile.pathString)
    engine.setLastOpeningFile(partFile)
    engine.markInterrupted()

    engine.triggerExitCode(0) // ffmpeg 常在這種情況下仍以 exit code 0 收尾

    val finalFile = tmpDir.resolve("test.flv")
    partFile.exists() shouldBe false
    finalFile.exists() shouldBe true

    // 這段的內容是有效的，仍要走一次成功收檔
    callback.downloaded.size shouldBe 1
    callback.downloaded[0].path shouldBe finalFile.absolutePathString()

    // 但同時要回報一次錯誤，讓上層重新要一個新的直播網址
    callback.errors.size shouldBe 1
    val (errorPath, _) = callback.errors[0]
    // 錯誤回報的路徑要是改名後的最終路徑，不能是已經不存在的
    // PART_ 路徑，否則 PlatformDownloader 會誤判影片不存在，連累把彈幕檔也刪掉
    errorPath shouldBe finalFile.pathString

    tmpDir.toFile().deleteRecursively()
  }

  test("handleExitCode 沒偵測到中斷時，維持原本的 exit code 0 = 成功 行為") {
    val tmpDir = Files.createTempDirectory("ffmpeg-engine-test")
    val partFile = tmpDir.resolve("PART_test2.flv")
    partFile.writeText("fake video bytes")

    val callback = RecordingCallback()
    val engine = newEngine(callback, partFile.pathString)
    engine.setLastOpeningFile(partFile)
    // 注意：沒呼叫 markInterrupted()

    engine.triggerExitCode(0)

    callback.downloaded.size shouldBe 1
    callback.errors.size shouldBe 0
    callback.finishedCount shouldBe 1

    tmpDir.toFile().deleteRecursively()
  }

  test("handleExitCode 偵測到中斷但檔案不存在時，回到原本的失敗处理") {
    val tmpDir = Files.createTempDirectory("ffmpeg-engine-test")
    val partFile = tmpDir.resolve("PART_test3.flv")
    // 故意不建立檔案：模擬連文件都没寫出來的情況

    val callback = RecordingCallback()
    val engine = newEngine(callback, partFile.pathString)
    engine.setLastOpeningFile(partFile)
    engine.markInterrupted()

    engine.triggerExitCode(1)

    callback.downloaded.size shouldBe 0
    callback.errors.size shouldBe 1

    tmpDir.toFile().deleteRecursively()
  }
})
