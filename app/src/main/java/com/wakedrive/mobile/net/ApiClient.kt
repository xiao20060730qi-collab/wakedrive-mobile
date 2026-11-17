package com.wakedrive.mobile.net

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Thin client for the existing Python backend. The backend keeps a cookie session
 * (wakedrive_session), so cookies are persisted across app restarts.
 */
object ApiClient {
    const val DEFAULT_BASE = "https://wakedrive.xyz/api"
    private const val PREFS = "wakedrive"

    private lateinit var prefs: SharedPreferences

    @Volatile
    var baseUrl: String = DEFAULT_BASE
        private set

    private lateinit var cookieJar: PersistentCookieJar

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        baseUrl = prefs.getString("baseUrl", DEFAULT_BASE) ?: DEFAULT_BASE
        cookieJar = PersistentCookieJar(prefs)
    }

    fun setBaseUrl(url: String) {
        val normalized = url.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
        baseUrl = normalized
        prefs.edit().putString("baseUrl", normalized).apply()
        cookieJar.clear()
    }

    fun isLoggedIn(): Boolean = cookieJar.hasCookies()

    fun clearSession() = cookieJar.clear()

    private class PersistentCookieJar(private val prefs: SharedPreferences) : CookieJar {
        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val stored = prefs.getStringSet(KEY, emptySet()).orEmpty().toMutableSet()
            // Drop older cookies for the same name before adding refreshed ones.
            val names = cookies.map { it.name }.toSet()
            stored.removeAll { line -> Cookie.parse(url, line)?.name in names }
            cookies.forEach { stored.add(it.toString()) }
            prefs.edit().putStringSet(KEY, stored).apply()
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return prefs.getStringSet(KEY, emptySet()).orEmpty()
                .mapNotNull { Cookie.parse(url, it) }
                .filter { it.expiresAt > System.currentTimeMillis() }
        }

        @Synchronized
        fun clear() {
            prefs.edit().remove(KEY).apply()
        }

        @Synchronized
        fun hasCookies(): Boolean {
            val now = System.currentTimeMillis()
            val url = HttpUrl.Builder().scheme("https").host("wakedrive.xyz").build()
            return prefs.getStringSet(KEY, emptySet()).orEmpty().any { line ->
                Cookie.parse(url, line)?.let { it.expiresAt > now } ?: false
            }
        }

        companion object {
            private const val KEY = "sessionCookies"
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            response.code to (response.body?.string().orEmpty())
        }
    }

    suspend fun login(username: String, password: String): Pair<Boolean, String?> {
        val payload = JSONObject().put("username", username).put("password", password)
        val request = Request.Builder()
            .url("$baseUrl/auth/login")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return try {
            val (code, body) = execute(request)
            if (code == 200) true to null
            else false to (parseDetail(body) ?: "登录失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun logout() {
        try {
            execute(Request.Builder().url("$baseUrl/auth/logout").post("{}".toRequestBody(JSON)).build())
        } catch (error: Exception) {
            // Best effort.
        }
        clearSession()
    }

    suspend fun createEvent(event: JSONObject): Pair<Boolean, Int> {
        val request = Request.Builder()
            .url("$baseUrl/events")
            .post(event.toString().toRequestBody(JSON))
            .build()
        return try {
            val (code, _) = execute(request)
            (code in 200..299) to code
        } catch (error: Exception) {
            false to 0
        }
    }

    suspend fun fetchEvents(limit: Int = 50, severity: String? = null, type: String? = null): Pair<JSONArray?, String?> {
        val builder = StringBuilder("$baseUrl/events?limit=$limit")
        if (!severity.isNullOrBlank()) builder.append("&severity=").append(java.net.URLEncoder.encode(severity, "UTF-8"))
        if (!type.isNullOrBlank()) builder.append("&type=").append(java.net.URLEncoder.encode(type, "UTF-8"))
        val request = Request.Builder().url(builder.toString()).get().build()
        return try {
            val (code, body) = execute(request)
            if (code == 200) {
                JSONObject(body).optJSONArray("items") to null
            } else {
                null to (parseDetail(body) ?: "请求失败（HTTP $code）")
            }
        } catch (error: Exception) {
            null to "无法连接服务器，请检查网络"
        }
    }

    suspend fun register(username: String, password: String): Pair<Boolean, String?> {
        val payload = JSONObject().put("username", username).put("password", password)
        val request = Request.Builder()
            .url("$baseUrl/auth/register")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return try {
            val (code, body) = execute(request)
            if (code == 201 || code == 200) true to null
            else false to (parseDetail(body) ?: "注册失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun me(): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/auth/me").get().build())
            if (code == 200) JSONObject(body).optJSONObject("user") else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun getSettings(): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/settings").get().build())
            if (code == 200) JSONObject(body) else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun putSettings(patch: JSONObject): Pair<Boolean, String?> {
        return try {
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/settings")
                    .put(patch.toString().toRequestBody(JSON)).build(),
            )
            if (code == 200) true to null else false to (parseDetail(body) ?: "保存失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun summary(range: String): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/analysis/summary?range=$range").get().build())
            if (code == 200) JSONObject(body) else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun createReport(payload: JSONObject): Pair<Boolean, String?> {
        return try {
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/reports")
                    .post(payload.toString().toRequestBody(JSON)).build(),
            )
            if (code in 200..299) true to null else false to (parseDetail(body) ?: "报告保存失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun fetchReports(limit: Int = 50): Pair<JSONArray?, String?> {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/reports?limit=$limit").get().build())
            if (code == 200) JSONObject(body).optJSONArray("items") to null else null to "报告加载失败（HTTP $code）"
        } catch (error: Exception) {
            null to "无法连接服务器，请检查网络"
        }
    }

    suspend fun fetchReport(id: String): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/reports/$id").get().build())
            if (code == 200) JSONObject(body) else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun aiChat(question: String): String? {
        return try {
            val payload = JSONObject().put("question", question)
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/ai/chat")
                    .post(payload.toString().toRequestBody(JSON)).build(),
            )
            if (code == 200) JSONObject(body).optString("answer").ifBlank { null } else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun restStops(lat: Double, lng: Double): JSONArray? {
        return try {
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/rest-stops?lat=$lat&lng=$lng").get().build(),
            )
            if (code == 200) JSONObject(body).optJSONArray("items") else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun acknowledgeEvent(id: String): Boolean {
        return try {
            val payload = JSONObject().put("status", "已处理").put("handledBy", "手机端管理员")
            val (code, _) = execute(
                Request.Builder().url("$baseUrl/events/$id")
                    .patch(payload.toString().toRequestBody(JSON)).build(),
            )
            code == 200
        } catch (error: Exception) {
            false
        }
    }

    suspend fun clearEvents(): Boolean {
        return try {
            val (code, _) = execute(Request.Builder().url("$baseUrl/events").delete().build())
            code == 200
        } catch (error: Exception) {
            false
        }
    }

    suspend fun adminLogin(username: String, password: String): Pair<Boolean, String?> {
        return try {
            val payload = JSONObject().put("username", username).put("password", password)
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/admin/login")
                    .post(payload.toString().toRequestBody(JSON)).build(),
            )
            if (code == 200) true to null else false to (parseDetail(body) ?: "管理员登录失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun adminLogout() {
        try {
            execute(Request.Builder().url("$baseUrl/admin/logout").post("{}".toRequestBody(JSON)).build())
        } catch (error: Exception) {
            // ignore
        }
    }

    suspend fun adminMe(): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/admin/me").get().build())
            if (code == 200) JSONObject(body).optJSONObject("user") else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun adminUsers(limit: Int = 100): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/admin/users?limit=$limit").get().build())
            if (code == 200) JSONObject(body) else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun adminUserEvents(userId: Long): JSONObject? {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/admin/users/$userId/events").get().build())
            if (code == 200) JSONObject(body) else null
        } catch (error: Exception) {
            null
        }
    }

    suspend fun adminDeleteUser(userId: Long): Pair<Boolean, String?> {
        return try {
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/admin/users/$userId").delete().build(),
            )
            if (code == 200) true to null else false to (parseDetail(body) ?: "删除失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun adminListAdmins(): Pair<JSONArray?, String?> {
        return try {
            val (code, body) = execute(Request.Builder().url("$baseUrl/admin/admins").get().build())
            if (code == 200) JSONObject(body).optJSONArray("items") to null
            else null to (parseDetail(body) ?: "仅 xiaoqi 可管理管理员（HTTP $code）")
        } catch (error: Exception) {
            null to "无法连接服务器，请检查网络"
        }
    }

    suspend fun adminCreateAdmin(username: String, password: String): Pair<Boolean, String?> {
        return try {
            val payload = JSONObject().put("username", username).put("password", password)
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/admin/admins")
                    .post(payload.toString().toRequestBody(JSON)).build(),
            )
            if (code == 201 || code == 200) true to null else false to (parseDetail(body) ?: "新增失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun adminResetPassword(adminId: Long, password: String): Pair<Boolean, String?> {
        return try {
            val payload = JSONObject().put("password", password)
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/admin/admins/$adminId/password")
                    .put(payload.toString().toRequestBody(JSON)).build(),
            )
            if (code == 200) true to null else false to (parseDetail(body) ?: "重置失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    suspend fun adminDeleteAdmin(adminId: Long): Pair<Boolean, String?> {
        return try {
            val (code, body) = execute(
                Request.Builder().url("$baseUrl/admin/admins/$adminId").delete().build(),
            )
            if (code == 200) true to null else false to (parseDetail(body) ?: "删除失败（HTTP $code）")
        } catch (error: Exception) {
            false to "无法连接服务器，请检查网络"
        }
    }

    private fun parseDetail(body: String): String? = try {
        JSONObject(body).optString("detail").ifBlank { null }
    } catch (error: Exception) {
        null
    }

    private val JSON = "application/json; charset=utf-8".toMediaType()
}
