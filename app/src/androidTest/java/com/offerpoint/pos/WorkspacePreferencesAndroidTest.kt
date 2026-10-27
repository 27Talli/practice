package com.offerpoint.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.offerpoint.pos.data.backup.DatabaseSnapshot
import com.offerpoint.pos.data.local.database.PosDatabase
import com.offerpoint.pos.data.preferences.*
import com.offerpoint.pos.data.repository.*
import com.offerpoint.pos.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkspacePreferencesAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "workspace-test-${UUID.randomUUID()}"
    @After fun after() { context.deleteSharedPreferences(name); context.deleteSharedPreferences("$name-other") }
    private suspend fun rejected(block: suspend () -> Unit) { try { block() } catch (_: IllegalArgumentException) { return }; throw AssertionError("Expected invalid layout to be rejected") }
    private fun custom() = WorkspacePreferences("My shop counter", DashboardLayout.GRID, AppColor.PURPLE, AppAppearance.DARK,
        AppTextSize.LARGEST, CheckoutStart.CART).move(DashboardTile.CHECKOUT, -1).editTile(DashboardTile.EXPENSES, false, "Shop spending")

    @Test fun layoutFilesRoundTripEverySupportedChoiceAndOrderedNickname() = runBlocking {
        val value = custom()
        val text = WorkspacePreferencesJson.encode(value)
        assertEquals(value, WorkspacePreferencesJson.decode(text))
        assertEquals(value, WorkspacePreferencesJson.read(ByteArrayInputStream(text.toByteArray())))
        val root = JSONObject(text)
        assertEquals("OPPOS_LAYOUT", root.getString("format"))
        assertFalse(root.has("products") || root.has("sales") || root.has("pinHash") || root.has("purchaseCostPaise"))
    }

    @Test fun unsupportedVersionUnknownToolsDuplicateItemsAndBusinessFieldsAreRejected() = runBlocking {
        val text = WorkspacePreferencesJson.encode(custom())
        val invalid = listOf(JSONObject(text).put("version", 99), JSONObject(text).put("products", JSONArray()),
            JSONObject(text).apply { getJSONArray("tiles").getJSONObject(0).put("id", "RUN_SQL") },
            JSONObject(text).apply { getJSONArray("tiles").put(1, getJSONArray("tiles").getJSONObject(0)) },
            JSONObject(text).apply { getJSONArray("tiles").getJSONObject(0).put("visible", "true") })
        for (root in invalid) rejected { WorkspacePreferencesJson.decode(root.toString()) }
        rejected { WorkspacePreferencesJson.decode("not json") }
    }

    @Test fun layoutImportIsBoundedBeforeParsingAndNamesCannotContainControlCharacters() = runBlocking {
        rejected { WorkspacePreferencesJson.read(ByteArrayInputStream(ByteArray(WorkspacePreferencesJson.MAX_BYTES + 1) { 32 })) }
        rejected { WorkspacePreferencesJson.decode(JSONObject(WorkspacePreferencesJson.encode(custom())).put("heading", "bad\nname").toString()) }
        rejected { WorkspacePreferencesJson.decode(JSONObject(WorkspacePreferencesJson.encode(custom())).apply { getJSONArray("tiles").getJSONObject(0).put("label", "X".repeat(25)) }.toString()) }
    }

    @Test fun missingToolsAreAddedToAnOlderLayoutWithoutReplacingItsOrder() = runBlocking {
        val value = JSONObject(WorkspacePreferencesJson.encode(custom())).put("tiles", JSONArray().put(JSONObject().put("id", "CHECKOUT").put("visible", false).put("label", "Sell")))
        val decoded = WorkspacePreferencesJson.decode(value.toString())
        assertEquals(DashboardTilePreference(DashboardTile.CHECKOUT, false, "Sell"), decoded.tiles.first())
        assertEquals(DashboardTile.entries.size, decoded.tiles.size)
    }

    @Test fun preferencesPersistAcrossRepositoryRecreationAndEmitChanges() = runBlocking {
        val repo = LocalWorkspacePreferencesRepository(context, name)
        assertEquals(WorkspacePreferences(), repo.get().value)
        val changed = async { withTimeout(10_000) { repo.observe().first { it.value == custom() } } }
        repo.save(custom())
        assertEquals(custom(), changed.await().value)
        assertEquals(custom(), LocalWorkspacePreferencesRepository(context, name).get().value)
        assertEquals(WorkspacePreferences(), LocalWorkspacePreferencesRepository(context, "$name-other").get().value)
    }

    @Test fun aCorruptPreferenceOffersDefaultsAndCanBeExplicitlyReset() = runBlocking {
        assertTrue(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("layout_v1", "broken").commit())
        val repo = LocalWorkspacePreferencesRepository(context, name)
        assertNotNull(repo.get().error); assertEquals(WorkspacePreferences(), repo.observe().first().value)
        repo.save(custom()); assertNull(repo.get().error); assertEquals(custom(), repo.get().value)
    }

    @Test fun customizationDoesNotWriteBusinessRecordsOrChangeTheRoomSchema() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).build()
        try {
            RoomShopRepository(db, false).initialize()
            RoomProductRepository(db).createProduct(1, ProductDraft("Test item", "Cosmetics", purchaseCostPaise = 100, sellingPricePaise = 200, openingQuantity = 3))
            val before = DatabaseSnapshot.fingerprint(db.openHelper.readableDatabase)
            LocalWorkspacePreferencesRepository(context, name).save(custom())
            assertEquals(before, DatabaseSnapshot.fingerprint(db.openHelper.readableDatabase))
            assertEquals(6, db.openHelper.readableDatabase.version)
        } finally { db.close() }
    }
}
