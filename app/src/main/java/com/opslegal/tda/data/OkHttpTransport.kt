package com.opslegal.tda.data

import com.opslegal.tda.core.agent.HttpResponse
import com.opslegal.tda.core.agent.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OkHttpTransport(private val client: OkHttpClient) : HttpTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, body: String): HttpResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { HttpResponse(it.code, it.body?.string().orEmpty()) }
        }

    companion object {
        // Planning turns can think for a while; keep generous timeouts.
        val shared = OkHttpTransport(
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .callTimeout(6, TimeUnit.MINUTES)
                .build(),
        )
    }
}
