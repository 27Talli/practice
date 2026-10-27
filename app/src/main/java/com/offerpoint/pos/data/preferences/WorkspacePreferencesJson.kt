package com.offerpoint.pos.data.preferences

import com.offerpoint.pos.domain.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** A small, versioned layout file. It accepts only the presentation fields below. */
object WorkspacePreferencesJson {
    const val MAX_BYTES = 32 * 1024
    private val fields = setOf("format", "version", "heading", "layout", "color", "appearance", "textSize", "checkoutStart", "tiles")
    private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()

    fun encode(preferences: WorkspacePreferences): String {
        val value = WorkspaceCustomization.validate(preferences)
        return JSONObject().put("format", "OPPOS_LAYOUT").put("version", 1)
            .put("heading", value.heading).put("layout", value.layout.name).put("color", value.color.name)
            .put("appearance", value.appearance.name).put("textSize", value.textSize.name).put("checkoutStart", value.checkoutStart.name)
            .put("tiles", JSONArray().apply { value.tiles.forEach { item ->
                put(JSONObject().put("id", item.tile.name).put("visible", item.visible).put("label", item.label))
            } }).toString(2)
    }

    fun decode(text: String): WorkspacePreferences {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "This layout file is too large. Choose an Offer Point layout file." }
        try {
            val root = JSONObject(text)
            require(keys(root) == fields && root.getString("format") == "OPPOS_LAYOUT" && root.get("version") == 1) { "Unsupported layout file." }
            val array = root.getJSONArray("tiles")
            require(array.length() <= DashboardTile.entries.size)
            val tiles = (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                require(keys(item) == setOf("id", "visible", "label") && item.get("visible") is Boolean)
                DashboardTilePreference(DashboardTile.valueOf(item.getString("id")), item.getBoolean("visible"), item.getString("label"))
            }
            return WorkspaceCustomization.validate(WorkspacePreferences(
                heading = root.getString("heading"), layout = DashboardLayout.valueOf(root.getString("layout")),
                color = AppColor.valueOf(root.getString("color")), appearance = AppAppearance.valueOf(root.getString("appearance")),
                textSize = AppTextSize.valueOf(root.getString("textSize")), checkoutStart = CheckoutStart.valueOf(root.getString("checkoutStart")), tiles = tiles,
            ))
        } catch (error: Exception) {
            throw IllegalArgumentException("This layout file is invalid or from an unsupported app version. Your current layout was not changed.", error)
        }
    }

    fun read(input: InputStream): WorkspacePreferences {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_BYTES) { "This layout file is too large." }
            output.write(buffer, 0, count)
        }
        return decode(output.toString("UTF-8"))
    }
}
