package com.offerpoint.pos.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.offerpoint.pos.domain.model.WorkspacePreferences
import com.offerpoint.pos.domain.model.WorkspacePreferencesSnapshot
import com.offerpoint.pos.domain.repository.WorkspacePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Separate from Room business records and the device PIN. Live and Demo use different app sandboxes. */
class LocalWorkspacePreferencesRepository(context: Context, name: String = "workspace_customization") : WorkspacePreferencesRepository {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val writes = Mutex()

    private fun snapshot(): WorkspacePreferencesSnapshot = try {
        val text = preferences.getString("layout_v1", null)
        WorkspacePreferencesSnapshot(text?.let(WorkspacePreferencesJson::decode) ?: WorkspacePreferences())
    } catch (_: Exception) {
        WorkspacePreferencesSnapshot(error = "Your saved layout could not be read. Open Customize app to reset it or load a layout file.")
    }

    override fun observe(): Flow<WorkspacePreferencesSnapshot> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "layout_v1") trySend(snapshot()) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(snapshot())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged().flowOn(Dispatchers.IO)

    override suspend fun get(): WorkspacePreferencesSnapshot = withContext(Dispatchers.IO) { snapshot() }

    override suspend fun save(preferences: WorkspacePreferences) = withContext(Dispatchers.IO) {
        val text = WorkspacePreferencesJson.encode(preferences)
        writes.withLock {
            check(this@LocalWorkspacePreferencesRepository.preferences.edit().putString("layout_v1", text).commit()) {
                "Your layout could not be saved. Check the phone's free storage and try again."
            }
        }
    }
}
