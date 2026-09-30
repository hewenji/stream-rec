/*
 * Douyin accounts listing + host-bridge login trigger (dycookie integration).
 *
 * See dyass/docs/dycookie-streamrec-bridge.md for the on-disk / HTTP contract.
 */

package github.hua0512.backend.routes

import github.hua0512.backend.logger
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val accountsJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun secretsDir(): File =
  File(System.getenv("DYCOOKIE_SECRETS_DIR") ?: "/opt/secrets")

private fun accountsFile(): File =
  System.getenv("DYCOOKIE_ACCOUNTS_FILE")?.let(::File)
    ?: File(secretsDir(), "accounts.json")

private fun bridgeUrl(): String =
  System.getenv("DYCOOKIE_BRIDGE_URL") ?: "http://host.docker.internal:18765"

private fun jsonStr(o: kotlinx.serialization.json.JsonObject, vararg keys: String): String? {
  for (key in keys) {
    val el = o[key] ?: continue
    if (el is kotlinx.serialization.json.JsonNull) continue
    val p = runCatching { el.jsonPrimitive }.getOrNull() ?: continue
    if (!p.isString && p.content == "null") continue
    return p.content
  }
  return null
}


@Serializable
data class DouyinAccountDto(
  val name: String,
  val outFile: String? = null,
  val cookiesFile: String,
  val enabled: Boolean = true,
  val status: String? = null,
  val note: String? = null,
  val sidFingerprint: String? = null,
  val lastOkAt: String? = null,
  /** True when the cookie file exists and is readable inside the container. */
  val filePresent: Boolean = false,
)

@Serializable
data class DouyinAccountsResponse(
  val version: Int = 1,
  val source: String,
  val accounts: List<DouyinAccountDto>,
  val gaps: List<String> = emptyList(),
)

/**
 * Load accounts.json; if missing, synthesize a list from douyin_cookies_*.txt in the secrets dir.
 */
fun loadDouyinAccounts(): DouyinAccountsResponse {
  val dir = secretsDir()
  val file = accountsFile()
  val gaps = mutableListOf<String>()

  if (!dir.isDirectory) {
    gaps += "secrets dir missing or unreadable: ${dir.path}"
  }

  val fromJson = if (file.isFile) {
    runCatching {
      val rootEl = accountsJson.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
      val version = rootEl["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
      val arr = rootEl["accounts"]?.jsonArray ?: emptyList()
      val list = arr.mapNotNull { el ->
        val o = el.jsonObject
        val name = jsonStr(o, "name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val outFile = jsonStr(o, "outFile", "out_file")
        val cookiesFile = jsonStr(o, "cookiesFile", "cookies_file")
          ?: outFile?.let { "/opt/secrets/$it" }
          ?: return@mapNotNull null
        DouyinAccountDto(
          name = name,
          outFile = outFile,
          cookiesFile = cookiesFile,
          enabled = o["enabled"]?.jsonPrimitive?.content?.let { it.equals("true", true) } ?: true,
          
          status = jsonStr(o, "status"),
          note = jsonStr(o, "note"),
          sidFingerprint = jsonStr(o, "sidFingerprint", "sid_fingerprint"),
          lastOkAt = jsonStr(o, "lastOkAt", "last_ok_at"),
          filePresent = File(cookiesFile).isFile,
        )
      }
      DouyinAccountsResponse(version = version, source = file.path, accounts = list)
    }.onFailure {
      logger.warn("Failed to parse accounts.json: {}", it.toString())
      gaps += "accounts.json parse error: ${it.message}"
    }.getOrNull()
  } else {
    gaps += "accounts.json missing at ${file.path}; falling back to cookie-file scan"
    null
  }

  if (fromJson != null) {
    return fromJson.copy(gaps = gaps + fromJson.gaps)
  }

  val scanned = dir.listFiles { f ->
    f.isFile && f.name.startsWith("douyin_cookies_") && f.name.endsWith(".txt")
  }
    ?.sortedBy { it.name }
    ?.map { f ->
      val stem = f.name.removePrefix("douyin_cookies_").removeSuffix(".txt")
      DouyinAccountDto(
        name = stem,
        outFile = f.name,
        cookiesFile = "/opt/secrets/${f.name}",
        enabled = true,
        status = "unknown",
        note = "synthesized from cookie file (no accounts.json)",
        filePresent = true,
      )
    }
    ?: emptyList()

  if (scanned.isEmpty()) {
    gaps += "no douyin_cookies_*.txt under ${dir.path}"
  }

  return DouyinAccountsResponse(
    version = 0,
    source = "scan:${dir.path}",
    accounts = scanned,
    gaps = gaps,
  )
}

fun Route.douyinAccountsRoute() {
  route("/douyin") {
    get("/accounts") {
      try {
        call.respond(loadDouyinAccounts())
      } catch (e: Exception) {
        logger.error("Failed to list Douyin accounts", e)
        call.respond(HttpStatusCode.InternalServerError, buildJsonObject {
          put("ok", false)
          put("error", e.message ?: "internal error")
        })
      }
    }

    post("/accounts/{name}/login") {
      val name = call.parameters["name"]?.trim().orEmpty()
      if (name.isEmpty()) {
        return@post call.respond(HttpStatusCode.BadRequest, buildJsonObject {
          put("ok", false)
          put("error", "missing account name")
        })
      }

      // Drain optional body; we only forward the account name.
      runCatching { call.receiveText() }

      val base = bridgeUrl().trimEnd('/')
      val url = "$base/login"
      val body = buildJsonObject { put("account", name) }.toString()

      try {
        val client = HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .build()
        val request = HttpRequest.newBuilder()
          .uri(URI.create(url))
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body))
          .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = HttpStatusCode.fromValue(response.statusCode())
        val raw = response.body().orEmpty()
        if (raw.isNotBlank() && raw.trimStart().startsWith("{")) {
          call.respondText(raw, ContentType.Application.Json, status)
        } else {
          call.respond(status, buildJsonObject {
            put("ok", status.isSuccess())
            put("account", name)
            put("message", raw.ifBlank { status.description })
            put("bridgeUrl", url)
          })
        }
      } catch (e: Exception) {
        logger.warn("Douyin login bridge unreachable ({}): {}", url, e.toString())
        call.respond(HttpStatusCode.BadGateway, buildJsonObject {
          put("ok", false)
          put("account", name)
          put("error", "dycookie host bridge unreachable at $url — ${e.message}")
          put("hint", "Start the host bridge (see docs/dycookie-streamrec-bridge.md) or set DYCOOKIE_BRIDGE_URL")
        })
      }
    }
  }
}
