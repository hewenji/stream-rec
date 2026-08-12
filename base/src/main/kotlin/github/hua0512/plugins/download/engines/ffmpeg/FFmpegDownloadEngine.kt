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

package github.hua0512.plugins.download.engines.ffmpeg

import github.hua0512.data.stream.FileInfo
import github.hua0512.download.exceptions.DownloadErrorException
import github.hua0512.flv.data.video.VideoResolution
import github.hua0512.plugins.download.engines.BaseDownloadEngine
import github.hua0512.utils.*
import github.hua0512.utils.Programs.ffmpeg
import github.hua0512.utils.Programs.ffprobe
import github.hua0512.utils.process.Redirect
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Instant
import org.slf4j.Logger
import java.io.OutputStream
import java.nio.file.Path
import kotlin.io.path.*
import kotlin.time.Clock

/**
 * FFmpegDownloadEngine is a download engine that uses ffmpeg to download the stream.
 * @author hua0512
 * @date : 2024/5/5 21:16
 */
open class FFmpegDownloadEngine(override val logger: Logger = Companion.logger) :
  BaseDownloadEngine() {

  companion object {
    @JvmStatic
    internal val logger = logger(FFmpegDownloadEngine::class.java)
  }

  /**
   * Whether to use ffmpeg built-in segmenter to download the stream
   */
  internal var useSegmenter: Boolean = false
  internal var detectErrors: Boolean = false

  protected var ous: OutputStream? = null
  protected var process: Process? = null
  protected var ffprobeProcess: Process? = null

  protected var lastOpeningFile: String? = null
  protected var lastOpeningFileTime: Long = 0
  protected var lastOpeningSize = 0L

  // 這次 ffmpeg 執行過程中有沒有偵測到上游連線被中斷（見 FFmpeg.kt 的
  // STREAM_INTERRUPTION_MARKER）。只代表「這一次 ffmpeg 進程」的狀態，
  // 每次呼叫 start() 都要重設，不會跨進程殘留。
  protected var streamInterrupted = false
  protected lateinit var outputFolder: Path
  protected lateinit var outputFileName: String

  private val resulutionSet = mutableSetOf<VideoResolution>()

  protected fun initPath(startInstant: Instant) {
    updateOutputFolder(startInstant)
    outputFileName = Path(downloadFilePath).name
    if (!useSegmenter) {
      lastOpeningFileTime = startInstant.epochSeconds
      // replace time placeholders if not using segmenter
      outputFileName = outputFileName.replacePlaceholders(context.name, context.title, context.platform, startInstant)
      // update downloadFilePath
      downloadFilePath = outputFolder.resolve(outputFileName).pathString
      lastOpeningFile = outputFileName
    }
  }

  private fun updateOutputFolder(startInstant: Instant) {
    outputFolder = Path(downloadFilePath.replacePlaceholders(context.name, context.title, context.platform, startInstant)).parent
    outputFolder.createDirectories()
  }

  override suspend fun start() = coroutineScope {
    val startTime = Clock.System.now()
    streamInterrupted = false
    initPath(startTime)
    // ffmpeg running commands
    val cmds = buildFFMpegCmd(
      headers,
      cookies,
      downloadUrl!!,
      downloadFormat!!,
      fileLimitSize,
      fileLimitDuration,
      useSegmenter,
      false,
      outputFileName
    )

    val streamer = context
    debug("ffmpeg command: ${cmds.joinToString(" ")}")
    if (!useSegmenter) {
      onDownloadStarted(downloadFilePath, startTime.epochSeconds)
    }

    if (detectErrors)
      launch {
        // detect errors by using ffprobe
        // source : https://superuser.com/questions/841235/how-do-i-use-ffmpeg-to-get-the-video-resolution
        // I doubt this will work for all streams, but it's worth a try
        val cmds = buildFFprobeCmd(headers, cookies, downloadUrl!!)
        val exitCode = executeProcess(
          ffprobe,
          *cmds,
          stdout = Redirect.CAPTURE,
          stderr = Redirect.CAPTURE,
          destroyForcibly = false,
          getProcess = {
            ffprobeProcess = it
          },
        ) { line ->
          if (line.contains("x")) {
            val res = line.split("x")
            if (res.size == 2) {
              val resolution = VideoResolution(res[0].toInt(), res[1].toInt())
              val result = resulutionSet.add(resolution)
              if (result) {
                debug("resolution detected: {}", resolution)

                if (resulutionSet.size > 1) {
                  error("resolution changed: {}", resolution)
                  sendStopSignal()
                  ffprobeProcess?.destroy()
                  ffprobeProcess = null
                }
              }
            }
          }
        }
      }

    val exitCode: Int = executeProcess(
      ffmpeg,
      *cmds,
      directory = outputFolder.toFile(),
      stdout = Redirect.CAPTURE,
      stderr = Redirect.CAPTURE,
      destroyForcibly = false,
      getOutputStream = {
        ous = it
      },
      getProcess = {
        process = it
      },
      onCancellation = {
        sendStopSignal()
      }) { line ->
      processFFmpegOutputLine(
        line = line,
        streamer = streamer.name,
        lastSize = lastOpeningSize,
        onSegmentStarted = { name ->
          processSegment(outputFolder, name)
        },
        onStreamInterrupted = { streamInterrupted = true }
      ) { size, diff, bitrate ->
        handleDownloadProgress(bitrate, size, diff)
      }
    }
    handleExitCode(exitCode)
    process = null
    ffprobeProcess = null
    ous = null
    resulutionSet.clear()
  }

  protected fun handleDownloadProgress(
    bitrate: String,
    size: Long,
    diff: Long,
  ) {
    // if using segmentation, ffmpeg does not provide the total size of the file
    if (useSegmenter) {
      // calculate the total size of the file
      val currentSize = lastOpeningFile?.let { outputFolder.resolve(it).fileSize() } ?: 0
      val newDiff = currentSize - lastOpeningSize
      trace(
        "currentSize: {}, lastPartedSize: {}, diff: {}, bitrate: {}",
        currentSize,
        lastOpeningSize,
        newDiff,
        bitrate
      )
      lastOpeningSize = currentSize
      val bitrate = getBitrate(bitrate)
      onDownloadProgress(newDiff, bitrate)
    } else {
      lastOpeningSize = size
      val bitrate = getBitrate(bitrate)
      onDownloadProgress(diff, bitrate)
    }
  }

  protected fun handleExitCode(exitCode: Int) {
    if (lastOpeningFile == null) {
      error("ffmpeg download failed, exit code: {}", exitCode)
      onDownloadError(downloadFilePath, DownloadErrorException("ffmpeg download failed (exit code: $exitCode)"))
      return
    }
    val file = outputFolder.resolve(lastOpeningFile!!)
    val fileExists = file.exists()

    if (streamInterrupted && fileExists) {
      // ffmpeg 在上游連線被切斷時常常仍以「看起來正常」的方式收尾（見
      // FFmpeg.kt 裡 STREAM_INTERRUPTION_MARKER 的說明），但沿用同一個網址
      // 立刻重連幾乎必定失敗（像抖音這種簽章網址斷線後就失效）。這裡照樣把
      // 已經寫出的部分收檔（內容仍然有效），但額外回報一次錯誤，讓上層走
      // 既有的重試機制重新要一個新的直播網址，而不是拿舊網址硬重試。
      warn(
        "ffmpeg exited (code {}) after an upstream stream interruption; finalizing this part but " +
            "reporting it as an error so the caller re-fetches a fresh stream URL", exitCode
      )
      // onDownloaded() 內部會把檔案從 PART_ 前綴改名成正式檔名，之後任何人再用
      // 舊的 file.pathString 都找不到檔案了。onDownloadError() 的 filePath 得用
      // 改名後的路徑，否則 PlatformDownloader 會誤判「影片檔不存在」而把這段
      // 的彈幕 XML 也刪掉——影片明明是好的，不該連累彈幕。
      val finalPath = file.parent.resolve(file.name.removePrefix(PART_PREFIX)).pathString
      onDownloaded(FileInfo(file.pathString, 0, lastOpeningFileTime, Clock.System.now().epochSeconds))
      onDownloadError(
        finalPath,
        DownloadErrorException("upstream stream interrupted (ffmpeg exit code: $exitCode)")
      )
    } else if (exitCode != 0) {
      error("ffmpeg download failed, exit code: $exitCode")
      // check if the file exists
      if (fileExists) {
        onDownloaded(FileInfo(file.pathString, 0, lastOpeningFileTime, Clock.System.now().epochSeconds))
        onDownloadFinished()
      } else {
        onDownloadError(file.pathString, DownloadErrorException("ffmpeg download failed, file not created"))
      }
    } else {
      // case when download is successful (exit code is 0)
      onDownloaded(FileInfo(file.pathString, 0, lastOpeningFileTime, Clock.System.now().epochSeconds))
      onDownloadFinished()
    }

    // delete 'core' file (ffmpeg error file) if it exists as we don't need it
    val coreFile = outputFolder.resolve("core")
    if (coreFile.exists()) {
      coreFile.deleteFile()
    }
  }


  protected fun processSegment(folder: Path, fileName: String) {
    // first segment
    if (lastOpeningFile == null) {
      debug("first segment: {}", fileName)
      lastOpeningFile = fileName
      val now = Clock.System.now().epochSeconds
      lastOpeningFileTime = now
      onDownloadStarted(folder.resolve(fileName).pathString, now)
      return
    }
    val now = Clock.System.now()
    val nowEpoch = now.epochSeconds
    updateOutputFolder(now)
    debug("segment finished: {}", lastOpeningFile)
    // construct file data
    val fileData = FileInfo(
      path = folder.resolve(lastOpeningFile!!).pathString,
      size = lastOpeningSize,
      createdAt = lastOpeningFileTime,
      updatedAt = nowEpoch
    )
    // notify last segment finished
    onDownloaded(fileData)
    // notify segment started
    debug("segment started: {}", fileName)
    // reset lastOpeningSize
    lastOpeningSize = 0
    lastOpeningFile = fileName
    lastOpeningFileTime = nowEpoch
    onDownloadStarted(folder.resolve(fileName).pathString, nowEpoch)
  }


  protected fun getBitrate(bitrate: String): Double = try {
    bitrate.substring(0, bitrate.indexOf("k")).toDouble()
  } catch (e: Exception) {
    0.0
  }


  protected open fun sendStopSignal() {
    if (ous == null) return
    // check if the process is still running
    if (process?.isAlive == false) return

    with(ous!!) {
      try {
        write("q\n".toByteArray())
        flush()
      } catch (e: Exception) {
        error("Error sending stop signal to ffmpeg process", throwable = e)
      }
    }
  }

  override suspend fun stop(exception: Exception?): Boolean {
    withIOContext {
      info("$downloadUrl stopping ffmpeg process...")
      ffprobeProcess?.destroy()
      sendStopSignal()
    }
    val code = withIOContext { process?.waitFor() }
    if (code != 0) {
      error("ffmpeg process exited with code $code")
    }
    return code == 0
  }
}