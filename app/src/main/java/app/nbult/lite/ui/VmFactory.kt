package app.nbult.lite.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.nbult.lite.AppContainer

@Composable
inline fun <reified VM : ViewModel> rememberViewModel(
    /** 传入不同的 key 会得到新的实例。切换学校时用它重建依赖学校上下文的 ViewModel。 */
    key: String? = null,
    crossinline create: (AppContainer) -> VM
): VM {
    val container = LocalAppContainer.current
    return viewModel(
        key = key,
        factory = viewModelFactory { initializer { create(container) } }
    )
}
