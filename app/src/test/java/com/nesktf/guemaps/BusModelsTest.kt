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

    @Test
    fun testSanitizeSellingPointCoordinates() {
        // Normal valid coordinates in Salta
        val normal = com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(-24.7859, -65.4117)
        assertNotNull(normal)
        assertEquals(-24.7859, normal!!.first, 0.0001)
        assertEquals(-65.4117, normal.second, 0.0001)

        // Missing decimal / multiplied by 10^6
        val scaled = com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(-24789386.0, -65431254.0)
        assertNotNull(scaled)
        assertEquals(-24.789386, scaled!!.first, 0.00001)
        assertEquals(-65.431254, scaled.second, 0.00001)

        // Positive longitude (needs sign correction)
        val positiveLon = com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(-24.78, 65.41)
        assertNotNull(positiveLon)
        assertEquals(-24.78, positiveLon!!.first, 0.01)
        assertEquals(-65.41, positiveLon.second, 0.01)

        // Null coordinates
        assertEquals(null, com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(null, -65.41))
        assertEquals(null, com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(-24.78, null))
        assertEquals(null, com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(null, null))

        // Out of province coordinates (Buenos Aires: -34.60, -58.38)
        assertEquals(null, com.nesktf.guemaps.data.model.sanitizeSellingPointCoordinates(-34.6037, -58.3816))
    }

    @Test
    fun testSellingPointResponseDeserialization() {
        val json = """
            {
              "error": 0,
              "version": 12,
              "sinCambios": false,
              "puntosVenta": [
                {
                  "id": 101,
                  "tipo": 4,
                  "nombre": "Terminal ATM Paseo Salta",
                  "domicilio": "Av. Paraguay 1450",
                  "detalleDomicilio": "Hall Central",
                  "latitud": -24.7912,
                  "longitud": -65.4215
                },
                {
                  "id": 102,
                  "tipo": 1,
                  "nombre": "Kiosco San Martín",
                  "domicilio": "San Martín 500",
                  "detalleDomicilio": null,
                  "latitud": -24789386.0,
                  "longitud": -65431254.0
                }
              ]
            }
        """.trimIndent()

        val resp = gson.fromJson(json, com.nesktf.guemaps.data.model.SellingPointResponse::class.java)
        assertEquals(0, resp.error)
        assertNotNull(resp.puntosVenta)
        assertEquals(2, resp.puntosVenta!!.size)

        val atm = resp.puntosVenta!![0]
        assertEquals(101L, atm.id)
        assertTrue(atm.isAtm)
        assertEquals("Terminal de Autogestión (ATM)", atm.tipoLabel)

        val store = resp.puntosVenta!![1]
        assertEquals(102L, store.id)
        assertFalse(store.isAtm)
        assertEquals("Venta y Recarga de Tarjetas", store.tipoLabel)
    }

    @Test
    fun testConfigResponseTarifasExtraction() {
        val json = """
            {
              "error": 0,
              "nombre": "SAETA Salta",
              "menu": [
                {
                  "titulo": "Tarifas",
                  "icono": "pricetag",
                  "tipo": "tarifas",
                  "contenidoJSON": [
                    {
                      "icon": "pricetag",
                      "key": "Boleto Común",
                      "value": "$1.450,00"
                    },
                    {
                      "icon": "pricetag",
                      "key": "Abono Social",
                      "value": "$870,00"
                    }
                  ]
                },
                {
                  "titulo": "Otro Menú",
                  "tipo": "info",
                  "contenidoJSON": "string simple no array"
                }
              ]
            }
        """.trimIndent()

        val config = gson.fromJson(json, com.nesktf.guemaps.data.model.ConfigResponse::class.java)
        assertEquals(0, config.error)
        val tarifas = config.extractTarifas()
        assertEquals(2, tarifas.size)
        assertEquals("Boleto Común", tarifas[0].key)
        assertEquals("$1.450,00", tarifas[0].value)
        assertEquals("Abono Social", tarifas[1].key)
        assertEquals("$870,00", tarifas[1].value)
    }

    @Test
    fun testSellingPointsDistanceSorting() {
        val userLat = -24.7859
        val userLon = -65.4117

        val pointNear = com.nesktf.guemaps.data.model.SellingPoint(
            id = 1,
            nombre = "Cerca",
            latitud = -24.7860,
            longitud = -65.4120
        )
        val pointFar = com.nesktf.guemaps.data.model.SellingPoint(
            id = 2,
            nombre = "Lejos",
            latitud = -24.8500,
            longitud = -65.4500
        )
        val pointNoCoords = com.nesktf.guemaps.data.model.SellingPoint(
            id = 3,
            nombre = "Sin Coordenadas",
            latitud = null,
            longitud = null
        )

        val list = listOf(pointFar, pointNoCoords, pointNear)

        val sorted = list.sortedBy { point ->
            val lat = point.latitud
            val lon = point.longitud
            if (lat != null && lon != null) {
                val dLat = userLat - lat
                val dLon = userLon - lon
                dLat * dLat + dLon * dLon
            } else {
                Double.MAX_VALUE
            }
        }

        assertEquals(1L, sorted[0].id)
        assertEquals(2L, sorted[1].id)
        assertEquals(3L, sorted[2].id)
    }

    @Test
    fun testClusterBusStopsSingleAndGrouped() {
        val line1 = com.nesktf.guemaps.data.model.FlatBusLine("Urbano", "100", "1A - San Carlos")
        val line2 = com.nesktf.guemaps.data.model.FlatBusLine("Urbano", "200", "2B - San Jose")

        // Stop 1: only on Line 1
        val stopLine1Only = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.7800,
            longitud = -65.4100,
            parada = true,
            codigoParada = "P01",
            descripcionParada = "Parada Solo 1A"
        )
        // Stop 2: on Line 1 and Line 2 at virtually the same location (within 10m)
        val stopSharedLine1 = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.7850,
            longitud = -65.4150,
            parada = true,
            codigoParada = "P02",
            descripcionParada = "Parada Compartida"
        )
        val stopSharedLine2 = com.nesktf.guemaps.data.model.BusNode(
            latitud = -24.78505, // ~5 meters away
            longitud = -65.41505,
            parada = true,
            codigoParada = "P02",
            descripcionParada = "Parada Compartida"
        )

        val activeLines = listOf(
            com.nesktf.guemaps.data.model.ActiveLineData(
                line = line1,
                colorHex = "#35399D",
                routeNodes = listOf(stopLine1Only, stopSharedLine1)
            ),
            com.nesktf.guemaps.data.model.ActiveLineData(
                line = line2,
                colorHex = "#F17614",
                routeNodes = listOf(stopSharedLine2)
            )
        )

        val clustered = com.nesktf.guemaps.data.model.clusterBusStops(activeLines)
        assertEquals(2, clustered.size)

        val singleStop = clustered.find { it.code == "P01" }
        assertNotNull(singleStop)
        assertFalse(singleStop!!.isGrouped)
        assertEquals(1, singleStop.lineCodes.size)
        assertEquals("#35399D", singleStop.colorHex)

        val groupedStop = clustered.find { it.code == "P02" }
        assertNotNull(groupedStop)
        assertTrue(groupedStop!!.isGrouped)
        assertEquals(2, groupedStop.lineCodes.size)
        assertTrue(groupedStop.lineNames.contains("1A"))
        assertTrue(groupedStop.lineNames.contains("2B"))
        assertEquals("#64748B", groupedStop.colorHex)
    }

    @Test
    fun testSpeedBufferSlidingWindow() {
        val buffer = ArrayDeque<Double>(16)
        // Add 18 samples from 10.0 to 27.0
        val samples = (10..27).map { it.toDouble() }

        for (s in samples) {
            buffer.addLast(s)
            if (buffer.size > 16) {
                buffer.removeFirst()
            }
        }

        // Buffer size should be capped at 16
        assertEquals(16, buffer.size)
        // 10.0 and 11.0 should have been removed; buffer now has 12.0 through 27.0
        assertEquals(12.0, buffer.first(), 0.001)
        assertEquals(27.0, buffer.last(), 0.001)

        val expectedAvg = (12..27).sum().toDouble() / 16.0
        assertEquals(expectedAvg, buffer.average(), 0.001)
    }

    @Test
    fun testBusSpeedTrackerMovingComputesSpeedAndResetsTimer() {
        val tracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = 16)
        val pos0 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7850, longitud = -65.4150)
        val t0 = 1_000_000L

        // Initial position
        val eval0 = tracker.processBusPosition(pos0, now = t0)
        assertFalse(eval0.sampleAdded)
        assertEquals(0, tracker.getSamples("101").size)

        // 10 seconds later: bus moved ~100m (lat moved by 0.0009 deg ~100m >= 15m)
        val t1 = t0 + 10_000L
        val pos1 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7859, longitud = -65.4150)
        val eval1 = tracker.processBusPosition(pos1, now = t1)

        assertTrue(eval1.sampleAdded)
        assertNotNull(eval1.instantSpeedKmh)
        assertTrue(eval1.instantSpeedKmh!! > 30.0) // ~100m in 10s is ~36 km/h
        assertEquals(1, tracker.getSamples("101").size)
    }

    @Test
    fun testBusSpeedTrackerStationaryDiscardsSampleUnder60s() {
        val tracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = 16)
        val pos0 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7850, longitud = -65.4150)
        val t0 = 1_000_000L

        tracker.processBusPosition(pos0, now = t0)

        // At 10s: exactly same position (0 meters moved < 15m, 10s elapsed < 60s)
        val eval10 = tracker.processBusPosition(pos0, now = t0 + 10_000L)
        assertFalse(eval10.sampleAdded)
        assertEquals(0, tracker.getSamples("101").size)

        // At 30s: moved 5 meters due to GPS inaccuracy (< 15m, 30s elapsed < 60s)
        val posJitter = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.78504, longitud = -65.4150)
        val eval30 = tracker.processBusPosition(posJitter, now = t0 + 30_000L)
        assertFalse(eval30.sampleAdded)
        assertEquals(0, tracker.getSamples("101").size)

        // At 59s: still under 60 seconds -> discard
        val eval59 = tracker.processBusPosition(pos0, now = t0 + 59_000L)
        assertFalse(eval59.sampleAdded)
        assertEquals(0, tracker.getSamples("101").size)
    }

    @Test
    fun testBusSpeedTrackerStationarySamplesZeroAfter60s() {
        val tracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = 16)
        val pos0 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7850, longitud = -65.4150)
        val t0 = 1_000_000L

        tracker.processBusPosition(pos0, now = t0)

        // At 60s: bus hasn't moved and >= 60 seconds have elapsed
        val eval60 = tracker.processBusPosition(pos0, now = t0 + 60_000L)
        assertTrue(eval60.sampleAdded)
        assertEquals(0.0, eval60.instantSpeedKmh!!, 0.001)
        assertEquals(0.0, eval60.averageSpeedKmh!!, 0.001)
        assertEquals(listOf(0.0), tracker.getSamples("101"))

        // At 70s: timer was reset at 60s, so elapsed is 10s < 60s -> discard
        val eval70 = tracker.processBusPosition(pos0, now = t0 + 70_000L)
        assertFalse(eval70.sampleAdded)
        assertEquals(listOf(0.0), tracker.getSamples("101"))

        // At 120s: 60s have passed since timer reset at 60s -> sample 0.0 again
        val eval120 = tracker.processBusPosition(pos0, now = t0 + 120_000L)
        assertTrue(eval120.sampleAdded)
        assertEquals(listOf(0.0, 0.0), tracker.getSamples("101"))
    }

    @Test
    fun testBusSpeedTrackerResumesMovingAfterStationary() {
        val tracker = com.nesktf.guemaps.data.model.BusSpeedTracker(bufferSize = 16)
        val pos0 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7850, longitud = -65.4150)
        val t0 = 1_000_000L

        tracker.processBusPosition(pos0, now = t0)
        // Confirmed stopped at 60s
        tracker.processBusPosition(pos0, now = t0 + 60_000L)
        assertEquals(listOf(0.0), tracker.getSamples("101"))

        // At 70s (10s later), bus drives ~100m (latitud changed by 0.0009)
        val pos1 = com.nesktf.guemaps.data.model.BusPos(interno = "101", latitud = -24.7859, longitud = -65.4150)
        val evalMoving = tracker.processBusPosition(pos1, now = t0 + 70_000L)

        assertTrue(evalMoving.sampleAdded)
        assertTrue(evalMoving.instantSpeedKmh!! > 30.0)
        assertEquals(2, tracker.getSamples("101").size)
        assertEquals(0.0, tracker.getSamples("101")[0], 0.001)
        assertTrue(tracker.getSamples("101")[1] > 30.0)
    }

    @Test
    fun testBusLiveDetailsTimeFormatting() {
        val bus = com.nesktf.guemaps.data.model.BusPos(interno = "100")

        // Arriving (<= 15 seconds)
        val arriving = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            estimatedSecondsRemaining = 10L
        )
        assertEquals("unos segundos", arriving.formatEstimatedTimeRemaining())

        // 30 segundos (< 45 seconds)
        val thirtySecs = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            estimatedSecondsRemaining = 30L
        )
        assertEquals("30 segundos", thirtySecs.formatEstimatedTimeRemaining())

        // 1 minuto (< 90 seconds)
        val oneMin = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            estimatedSecondsRemaining = 60L
        )
        assertEquals("1 minuto", oneMin.formatEstimatedTimeRemaining())

        // 30 minutos
        val thirtyMins = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            estimatedSecondsRemaining = 1800L
        )
        assertEquals("30 minutos", thirtyMins.formatEstimatedTimeRemaining())

        // Hours format (> 3600 seconds)
        val hourAndHalf = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            estimatedSecondsRemaining = 5400L
        )
        assertEquals("1 hora y 30 minutos", hourAndHalf.formatEstimatedTimeRemaining())
    }

    @Test
    fun testBusLiveDetailsArrivalSummary() {
        val bus = com.nesktf.guemaps.data.model.BusPos(interno = "100")

        // 1. With estimated arrival epoch
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 16)
        cal.set(java.util.Calendar.MINUTE, 34)
        val epoch = cal.timeInMillis

        val detailsWithTime = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            speedKmh = 25.0,
            averageSpeedKmh = 25.0,
            distanceToUserStopMeters = 1500.0,
            estimatedSecondsRemaining = 1800L, // 30 mins
            estimatedArrivalEpochMs = epoch
        )
        val summary = detailsWithTime.formatEstimatedArrivalSummary()
        assertEquals("Llega en 30 minutos (16:34 hs)", summary)

        // 2. Stopped bus (< 1.0 km/h)
        val stopped = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            speedKmh = 0.0,
            averageSpeedKmh = 0.0,
            distanceToUserStopMeters = 800.0
        )
        assertEquals("Colectivo detenido", stopped.formatEstimatedArrivalSummary())

        // 3. Arriving in seconds
        val atStop = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            speedKmh = 5.0,
            averageSpeedKmh = 5.0,
            distanceToUserStopMeters = 15.0,
            estimatedSecondsRemaining = 0L,
            estimatedArrivalEpochMs = epoch
        )
        assertEquals("Llega en unos segundos (16:34 hs)", atStop.formatEstimatedArrivalSummary())

        // 4. Calculating when no distance or speed available
        val calculating = com.nesktf.guemaps.data.model.BusLiveDetails(
            bus = bus,
            distanceToUserStopMeters = null
        )
        assertEquals("Calculando tiempo...", calculating.formatEstimatedArrivalSummary())
    }

    @Test
    fun testMaxSelectedLinesAndPaletteColors() {
        assertEquals(5, com.nesktf.guemaps.ui.map.MapViewModel.MAX_SELECTED_LINES)
        val palette = com.nesktf.guemaps.ui.map.BUS_LINE_PALETTE.take(com.nesktf.guemaps.ui.map.MapViewModel.MAX_SELECTED_LINES)
        assertEquals(5, palette.size)
        assertEquals("#35399D", palette[0])
        assertEquals("#724829", palette[1])
        assertEquals("#70B91A", palette[2])
        assertEquals("#BE45B4", palette[3])
        assertEquals("#F17614", palette[4])
    }
}


