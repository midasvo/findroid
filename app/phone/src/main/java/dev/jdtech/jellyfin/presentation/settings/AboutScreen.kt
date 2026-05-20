package dev.jdtech.jellyfin.presentation.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.jdtech.jellyfin.BuildConfig
import dev.jdtech.jellyfin.R
import dev.jdtech.jellyfin.core.R as CoreR
import dev.jdtech.jellyfin.presentation.theme.FindroidTheme
import dev.jdtech.jellyfin.presentation.theme.spacings
import dev.jdtech.jellyfin.settings.R as SettingsR
import dev.jdtech.jellyfin.viewmodels.AboutViewModel
import timber.log.Timber

@Composable
fun AboutScreen(
    navigateBack: () -> Unit,
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    AboutScreenLayout(
        navigateBack = navigateBack,
        onShareDeviceProfile = {
            val json = safeBuildReport(context, viewModel)
            if (json != null) shareJson(context, json)
        },
        onCopyDeviceProfile = {
            val json = safeBuildReport(context, viewModel)
            if (json != null) {
                copyToClipboard(context, json)
                Toast.makeText(
                    context,
                    context.getString(SettingsR.string.export_device_profile_copied),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        },
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun AboutScreenLayout(
    navigateBack: () -> Unit,
    onShareDeviceProfile: () -> Unit,
    onCopyDeviceProfile: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current

    val safePaddingStart =
        with(density) { WindowInsets.safeDrawing.getLeft(this, layoutDirection).toDp() }
    val safePaddingEnd =
        with(density) { WindowInsets.safeDrawing.getRight(this, layoutDirection).toDp() }

    val paddingStart = safePaddingStart
    val paddingEnd = safePaddingEnd

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val libraries by produceLibraries(R.raw.aboutlibraries)

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(SettingsR.string.about)) },
                navigationIcon = {
                    IconButton(onClick = navigateBack) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                windowInsets = WindowInsets.statusBars.union(WindowInsets.displayCutout),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                PaddingValues(
                    start = paddingStart + innerPadding.calculateStartPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding(),
                    end = paddingEnd + innerPadding.calculateEndPadding(layoutDirection),
                    bottom = innerPadding.calculateBottomPadding(),
                ),
            header = {
                item {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier =
                                Modifier.padding(horizontal = MaterialTheme.spacings.default),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            Image(
                                painter = painterResource(CoreR.drawable.ic_banner),
                                contentDescription = null,
                                modifier = Modifier.width(240.dp),
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            Text(
                                text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            Text(
                                text = stringResource(CoreR.string.app_description),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            HorizontalDivider()
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.small)
                            ) {
                                FilledTonalIconButton(
                                    onClick = {
                                        try {
                                            uriHandler.openUri(
                                                "https://github.com/midasvo/findroid-ce"
                                            )
                                        } catch (e: IllegalArgumentException) {
                                            Toast.makeText(
                                                    context,
                                                    e.localizedMessage,
                                                    Toast.LENGTH_SHORT,
                                                )
                                                .show()
                                        }
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(CoreR.drawable.ic_github),
                                        contentDescription = null,
                                    )
                                }
                            }
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            HorizontalDivider()
                            Spacer(Modifier.height(MaterialTheme.spacings.medium))
                            Text(
                                text = stringResource(SettingsR.string.export_device_profile),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.extraSmall))
                            Text(
                                text = stringResource(
                                    SettingsR.string.export_device_profile_summary,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                            Row(
                                horizontalArrangement =
                                    Arrangement.spacedBy(MaterialTheme.spacings.small)
                            ) {
                                FilledTonalButton(onClick = onShareDeviceProfile) {
                                    Text(stringResource(CoreR.string.share))
                                }
                                OutlinedButton(onClick = onCopyDeviceProfile) {
                                    Text(
                                        stringResource(
                                            SettingsR.string.export_device_profile_copy,
                                        ),
                                    )
                                }
                            }
                            Spacer(Modifier.height(MaterialTheme.spacings.small))
                        }
                    }
                }
            },
        )
    }
}

private fun safeBuildReport(context: Context, viewModel: AboutViewModel): String? {
    return try {
        viewModel.buildCapabilityReportJson(
            findroidVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            findroidBuildType = BuildConfig.BUILD_TYPE,
        )
    } catch (e: Exception) {
        Timber.e(e, "Failed to build device capability report")
        Toast.makeText(
            context,
            context.getString(SettingsR.string.export_device_profile_failed),
            Toast.LENGTH_SHORT,
        ).show()
        null
    }
}

private fun shareJson(context: Context, json: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_SUBJECT, "Findroid device profile")
        putExtra(Intent.EXTRA_TEXT, json)
    }
    try {
        context.startActivity(Intent.createChooser(sendIntent, null))
    } catch (e: Exception) {
        Timber.e(e, "Failed to launch share intent for device profile")
    }
}

private fun copyToClipboard(context: Context, json: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = ClipData.newPlainText("Findroid device profile", json)
    clipboard?.setPrimaryClip(clip)
}

@Composable
@PreviewScreenSizes
private fun AboutScreenLayoutPreview() {
    FindroidTheme {
        AboutScreenLayout(
            navigateBack = {},
            onShareDeviceProfile = {},
            onCopyDeviceProfile = {},
        )
    }
}
