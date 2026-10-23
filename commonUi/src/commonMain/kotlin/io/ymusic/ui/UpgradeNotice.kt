package io.ymusic.ui

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalUriHandler
import io.ymusic.settings.AppSettings
import io.ymusic.settings.SettingsStore
import io.ymusic.settings.flow
import io.ymusic.settings.put
import io.ymusic.ui.resources.Res
import io.ymusic.ui.resources.upgraded_message
import io.ymusic.ui.resources.upgraded_view_changelog
import org.jetbrains.compose.resources.getString

/**
 * Whether this launch is the first of a newer version. Decided once per process from the last recorded
 * launch; a fresh install (an empty settings store) is never "updated", while an install that predates
 * the record (none recorded, but settings exist) is.
 */
internal class UpgradeNotice(private val settings: SettingsStore, private val versionCode: Int?) {
    private val previous = settings.flow(AppSettings.lastRunVersionCode).value

    var pending = versionCode != null && versionCode > previous && !settings.startedEmpty
        private set

    suspend fun recordLaunch() {
        if (versionCode != null && versionCode != previous) settings.put(AppSettings.lastRunVersionCode, versionCode)
    }

    fun consume() {
        pending = false
    }
}

/** "YMusic updated to X" on the first launch after an update, with a View changelog action. */
@Composable
internal fun UpgradeSnackbar(graph: AppGraph, snackbarHostState: SnackbarHostState) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(graph) {
        val notice = graph.upgradeNotice
        notice.recordLaunch()
        if (!notice.pending) return@LaunchedEffect
        notice.consume()
        val version = graph.appVersionName ?: graph.appVersionCode.toString()
        val result = snackbarHostState.showSnackbar(
            getString(Res.string.upgraded_message, version),
            actionLabel = getString(Res.string.upgraded_view_changelog),
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) {
            uriHandler.openUri(AppLinks(graph.remoteConfig?.replayCache?.lastOrNull()).changelog)
        }
    }
}
