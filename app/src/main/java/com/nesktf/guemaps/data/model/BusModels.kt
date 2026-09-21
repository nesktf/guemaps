package com.nesktf.guemaps.data.model

import com.google.gson.JsonArray
import com.google.gson.annotations.SerializedName
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class BusGroupsResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("grupos") val grupos: BusGroupNode? = null
)

data class BusGroupNode(
    @SerializedName("codGrupo") val codGrupo: String = "",
    @SerializedName("subGrupos") val subGrupos: List<BusGroupNode>? = null,
    @SerializedName("lineas") val lineas: List<BusEntry>? = null
) {
    /**
     * Recursively flattens the group tree into a list of lines with their breadcrumb path.
     */
    fun flattenLines(parentPath: String = ""): List<FlatBusLine> {
        val currentPath = if (parentPath.isEmpty()) {
            if (codGrupo != "Grupos de Lineas") codGrupo else ""
        } else {
            "$parentPath > $codGrupo"
        }

        val result = mutableListOf<FlatBusLine>()
        lineas?.forEach { entry ->
            result.add(
                FlatBusLine(
                    groupPath = currentPath,
                    codLinea = entry.codLinea,
                    descripcion = entry.descripcion
                )
            )
        }
        subGrupos?.forEach { childGroup ->
            result.addAll(childGroup.flattenLines(currentPath))
        }
        return result
    }

    /**
     * Extracts top-level categories and their respective subgroups and lines.
     */
    fun extractCategories(): List<BusCategory> {
        val rootSubs = subGrupos ?: return emptyList()
        return rootSubs.map { catNode ->
            val catName = catNode.codGrupo
            val directLines = catNode.lineas ?: emptyList()
            val collectedSubgroups = mutableListOf<BusSubgroup>()

            fun collectSubgroups(node: BusGroupNode, parentName: String) {
                val currentName = if (parentName.isNotEmpty()) "$parentName - ${node.codGrupo}" else node.codGrupo
                if (!node.lineas.isNullOrEmpty()) {
                    collectedSubgroups.add(BusSubgroup(catName, currentName, node.lineas))
                }
                node.subGrupos?.forEach { child ->
                    collectSubgroups(child, currentName)
                }
            }

            catNode.subGrupos?.forEach { sub ->
                collectSubgroups(sub, "")
            }

            BusCategory(
                name = catName,
                directLines = directLines,
                subgroups = collectedSubgroups
            )
        }
    }
}

data class BusSubgroup(
    val categoryName: String,
    val subgroupName: String,
    val lines: List<BusEntry>
)

data class BusCategory(
    val name: String,
    val directLines: List<BusEntry> = emptyList(),
    val subgroups: List<BusSubgroup> = emptyList()
)

data class BusEntry(
    @SerializedName("codLinea") val codLinea: String = "",
    @SerializedName("descripcion") val descripcion: String = ""
)

data class FlatBusLine(
    val groupPath: String,
    val codLinea: String,
    val descripcion: String
) {
    val nombreCorto: String
        get() {
            val parts = descripcion.split("-")
            return if (parts.size > 1 && parts[0].trim().length <= 6) {
                parts[0].trim()
            } else {
                descripcion.take(64)
            }
        }

    val nombreLinea: String
        get() = descripcion
}

data class BusPreset(
    val id: String,
    val name: String,
    val lineCodesHash: String,
    val lines: List<FlatBusLine>,
    val createdAt: Long = System.currentTimeMillis()
)

fun computeLineCodesHash(lines: List<FlatBusLine>): String {
    return lines.map { it.codLinea.trim() }.sorted().joinToString("|")
}

data class BusStopRecord(
    val lineId: String,
    val stopCode: String,
    val stopName: String,
    val latitude: Double,
    val longitude: Double
)

data class ActiveLineData(
    val line: FlatBusLine,
    val colorHex: String,
    val isLoadingRoute: Boolean = true,
    val routeNodes: List<BusNode> = emptyList(),
    val isRouteOffline: Boolean = false,
    val activeBuses: List<BusPos> = emptyList()
)

data class MapBusStop(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val name: String,
    val code: String? = null,
    val lineCodes: List<String> = emptyList(),
    val lineNames: List<String> = emptyList(),
    val colorHex: String = "#35399D"
) {
    val isGrouped: Boolean get() = lineCodes.size > 1
}

