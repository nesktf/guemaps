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
}
