package dev.windock.updates

import dev.windock.CrashLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, INSTALLING, CURRENT, ERROR }

internal data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val visible: Boolean = false,
    val release: UpdateRelease? = null,
    val progress: Float = 0f,
    val error: String? = null,
)

internal sealed interface UpdateUiEvent {
    data object Check : UpdateUiEvent
    data object Install : UpdateUiEvent
    data object Dismiss : UpdateUiEvent
}

internal sealed interface UpdateNavEvent {
    data object ExitForInstaller : UpdateNavEvent
}

/** A separate MVI flow for updates; themes and Windows integration keep their existing state. */
internal class UpdateStore(private val scope: CoroutineScope) {
    private val service = UpdateService()
    private val mutableState = MutableStateFlow(UpdateUiState())
    val state = mutableState.asStateFlow()
    private val events = Channel<UpdateNavEvent>(Channel.BUFFERED)
    val navigation = events.receiveAsFlow()
    private var operation: Job? = null
    private var dismissedVersion: ReleaseVersion? = null

    suspend fun poll() {
        if (!UpdateBuild.canInstall) return
        delay(20_000)
        while (currentCoroutineContext().isActive) {
            if (!state.value.visible) check(manual = false)
            delay(6 * 60 * 60_000L)
        }
    }

    fun dispatch(event: UpdateUiEvent) {
        when (event) {
            UpdateUiEvent.Check -> check(manual = true)
            UpdateUiEvent.Install -> install()
            UpdateUiEvent.Dismiss -> {
                if (state.value.phase == UpdatePhase.INSTALLING) return
                dismissedVersion = state.value.release?.version
                operation?.cancel()
                mutableState.value = UpdateUiState()
            }
        }
    }

    private fun check(manual: Boolean) {
        if (operation?.isActive == true) {
            if (manual) mutableState.update { it.copy(visible = true) }
            return
        }
        mutableState.value = UpdateUiState(phase = UpdatePhase.CHECKING, visible = manual)
        operation = scope.launch {
            try {
                val release = withContext(Dispatchers.IO) { service.latest() }
                val show = mutableState.value.visible
                mutableState.value = when {
                    release == null -> UpdateUiState(UpdatePhase.CURRENT, visible = show)
                    !show && dismissedVersion == release.version -> UpdateUiState()
                    else -> UpdateUiState(UpdatePhase.AVAILABLE, visible = true, release = release)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.exception("update check", error)
                mutableState.update { it.copy(phase = UpdatePhase.ERROR,
                    error = "Não foi possível verificar as atualizações. Confira sua conexão e tente novamente.") }
            }
        }
    }

    private fun install() {
        if (operation?.isActive == true || !UpdateBuild.canInstall) return
        val release = state.value.release ?: return
        mutableState.value = UpdateUiState(UpdatePhase.DOWNLOADING, visible = true, release = release)
        operation = scope.launch {
            try {
                val installer = withContext(Dispatchers.IO) {
                    service.download(release) { value -> mutableState.update { it.copy(progress = value) } }
                }
                mutableState.update { it.copy(phase = UpdatePhase.INSTALLING) }
                withContext(Dispatchers.IO) { service.prepareInstaller(installer, release.sha256) }
                events.send(UpdateNavEvent.ExitForInstaller)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                CrashLog.exception("update installation", error)
                mutableState.update { it.copy(phase = UpdatePhase.ERROR,
                    error = "Não foi possível preparar a atualização. O WinDock continua funcionando. Tente novamente.") }
            }
        }
    }
}
