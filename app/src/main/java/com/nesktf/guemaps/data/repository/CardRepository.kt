package com.nesktf.guemaps.data.repository

import android.graphics.Bitmap
import com.nesktf.guemaps.data.local.GuemapsDatabase
import com.nesktf.guemaps.data.model.CardBalanceResponse
import com.nesktf.guemaps.data.model.SavedCard
import com.nesktf.guemaps.data.remote.SaetaApiClient

class CardRepository(
    private val apiClient: SaetaApiClient,
    private val database: GuemapsDatabase
) {

    suspend fun getCaptcha(): Result<Bitmap> {
        return apiClient.fetchCaptcha()
    }

    suspend fun checkBalance(cardNumber: String, captcha: String): Result<CardBalanceResponse> {
        val result = apiClient.checkCardBalance(cardNumber, captcha)
        if (result.isSuccess) {
            val balance = result.getOrThrow()
            if (balance.error == 0) {
                // Store in recent cards upon successful query
                database.saveRecentCard(cardNumber)
            }
        }
        return result
    }

    fun getRecentCards(): List<SavedCard> {
        return database.getRecentCards()
    }
}
