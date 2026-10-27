package com.offerpoint.pos.domain.repository

import com.offerpoint.pos.domain.model.WorkspacePreferences
import com.offerpoint.pos.domain.model.WorkspacePreferencesSnapshot
import kotlinx.coroutines.flow.Flow

interface WorkspacePreferencesRepository {
    fun observe(): Flow<WorkspacePreferencesSnapshot>
    suspend fun get(): WorkspacePreferencesSnapshot
    suspend fun save(preferences: WorkspacePreferences)
}
