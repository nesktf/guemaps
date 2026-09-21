package com.nesktf.guemaps.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.nesktf.guemaps.data.model.BusGroupsResponse
import com.nesktf.guemaps.data.model.BusPosResponse
import com.nesktf.guemaps.data.model.BusRouteResponse
import com.nesktf.guemaps.data.model.CardBalance
import com.nesktf.guemaps.data.model.CardBalanceDeserializer
import com.nesktf.guemaps.data.model.CardBalanceResponse
import com.nesktf.guemaps.data.model.ConfigResponse
import com.nesktf.guemaps.data.model.MonederoWrapper
import com.nesktf.guemaps.data.model.MonederoWrapperDeserializer
import com.nesktf.guemaps.data.model.SellingPointResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class InMemoryCookieJar : CookieJar {
    private val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val current = cookieStore.getOrPut(host) { mutableListOf() }
        cookies.forEach { newCookie ->
            current.removeAll { it.name == newCookie.name }
            current.add(newCookie)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return cookieStore[url.host]?.filter { !it.hasExpired() } ?: emptyList()
    }

    fun clear() {
        cookieStore.clear()
    }

    private fun Cookie.hasExpired(): Boolean {
        return expiresAt < System.currentTimeMillis()
    }
}

class SaetaApiClient(
    private val client: OkHttpClient = createDefaultClient(),
    private val gson: Gson = createDefaultGson()
) {
    companion object {
        const val BASE_URL = "https://salta.miredbus.com.ar"
        const val API_REST_URL = "$BASE_URL/rest"

        fun createDefaultGson(): Gson {
            return GsonBuilder()
                .registerTypeAdapter(CardBalance::class.java, CardBalanceDeserializer())
                .registerTypeAdapter(MonederoWrapper::class.java, MonederoWrapperDeserializer())
                .create()
        }

        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .cookieJar(InMemoryCookieJar())
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val request = chain.request().newBuilder()
                        .header("User-Agent", "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36")
                        .header("Accept", "application/json, image/png, */*")
                        .build()
                    chain.proceed(request)
                }
                .build()
        }
    }

    suspend fun fetchBusGroups(): Result<BusGroupsResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_REST_URL/gruposLineas")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val result = gson.fromJson(body, BusGroupsResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchBusRoute(lineId: String): Result<BusRouteResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_REST_URL/rutaLinea/$lineId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val result = gson.fromJson(body, BusRouteResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchActiveBuses(lineId: String): Result<BusPosResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_REST_URL/posicionesBuses/$lineId")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val result = gson.fromJson(body, BusPosResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchCaptcha(): Result<Bitmap> = withContext(Dispatchers.IO) {
        try {
            val msecs = System.currentTimeMillis()
            val request = Request.Builder()
                .url("$BASE_URL/captcha.png?time=$msecs")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Failed to load captcha image: ${response.code}"))
            }

            val inputStream = response.body?.byteStream()
                ?: return@withContext Result.failure(IOException("Empty captcha body"))

            val bitmap = BitmapFactory.decodeStream(inputStream)
                ?: return@withContext Result.failure(IOException("Failed to decode captcha bitmap"))

            Result.success(bitmap)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun checkCardBalance(cardNumber: String, captcha: String): Result<CardBalanceResponse> = withContext(Dispatchers.IO) {
        try {
            val cleanCard = cardNumber.trim()
            val cleanCaptcha = captcha.trim()
            val request = Request.Builder()
                .url("$API_REST_URL/getSaldoCaptcha/$cleanCard/$cleanCaptcha")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            android.util.Log.d("SaetaApiClient", "Card balance response: $body")
            val result = gson.fromJson(body, CardBalanceResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchSellingPoints(version: Long = 0): Result<SellingPointResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_REST_URL/getPuntosVenta/$version")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val result = gson.fromJson(body, SellingPointResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchConfig(): Result<ConfigResponse> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$API_REST_URL/getConfiguracion")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error code ${response.code}"))
            }

            val body = response.body?.string()
                ?: return@withContext Result.failure(IOException("Empty response body"))

            val result = gson.fromJson(body, ConfigResponse::class.java)
            Result.success(result)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