fun clusterBusStops(
    activeLines: List<ActiveLineData>,
    fallbackNodes: List<BusNode> = emptyList()
): List<MapBusStop> {
    if (activeLines.isEmpty() && fallbackNodes.isEmpty()) return emptyList()

    data class Candidate(
        val node: BusNode,
        val lineCode: String,
        val lineName: String,
        val colorHex: String
    )
    val candidates = mutableListOf<Candidate>()

    if (activeLines.isNotEmpty()) {
        for (lineData in activeLines) {
            for (node in lineData.routeNodes) {
                if (node.parada) {
                    candidates.add(
                        Candidate(
                            node = node,
                            lineCode = lineData.line.codLinea,
                            lineName = lineData.line.nombreCorto,
                            colorHex = lineData.colorHex
                        )
                    )
                }
            }
        }
    } else {
        for (node in fallbackNodes) {
            if (node.parada) {
                candidates.add(
                    Candidate(
                        node = node,
                        lineCode = "",
                        lineName = "",
                        colorHex = "#35399D"
                    )
                )
            }
        }
    }

    val result = mutableListOf<MapBusStop>()
    val maxDistMetersSq = 15.0 * 15.0

    for (c in candidates) {
        val lat = c.node.latitud
        val lon = c.node.longitud
        val code = c.node.codigoParada?.trim()?.takeIf { it.isNotEmpty() }
        val name = sanitizeStopDescription(c.node.descripcionParada ?: "")

        var matchedIndex = -1
        for (i in result.indices) {
            val existing = result[i]
            val codeMatch = code != null && existing.code != null && code.equals(existing.code, ignoreCase = true)
            if (codeMatch) {
                matchedIndex = i
                break
            }
            val dLatM = (lat - existing.latitude) * 111000.0
            val dLonM = (lon - existing.longitude) * 100700.0
            if (dLatM * dLatM + dLonM * dLonM <= maxDistMetersSq) {
                matchedIndex = i
                break
            }
        }

        if (matchedIndex >= 0) {
            val existing = result[matchedIndex]
            val newLineCodes = if (c.lineCode.isNotEmpty() && !existing.lineCodes.contains(c.lineCode)) {
                existing.lineCodes + c.lineCode
            } else existing.lineCodes

            val newLineNames = if (c.lineName.isNotEmpty() && !existing.lineNames.contains(c.lineName)) {
                existing.lineNames + c.lineName
            } else existing.lineNames

            val newColorHex = if (newLineCodes.size > 1) "#64748B" else existing.colorHex
            val betterName = if (existing.name.isBlank() && name.isNotBlank()) name else existing.name
            val betterCode = existing.code ?: code

            result[matchedIndex] = existing.copy(
                lineCodes = newLineCodes,
                lineNames = newLineNames,
                colorHex = newColorHex,
                name = betterName,
                code = betterCode
            )
        } else {
            val lineCodes = if (c.lineCode.isNotEmpty()) listOf(c.lineCode) else emptyList()
            val lineNames = if (c.lineName.isNotEmpty()) listOf(c.lineName) else emptyList()
            val stopId = code ?: "stop_${lat}_${lon}"
            result.add(
                MapBusStop(
                    id = stopId,
                    latitude = lat,
                    longitude = lon,
                    name = if (name.isNotBlank()) name else "Parada de colectivo",
                    code = code,
                    lineCodes = lineCodes,
                    lineNames = lineNames,
                    colorHex = c.colorHex
                )
            )
        }
    }

    return result
}

fun sanitizeStopDescription(raw: String): String {
    if (!raw.contains('\uFFFD')) return raw.trim()

    var text = raw
    // Known Salta locations and street names corrupted by SAETA API's legacy charset conversion
    val replacements = listOf(
        "Alarc\uFFFDn" to "Alarcón",
        "Asunci\uFFFDn" to "Asunción",
        "A\uFFFDrea" to "Aérea",
        "Bah\uFFFD" to "Bahía",
        "B\uFFFDl" to "Bélgica",
        "Caba\uFFFDas" to "Cabañas",
        "Casta\uFFFDos" to "Castaños",
        "Cha\uFFFDares" to "Chañares",
        "De\uFFFDn" to "Deán",
        "D\uFFFDvalos" to "Dávalos",
        "D\uFFFDva" to "Dávalos",
        "Espa\uFFFD" to "España",
        "Estaci\uFFFDn" to "Estación",
        "G\uFFFDemes" to "Güemes",
        "G\uFFFD\uFFFDemes" to "Güemes",
        "G\uFFFD\uFFFD" to "Güemes",
        "G\uFFFD" to "Güemes",
        "Hern\uFFFDn" to "Hernán",
        "Hip\uFFFDlito" to "Hipólito",
        "Hoster\uFFFD" to "Hostería",
        "Iba\uFFFDez" to "Ibáñez",
        "Iba\uFFFD" to "Ibáñez",
        "Joaqu\uFFFDn" to "Joaquín",
        "Lop\uFFFDz" to "López",
        "L\uFFFDpez" to "López",
        "Mar\uFFFD" to "María",
        "Mill\uFFFDn" to "Millán",
        "Mu\uFFFDoz" to "Muñoz",
        "Nicol\uFFFDs" to "Nicolás",
        "N\uFFFDutico" to "Náutico",
        "Panader\uFFFD" to "Panadería",
        "Paran\uFFFD" to "Paraná",
        "Patr\uFFFDn" to "Patrón",
        "Peque\uFFFDo" to "Pequeño",
        "Pe\uFFFD" to "Peña",
        "Pr\uFFFDfugos" to "Prófugos",
        "Pr\uFFFDstamo" to "Préstamo",
        "Quebrade\uFFFDo" to "Quebradeño",
        "Sue\uFFFDos" to "Sueños",
        "S\uFFFDato" to "Sábato",
        "Tucum\uFFFD" to "Tucumán",
        "T\uFFFDpac" to "Túpac",
        "Vilari\uFFFDo" to "Vilariño",
        "Vi\uFFFDuelas" to "Viñuelas",
        "Vi\uFFFDal" to "Viñal",
        "Vi\uFFFD" to "Viña",
        "V\uFFFDctor" to "Víctor"
    )

    // Barrio abbreviation: "B\uFFFD " -> "B° "
    text = text.replace(Regex("""B\uFFFD(?=\s|$)"""), "B°")
    text = text.replace("Calle \uFFFD", "Calle Ñ")

    for ((target, replacement) in replacements) {
        text = text.replace(target, replacement, ignoreCase = true)
    }

    // Fallback: if between letters, ñ is by far the most frequent in Spanish
    text = text.replace(Regex("""(?<=\p{L})\uFFFD(?=\p{L})"""), "ñ")
    // Remove any remaining stray \uFFFD
    text = text.replace("\uFFFD", "")

    return text.replace(Regex("""\s+"""), " ").trim()
}

