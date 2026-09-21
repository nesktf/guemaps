package com.nesktf.guemaps.data.model

import androidx.core.text.HtmlCompat

data class NewsArticle(
    val id: String,
    val title: String,
    val date: String,
    val summary: String,
    val imageUrl: String,
    val contentHtml: String? = null,
    val isRead: Boolean = false,
    val cachedAt: Long = System.currentTimeMillis()
)

/**
 * Parses article date formatted like "dd/MM/yyyy - HH:mm" or "dd/MM/yyyy" into a comparable key (YYYYMMDDHHmm).
 */
fun parseArticleDateKey(dateStr: String): Long {
    if (dateStr.isBlank()) return 0L
    return try {
        val parts = dateStr.trim().split(Regex("""\s*[-–]\s*"""))
        val datePart = parts.getOrNull(0) ?: ""
        val dmy = datePart.split(Regex("""[/.-]"""))
        if (dmy.size >= 3) {
            val day = dmy[0].trim().toIntOrNull() ?: 0
            val month = dmy[1].trim().toIntOrNull() ?: 0
            val year = dmy[2].trim().toIntOrNull() ?: 0
            var hour = 0
            var min = 0
            if (parts.size > 1) {
                val hm = parts[1].trim().split(":")
                if (hm.size >= 2) {
                    hour = hm[0].trim().toIntOrNull() ?: 0
                    min = hm[1].trim().toIntOrNull() ?: 0
                }
            }
            year.toLong() * 100000000L + month.toLong() * 1000000L + day.toLong() * 10000L + hour.toLong() * 100L + min.toLong()
        } else {
            0L
        }
    } catch (_: Exception) {
        0L
    }
}

val NewsArticleComparator: Comparator<NewsArticle> = compareByDescending<NewsArticle> {
    parseArticleDateKey(it.date)
}.thenByDescending {
    it.id.toLongOrNull() ?: 0L
}

fun List<NewsArticle>.sortedByMostRecent(): List<NewsArticle> {
    return this.sortedWith(NewsArticleComparator)
}

fun resolveUrl(base: String, relative: String): String {
    val cleanRelative = relative.trim()
    if (cleanRelative.startsWith("http://") || cleanRelative.startsWith("https://")) {
        return cleanRelative
    }
    val cleanBase = if (base.endsWith("/")) base else "$base/"
    return if (cleanRelative.startsWith("../")) {
        val parent = cleanBase.removeSuffix("/").substringBeforeLast("/", "https://www.saetasalta.com.ar")
        "$parent/${cleanRelative.removePrefix("../")}"
    } else if (cleanRelative.startsWith("/")) {
        val domain = cleanBase.substringBefore("://") + "://" + cleanBase.substringAfter("://").substringBefore("/")
        "$domain$cleanRelative"
    } else {
        "$cleanBase$cleanRelative"
    }
}

fun decodeHtmlEntities(text: String): String {
    if (!text.contains("&")) return text
    var s = text
    val entityMap = mapOf(
        "&aacute;" to "á", "&eacute;" to "é", "&iacute;" to "í", "&oacute;" to "ó", "&uacute;" to "ú",
        "&Aacute;" to "Á", "&Eacute;" to "É", "&Iacute;" to "Í", "&Oacute;" to "Ó", "&Uacute;" to "Ú",
        "&ntilde;" to "ñ", "&Ntilde;" to "Ñ",
        "&uuml;" to "ü", "&Uuml;" to "Ü",
        "&quot;" to "\"", "&amp;" to "&", "&apos;" to "'", "&lt;" to "<", "&gt;" to ">",
        "&nbsp;" to " ", "&ndash;" to "–", "&mdash;" to "—", "&hellip;" to "…",
        "&ldquo;" to "“", "&rdquo;" to "”", "&lsquo;" to "‘", "&rsquo;" to "’"
    )
    for ((k, v) in entityMap) {
        s = s.replace(k, v)
    }
    s = s.replace(Regex("""&#(\d+);""")) { match ->
        try {
            val code = match.groupValues[1].toInt()
            code.toChar().toString()
        } catch (_: Exception) {
            match.value
        }
    }
    s = s.replace(Regex("""&#x([0-9a-fA-F]+);""")) { match ->
        try {
            val code = match.groupValues[1].toInt(16)
            code.toChar().toString()
        } catch (_: Exception) {
            match.value
        }
    }
    return s
}

