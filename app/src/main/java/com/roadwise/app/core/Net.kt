package com.roadwise.app.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class Net(ctx: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
        }
        .build()

    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val _online = MutableStateFlow(checkNow())
    val online: StateFlow<Boolean> = _online

    init {
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { _online.value = checkNow() }
            override fun onLost(network: Network) { _online.value = checkNow() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _online.value = checkNow() }
        })
    }

    private fun checkNow(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    val isOnline get() = _online.value

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url)
        headers.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).await().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
            body
        }
    }

    suspend fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val rb = Request.Builder().url(url).post(json.toRequestBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> rb.header(k, v) }
        http.newCall(rb.build()).await().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
            body
        }
    }

    /** Overpass query with a long timeout, falling back to a mirror if the main server is busy. */
    suspend fun overpass(query: String): String {
        var last: Exception? = null
        for (base in OVERPASS_SERVERS) {
            try { return postForm(base, "data=" + java.net.URLEncoder.encode(query, "UTF-8"), 90) }
            catch (e: Exception) { last = e }
        }
        throw last ?: IOException("Overpass unavailable")
    }

    suspend fun postForm(url: String, form: String, timeoutSec: Long = 30): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
        val client = if (timeoutSec == 30L) http else http.newBuilder().readTimeout(timeoutSec, TimeUnit.SECONDS).build()
        client.newCall(req).await().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}: ${body.take(200)}")
            body
        }
    }

    /** Streams a URL into [dest] (via a .part file), reporting bytes downloaded. */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        http.newBuilder().readTimeout(120, TimeUnit.SECONDS).build().newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext false
            if (!r.isSuccessful) throw IOException("HTTP ${r.code} for $url")
            val body = r.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val part = File(dest.parentFile, dest.name + ".part")
            part.outputStream().use { out ->
                body.byteStream().use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        ensureActive() // stop promptly when the user cancels
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) throw IOException("rename failed")
            true
        }
    }

    /** Runs an HTTP call that is cancelled the moment the coroutine is (so time limits really stop it). */
    private suspend fun okhttp3.Call.await(): okhttp3.Response = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (cont.isActive) cont.resumeWith(Result.success(response)) else response.close()
            }
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }
        })
    }

    companion object {
        const val USER_AGENT = "Roadwise/1.0 (Android navigation app; OpenStreetMap data)"
        val OVERPASS_SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )
    }
}
