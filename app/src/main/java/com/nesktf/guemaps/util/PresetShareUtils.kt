package com.nesktf.guemaps.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import com.google.gson.Gson
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.nesktf.guemaps.data.model.BusStopRecord
import com.nesktf.guemaps.data.model.FlatBusLine
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class SharedPresetPayload(
    val name: String,
    val lines: List<FlatBusLine>,
    val stop: BusStopRecord? = null
)

object PresetShareUtils {
    const val WEB_HOST = "guemaps.app"
    const val WEB_PATH = "/preset"
    const val SCHEME = "guemaps"
    const val HOST = "preset"
    const val PARAM_LINES = "l"
    const val PARAM_NAME = "n"
    const val PARAM_STOP = "s"
    const val PARAM_LEGACY_DATA = "d"

    private val gson = Gson()

    fun encodeToUri(payload: SharedPresetPayload): String {
        val linesParam = payload.lines
            .map { it.codLinea.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(",")

        val encodedLines = URLEncoder.encode(linesParam, "UTF-8")
        val builder = StringBuilder("https://$WEB_HOST$WEB_PATH?$PARAM_LINES=$encodedLines")

        if (payload.name.isNotBlank()) {
            builder.append("&$PARAM_NAME=").append(URLEncoder.encode(payload.name.trim(), "UTF-8"))
        }

        if (payload.stop != null) {
            val s = payload.stop
            val stopValue = listOf(
                s.stopCode,
                s.latitude.toString(),
                s.longitude.toString(),
                s.stopName,
                s.lineId
            ).joinToString("|")
            builder.append("&$PARAM_STOP=").append(URLEncoder.encode(stopValue, "UTF-8"))
        }

        return builder.toString()
    }

    fun decodeFromUri(uriString: String): SharedPresetPayload? {
        val trimmed = uriString.trim()
        if (trimmed.isEmpty()) return null

        val isValidPrefix = trimmed.startsWith("https://$WEB_HOST$WEB_PATH", ignoreCase = true) ||
                trimmed.startsWith("http://$WEB_HOST$WEB_PATH", ignoreCase = true) ||
                trimmed.startsWith("$SCHEME://$HOST", ignoreCase = true)

        if (!isValidPrefix) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            val isCustomScheme = uri?.scheme.equals(SCHEME, ignoreCase = true) && uri?.host.equals(HOST, ignoreCase = true)
            val isWebScheme = (uri?.scheme.equals("https", ignoreCase = true) || uri?.scheme.equals("http", ignoreCase = true)) &&
                    uri?.host.equals(WEB_HOST, ignoreCase = true) &&
                    (uri?.path?.startsWith(WEB_PATH) == true)
            if (!isCustomScheme && !isWebScheme) return null
        }

        val queryString = if (trimmed.contains("?")) trimmed.substringAfter("?") else ""
        if (queryString.isEmpty()) return null

        val params = parseQueryParams(queryString)

        // Check compact parameters first (Option 1: l, n, s)
        val linesRaw = params[PARAM_LINES] ?: params["lines"]
        if (!linesRaw.isNullOrBlank()) {
            val lineCodes = linesRaw.split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            if (lineCodes.isEmpty()) return null

            val name = params[PARAM_NAME] ?: params["name"] ?: "Líneas compartidas"
            val stopRaw = params[PARAM_STOP] ?: params["stop"]
            val stop = parseStopRecord(stopRaw)

            val lines = lineCodes.map { code ->
                FlatBusLine(
                    groupPath = "",
                    codLinea = code,
                    descripcion = "Línea $code"
                )
            }

            return SharedPresetPayload(
                name = name,
                lines = lines,
                stop = stop
            )
        }

        // Fallback to legacy base64 data parameter (d)
        val legacyData = params[PARAM_LEGACY_DATA]
        if (!legacyData.isNullOrBlank()) {
            return decodeFromLegacyData(legacyData)
        }

        return null
    }

    fun decodeFromUri(uri: Uri): SharedPresetPayload? {
        return decodeFromUri(uri.toString())
    }

    private fun parseQueryParams(queryString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        queryString.split("&").forEach { pair ->
            if (pair.contains("=")) {
                val key = pair.substringBefore("=")
                val rawValue = pair.substringAfter("=")
                val decoded = runCatching { URLDecoder.decode(rawValue, "UTF-8") }.getOrDefault(rawValue)
                map[key] = decoded
            }
        }
        return map
    }

    private fun parseStopRecord(stopRaw: String?): BusStopRecord? {
        if (stopRaw.isNullOrBlank()) return null
        val parts = stopRaw.split("|")
        if (parts.size >= 4) {
            val stopCode = parts[0].trim()
            val lat = parts[1].toDoubleOrNull() ?: return null
            val lon = parts[2].toDoubleOrNull() ?: return null
            val stopName = parts[3].trim()
            val lineId = if (parts.size >= 5) parts[4].trim() else ""
            return BusStopRecord(
                lineId = lineId,
                stopCode = stopCode,
                stopName = stopName,
                latitude = lat,
                longitude = lon
            )
        }
        return null
    }

    private fun decodeFromLegacyData(dataParam: String): SharedPresetPayload? {
        return try {
            val jsonBytes = Base64.getUrlDecoder().decode(dataParam)
            val json = String(jsonBytes, StandardCharsets.UTF_8)
            val payload = gson.fromJson(json, SharedPresetPayload::class.java)
            if (payload != null && payload.lines.isNotEmpty()) {
                payload
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun generateQrCodeWithLogo(
        content: String,
        logoBitmap: Bitmap?,
        sizePx: Int = 800
    ): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 1,
            EncodeHintType.CHARACTER_SET to "UTF-8"
        )

        val bitMatrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            hints
        )

        val width = bitMatrix.width
        val height = bitMatrix.height
        val pixels = IntArray(width * height)

        val qrColor = Color.parseColor("#1B1B2F")
        val bgColor = Color.WHITE

        for (y in 0 until height) {
            val offset = y * width
            for (x in 0 until width) {
                pixels[offset + x] = if (bitMatrix.get(x, y)) qrColor else bgColor
            }
        }

        val qrBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        qrBitmap.setPixels(pixels, 0, width, 0, 0, width, height)

        if (logoBitmap == null) {
            return qrBitmap
        }

        val combinedBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(combinedBitmap)
        canvas.drawBitmap(qrBitmap, 0f, 0f, null)

        val logoTargetSize = (width * 0.22f).toInt()
        val badgeSize = (logoTargetSize * 1.22f).toInt()
        val centerX = width / 2f
        val centerY = height / 2f

        val badgeRect = RectF(
            centerX - badgeSize / 2f,
            centerY - badgeSize / 2f,
            centerX + badgeSize / 2f,
            centerY + badgeSize / 2f
        )

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E0E0E0")
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }

        val cornerRadius = badgeSize * 0.28f
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, badgePaint)
        canvas.drawRoundRect(badgeRect, cornerRadius, cornerRadius, borderPaint)

        val logoDstRect = RectF(
            centerX - logoTargetSize / 2f,
            centerY - logoTargetSize / 2f,
            centerX + logoTargetSize / 2f,
            centerY + logoTargetSize / 2f
        )
        canvas.drawBitmap(logoBitmap, null, logoDstRect, Paint(Paint.FILTER_BITMAP_FLAG))

        return combinedBitmap
    }
}
