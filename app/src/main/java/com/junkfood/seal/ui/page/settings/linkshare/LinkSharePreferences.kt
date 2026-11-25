package com.junkfood.seal.ui.page.settings.linkshare

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoMode
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.text.isDigitsOnly
import com.junkfood.seal.R
import com.junkfood.seal.ui.common.booleanState
import com.junkfood.seal.ui.common.intState
import com.junkfood.seal.ui.common.stringState
import com.junkfood.seal.ui.component.BackButton
import com.junkfood.seal.ui.component.ConfirmButton
import com.junkfood.seal.ui.component.DismissButton
import com.junkfood.seal.ui.component.PreferenceInfo
import com.junkfood.seal.ui.component.PreferenceItem
import com.junkfood.seal.ui.component.PreferenceSubtitle
import com.junkfood.seal.ui.component.PreferenceSwitch
import com.junkfood.seal.util.LINKSHARE_API_TOKEN
import com.junkfood.seal.util.LINKSHARE_AUTO_COPY
import com.junkfood.seal.util.LINKSHARE_AUTO_UPLOAD
import com.junkfood.seal.util.LINKSHARE_DELETE_LOCAL_AFTER_UPLOAD
import com.junkfood.seal.util.LINKSHARE_ENABLED
import com.junkfood.seal.util.LINKSHARE_SERVER_URL
import com.junkfood.seal.util.LINKSHARE_TTL_DAYS
import com.junkfood.seal.util.LinkShareUtil
import com.junkfood.seal.util.PreferenceUtil.updateBoolean
import com.junkfood.seal.util.PreferenceUtil.updateInt
import com.junkfood.seal.util.PreferenceUtil.updateString
import com.junkfood.seal.util.ToastUtil
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkSharePreferences(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior =
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
            rememberTopAppBarState(),
            canScroll = { true },
        )

    var enabled by LINKSHARE_ENABLED.booleanState
    var serverUrl by LINKSHARE_SERVER_URL.stringState
    var apiToken by LINKSHARE_API_TOKEN.stringState
    var ttlDays by LINKSHARE_TTL_DAYS.intState
    var autoUpload by LINKSHARE_AUTO_UPLOAD.booleanState
    var autoCopy by LINKSHARE_AUTO_COPY.booleanState
    var deleteLocal by LINKSHARE_DELETE_LOCAL_AFTER_UPLOAD.booleanState

    var showServerUrlDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showTtlDialog by remember { mutableStateOf(false) }
    var testingConnection by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(text = stringResource(id = R.string.linkshare)) },
                navigationIcon = { BackButton { onNavigateBack() } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(contentPadding = paddingValues) {
            item {
                PreferenceSwitch(
                    title = stringResource(R.string.linkshare_enable),
                    description = stringResource(R.string.linkshare_enable_desc),
                    icon = Icons.Outlined.CloudUpload,
                    isChecked = enabled,
                    onClick = {
                        enabled = !enabled
                        LINKSHARE_ENABLED.updateBoolean(enabled)
                    },
                )
            }

            item { PreferenceSubtitle(text = stringResource(R.string.linkshare_server_section)) }

            item {
                PreferenceItem(
                    title = stringResource(R.string.linkshare_server_url),
                    description =
                        serverUrl.ifBlank { stringResource(R.string.linkshare_server_url_empty) },
                    icon = Icons.Outlined.Link,
                    enabled = enabled,
                    onClick = { showServerUrlDialog = true },
                )
            }

            item {
                PreferenceItem(
                    title = stringResource(R.string.linkshare_api_token),
                    description =
                        if (apiToken.isBlank()) stringResource(R.string.linkshare_api_token_empty)
                        else stringResource(R.string.linkshare_api_token_configured),
                    icon = Icons.Outlined.Key,
                    enabled = enabled,
                    onClick = { showTokenDialog = true },
                )
            }

            item {
                PreferenceItem(
                    title = stringResource(R.string.linkshare_ttl_days),
                    description = stringResource(R.string.linkshare_ttl_days_desc, ttlDays),
                    icon = Icons.Outlined.Schedule,
                    enabled = enabled,
                    onClick = { showTtlDialog = true },
                )
            }

            item {
                PreferenceItem(
                    title = stringResource(R.string.linkshare_test_connection),
                    description =
                        if (testingConnection) stringResource(R.string.linkshare_testing)
                        else stringResource(R.string.linkshare_test_connection_desc),
                    icon = Icons.Outlined.CloudDone,
                    enabled = enabled && !testingConnection,
                    onClick = {
                        testingConnection = true
                        scope.launch {
                            LinkShareUtil.testConnection(serverUrl, apiToken)
                                .onSuccess {
                                    ToastUtil.makeToastSuspend(
                                        context.getString(R.string.linkshare_connection_ok)
                                    )
                                }
                                .onFailure { err ->
                                    ToastUtil.makeToastSuspend(
                                        context.getString(
                                            R.string.linkshare_connection_failed,
                                            err.message ?: "Unknown error",
                                        )
                                    )
                                }
                            testingConnection = false
                        }
                    },
                )
            }

            item { PreferenceSubtitle(text = stringResource(R.string.linkshare_behavior_section)) }

            item {
                PreferenceSwitch(
                    title = stringResource(R.string.linkshare_auto_upload),
                    description = stringResource(R.string.linkshare_auto_upload_desc),
                    icon = Icons.Outlined.AutoMode,
                    enabled = enabled,
                    isChecked = autoUpload,
                    onClick = {
                        autoUpload = !autoUpload
                        LINKSHARE_AUTO_UPLOAD.updateBoolean(autoUpload)
                    },
                )
            }

            item {
                PreferenceSwitch(
                    title = stringResource(R.string.linkshare_auto_copy),
                    description = stringResource(R.string.linkshare_auto_copy_desc),
                    icon = Icons.Outlined.ContentCopy,
                    enabled = enabled,
                    isChecked = autoCopy,
                    onClick = {
                        autoCopy = !autoCopy
                        LINKSHARE_AUTO_COPY.updateBoolean(autoCopy)
                    },
                )
            }

            item {
                PreferenceSwitch(
                    title = stringResource(R.string.linkshare_delete_local),
                    description = stringResource(R.string.linkshare_delete_local_desc),
                    icon = Icons.Outlined.DeleteOutline,
                    enabled = enabled,
                    isChecked = deleteLocal,
                    onClick = {
                        deleteLocal = !deleteLocal
                        LINKSHARE_DELETE_LOCAL_AFTER_UPLOAD.updateBoolean(deleteLocal)
                    },
                )
            }

            item { PreferenceInfo(text = stringResource(R.string.linkshare_hint)) }
        }
    }

    if (showServerUrlDialog) {
        LinkShareServerUrlDialog(
            initialValue = serverUrl,
            onDismissRequest = { showServerUrlDialog = false },
            onConfirm = { newUrl ->
                serverUrl = newUrl
                LINKSHARE_SERVER_URL.updateString(newUrl)
                showServerUrlDialog = false
            },
        )
    }

    if (showTokenDialog) {
        LinkShareTokenDialog(
            initialValue = apiToken,
            onDismissRequest = { showTokenDialog = false },
            onConfirm = { newToken ->
                apiToken = newToken
                LINKSHARE_API_TOKEN.updateString(newToken)
                showTokenDialog = false
            },
        )
    }

    if (showTtlDialog) {
        LinkShareTtlDialog(
            initialValue = ttlDays,
            onDismissRequest = { showTtlDialog = false },
            onConfirm = { newDays ->
                ttlDays = newDays
                LINKSHARE_TTL_DAYS.updateInt(newDays)
                showTtlDialog = false
            },
        )
    }
}

