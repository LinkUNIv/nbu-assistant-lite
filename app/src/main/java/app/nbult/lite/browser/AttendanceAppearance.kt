package app.nbult.lite.browser

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import app.nbult.lite.ui.theme.*

internal fun attendanceDark(context: Context): Boolean = context.getSharedPreferences("appearance_snapshot", Context.MODE_PRIVATE)
    .getBoolean("dark", context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)

internal fun attendanceBackground(context: Context): Int =
    (if (attendanceDark(context)) darkScheme(SeedColor.Blue) else lightScheme(SeedColor.Blue)).background.toArgb()
