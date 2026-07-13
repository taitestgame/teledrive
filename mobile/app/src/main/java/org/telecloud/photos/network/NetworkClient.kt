package org.telecloud.photos.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class NetworkClient(private val context: Context) {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
    private val prefs = EncryptedSharedPreferences.create(
        "telecloud_secure_prefs",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    @Volatile
    private var cachedAuthHeader: String? = null

    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val tokenCookie = cookies.find { it.name == "session_token" }
            if (tokenCookie != null) {
                prefs.edit().putString("session_token", tokenCookie.value).apply()
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val token = prefs.getString("session_token", null) ?: return emptyList()
            return listOf(
                Cookie.Builder()
                    .name("session_token")
                    .value(token)
                    .domain(url.host)
                    .path("/")
                    .build()
            )
        }
    }

    internal val okHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val username = prefs.getString("username", null)
                val password = prefs.getString("password", null)
                val requestBuilder = chain.request().newBuilder()
                if (username != null && password != null) {
                    val credentials = okhttp3.Credentials.basic(username, password)
                    requestBuilder.header("Authorization", credentials)
                }
                chain.proceed(requestBuilder.build())
            }
            .addInterceptor(logging)
            .build()
    }

    fun getServerUrl(): String? {
        return prefs.getString("server_url", null)
    }

    fun setServerUrl(url: String) {
        val normalizedUrl = if (url.endsWith("/")) url else "$url/"
        prefs.edit().putString("server_url", normalizedUrl).apply()
    }

    fun saveCredentials(username: String, password: String) {
        prefs.edit()
            .putString("username", username)
            .putString("password", password)
            .apply()
        cachedAuthHeader = okhttp3.Credentials.basic(username, password)
    }

    fun clearSession() {
        prefs.edit()
            .remove("username")
            .remove("password")
            .apply()
        cachedAuthHeader = null
    }

    fun hasCredentials(): Boolean {
        return !prefs.getString("username", null).isNullOrEmpty() && !prefs.getString("password", null).isNullOrEmpty()
    }

    fun getAuthorizationHeader(): String? {
        val cached = cachedAuthHeader
        if (cached != null) return cached
        val username = prefs.getString("username", null)
        val password = prefs.getString("password", null)
        if (username != null && password != null) {
            val header = okhttp3.Credentials.basic(username, password)
            cachedAuthHeader = header
            return header
        }
        return null
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        cachedAuthHeader = null
    }

    fun isWifiOnly(): Boolean {
        return prefs.getBoolean("wifi_only", false)
    }

    fun setWifiOnly(enabled: Boolean) {
        prefs.edit().putBoolean("wifi_only", enabled).apply()
    }

    fun isAutoBackupEnabled(): Boolean {
        return prefs.getBoolean("auto_backup", false)
    }

    fun setAutoBackupEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_backup", enabled).apply()
    }

    fun getBackupFolder(): String {
        return prefs.getString("backup_folder", "/Backups") ?: "/Backups"
    }

    fun setBackupFolder(folder: String) {
        var cleanFolder = folder.trim()
        if (cleanFolder.isEmpty()) {
            cleanFolder = "/Backups"
        }
        if (!cleanFolder.startsWith("/")) {
            cleanFolder = "/$cleanFolder"
        }
        prefs.edit().putString("backup_folder", cleanFolder).apply()
    }

    fun getApi(): TeleCloudApi? {
        val baseUrl = getServerUrl() ?: return null
        val httpUrl = baseUrl.toHttpUrlOrNull() ?: return null
        return try {
            Retrofit.Builder()
                .baseUrl(httpUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(TeleCloudApi::class.java)
        } catch (e: Exception) {
            null
        }
    }
}