@Composable
private fun LinkShareServerUrlDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Link, null) },
        title = { Text(stringResource(R.string.linkshare_server_url)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.linkshare_server_url_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    value = value,
                    onValueChange = {
                        value = it
                        isError = false
                    },
                    isError = isError,
                    singleLine = true,
                    label = { Text(stringResource(R.string.linkshare_server_url)) },
                    placeholder = { Text("https://linkshare.example.com") },
                    supportingText = {
                        if (isError) {
                            Text(stringResource(R.string.invalid_input))
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                )
            }
        },
        dismissButton = { DismissButton { onDismissRequest() } },
        confirmButton = {
            ConfirmButton {
                val trimmed = value.trim()
                if (trimmed.isEmpty()) {
                    onConfirm("")
                } else {
                    val normalized = LinkShareUtil.normalizeServerUrl(trimmed)
                    if (normalized != null) {
                        onConfirm(normalized)
                    } else {
                        isError = true
                    }
                }
            }
        },
    )
}

@Composable
private fun LinkShareTokenDialog(
    initialValue: String,
    onDismissRequest: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Key, null) },
        title = { Text(stringResource(R.string.linkshare_api_token)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.linkshare_api_token_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.linkshare_api_token)) },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                )
            }
        },
        dismissButton = { DismissButton { onDismissRequest() } },
        confirmButton = { ConfirmButton { onConfirm(value.trim()) } },
    )
}

@Composable
private fun LinkShareTtlDialog(
    initialValue: Int,
    onDismissRequest: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(initialValue.toString()) }
    var isError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(Icons.Outlined.Schedule, null) },
        title = { Text(stringResource(R.string.linkshare_ttl_days)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.linkshare_ttl_days_dialog_desc),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    value = value,
                    onValueChange = {
                        if (it.isDigitsOnly()) {
                            value = it
                            isError = false
                        }
                    },
                    isError = isError,
                    singleLine = true,
                    label = { Text(stringResource(R.string.linkshare_ttl_days)) },
                    supportingText = {
                        if (isError) {
                            Text(stringResource(R.string.invalid_input))
                        }
                    },
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done,
                        ),
                )
            }
        },
        dismissButton = { DismissButton { onDismissRequest() } },
        confirmButton = {
            ConfirmButton {
                val parsed = value.toIntOrNull()
                if (parsed != null && parsed in 1..90) {
                    onConfirm(parsed)
                } else {
                    isError = true
                }
            }
        },
    )
}