fun cleanHtmlText(raw: String): String {
    if (raw.isBlank()) return ""
    val stripped = raw.replace(Regex("""<[^>]*>"""), "")
    return decodeHtmlEntities(stripped).replace(Regex("""\s+"""), " ").trim()
}

/**
 * Scrapes news articles from the SAETA noticias list HTML.
 */
fun parseNewsListHtml(
    html: String,
    baseUrl: String = "https://www.saetasalta.com.ar/saetaw/"
): List<NewsArticle> {
    val articles = mutableListOf<NewsArticle>()

    val blogItemRegex = Regex("""<div[^>]*class="[^"]*blog-item[^"]*"[^>]*>(.*?)</div>\s*</div>""", RegexOption.DOT_MATCHES_ALL)
    val matches = blogItemRegex.findAll(html).toList()

    val blocks = if (matches.isNotEmpty()) {
        matches.map { it.groupValues[1] }
    } else {
        html.split(Regex("""class="[^"]*blog-item[^"]*"""")).drop(1)
    }

    for (block in blocks) {
        val idMatch = Regex("""href="[^"]*noticia\?id=([0-9]+)"""", RegexOption.IGNORE_CASE).find(block)
        val id = idMatch?.groupValues?.get(1)?.trim() ?: continue

        val titleMatch = Regex("""<h2[^>]*class="[^"]*blog-title[^"]*"[^>]*>(.*?)</h2>""", RegexOption.DOT_MATCHES_ALL).find(block)
        val title = cleanHtmlText(titleMatch?.groupValues?.get(1) ?: "")

        val metaMatch = Regex("""<div[^>]*class="[^"]*blog-meta[^"]*"[^>]*>.*?<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(block)
        val date = cleanHtmlText(metaMatch?.groupValues?.get(1) ?: "")

        val textMatch = Regex("""<div[^>]*class="[^"]*blog-text[^"]*"[^>]*>.*?<p[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL).find(block)
        val summary = cleanHtmlText(textMatch?.groupValues?.get(1) ?: "")

        val imgMatch = Regex("""<img[^>]+alt="imagen_noticia"[^>]*>""", RegexOption.IGNORE_CASE).find(block)
            ?: Regex("""<img[^>]*>""", RegexOption.IGNORE_CASE).find(block)
        val srcMatch = imgMatch?.let { Regex("""src="([^"]+)"""", RegexOption.IGNORE_CASE).find(it.value) }
        val rawSrc = srcMatch?.groupValues?.get(1)?.trim() ?: ""
        val imageUrl = if (rawSrc.isNotEmpty()) resolveUrl(baseUrl, rawSrc) else ""

        if (title.isNotEmpty()) {
            articles.add(
                NewsArticle(
                    id = id,
                    title = title,
                    date = date,
                    summary = summary,
                    imageUrl = imageUrl
                )
            )
        }
    }

    return articles
}

/**
 * Scrapes article body content from SAETA single article detail HTML.
 * Targeted at the second class "row" inside div class "feature".
 */
fun parseArticleDetailHtml(html: String): String {
    val featureIdx = html.indexOf("feature")
    val searchScope = if (featureIdx >= 0) {
        html.substring(featureIdx)
    } else {
        html
    }

    // Split rows inside feature
    val rows = searchScope.split(Regex("""<div[^>]*class="[^"]*\brow\b[^"]*"[^>]*>"""))
    val bodyRow = if (rows.size >= 3) {
        rows[2].substringBefore("""<div class="about""").substringBefore("""<div class="footer""")
    } else if (rows.size == 2) {
        rows[1].substringBefore("""<div class="about""").substringBefore("""<div class="footer""")
    } else {
        searchScope
    }

    // Extract inside <div class="col">
    val colMatch = Regex("""<div[^>]*class="[^"]*\bcol\b[^"]*"[^>]*>(.*)""", RegexOption.DOT_MATCHES_ALL).find(bodyRow)
    val rawContent = colMatch?.groupValues?.get(1) ?: bodyRow

    val withoutComments = rawContent.replace(Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL), "")
    return withoutComments
        .substringBefore("""<div class="about"""")
        .substringBefore("""<div class="footer"""")
        .trim()
}
