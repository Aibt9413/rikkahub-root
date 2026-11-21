package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.LockKey
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.data.ai.root.RootShellManager
import me.rerere.rikkahub.data.datastore.RootPermissionSettings
import me.rerere.rikkahub.data.datastore.RootTier
import me.rerere.rikkahub.data.datastore.ShellBackend
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupIcon
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.compose.koinInject

@Composable
fun SettingRootPermissionPage() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val settingsStore = koinInject<SettingsStore>()
    val shell = koinInject<RootShellManager>()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val root = settings.rootPermission
    val scope = rememberCoroutineScope()
    var rootGranted by remember { mutableStateOf<Boolean?>(null) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        rootGranted = shell.isRootAvailable()
    }

    fun update(transform: (RootPermissionSettings) -> RootPermissionSettings) {
        scope.launch {
            settingsStore.update { it.copy(rootPermission = transform(it.rootPermission)) }
        }
    }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.setting_root_page_title)) },
                navigationIcon = { BackButton() },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { contentPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                val containerColor = MaterialTheme.colorScheme.primaryContainer
                val contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                CardGroup(title = { Text(stringResource(R.string.setting_root_page_general)) }) {
                    item(
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Shield01, containerColor = containerColor, contentColor = contentColor)
                        },
                        headlineContent = { Text(stringResource(R.string.setting_root_page_enable)) },
                        supportingContent = {
                            Text(stringResource(R.string.setting_root_page_enable_desc))
                        },
                        trailingContent = {
                            Switch(
                                checked = root.enabled,
                                onCheckedChange = { enabled ->
                                    update {
                                        it.copy(
                                            enabled = enabled,
                                            tier = if (enabled) RootTier.READ_ONLY else RootTier.OFF,
                                        )
                                    }
                                },
                            )
                        },
                    )
                    item(
                        overlineContent = { Text(stringResource(R.string.setting_root_page_tier)) },
                        headlineContent = { Text(stringResource(R.string.setting_root_page_tier)) },
                        supportingContent = {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    RootTier.entries.forEach { tier ->
                                        if (tier != RootTier.OFF) {
                                            FilterChip(
                                                selected = root.tier == tier,
                                                enabled = root.enabled,
                                                onClick = { update { it.copy(tier = tier) } },
                                                label = {
                                                    Text(
                                                        when (tier) {
                                                            RootTier.READ_ONLY -> stringResource(R.string.setting_root_tier_read_only)
                                                            RootTier.RESTRICTED_WRITE -> stringResource(R.string.setting_root_tier_restricted)
                                                            RootTier.FULL -> stringResource(R.string.setting_root_tier_full)
                                                            RootTier.OFF -> stringResource(R.string.setting_root_tier_off)
                                                        }
                                                    )
                                                },
                                            )
                                        }
                                    }
                                }
                                Text(stringResource(R.string.setting_root_tier_read_only_desc))
                                Text(stringResource(R.string.setting_root_tier_restricted_desc))
                                Text(stringResource(R.string.setting_root_tier_full_desc))
                            }
                        },
                    )
                    item(
                        leadingContent = {
                            CardGroupIcon(HugeIcons.LockKey, containerColor = containerColor, contentColor = contentColor)
                        },
                        headlineContent = { Text(stringResource(R.string.setting_root_page_backend)) },
                        supportingContent = {
                            BackendControls(
                                root = root,
                                shell = shell,
                                onBackend = { backend -> update { it.copy(backend = backend) } },
                            )
                        },
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.secondaryContainer
                val contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                CardGroup(title = { Text(stringResource(R.string.setting_root_page_confirm)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_root_page_confirm_every)) },
                        supportingContent = { Text(stringResource(R.string.setting_root_page_confirm_every_desc)) },
                        trailingContent = {
                            Switch(
                                checked = root.confirmEveryRequest,
                                onCheckedChange = { value -> update { it.copy(confirmEveryRequest = value) } },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_root_page_auto_review)) },
                        supportingContent = { Text(stringResource(R.string.setting_root_page_auto_review_desc)) },
                        trailingContent = {
                            Switch(
                                checked = root.autoReview,
                                onCheckedChange = { value -> update { it.copy(autoReview = value) } },
                            )
                        },
                    )
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_root_page_per_turn)) },
                        supportingContent = { Text(stringResource(R.string.setting_root_page_per_turn_desc)) },
                        trailingContent = {
                            Switch(
                                checked = root.allowPerTurn,
                                onCheckedChange = { value -> update { it.copy(allowPerTurn = value) } },
                            )
                        },
                    )
                }
            }

            item {
                val containerColor = MaterialTheme.colorScheme.tertiaryContainer
                val contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                CardGroup(title = { Text(stringResource(R.string.setting_root_page_protected)) }) {
                    item(
                        leadingContent = {
                            CardGroupIcon(HugeIcons.Shield01, containerColor = containerColor, contentColor = contentColor)
                        },
                        headlineContent = { Text(stringResource(R.string.setting_root_page_protected_desc)) },
                        supportingContent = { Text(stringResource(R.string.setting_root_page_protected_hint)) },
                        trailingContent = {
                            TextButton(onClick = { showAddDialog = true }) {
                                Text(stringResource(R.string.setting_root_page_protected_add))
                            }
                        },
                    )
                    root.protectedPackages.forEach { pkg ->
                        item(
                            headlineContent = { Text(pkg, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text(stringResource(R.string.setting_root_page_protected_package)) },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        update { it.copy(protectedPackages = it.protectedPackages - pkg) }
                                    },
                                ) {
                                    Text(stringResource(R.string.setting_root_page_protected_remove))
                                }
                            },
                        )
                    }
                }
            }

            item {
                CardGroup(title = { Text(stringResource(R.string.setting_root_page_notes)) }) {
                    item(
                        headlineContent = { Text(stringResource(R.string.setting_root_page_note_text)) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        var value by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.setting_root_page_protected_add)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    label = { Text("com.example.app") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pkg = value.trim()
                        if (pkg.isNotEmpty()) {
                            update { it.copy(protectedPackages = it.protectedPackages + pkg) }
                        }
                        showAddDialog = false
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}


@Composable
private fun BackendControls(
    root: RootPermissionSettings,
    shell: RootShellManager,
    onBackend: (ShellBackend) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var status by remember(root.backend) { mutableStateOf("检测中…") }
    var adbCode by rememberSaveable { mutableStateOf("") }
    var adbMessage by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    suspend fun refreshStatus(): String = when (root.backend) {
        ShellBackend.ROOT -> if (shell.isRootAvailable()) "已获得 root" else "未获得 root（请在 KernelSU/Magisk 授权）"
        ShellBackend.SHIZUKU -> when {
            !shell.shizuku.installed() -> "未安装 Shizuku"
            !shell.shizuku.running() -> "Shizuku 未运行"
            shell.shizuku.granted() -> "已授权"
            else -> "尚未授权"
        }
        ShellBackend.ADB -> shell.adb.statusText()
    }

    LaunchedEffect(root.backend) { status = refreshStatus() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = root.backend == ShellBackend.ROOT,
                onClick = { onBackend(ShellBackend.ROOT) },
                label = { Text(stringResource(R.string.setting_root_backend_root)) },
            )
            FilterChip(
                selected = root.backend == ShellBackend.SHIZUKU,
                onClick = { onBackend(ShellBackend.SHIZUKU) },
                label = { Text(stringResource(R.string.setting_root_backend_shizuku)) },
            )
            FilterChip(
                selected = root.backend == ShellBackend.ADB,
                onClick = { onBackend(ShellBackend.ADB) },
                label = { Text(stringResource(R.string.setting_root_backend_adb)) },
            )
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        when (root.backend) {
            ShellBackend.SHIZUKU -> {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        shell.shizuku.requestPermission()
                        scope.launch {
                            busy = true
                            shell.shizuku.awaitPermission(15_000)
                            status = refreshStatus()
                            busy = false
                        }
                    },
                ) { Text(stringResource(R.string.setting_root_shizuku_request)) }
            }
            ShellBackend.ADB -> {
                OutlinedTextField(
                    value = adbCode,
                    onValueChange = { adbCode = it.filter(Char::isDigit).take(6) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.setting_root_adb_pair_code)) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        enabled = !busy && adbCode.length == 6,
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = shell.adb.pair(adbCode)
                                adbMessage = result.exceptionOrNull()?.message ?: "配对成功"
                                status = refreshStatus()
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.setting_root_adb_pair)) }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                val result = shell.adb.connect()
                                adbMessage = result.exceptionOrNull()?.message ?: "已连接"
                                status = refreshStatus()
                                busy = false
                            }
                        },
                    ) { Text(stringResource(R.string.setting_root_adb_connect)) }
                }
                if (adbMessage.isNotBlank()) {
                    Text(adbMessage, style = MaterialTheme.typography.bodySmall)
                }
            }
            else -> Unit
        }
    }
}
