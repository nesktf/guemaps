package com.nesktf.guemaps.data.model

import com.google.gson.annotations.SerializedName

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
)

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
)

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
