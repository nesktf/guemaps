package com.nesktf.guemaps.data.model

import com.google.gson.annotations.SerializedName
import java.security.MessageDigest

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
    val userNearestStopName: String? = null,
    val distanceToUserStopMeters: Double? = null,
    val nearestStopName: String? = null,
    val distanceToNearestStopMeters: Double? = null
) {
    fun formatSpeed(): String {
        return when {
            speedKmh == null -> "Calculando..."
            speedKmh < 3.0 -> "Detenido"
            else -> "%.0f km/h".format(java.util.Locale.US, speedKmh)
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
}
