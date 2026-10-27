package com.offerpoint.pos

import com.offerpoint.pos.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class WorkspaceCustomizationTest {
    @Test fun defaultLayoutKeepsEveryToolAndTheExistingCheckoutStartRules() {
        val value = WorkspaceCustomization.validate(WorkspacePreferences())
        assertEquals(DashboardTile.entries.toList(), value.tiles.map { it.tile })
        assertTrue(value.tiles.all { it.visible })
        assertEquals("scan", value.checkoutSection("OP"))
        assertEquals("products", value.checkoutSection("FF"))
    }

    @Test fun movingAHiddenRenamedTilePreservesItsVisibilityAndName() {
        val initial = WorkspacePreferences().editTile(DashboardTile.CHECKOUT, false, "Sell now")
        val moved = initial.move(DashboardTile.CHECKOUT, -1)
        assertEquals(DashboardTile.CHECKOUT, moved.tiles[3].tile)
        assertEquals(DashboardTilePreference(DashboardTile.CHECKOUT, false, "Sell now"), moved.tiles[3])
        assertEquals(initial.tiles.map { it.tile }.toSet(), moved.tiles.map { it.tile }.toSet())
    }

    @Test fun movingAtTheEdgesIsStableAndInvalidDirectionsAreRejected() {
        val value = WorkspacePreferences()
        assertEquals(value, value.move(DashboardTile.SHOP, -1))
        assertEquals(value, value.move(DashboardTile.REPORTS, 1))
        assertThrows(IllegalArgumentException::class.java) { value.move(DashboardTile.SHOP, 2) }
    }

    @Test fun hidingAllDashboardItemsIsAllowedWithoutDeletingToolDefinitions() {
        val value = WorkspaceCustomization.validate(WorkspacePreferences(tiles = DashboardTile.entries.map { DashboardTilePreference(it, false) }))
        assertTrue(value.dashboardRows().isEmpty())
        assertEquals(7, DashboardTile.entries.count { it.isTool })
        assertEquals("scan", value.checkoutSection("OP"))
    }

    @Test fun gridPairsAdjacentToolsInOrderAndKeepsInformationCardsFullWidth() {
        val value = WorkspacePreferences(layout = DashboardLayout.GRID)
        val rows = value.dashboardRows()
        assertEquals(listOf(DashboardTile.SHOP), rows[0].map { it.tile })
        assertEquals(listOf(DashboardTile.TODAY), rows[1].map { it.tile })
        assertEquals(listOf(DashboardTile.PRODUCTS, DashboardTile.INVENTORY), rows[2].map { it.tile })
        assertEquals(value.tiles, rows.flatten())
        assertTrue(rows.all { it.size in 1..2 })
    }

    @Test fun hiddenItemsDoNotLeaveHolesAndInfoCardsBreakGridRows() {
        val value = WorkspacePreferences(layout = DashboardLayout.GRID, tiles = listOf(
            DashboardTilePreference(DashboardTile.PRODUCTS), DashboardTilePreference(DashboardTile.SHOP),
            DashboardTilePreference(DashboardTile.INVENTORY, false), DashboardTilePreference(DashboardTile.CHECKOUT),
            DashboardTilePreference(DashboardTile.TODAY), DashboardTilePreference(DashboardTile.REPORTS),
        ))
        assertEquals(listOf(listOf(DashboardTile.PRODUCTS), listOf(DashboardTile.SHOP), listOf(DashboardTile.CHECKOUT),
            listOf(DashboardTile.TODAY), listOf(DashboardTile.REPORTS)), value.dashboardRows().map { row -> row.map { it.tile } })
    }

    @Test fun oldLayoutsGainMissingToolsWithoutLosingExistingChoices() {
        val value = WorkspaceCustomization.validate(WorkspacePreferences(heading = " My counter ", tiles = listOf(DashboardTilePreference(DashboardTile.CHECKOUT, false, " Sell "))))
        assertEquals("My counter", value.heading)
        assertEquals(DashboardTilePreference(DashboardTile.CHECKOUT, false, "Sell"), value.tiles.first())
        assertEquals(DashboardTile.entries.size, value.tiles.size)
    }

    @Test fun duplicateTilesLongNamesAndControlCharactersAreRejected() {
        val value = WorkspacePreferences()
        val invalid = listOf(value.copy(heading = " "), value.copy(heading = "a".repeat(41)), value.copy(heading = "Bad\nname"),
            value.copy(tiles = listOf(DashboardTilePreference(DashboardTile.SHOP), DashboardTilePreference(DashboardTile.SHOP))),
            value.editTile(DashboardTile.CHECKOUT, label = "a".repeat(25)), value.editTile(DashboardTile.CHECKOUT, label = "Bad\u0000name"))
        invalid.forEach { assertThrows(IllegalArgumentException::class.java) { WorkspaceCustomization.validate(it) } }
        assertEquals("Checkout", WorkspaceCustomization.validate(value.editTile(DashboardTile.CHECKOUT, label = " ")).tiles.single { it.tile == DashboardTile.CHECKOUT }.displayName)
    }

    @Test fun checkoutPreferenceChoosesOnlyAKnownSectionForEitherShop() {
        assertEquals("products", WorkspacePreferences(checkoutStart = CheckoutStart.PRODUCTS).checkoutSection("OP"))
        assertEquals("scan", WorkspacePreferences(checkoutStart = CheckoutStart.SCAN).checkoutSection("FF"))
        assertEquals("cart", WorkspacePreferences(checkoutStart = CheckoutStart.CART).checkoutSection("OP"))
    }
}
