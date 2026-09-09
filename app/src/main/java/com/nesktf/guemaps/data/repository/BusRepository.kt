package com.nesktf.guemaps.data.repository

import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.BusGroupNode
import com.nesktf.guemaps.data.model.BusNode
import com.nesktf.guemaps.data.model.BusPos
import com.nesktf.guemaps.data.model.FlatBusLine
import com.nesktf.guemaps.data.remote.SaetaApiClient

data class BusGroupsResult(
    val rootGroup: BusGroupNode,
    val flatLines: List<FlatBusLine>,
    val isFromCache: Boolean
)

data class BusRouteResult(
    val nodes: List<BusNode>,
    val isFromCache: Boolean
)

class BusRepository(
    private val apiClient: SaetaApiClient,
    private val database: GuemapsDatabase
) {

    suspend fun getBusGroups(forceNetwork: Boolean = false): Result<BusGroupsResult> {
        val networkResult = apiClient.fetchBusGroups()
        if (networkResult.isSuccess) {
            val response = networkResult.getOrThrow()
            if (response.grupos != null) {
                database.saveBusGroups(response)
                val flat = response.grupos.flattenLines()
                return Result.success(BusGroupsResult(response.grupos, flat, isFromCache = false))
            }
        }

        // Network failed or returned invalid response, fallback to local SQLite cache
        val cached = database.getCachedBusGroups()
        if (cached?.grupos != null) {
            val flat = cached.grupos.flattenLines()
            if (flat.isNotEmpty()) {
                return Result.success(BusGroupsResult(cached.grupos, flat, isFromCache = true))
            }
        }

        return Result.failure(networkResult.exceptionOrNull() ?: Exception("Error al cargar líneas"))
    }

    suspend fun getBusRoute(lineId: String, forceNetwork: Boolean = false): Result<BusRouteResult> {
        val networkResult = apiClient.fetchBusRoute(lineId)
        if (networkResult.isSuccess) {
            val response = networkResult.getOrThrow()
            val nodes = response.nodos ?: emptyList()
            if (nodes.isNotEmpty()) {
                database.saveBusRoute(lineId, response)
            }
            return Result.success(BusRouteResult(nodes, isFromCache = false))
        }

        // Fallback to cache if network failed
        val cached = database.getCachedBusRoute(lineId)
        if (cached?.nodos != null && cached.nodos.isNotEmpty()) {
            return Result.success(BusRouteResult(cached.nodos, isFromCache = true))
        }

        return Result.failure(networkResult.exceptionOrNull() ?: Exception("Error al cargar recorrido"))
    }

    suspend fun getActiveBuses(lineId: String): Result<List<BusPos>> {
        val networkResult = apiClient.fetchActiveBuses(lineId)
        return networkResult.map { it.posiciones ?: emptyList() }
    }

    fun getFavoriteLines(): List<FlatBusLine> {
        return database.getFavoriteLines()
    }

    fun toggleFavoriteLine(line: FlatBusLine): Boolean {
        return if (database.isFavoriteLine(line.codLinea)) {
            database.removeFavoriteLine(line.codLinea)
            false
        } else {
            database.addFavoriteLine(line)
            true
        }
    }

    fun isFavoriteLine(lineCode: String): Boolean {
        return database.isFavoriteLine(lineCode)
    }
}
