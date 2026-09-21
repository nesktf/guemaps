package com.nesktf.guemaps.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class NewsScraperClient(
    private val client: OkHttpClient = createNewsOkHttpClient()
) {
    companion object {
        const val NEWS_LIST_URL = "https://www.saetasalta.com.ar/saetaw/noticias"
        const val ARTICLE_DETAIL_BASE_URL = "https://www.saetasalta.com.ar/saetaw/noticia?id="

        private fun createNewsOkHttpClient(): OkHttpClient {
            val builder = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)

            try {
                val trustAllCerts = arrayOf<TrustManager>(
                    object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    }
                )

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (_: Exception) {}

            return builder.build()
        }
    }

    suspend fun fetchNewsListHtml(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(NEWS_LIST_URL)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-AR,es;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error ${response.code} fetching news list"))
            }

            val body = response.body?.string() ?: ""
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchArticleDetailHtml(id: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$ARTICLE_DETAIL_BASE_URL$id")
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "es-AR,es;q=0.9")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error ${response.code} fetching article $id"))
            }

            val body = response.body?.string() ?: ""
            Result.success(body)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchImageBytes(imageUrl: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(imageUrl)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:109.0) Gecko/109.0 Firefox/119.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP error ${response.code} fetching image"))
            }

            val bytes = response.body?.bytes()
                ?: return@withContext Result.failure(IOException("Empty image body"))

            Result.success(bytes)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
