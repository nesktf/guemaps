package com.nesktf.guemaps

import com.google.gson.Gson
import com.nesktf.guemaps.data.model.BusGroupsResponse
import com.nesktf.guemaps.data.model.BusPosResponse
import com.nesktf.guemaps.data.model.BusRouteResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BusModelsTest {

    private val gson = Gson()

    @Test
    fun testBusGroupsDeserializationAndFlattening() {
        val json = """
            {
              "error": 0,
              "grupos": {
                "codGrupo": "Grupos de Lineas",
                "subGrupos": [
                  {
                    "codGrupo": "URBANO",
                    "subGrupos": [
                      {
                        "codGrupo": "Corredor 1",
                        "subGrupos": null,
                        "lineas": [
                          { "codLinea": "100", "descripcion": "1B" },
                          { "codLinea": "101", "descripcion": "1A San Carlos" }
                        ]
                      },
                      {
                        "codGrupo": "Corredor 2",
                        "subGrupos": null,
                        "lineas": [
                          { "codLinea": "105", "descripcion": "2B" }
                        ]
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val response = gson.fromJson(json, BusGroupsResponse::class.java)
        assertEquals(0, response.error)
        assertNotNull(response.grupos)

        val flatLines = response.grupos!!.flattenLines()
        assertEquals(3, flatLines.size)

        val first = flatLines[0]
        assertEquals("100", first.codLinea)
        assertEquals("1B", first.descripcion)
        assertTrue(first.groupPath.contains("URBANO"))
        assertTrue(first.groupPath.contains("Corredor 1"))

        val second = flatLines[1]
        assertEquals("101", second.codLinea)
        assertEquals("1A San Carlos", second.descripcion)

        val third = flatLines[2]
        assertEquals("105", third.codLinea)
        assertEquals("2B", third.descripcion)

        // Test category and subgroup extraction
        val categories = response.grupos!!.extractCategories()
        assertEquals(1, categories.size)
        val cat = categories[0]
        assertEquals("URBANO", cat.name)
        assertEquals(2, cat.subgroups.size)
        assertEquals("Corredor 1", cat.subgroups[0].subgroupName)
        assertEquals(2, cat.subgroups[0].lines.size)
        assertEquals("Corredor 2", cat.subgroups[1].subgroupName)
        assertEquals(1, cat.subgroups[1].lines.size)
    }

    @Test
    fun testBusRouteDeserialization() {
        val json = """
            {
              "error": 0,
              "nodos": [
                {
                  "latitud": -24.85349,
                  "longitud": -65.42859,
                  "parada": false,
                  "codigoParada": "",
                  "descripcionParada": ""
                },
                {
                  "latitud": -24.85426,
                  "longitud": -65.42767,
                  "parada": true,
                  "codigoParada": "p001-1b",
                  "descripcionParada": "Av. San Martín 120"
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, BusRouteResponse::class.java)
        assertEquals(0, response.error)
        assertNotNull(response.nodos)
        assertEquals(2, response.nodos!!.size)

        val nonStop = response.nodos!![0]
        assertFalse(nonStop.parada)
        assertEquals(-24.85349, nonStop.latitud, 0.0001)

        val stop = response.nodos!![1]
        assertTrue(stop.parada)
        assertEquals("p001-1b", stop.codigoParada)
        assertEquals("Av. San Martín 120", stop.descripcionParada)
    }

    @Test
    fun testBusPosDeserialization() {
        val json = """
            {
              "error": 0,
              "posiciones": [
                {
                  "interno": "121",
                  "latitud": -24.7952,
                  "longitud": -65.4065,
                  "orientacion": 96.0,
                  "proximaParada": "Isla Cisne e Isla Norte",
                  "vehiculoRampa": true,
                  "vehiculoNoVisibles": false
                },
                {
                  "interno": "123",
                  "latitud": -24.8533,
                  "longitud": -65.4288,
                  "orientacion": 0.0,
                  "proximaParada": null,
                  "vehiculoRampa": false,
                  "vehiculoNoVisibles": false
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, BusPosResponse::class.java)
        assertEquals(0, response.error)
        assertNotNull(response.posiciones)
        assertEquals(2, response.posiciones!!.size)

        val bus1 = response.posiciones!![0]
        assertEquals("121", bus1.interno)
        assertTrue(bus1.vehiculoRampa)
        assertEquals(96.0f, bus1.orientacion, 0.1f)
        assertEquals("Isla Cisne e Isla Norte", bus1.proximaParada)

        val bus2 = response.posiciones!![1]
        assertEquals("123", bus2.interno)
        assertFalse(bus2.vehiculoRampa)
    }

    @Test
    fun testFlatBusLineHelperProperties() {
        val line1 = com.nesktf.guemaps.data.model.FlatBusLine("Corredor 1", "101", "1A - VILLA PRIMAVERA")
        assertEquals("1A", line1.nombreCorto)
        assertEquals("1A - VILLA PRIMAVERA", line1.nombreLinea)

        val line2 = com.nesktf.guemaps.data.model.FlatBusLine("Troncales", "200", "TRONCAL N-S")
        assertEquals("TRONCAL N-S", line2.nombreCorto)

        val line3 = com.nesktf.guemaps.data.model.FlatBusLine("Metropolitano", "515", "Cerrillos - Santa Teresita")
        assertEquals("Cerrillos - Santa Teresita", line3.nombreCorto)

        val longString = "A".repeat(80)
        val line4 = com.nesktf.guemaps.data.model.FlatBusLine("Larga", "999", longString)
        assertEquals(64, line4.nombreCorto.length)
        assertEquals("A".repeat(64), line4.nombreCorto)
    }

    @Test
    fun testMultiBusSelectionSerialization() {
        val lines = listOf(
            com.nesktf.guemaps.data.model.FlatBusLine("Corredor 1", "101", "1A"),
            com.nesktf.guemaps.data.model.FlatBusLine("Corredor 2", "102", "2B")
        )
        val json = gson.toJson(lines)
        val type = object : com.google.gson.reflect.TypeToken<List<com.nesktf.guemaps.data.model.FlatBusLine>>() {}.type
        val restored: List<com.nesktf.guemaps.data.model.FlatBusLine> = gson.fromJson(json, type)
        assertEquals(2, restored.size)
        assertEquals("101", restored[0].codLinea)
        assertEquals("102", restored[1].codLinea)
    }

    @Test
    fun testComputeLineCodesHashOrderIndependent() {
        val lineA = com.nesktf.guemaps.data.model.FlatBusLine("Corredor 1", "101", "1A")
        val lineB = com.nesktf.guemaps.data.model.FlatBusLine("Corredor 2", "105", "2B")
        val lineC = com.nesktf.guemaps.data.model.FlatBusLine("Corredor 3", "110", "3C")

        val hash1 = com.nesktf.guemaps.data.model.computeLineCodesHash(listOf(lineA, lineB, lineC))
        val hash2 = com.nesktf.guemaps.data.model.computeLineCodesHash(listOf(lineC, lineA, lineB))
        val hash3 = com.nesktf.guemaps.data.model.computeLineCodesHash(listOf(lineB, lineC, lineA))

        assertEquals("101|105|110", hash1)
        assertEquals(hash1, hash2)
        assertEquals(hash1, hash3)

        // Different lines should have different hash
        val lineD = com.nesktf.guemaps.data.model.FlatBusLine("Corredor 4", "115", "4D")
        val hash4 = com.nesktf.guemaps.data.model.computeLineCodesHash(listOf(lineA, lineB, lineD))
        org.junit.Assert.assertNotEquals(hash1, hash4)
    }

    @Test
    fun testBusPresetSerialization() {
        val lines = listOf(
            com.nesktf.guemaps.data.model.FlatBusLine("Corredor 2", "105", "2B"),
            com.nesktf.guemaps.data.model.FlatBusLine("Corredor 1", "101", "1A")
        )
        val hash = com.nesktf.guemaps.data.model.computeLineCodesHash(lines)
        val preset = com.nesktf.guemaps.data.model.BusPreset(
            id = "preset-1",
            name = "Trabajo",
            lineCodesHash = hash,
            lines = lines,
            createdAt = 123456789L
        )

        val json = gson.toJson(preset)
        val restored = gson.fromJson(json, com.nesktf.guemaps.data.model.BusPreset::class.java)

        assertEquals("preset-1", restored.id)
        assertEquals("Trabajo", restored.name)
        assertEquals("101|105", restored.lineCodesHash)
        assertEquals(2, restored.lines.size)
        // Original order is preserved in lines
        assertEquals("105", restored.lines[0].codLinea)
        assertEquals("101", restored.lines[1].codLinea)
    }

    @Test
    fun testComputeRouteHash() {
        val stop1 = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.789,
            longitud = -65.412,
            parada = true,
            codigoParada = "p001",
            descripcionParada = "Av. San Martín 100"
        )
        val stop2 = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.792,
            longitud = -65.415,
            parada = true,
            codigoParada = "p002",
            descripcionParada = "Pellegrini 250"
        )
        val waypoint = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.790,
            longitud = -65.413,
            parada = false
        )

        val route1 = BusRouteResponse(nodos = listOf(waypoint, stop1, stop2))
        val hash1 = com.nesktf.guemaps.data.model.computeRouteHash(route1)
        assertTrue(hash1.isNotEmpty())

        // Route with same stops but additional waypoint has the SAME stops hash
        val route2 = BusRouteResponse(nodos = listOf(
            waypoint,
            stop1,
            com.nesktf.guemaps.data.model.BusNode(latitud = -24.791, longitud = -65.414, parada = false),
            stop2
        ))
        val hash2 = com.nesktf.guemaps.data.model.computeRouteHash(route2)
        assertEquals(hash1, hash2)

        // Route with modified stop description has a DIFFERENT hash
        val modifiedStop = stop2.copy(descripcionParada = "Pellegrini 300")
        val route3 = BusRouteResponse(nodos = listOf(stop1, modifiedStop))
        val hash3 = com.nesktf.guemaps.data.model.computeRouteHash(route3)
        org.junit.Assert.assertNotEquals(hash1, hash3)

        // Empty route returns empty string
        assertEquals("", com.nesktf.guemaps.data.model.computeRouteHash(BusRouteResponse(nodos = emptyList())))
        assertEquals("", com.nesktf.guemaps.data.model.computeRouteHash(BusRouteResponse(nodos = null)))
        assertEquals("", com.nesktf.guemaps.data.model.computeRouteHash(BusRouteResponse(nodos = listOf(waypoint))))
    }

    @Test
    fun testSanitizeStopDescription() {
        // Test Barrio abbreviation
        assertEquals("B° Los Virreyes S-N", com.nesktf.guemaps.data.model.sanitizeStopDescription("B\uFFFD Los Virreyes S-N"))

        // Test Güemes
        assertEquals("Dr Juan M Güemes y Av. del Tra", com.nesktf.guemaps.data.model.sanitizeStopDescription("Dr Juan M G\uFFFDemes y Av. del Tra"))
        assertEquals("Av. Gral. Güemes - ANSES", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Gral. G\uFFFD\uFFFDemes - ANSES"))

        // Test España
        assertEquals("Av. Sarmiento y España", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Sarmiento y Espa\uFFFD"))

        // Test Ibáñez and Muñoz
        assertEquals("Av. Enio Pontusi e Ibáñez", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Enio Pontusi e Iba\uFFFDez"))
        assertEquals("Juan Muñoz Cabrera y Quevedo", com.nesktf.guemaps.data.model.sanitizeStopDescription("Juan Mu\uFFFDoz Cabrera y Quevedo"))

        // Test Bélgica and Tucumán
        assertEquals("Av. Paraguay - Pasando Av. Bélgica", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Paraguay - Pasando Av. B\uFFFDl"))
        assertEquals("Av. Jujuy 780 - Esquina Tucumán", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Jujuy 780 - Esquina Tucum\uFFFD"))

        // Test Calle Ñ
        assertEquals("Av. Democracia y Calle Ñ", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. Democracia y Calle \uFFFD"))

        // Clean string remains unchanged
        assertEquals("Av. San Martín 120", com.nesktf.guemaps.data.model.sanitizeStopDescription("Av. San Martín 120"))

        // BusNode helper property
        val node = com.nesktf.guemaps.data.model.BusNode(
            parada = true,
            descripcionParada = "Chile y Roque Saenz Pe\uFFFD"
        )
        assertEquals("Chile y Roque Saenz Peña", node.cleanDescripcionParada)
    }
}

