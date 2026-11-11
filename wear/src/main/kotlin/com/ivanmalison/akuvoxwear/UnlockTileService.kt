package com.ivanmalison.akuvoxwear

import android.content.ComponentName
import androidx.wear.protolayout.ActionBuilders.booleanExtra
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.tile

class UnlockTileService : Material3TileService() {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        fun openAndUnlock(armPin: Boolean) = clickable(
            action = launchAction(
                ComponentName(this@UnlockTileService, MainActivity::class.java),
                mapOf(
                    MainActivity.EXTRA_REQUEST_UNLOCK to booleanExtra(true),
                    MainActivity.EXTRA_ARM_PIN to booleanExtra(armPin),
                ),
            ),
            id = if (armPin) "unlock-pin" else "unlock",
        )
        val layout = primaryLayout(
            titleSlot = { text("SmartPlus".layoutString) },
            mainSlot = {
                textButton(
                    onClick = openAndUnlock(armPin = false),
                    labelContent = { text("Unlock".layoutString) },
                    width = expand(),
                    height = expand(),
                )
            },
            bottomSlot = {
                textEdgeButton(
                    onClick = openAndUnlock(armPin = true),
                    colors = filledTonalButtonColors(),
                    labelContent = { text("+ Pin".layoutString) },
                )
            },
        )
        return tile(Timeline.fromLayoutElement(layout))
    }
}