fun computeRouteHash(routeResponse: BusRouteResponse): String {
    val nodes = routeResponse.nodos ?: return ""
    val stops = nodes.filter { it.parada }
    if (stops.isEmpty()) return ""
    val sb = java.lang.StringBuilder()
    for (stop in stops) {
        sb.append(stop.codigoParada?.trim() ?: "").append(';')
        sb.append(stop.cleanDescripcionParada).append(';')
        sb.append(stop.latitud).append(';')
        sb.append(stop.longitud).append('|')
    }
    val md = MessageDigest.getInstance("MD5")
    val digest = md.digest(sb.toString().toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}

data class BusRouteResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("nodos") val nodos: List<BusNode>? = null
)

data class BusNode(
    @SerializedName("latitud") val latitud: Double = 0.0,
    @SerializedName("longitud") val longitud: Double = 0.0,
    @SerializedName("parada") val parada: Boolean = false,
    @SerializedName("codigoParada") val codigoParada: String? = null,
    @SerializedName("descripcionParada") val descripcionParada: String? = null
) {
    val cleanDescripcionParada: String
        get() = sanitizeStopDescription(descripcionParada ?: "")
}

data class BusPosResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("posiciones") val posiciones: List<BusPos>? = null
)

data class BusPos(
    @SerializedName("interno") val interno: String = "",
    @SerializedName("latitud") val latitud: Double = 0.0,
    @SerializedName("longitud") val longitud: Double = 0.0,
    @SerializedName("orientacion") val orientacion: Float = 0f,
    @SerializedName("proximaParada") val proximaParada: String? = null,
    @SerializedName("vehiculoRampa") val vehiculoRampa: Boolean = false,
    @SerializedName("vehiculoNoVisibles") val vehiculoNoVisibles: Boolean = false
)

