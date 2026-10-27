package dev.windock.updates

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

internal val LocalUpdateCheck = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
internal fun UpdateSettingsRow() {
    val check = LocalUpdateCheck.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("WinDock " + UpdateBuild.version, fontSize = 12.sp)
        TextButton(onClick = check) { Text("Verificar atualizações") }
    }
}

@Composable
internal fun UpdateDialog(state: UpdateUiState, dispatch: (UpdateUiEvent) -> Unit) {
    if (!state.visible) return
    val installing = state.phase == UpdatePhase.INSTALLING
    DialogWindow(
        onCloseRequest = { if (!installing) dispatch(UpdateUiEvent.Dismiss) },
        title = "Atualização do WinDock",
        state = rememberDialogState(width = 500.dp, height = 470.dp),
        resizable = false,
        alwaysOnTop = true,
    ) {
        // This dialog is created at application scope, without a settings-window
        // owner. Explicitly raise it when opened, including manual checks.
        LaunchedEffect(window) {
            while (!window.isShowing) withFrameNanos { }
            window.toFront()
            window.requestFocus()
        }
        MaterialTheme {
            Surface {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(when (state.phase) {
                        UpdatePhase.AVAILABLE -> "Uma nova versão está disponível"
                        UpdatePhase.DOWNLOADING -> "Baixando atualização…"
                        UpdatePhase.INSTALLING -> "Preparando a instalação…"
                        UpdatePhase.CURRENT -> "Seu WinDock está atualizado"
                        UpdatePhase.ERROR -> "Não foi possível atualizar"
                        else -> "Verificando atualizações…"
                    }, style = MaterialTheme.typography.titleLarge)
                    Text("Versão atual: " + UpdateBuild.version +
                        (state.release?.let { "  →  " + it.version } ?: ""), style = MaterialTheme.typography.bodyMedium)
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (state.phase) {
                                UpdatePhase.AVAILABLE -> {
                                    Text(if (UpdateBuild.canInstall)
                                        "Deseja atualizar agora? O dock será fechado durante a instalação e abrirá novamente ao terminar. Seus temas e preferências serão mantidos."
                                    else "Esta é uma execução de desenvolvimento ou uma plataforma sem atualização automática. Instale a versão para Windows pelo MSI para habilitar esse recurso.")
                                    state.release?.notes?.takeIf { it.isNotBlank() }?.let {
                                        Text("Novidades", style = MaterialTheme.typography.titleSmall)
                                        Text(it, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                UpdatePhase.DOWNLOADING -> {
                                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                                    Text((state.progress * 100).toInt().toString() + "% — conferindo o instalador ao concluir.")
                                }
                                UpdatePhase.INSTALLING -> {
                                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                    Text("O instalador do Windows continuará a atualização. O computador não será reiniciado automaticamente.")
                                }
                                UpdatePhase.ERROR -> Text(state.error.orEmpty())
                                UpdatePhase.CURRENT -> Text("Nenhuma versão estável mais recente foi encontrada.")
                                else -> CircularProgressIndicator()
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { dispatch(UpdateUiEvent.Dismiss) }, enabled = !installing) {
                            Text(when (state.phase) {
                                UpdatePhase.DOWNLOADING -> "Cancelar download"
                                UpdatePhase.AVAILABLE -> "Agora não"
                                else -> "Fechar"
                            })
                        }
                        if (state.phase == UpdatePhase.AVAILABLE && UpdateBuild.canInstall) {
                            Button(onClick = { dispatch(UpdateUiEvent.Install) }) { Text("Atualizar agora") }
                        }
                        if (state.phase == UpdatePhase.ERROR) {
                            Button(onClick = { dispatch(if (state.release != null && UpdateBuild.canInstall)
                                UpdateUiEvent.Install else UpdateUiEvent.Check) }) { Text("Tentar novamente") }
                        }
                    }
                }
            }
        }
    }
}
