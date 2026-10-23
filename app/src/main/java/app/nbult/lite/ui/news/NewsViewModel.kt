package app.nbult.lite.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nbult.lite.data.db.NewsItem
import app.nbult.lite.data.repo.NewsRepository
import app.nbult.lite.net.NetStatus
import app.nbult.lite.net.NetworkMonitor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class NewsUiState(
    val loading: Boolean = false,
    val items: List<NewsItem> = emptyList(),
    val error: String? = null
)

class NewsViewModel(
    private val repository: NewsRepository,
    private val monitor: NetworkMonitor
) : ViewModel() {

    private val _state = MutableStateFlow(NewsUiState())
    val state: StateFlow<NewsUiState> = _state.asStateFlow()

    val networkStatus: StateFlow<NetStatus> = monitor.status

    init {
        viewModelScope.launch {
            repository.observe().collect { items ->
                _state.value = _state.value.copy(items = items)
            }
        }
        refresh()
    }

    fun refresh() {
        if (monitor.status.value == NetStatus.OFFLINE) return
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            repository.refresh().onSuccess {
                _state.value = _state.value.copy(loading = false)
            }.onFailure {
                _state.value = _state.value.copy(
                    loading = false,
                    error = it.message ?: "加载失败"
                )
            }
        }
    }

    fun markRead(id: Long) {
        viewModelScope.launch { repository.markRead(id) }
    }

    fun loadContent(id: Long) {
        viewModelScope.launch { repository.loadContent(id) }
    }
}
