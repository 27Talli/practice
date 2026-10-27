package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.BackupPreview
import com.offerpoint.pos.domain.model.BackupResult
import kotlinx.coroutines.flow.Flow

/** Locations are user-selected document URIs; passwords never enter Room or saved UI state. */
interface BackupRepository {
    fun observeGeneration(): Flow<String>
    suspend fun export(destination: String, password: CharArray): BackupResult
    suspend fun inspect(source: String, password: CharArray): BackupPreview
    suspend fun restore(token: String, recoveryDestination: String, password: CharArray)
    fun discard(token: String)
}
