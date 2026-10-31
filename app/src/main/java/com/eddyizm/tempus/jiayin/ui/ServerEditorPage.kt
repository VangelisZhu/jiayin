@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.eddyizm.tempus.jiayin.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.eddyizm.tempus.jiayin.JiayinViewModel
import com.eddyizm.tempus.jiayin.data.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable fun ServerEditorPage(vm: JiayinViewModel, state: ServerEditorState) {
    val draft = state.draft
    val original = draft.originalId?.let(vm.repo.profiles::find)
    var visible by remember { mutableStateOf(false) }
    var automaticLyrics by remember { mutableStateOf(vm.repo.settings.getBoolean("auto-repair-lyrics", false)) }
    val addressChanged = original != null && runCatching { ServerEditorPolicy.address(draft.address) }.getOrNull() != original.address
    val userChanged = original != null && draft.username.trim() != original.username
    val focus = remember { listOf("address", "username", "password").associateWith { FocusRequester() } }
    fun update(value: ServerDraft) = vm.editServerDraft(value.copy(acceptUnverified = false))
    Dialog(onDismissRequest = vm::closeServerEditor, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        dismissOnBackPress = !state.committing, dismissOnClickOutside = false)) {
        val view = LocalView.current
        val light = MaterialTheme.colorScheme.surface.luminance() > .5f
        SideEffect { (view.parent as? DialogWindowProvider)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        } }
        BackHandler { if (!state.committing) vm.closeServerEditor() }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                PageTitle(if (original == null) "添加音乐服务器" else "编辑连接", "连接你的音乐服务", if (state.committing) null else vm::closeServerEditor)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(draft.name, { update(draft.copy(name = it)) }, Modifier.fillMaxWidth(), enabled = !state.committing, label = { Text("名称（可选）") }, singleLine = true)
                    OutlinedTextField(draft.address, { update(draft.copy(address = it, preserve = null, confirmedSameLibrary = false)) }, Modifier.fillMaxWidth().focusRequester(focus.getValue("address")), enabled = !state.committing,
                        label = { Text("服务器地址") }, placeholder = { Text("https://music.example.com") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false), isError = "address" in state.errors,
                        supportingText = { Text(state.errors["address"] ?: "填写音乐服务入口，不是 NAS 管理页面") })
                    OutlinedTextField(draft.username, { update(draft.copy(username = it, confirmedSameLibrary = false)) }, Modifier.fillMaxWidth().focusRequester(focus.getValue("username")), enabled = !state.committing,
                        label = { Text("账户") }, singleLine = true, isError = "username" in state.errors, supportingText = { state.errors["username"]?.let { Text(it) } })
                    OutlinedTextField(draft.password, { update(draft.copy(password = it)) }, Modifier.fillMaxWidth().focusRequester(focus.getValue("password")), enabled = !state.committing,
                        label = { Text("密码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { TextButton({ visible = !visible }) { Text(if (visible) "隐藏" else "显示") } }, isError = "password" in state.errors,
                        supportingText = { Text(state.errors["password"] ?: if (original?.authenticated == true && !addressChanged && !userChanged) "留空保留已保存的凭据" else "新连接或地址／账户变更后需填写密码") })
                    if (userChanged) Text("账户已改变：将另存为独立连接，原连接及其数据保留。")
                    else if (addressChanged) {
                        Text("地址变化后如何处理数据？", style = MaterialTheme.typography.titleMedium)
                        FilterChip(draft.preserve == true, { update(draft.copy(preserve = true)) }, enabled = !state.committing, label = { Text("同一 NAS、同一账户换地址") })
                        FilterChip(draft.preserve == false, { update(draft.copy(preserve = false, confirmedSameLibrary = false)) }, enabled = !state.committing, label = { Text("另一台服务器 · 另存连接") })
                        if (draft.preserve == true) Row {
                            Checkbox(draft.confirmedSameLibrary, { update(draft.copy(confirmedSameLibrary = it)) }, enabled = !state.committing)
                            Text("确认仍是同一音乐服务、同一账户，且未重建曲库；保留原下载、记录与队列。", Modifier.padding(top = 10.dp))
                        }
                        state.errors["identity"]?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    if (state.needsIdentityConfirmation || draft.acceptUnverified) Row {
                        Checkbox(draft.acceptUnverified, { vm.editServerDraft(draft.copy(acceptUnverified = it)) }, enabled = !state.committing)
                        Text("无法核实歌曲标识，我已确认是原音乐服务和曲库，仍保留本地数据。", Modifier.padding(top = 10.dp))
                    }
                    Text("认证信息加密保存在本机。测试连接不会保存或切换账户。", style = MaterialTheme.typography.bodySmall)
                    if (vm.repo.profiles.all().isEmpty()) {
                        Row {
                            Checkbox(automaticLyrics, { automaticLyrics = it; vm.repo.settings.edit().putBoolean("auto-repair-lyrics", it).apply() }, enabled = !state.committing)
                            Text("允许乱码歌词自动联网修复", Modifier.padding(top = 10.dp))
                        }
                        Text("开启后，仅发现乱码时向 LRCLIB 发送歌名和歌手；正常歌词不会自动匹配。可在设置关闭。", style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(12.dp))
                }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        state.message?.let { text -> Text(text, Modifier.fillMaxWidth().heightIn(max = 100.dp).verticalScroll(rememberScrollState()).padding(8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                            color = if (state.success || state.busy) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton({ vm.submitServerEditor(testOnly = true) }, enabled = !state.busy && state.savedId == null) { Text("测试连接") }
                            OutlinedButton({ vm.submitServerEditor() }, enabled = !state.busy && state.savedId == null) { Text("保存") }
                            if (original?.id != vm.repo.profiles.active()?.profile?.id || original == null || userChanged || (addressChanged && draft.preserve == false))
                                Button({ vm.submitServerEditor(activate = true) }, enabled = !state.busy && state.savedId == null) { Text("保存并切换") }
                            TextButton(vm::closeServerEditor, enabled = !state.committing) { Text(if (state.savedId != null) "返回服务器列表" else "取消") }
                        }
                    }
                }
            }
        }
        LaunchedEffect(state.errors) { state.errors.keys.firstNotNullOfOrNull { focus[it] }?.requestFocus() }
    }
}
