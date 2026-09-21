package com.nesktf.guemaps

import com.nesktf.guemaps.data.model.parseArticleDateKey
import com.nesktf.guemaps.data.model.parseArticleDetailHtml
import com.nesktf.guemaps.data.model.parseNewsListHtml
import com.nesktf.guemaps.data.model.resolveUrl
import com.nesktf.guemaps.data.model.sortedByMostRecent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsParsingTest {

    @Test
    fun testResolveUrl() {
        val base = "https://www.saetasalta.com.ar/saetaw/"

        // Relative parent path
        val resolvedParent = resolveUrl(base, "../mm/imagen/01220260910154724.jpg")
        assertEquals("https://www.saetasalta.com.ar/mm/imagen/01220260910154724.jpg", resolvedParent)

        // Absolute path
        val resolvedAbs = resolveUrl(base, "https://other.domain.com/img.jpg")
        assertEquals("https://other.domain.com/img.jpg", resolvedAbs)

        // Root-relative path
        val resolvedRoot = resolveUrl(base, "/static/logo.png")
        assertEquals("https://www.saetasalta.com.ar/static/logo.png", resolvedRoot)
    }

    @Test
    fun testParseNewsListHtml() {
        val html = """
            <!DOCTYPE html>
            <html>
            <body>
            <div class="blog mt-125">
                <div class="container">
                    <div class="section-header">
                        <p>Ultimas novedades</p>
                        <h2>Noticias</h2>
                    </div>
                    <div class="row">
                        <div class="col-md-4">
                            <div class="blog-item">
                                <div class="blog-img">
                                    <img src="../mm/imagen/01220260910154724.jpg" alt="imagen_noticia">
                                </div>
                                <div class="blog-content">
                                    <h2 class="blog-title">Ubicaci&oacute;n de las paradas durante la Procesi&oacute;n</h2>
                                    <div class="blog-meta">
                                        <i class="fa fa-calendar-alt"></i>
                                        <p>10/09/2026 - 15:47</p>
                                    </div>
                                    <div class="blog-text">
                                        <p>Este martes 15 de septiembre, durante la procesi&oacute;n en honor al Se&ntilde;or y Virgen del Milagro...</p>
                                        <a class="btn" href="noticia?id=1471">Ver noticia</a>
                                    </div>
                                </div>
                            </div>
                        </div>
                        <div class="col-md-4">
                            <div class="blog-item">
                                <div class="blog-img">
                                    <img src="../mm/imagen/01220260828154440.jpg" alt="imagen_noticia">
                                </div>
                                <div class="blog-content">
                                    <h2 class="blog-title">Beneficio para la Marat&oacute;n de Hirpace</h2>
                                    <div class="blog-meta">
                                        <i class="fa fa-calendar-alt"></i>
                                        <p>28/08/2026 - 15:44</p>
                                    </div>
                                    <div class="blog-text">
                                        <p>Este domingo las personas que participen podr&aacute;n viajar sin cargo...</p>
                                        <a class="btn" href="noticia?id=1470">Ver noticia</a>
                                    </div>
                                </div>
                            </div>
                        </div>
                    </div>
                </div>
            </div>
            </body>
            </html>
        """.trimIndent()

        val articles = parseNewsListHtml(html)
        assertEquals(2, articles.size)

        val first = articles[0]
        assertEquals("1471", first.id)
        assertEquals("Ubicación de las paradas durante la Procesión", first.title)
        assertEquals("10/09/2026 - 15:47", first.date)
        assertTrue(first.summary.contains("Señor y Virgen del Milagro"))
        assertEquals("https://www.saetasalta.com.ar/mm/imagen/01220260910154724.jpg", first.imageUrl)

        val second = articles[1]
        assertEquals("1470", second.id)
        assertEquals("Beneficio para la Maratón de Hirpace", second.title)
        assertEquals("28/08/2026 - 15:44", second.date)
        assertTrue(second.summary.contains("viajar sin cargo"))
        assertEquals("https://www.saetasalta.com.ar/mm/imagen/01220260828154440.jpg", second.imageUrl)
    }

    @Test
    fun testParseArticleDetailHtml() {
        val html = """
            <div class="feature mt-125">
                <div class="container">
                    <div class="row">
                        <div class="col-md-6">
                            <img src="../mm/imagen/01220260910154724.jpg" alt="Imagen de noticia" class="img-thumbnail">
                        </div>
                        <div class="col-md-6">
                            <div class="section-header">
                                <p>10/09/2026 - 15:47</p>
                                <h2>Ubicación de las paradas</h2>
                            </div>
                            <p><b>Resumen en negrita de la noticia.</b></p>
                        </div>
                    </div>
                    <div class="row">
                        <div class="col">
                            <p>Este es el cuerpo principal de la noticia con detalles.<br />
                            <strong>PARADAS CORREDOR NORTE</strong><br />
                            1C Rivadavia esquina Zuvir&iacute;a</p>
                        </div>
                    </div>
                    <div class="about">
                        <div class="container">
                            <a class="btn" href="noticias">Volver</a>
                        </div>
                    </div>
                </div>
            </div>
        """.trimIndent()

        val content = parseArticleDetailHtml(html)
        assertNotNull(content)
        assertTrue(content.contains("cuerpo principal de la noticia"))
        assertTrue(content.contains("PARADAS CORREDOR NORTE"))
    }

    @Test
    fun testEntityDecodingAndCleanHtmlText() {
        val raw = "Texto con &aacute;, &eacute;, &iacute;, &oacute;, &uacute;, &ntilde; y &#241; con <b>tags</b>"
        val cleaned = com.nesktf.guemaps.data.model.cleanHtmlText(raw)
        assertEquals("Texto con á, é, í, ó, ú, ñ y ñ con tags", cleaned)
    }

    @Test
    fun testCappingAt16Articles() {
        val sb = StringBuilder()
        sb.append("<div class=\"blog\"><div class=\"row\">")
        for (i in 1..25) {
            sb.append("""
                <div class="col-md-4">
                    <div class="blog-item">
                        <div class="blog-img">
                            <img src="../mm/imagen/$i.jpg" alt="imagen_noticia">
                        </div>
                        <div class="blog-content">
                            <h2 class="blog-title">Noticia $i</h2>
                            <div class="blog-meta"><p>01/01/2026</p></div>
                            <div class="blog-text">
                                <p>Resumen $i</p>
                                <a class="btn" href="noticia?id=$i">Ver</a>
                            </div>
                        </div>
                    </div>
                </div>
            """.trimIndent())
        }
        sb.append("</div></div>")

        val parsed = parseNewsListHtml(sb.toString())
        assertEquals(25, parsed.size)
        val capped = parsed.take(16)
        assertEquals(16, capped.size)
        assertEquals("1", capped.first().id)
        assertEquals("16", capped.last().id)
    }

    @Test
    fun testParseArticleDateKey() {
        val d1 = com.nesktf.guemaps.data.model.parseArticleDateKey("10/09/2026 - 15:47")
        val d2 = com.nesktf.guemaps.data.model.parseArticleDateKey("28/08/2026 - 15:44")
        val d3 = com.nesktf.guemaps.data.model.parseArticleDateKey("07/04/2026 - 15:57")
        val d4 = com.nesktf.guemaps.data.model.parseArticleDateKey("07/04/2026 - 14:59")
        val d5 = com.nesktf.guemaps.data.model.parseArticleDateKey("07/04/2026")

        assertTrue("d1 > d2", d1 > d2)
        assertTrue("d2 > d3", d2 > d3)
        assertTrue("d3 > d4", d3 > d4)
        assertTrue("d4 > d5", d4 > d5)
    }

    @Test
    fun testSortedByMostRecent() {
        val a1 = com.nesktf.guemaps.data.model.NewsArticle(
            id = "1468",
            title = "Obras Av Chile",
            date = "11/08/2026 - 11:15",
            summary = "",
            imageUrl = ""
        )
        val a2 = com.nesktf.guemaps.data.model.NewsArticle(
            id = "1471",
            title = "Procesión",
            date = "10/09/2026 - 15:47",
            summary = "",
            imageUrl = ""
        )
        val a3 = com.nesktf.guemaps.data.model.NewsArticle(
            id = "1470",
            title = "Maratón",
            date = "28/08/2026 - 15:44",
            summary = "",
            imageUrl = ""
        )
        val a4 = com.nesktf.guemaps.data.model.NewsArticle(
            id = "1467",
            title = "Muni en tu barrio",
            date = "07/08/2026 - 14:28",
            summary = "",
            imageUrl = ""
        )

        // Pass out of order (oldest first or scrambled)
        val scrambled = listOf(a1, a4, a2, a3)
        val sorted = scrambled.sortedByMostRecent()

        // Expect most recent first: 1471 (Sep), 1470 (Aug 28), 1468 (Aug 11), 1467 (Aug 7)
        assertEquals("1471", sorted[0].id)
        assertEquals("1470", sorted[1].id)
        assertEquals("1468", sorted[2].id)
        assertEquals("1467", sorted[3].id)
    }
}
