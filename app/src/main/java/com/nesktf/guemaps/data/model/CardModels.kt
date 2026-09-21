package com.nesktf.guemaps.data.model

import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.lang.reflect.Type
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CardBalanceResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("estado") val estado: String? = null,
    @SerializedName("fechaSaldo") val fechaSaldo: Long? = null,
    @SerializedName("nroExternoTarjeta") val nroExternoTarjeta: String? = null,
    @SerializedName("tipoTarjeta") val tipoTarjeta: String? = null,
    @SerializedName("saldos") val saldos: List<MonederoWrapper>? = null
) {
    fun getFormattedDate(): String {
        return fechaSaldo?.let {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
            sdf.format(Date(it))
        } ?: "-"
    }

    fun getErrorMessage(): String? {
        return when (error) {
            0 -> null
            1 -> "Código captcha incorrecto o vencido"
            2 -> "Número de tarjeta no válido"
            3 -> "Número de tarjeta duplicado"
            98 -> "Sesión suspendida temporalmente"
            99 -> "Sesión expirada"
            100 -> "Tarjeta inexistente o dada de baja"
            else -> "Error del servicio ($error)"
        }
    }
}

data class MonederoWrapper(
    @SerializedName("monedero") val monedero: CardBalance? = null
)

class MonederoWrapperDeserializer : JsonDeserializer<MonederoWrapper> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): MonederoWrapper {
        if (!json.isJsonObject) return MonederoWrapper()
        val obj = json.asJsonObject

        val monedero = if (obj.has("monedero") && !obj.get("monedero").isJsonNull) {
            context.deserialize<CardBalance>(obj.get("monedero"), CardBalance::class.java)
        } else null

        var detectedSaldo: Double? = null
        var rawSaldoStr: String? = null
        val candidates = listOf("saldo", "saldoActual", "monto", "valor", "importe", "credito", "cantidad", "pasajes", "balance")
        for (key in candidates) {
            if (obj.has(key) && !obj.get(key).isJsonNull) {
                try {
                    detectedSaldo = obj.get(key).asDouble
                    rawSaldoStr = obj.get(key).asString
                    break
                } catch (e: Exception) {}
            }
        }

        val finalMonedero = if (monedero != null && monedero.saldo == null && (detectedSaldo != null || rawSaldoStr != null)) {
            monedero.copy(saldo = detectedSaldo, rawSaldoString = rawSaldoStr)
        } else monedero

        return MonederoWrapper(monedero = finalMonedero)
    }
}

object MoneyFormatter {
    fun format(amount: Double?): String {
        if (amount == null) return "-"
        val valueStr = String.format(Locale.US, "%.2f", amount)
        return "$ $valueStr"
    }

    fun format(raw: String?): String {
        if (raw.isNullOrBlank()) return "-"
        val clean = raw.replace("$", "").trim()
        val normalized = if (clean.contains(",") && clean.contains(".")) {
            clean.replace(".", "").replace(",", ".")
        } else if (clean.contains(",")) {
            clean.replace(",", ".")
        } else {
            clean
        }
        val d = normalized.toDoubleOrNull()
        return if (d != null) {
            format(d)
        } else {
            if (clean.isEmpty()) "-" else "$ $clean"
        }
    }
}

data class CardBalance(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("prefijoSaldo") val prefijoSaldo: String? = null,
    @SerializedName("sufijoSaldo") val sufijoSaldo: String? = null,
    @SerializedName("unidadPasajes") val unidadPasajes: Boolean? = null,
    @SerializedName("saldo") val saldo: Double? = null,
    val rawSaldoString: String? = null
) {
    fun formatDisplay(): String {
        return if (saldo != null) {
            MoneyFormatter.format(saldo)
        } else if (!rawSaldoString.isNullOrBlank()) {
            MoneyFormatter.format(rawSaldoString)
        } else {
            "-"
        }
    }
}

class CardBalanceDeserializer : JsonDeserializer<CardBalance> {
    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): CardBalance {
        if (!json.isJsonObject) return CardBalance()
        val obj = json.asJsonObject
        val id = if (obj.has("id") && !obj.get("id").isJsonNull) obj.get("id").asInt else null
        val nombre = if (obj.has("nombre") && !obj.get("nombre").isJsonNull) obj.get("nombre").asString else null
        val prefijoSaldo = if (obj.has("prefijoSaldo") && !obj.get("prefijoSaldo").isJsonNull) obj.get("prefijoSaldo").asString else null
        val sufijoSaldo = if (obj.has("sufijoSaldo") && !obj.get("sufijoSaldo").isJsonNull) obj.get("sufijoSaldo").asString else null
        val unidadPasajes = if (obj.has("unidadPasajes") && !obj.get("unidadPasajes").isJsonNull) obj.get("unidadPasajes").asBoolean else null

        var detectedSaldo: Double? = null
        var rawSaldoStr: String? = null
        val candidates = listOf("saldo", "saldoActual", "monto", "valor", "importe", "credito", "cantidad", "pasajes", "balance")
        for (key in candidates) {
            if (obj.has(key) && !obj.get(key).isJsonNull) {
                try {
                    detectedSaldo = obj.get(key).asDouble
                    rawSaldoStr = obj.get(key).asString
                    break
                } catch (e: Exception) {}
            }
        }

        if (detectedSaldo == null && rawSaldoStr == null) {
            for ((key, elem) in obj.entrySet()) {
                if (key !in listOf("id", "nombre", "prefijoSaldo", "sufijoSaldo", "unidadPasajes") && !elem.isJsonNull) {
                    try {
                        detectedSaldo = elem.asDouble
                        rawSaldoStr = elem.asString
                        break
                    } catch (e: Exception) {
                        rawSaldoStr = elem.asString
                    }
                }
            }
        }

        return CardBalance(
            id = id,
            nombre = nombre,
            prefijoSaldo = prefijoSaldo,
            sufijoSaldo = sufijoSaldo,
            unidadPasajes = unidadPasajes,
            saldo = detectedSaldo,
            rawSaldoString = rawSaldoStr
        )
    }
}

data class SavedCard(
    val cardNumber: String,
    val alias: String? = null,
    val lastChecked: Long = System.currentTimeMillis()
)
