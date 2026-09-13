package com.ivanmalison.akuvoxwear

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

class UnlockComplicationService : SuspendingComplicationDataSourceService() {
    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        unlockComplication(type, tapAction = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        unlockComplication(request.complicationType, unlockTapAction())

    private fun unlockComplication(type: ComplicationType, tapAction: PendingIntent?): ComplicationData? {
        val label = PlainComplicationText.Builder(getString(R.string.unlock_label)).build()
        val title = PlainComplicationText.Builder(getString(R.string.unlock_title)).build()
        val description = PlainComplicationText.Builder(getString(R.string.complication_description)).build()
        val monochromaticImage = MonochromaticImage
            .Builder(Icon.createWithResource(this, R.drawable.ic_unlock))
            .build()
        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(label, description)
                    .setMonochromaticImage(monochromaticImage)
                    .setTapAction(tapAction)
                    .build()

            ComplicationType.LONG_TEXT ->
                LongTextComplicationData.Builder(label, description)
                    .setTitle(title)
                    .setMonochromaticImage(monochromaticImage)
                    .setTapAction(tapAction)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(monochromaticImage, description)
                    .setTapAction(tapAction)
                    .build()

            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage
                        .Builder(Icon.createWithResource(this, R.drawable.ic_launcher), SmallImageType.ICON)
                        .build(),
                    description,
                )
                    .setTapAction(tapAction)
                    .build()

            else -> null
        }
    }

    private fun unlockTapAction(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_REQUEST_UNLOCK, true)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
