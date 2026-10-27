package com.offerpoint.pos.domain.model

enum class DashboardTile(val title: String, val description: String, val action: String, val isTool: Boolean = true) {
    SHOP("Current shop", "The shop you are working in", "", false),
    TODAY("Today's sales", "Net sales from saved records, in Pakistan time", "", false),
    PRODUCTS("Products", "Add, edit, and find products", "MANAGE"),
    INVENTORY("Inventory", "View quantities and adjust stock", "MANAGE"),
    CHECKOUT("Checkout", "Scan items and record a cash sale", "CASH SALE"),
    SALES("Sales history", "Open completed transactions and receipts", "VIEW"),
    RETURNS("Returns & refunds", "Saved refunds and original receipt links", "VIEW"),
    EXPENSES("Expenses", "Record and review shop expenses", "MANAGE"),
    REPORTS("Reports", "Net sales, costs, profit and CSV export", "VIEW"),
}

enum class DashboardLayout(val title: String) { CARDS("Large cards"), GRID("Two-column grid"), COMPACT("Compact list") }
enum class AppColor(val title: String) { TEAL("Teal"), BLUE("Blue"), PURPLE("Purple"), AMBER("Warm amber") }
enum class AppAppearance(val title: String) { SYSTEM("Follow phone setting"), LIGHT("Light"), DARK("Dark") }
enum class AppTextSize(val title: String, val multiplier: Float) { STANDARD("Standard", 1f), LARGER("Larger", 1.1f), LARGEST("Largest", 1.25f) }
enum class CheckoutStart(val title: String) { AUTO("Scan for Offer Point; products for Fancy Footwears"), SCAN("Camera scanner"), PRODUCTS("Product list"), CART("Cart") }

data class DashboardTilePreference(val tile: DashboardTile, val visible: Boolean = true, val label: String = "") {
    val displayName: String get() = label.ifBlank { tile.title }
}

/** Device-local presentation only. Never contains prices, stock, shop branding or transaction rules. */
data class WorkspacePreferences(
    val heading: String = "At your counter",
    val layout: DashboardLayout = DashboardLayout.CARDS,
    val color: AppColor = AppColor.TEAL,
    val appearance: AppAppearance = AppAppearance.SYSTEM,
    val textSize: AppTextSize = AppTextSize.STANDARD,
    val checkoutStart: CheckoutStart = CheckoutStart.AUTO,
    val tiles: List<DashboardTilePreference> = DashboardTile.entries.map { DashboardTilePreference(it) },
) {
    fun checkoutSection(shopCode: String): String = when (checkoutStart) {
        CheckoutStart.AUTO -> if (shopCode == "OP") "scan" else "products"
        CheckoutStart.SCAN -> "scan"
        CheckoutStart.PRODUCTS -> "products"
        CheckoutStart.CART -> "cart"
    }

    fun move(tile: DashboardTile, direction: Int): WorkspacePreferences {
        require(direction == -1 || direction == 1)
        val order = tiles.toMutableList()
        val index = order.indexOfFirst { it.tile == tile }
        require(index >= 0) { "This dashboard item is unavailable." }
        val destination = (index + direction).coerceIn(0, order.lastIndex)
        val other = order[destination]
        order[destination] = order[index]
        order[index] = other
        return copy(tiles = order)
    }

    fun editTile(tile: DashboardTile, visible: Boolean? = null, label: String? = null) = copy(
        tiles = tiles.map { if (it.tile == tile) it.copy(visible = visible ?: it.visible, label = label ?: it.label) else it },
    )

    /** Information cards stay full width; adjacent tool cards pair in grid mode without changing order. */
    fun dashboardRows(): List<List<DashboardTilePreference>> {
        val rows = mutableListOf<List<DashboardTilePreference>>()
        for (item in tiles.filter { it.visible }) {
            val last = rows.lastOrNull()
            if (layout == DashboardLayout.GRID && item.tile.isTool && last?.size == 1 && last[0].tile.isTool) {
                rows[rows.lastIndex] = last + item
            } else rows.add(listOf(item))
        }
        return rows
    }
}

object WorkspaceCustomization {
    fun validate(value: WorkspacePreferences): WorkspacePreferences {
        val heading = value.heading.trim()
        require(heading.length in 1..40 && heading.none(Char::isISOControl)) { "Use a dashboard heading of 1–40 characters without line breaks." }
        require(value.tiles.size <= DashboardTile.entries.size && value.tiles.map { it.tile }.distinct().size == value.tiles.size) { "Choose each dashboard item once." }
        val normalized = value.tiles.map { item ->
            val label = item.label.trim()
            require(label.length <= 24 && label.none(Char::isISOControl)) { "Shortcut names can contain up to 24 characters without line breaks." }
            item.copy(label = label)
        }
        // A future app update can add new tools to an older saved layout without losing its order.
        val missing = DashboardTile.entries.filter { tile -> normalized.none { it.tile == tile } }.map { DashboardTilePreference(it) }
        return value.copy(heading = heading, tiles = normalized + missing)
    }
}

data class WorkspacePreferencesSnapshot(val value: WorkspacePreferences = WorkspacePreferences(), val error: String? = null, val ready: Boolean = true)
