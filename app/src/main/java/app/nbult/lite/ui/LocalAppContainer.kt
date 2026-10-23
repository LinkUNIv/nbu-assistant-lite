package app.nbult.lite.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.nbult.lite.AppContainer

val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided")
}
