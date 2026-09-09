package com.nesktf.guemaps

import com.google.gson.Gson
import com.nesktf.guemaps.data.model.CardBalance
import com.nesktf.guemaps.data.model.CardBalanceResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CardModelsTest {

    private val gson = Gson()

    @Test
    fun testCardBalanceSuccessDeserialization() {
        val json = """
            {
              "error": 0,
              "estado": "ACTIVA",
              "fechaSaldo": 1700000000000,
              "nroExternoTarjeta": "12345678",
              "tipoTarjeta": "COMUN",
              "saldos": [
                {
                  "monedero": {
                    "id": 0,
                    "nombre": "Principal (Dinero)",
                    "prefijoSaldo": "$",
                    "sufijoSaldo": "",
                    "unidadPasajes": false,
                    "saldo": 1450.50
                  }
                }
              ]
            }
        """.trimIndent()

        val response = gson.fromJson(json, CardBalanceResponse::class.java)
        assertEquals(0, response.error)
        assertEquals("ACTIVA", response.estado)
        assertEquals("12345678", response.nroExternoTarjeta)
        assertEquals("COMUN", response.tipoTarjeta)
        assertNull(response.getErrorMessage())

        assertNotNull(response.saldos)
        assertEquals(1, response.saldos!!.size)
        val monedero = response.saldos!![0].monedero
        assertNotNull(monedero)
        assertEquals("Principal (Dinero)", monedero!!.nombre)
        assertEquals(1450.50, monedero.saldo!!, 0.01)
        assertEquals("$ 1450.50", monedero.formatDisplay())
    }

    @Test
    fun testCardBalanceErrorCodes() {
        val captchaErrorJson = """{"error": 1}"""
        val captchaResp = gson.fromJson(captchaErrorJson, CardBalanceResponse::class.java)
        assertEquals(1, captchaResp.error)
        assertEquals("Código captcha incorrecto o vencido", captchaResp.getErrorMessage())

        val cardErrorJson = """{"error": 2}"""
        val cardResp = gson.fromJson(cardErrorJson, CardBalanceResponse::class.java)
        assertEquals(2, cardResp.error)
        assertEquals("Número de tarjeta no válido", cardResp.getErrorMessage())

        val deletedCardJson = """{"error": 100}"""
        val deletedResp = gson.fromJson(deletedCardJson, CardBalanceResponse::class.java)
        assertEquals(100, deletedResp.error)
        assertEquals("Tarjeta inexistente o dada de baja", deletedResp.getErrorMessage())
    }

    @Test
    fun testBalanceFormattingWithPassages() {
        val monedero = CardBalance(
            id = 0,
            nombre = "Abono Escolar",
            prefijoSaldo = "",
            sufijoSaldo = "pasajes",
            unidadPasajes = true,
            saldo = 40.0
        )
        assertEquals("40 pasajes", monedero.formatDisplay())
    }
}