data class BusLiveDetails(
    val bus: BusPos,
    val speedKmh: Double? = null,
    val averageSpeedKmh: Double? = null,
    val userNearestStopName: String? = null,
    val distanceToUserStopMeters: Double? = null,
    val nearestStopName: String? = null,
    val distanceToNearestStopMeters: Double? = null,
    val estimatedSecondsRemaining: Long? = null,
    val estimatedArrivalEpochMs: Long? = null
) {
    fun formatSpeed(): String {
        val spd = speedKmh ?: averageSpeedKmh
        return when {
            spd == null -> "Calculando..."
            spd < 3.0 -> "Detenido"
            else -> "%.0f km/h".format(java.util.Locale.US, spd)
        }
    }

    fun formatUserStopDistance(): String {
        return when {
            distanceToUserStopMeters == null -> ""
            distanceToUserStopMeters < 1000 -> "A %.0f m de tu parada".format(java.util.Locale.US, distanceToUserStopMeters)
            else -> "A %.1f km de tu parada".format(java.util.Locale.US, distanceToUserStopMeters / 1000.0)
        }
    }

    fun formatDistance(): String {
        val dist = distanceToUserStopMeters ?: distanceToNearestStopMeters
        return when {
            dist == null -> ""
            dist < 1000 -> "a %.0f m".format(java.util.Locale.US, dist)
            else -> "a %.1f km".format(java.util.Locale.US, dist / 1000.0)
        }
    }

    fun formatEstimatedTimeRemaining(): String? {
        val sec = estimatedSecondsRemaining ?: return null
        return when {
            sec <= 15 -> "unos segundos"
            sec < 45 -> "30 segundos"
            sec < 90 -> "1 minuto"
            sec < 3600 -> {
                val mins = Math.round(sec / 60.0).toInt()
                if (mins <= 1) "1 minuto" else "$mins minutos"
            }
            else -> {
                val hours = sec / 3600
                val remMins = Math.round((sec % 3600) / 60.0).toInt()
                val hourStr = if (hours == 1L) "1 hora" else "$hours horas"
                if (remMins > 0) {
                    val minStr = if (remMins == 1) "1 minuto" else "$remMins minutos"
                    "$hourStr y $minStr"
                } else {
                    hourStr
                }
            }
        }
    }

    fun formatEstimatedArrivalTime(): String? {
        val epoch = estimatedArrivalEpochMs ?: return null
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return "${sdf.format(Date(epoch))} hs"
    }

    fun formatEstimatedArrivalSummary(): String {
        val rel = formatEstimatedTimeRemaining()
        val abs = formatEstimatedArrivalTime()
        return when {
            rel != null && abs != null -> "Llega en $rel ($abs)"
            rel != null -> "Llega en $rel"
            (averageSpeedKmh ?: speedKmh) != null && (averageSpeedKmh ?: speedKmh)!! < 1.0 -> "Colectivo detenido"
            else -> "Calculando tiempo..."
        }
    }
}

data class SellingPoint(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("tipo") val tipo: Int? = null,
    @SerializedName("nombre") val nombre: String = "",
    @SerializedName("domicilio") val domicilio: String = "",
    @SerializedName("detalleDomicilio") val detalleDomicilio: String? = null,
    @SerializedName("latitud") val latitud: Double? = null,
    @SerializedName("longitud") val longitud: Double? = null
) {
    val isAtm: Boolean get() = tipo == 4
    val tipoLabel: String
        get() = when (tipo) {
            4 -> "Terminal de Autogestión (ATM)"
            1 -> "Venta y Recarga de Tarjetas"
            else -> "Punto de Venta y Recarga"
        }
}

fun sanitizeSellingPointCoordinates(lat: Double?, lon: Double?): Pair<Double, Double>? {
    if (lat == null || lon == null) return null
    var safeLat = -Math.abs(lat)
    var safeLon = -Math.abs(lon)

    while (Math.abs(safeLat) > 30.0) safeLat /= 10.0
    while (Math.abs(safeLat) < 20.0 && Math.abs(safeLat) > 0.001) safeLat *= 10.0

    while (Math.abs(safeLon) > 75.0) safeLon /= 10.0
    while (Math.abs(safeLon) < 55.0 && Math.abs(safeLon) > 0.001) safeLon *= 10.0

    if (safeLat in -26.5..-22.0 && safeLon in -68.5..-62.0) {
        return Pair(safeLat, safeLon)
    }
    return null
}

data class SellingPointResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("version") val version: Long = 0,
    @SerializedName("sinCambios") val sinCambios: Boolean = false,
    @SerializedName("puntosVenta") val puntosVenta: List<SellingPoint>? = null
)

data class TarifaItem(
    @SerializedName("icon") val icon: String? = null,
    @SerializedName("key") val key: String = "",
    @SerializedName("value") val value: String = ""
) {
    fun formatDisplay(): String = MoneyFormatter.format(value)
}

data class ConfigResponse(
    @SerializedName("error") val error: Int = 0,
    @SerializedName("nombre") val nombre: String? = null,
    @SerializedName("menu") val menu: JsonArray? = null
) {
    fun extractTarifas(): List<TarifaItem> {
        val list = mutableListOf<TarifaItem>()
        val arr = menu ?: return list
        for (elem in arr) {
            if (elem.isJsonObject) {
                val obj = elem.asJsonObject
                val tipo = obj.get("tipo")?.asString ?: ""
                val titulo = obj.get("titulo")?.asString ?: ""
                if (tipo.equals("tarifas", ignoreCase = true) || titulo.equals("tarifas", ignoreCase = true)) {
                    val contenido = obj.get("contenidoJSON")
                    if (contenido != null && contenido.isJsonArray) {
                        for (item in contenido.asJsonArray) {
                            if (item.isJsonObject) {
                                val itemObj = item.asJsonObject
                                val key = itemObj.get("key")?.asString ?: ""
                                val value = itemObj.get("value")?.asString ?: ""
                                val icon = itemObj.get("icon")?.asString
                                if (key.isNotBlank() && value.isNotBlank()) {
                                    list.add(TarifaItem(icon = icon, key = key, value = value))
                                }
                            }
                        }
                    }
                }
            }
        }
        return list
    }
}
