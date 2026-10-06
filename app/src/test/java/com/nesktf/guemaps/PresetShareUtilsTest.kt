package com.nesktf.guemaps

import com.nesktf.guemaps.data.model.BusStopRecord
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.util.PresetShareUtils
import com.nesktf.guemaps.util.SharedPresetPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetShareUtilsTest {

    @Test
    fun testEncodeAndDecodePresetPayload_WithoutStop() {
        val lines = listOf(
            FlatBusLine(groupPath = "URBANO > Corredor 1", codLinea = "101", descripcion = "1A San Carlos"),
            FlatBusLine(groupPath = "URBANO > Corredor 2", codLinea = "105", descripcion = "2B")
        )
        val original = SharedPresetPayload(
            name = "Casa al Trabajo",
            lines = lines,
            stop = null
        )

        val encodedUri = PresetShareUtils.encodeToUri(original)
        assertTrue(encodedUri.startsWith("https://guemaps.app/preset?l="))

        val decoded = PresetShareUtils.decodeFromUri(encodedUri)
        assertNotNull(decoded)
        assertEquals("Casa al Trabajo", decoded!!.name)
        assertEquals(2, decoded.lines.size)
        assertEquals("101", decoded.lines[0].codLinea)
        assertEquals("105", decoded.lines[1].codLinea)
        assertNull(decoded.stop)
    }

    @Test
    fun testEncodeAndDecodePresetPayload_WithStop() {
        val lines = listOf(
            FlatBusLine(groupPath = "URBANO > Corredor 7", codLinea = "701", descripcion = "7A")
        )
        val stop = BusStopRecord(
            lineId = "701",
            stopCode = "STP_123",
            stopName = "Av. San Martín y Alvarado",
            latitude = -24.785,
            longitude = -65.411
        )
        val original = SharedPresetPayload(
            name = "Facultad",
            lines = lines,
            stop = stop
        )

        val encodedUri = PresetShareUtils.encodeToUri(original)
        assertTrue(encodedUri.startsWith("https://guemaps.app/preset?l="))

        val decoded = PresetShareUtils.decodeFromUri(encodedUri)
        assertNotNull(decoded)
        assertEquals("Facultad", decoded!!.name)
        assertEquals(1, decoded.lines.size)
        assertNotNull(decoded.stop)
        assertEquals("STP_123", decoded.stop!!.stopCode)
        assertEquals("Av. San Martín y Alvarado", decoded.stop!!.stopName)
        assertEquals(-24.785, decoded.stop!!.latitude, 0.0001)
        assertEquals(-65.411, decoded.stop!!.longitude, 0.0001)
        assertEquals("701", decoded.stop!!.lineId)
    }

    @Test
    fun testDecodeCustomSchemeUri() {
        val uri = "guemaps://preset?l=101,105&n=Casa%20al%20Trabajo"
        val decoded = PresetShareUtils.decodeFromUri(uri)
        assertNotNull(decoded)
        assertEquals("Casa al Trabajo", decoded!!.name)
        assertEquals(2, decoded.lines.size)
        assertEquals("101", decoded.lines[0].codLinea)
        assertEquals("105", decoded.lines[1].codLinea)
    }

    @Test
    fun testCatalogEnrichmentLogic() {
        val uri = "https://guemaps.app/preset?l=101,105&n=Trabajo"
        val decoded = PresetShareUtils.decodeFromUri(uri)
        assertNotNull(decoded)

        val catalog = listOf(
            FlatBusLine(groupPath = "URBANO > Corredor 1", codLinea = "101", descripcion = "1A San Carlos"),
            FlatBusLine(groupPath = "URBANO > Corredor 2", codLinea = "105", descripcion = "2B Santa Ana")
        )
        val catalogMap = catalog.associateBy { it.codLinea.trim() }

        val enrichedLines = decoded!!.lines.map { line ->
            catalogMap[line.codLinea.trim()]?.let { matched ->
                line.copy(groupPath = matched.groupPath, descripcion = matched.descripcion)
            } ?: line
        }

        assertEquals(2, enrichedLines.size)
        assertEquals("URBANO > Corredor 1", enrichedLines[0].groupPath)
        assertEquals("1A San Carlos", enrichedLines[0].descripcion)
        assertEquals("1A San Carlos", enrichedLines[0].nombreLinea)
        assertEquals("1A San Carlos", enrichedLines[0].nombreCorto)

        assertEquals("URBANO > Corredor 2", enrichedLines[1].groupPath)
        assertEquals("2B Santa Ana", enrichedLines[1].descripcion)
        assertEquals("2B Santa Ana", enrichedLines[1].nombreCorto)
    }

    @Test
    fun testDecodeInvalidUris() {
        assertNull(PresetShareUtils.decodeFromUri(""))
        assertNull(PresetShareUtils.decodeFromUri("https://google.com"))
        assertNull(PresetShareUtils.decodeFromUri("https://guemaps.app/other?l=101"))
        assertNull(PresetShareUtils.decodeFromUri("guemaps://other?l=101"))
        assertNull(PresetShareUtils.decodeFromUri("guemaps://preset"))
        assertNull(PresetShareUtils.decodeFromUri("guemaps://preset?l="))
        assertNull(PresetShareUtils.decodeFromUri("guemaps://preset?d=!!!invalid_base64@@@"))
        assertNull(PresetShareUtils.decodeFromUri("guemaps://preset?d=e30")) // empty JSON object "{}" -> lines is empty
    }
}
