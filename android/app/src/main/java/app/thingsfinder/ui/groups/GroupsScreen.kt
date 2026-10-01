package app.thingsfinder.ui.groups

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.thingsfinder.R
import app.thingsfinder.domain.AppLink
import app.thingsfinder.domain.BoxLinks
import app.thingsfinder.domain.InviteKeys
import app.thingsfinder.platform.CodeScanner
import app.thingsfinder.platform.QrCodes
import app.thingsfinder.platform.ScanResult
import app.thingsfinder.platform.Sharing
import app.thingsfinder.sync.CloudState
import app.thingsfinder.sync.GroupInvite
import app.thingsfinder.sync.GroupMember
import app.thingsfinder.sync.RemoteGroup
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.common.containerFactory
import app.thingsfinder.ui.common.text
import app.thingsfinder.ui.components.ConfirmDialog
import app.thingsfinder.ui.components.ContentWidth
import app.thingsfinder.ui.components.EmptyState
import app.thingsfinder.ui.components.ErrorState
import app.thingsfinder.ui.components.LoadingState
import app.thingsfinder.ui.components.NameDialog
import app.thingsfinder.ui.components.QrImage
import app.thingsfinder.ui.components.ScreenPreviews
import app.thingsfinder.ui.components.SectionHeader
import app.thingsfinder.ui.components.pluralText
import app.thingsfinder.ui.theme.ThingsFinderTheme
import kotlinx.coroutines.launch

/** Dialogs on the groups screen. */
private sealed interface GroupDialog {
    data object Create : GroupDialog
    data object JoinLink : GroupDialog
    data object JoinKey : GroupDialog
    data class Rename(val group: RemoteGroup) : GroupDialog
    data class ResetInvite(val group: RemoteGroup) : GroupDialog
    data class Delete(val group: RemoteGroup) : GroupDialog
    data class Leave(val group: RemoteGroup) : GroupDialog
    data class RemoveMember(val groupId: Long, val member: GroupMember) : GroupDialog
}

/** What the content's buttons ask for; the screen opens dialogs or calls the ViewModel. */
private data class GroupsActions(
    val onSelect: (Long) -> Unit,
    val onSwitch: (RemoteGroup) -> Unit,
    val onDialog: (GroupDialog) -> Unit,
    val onScanInvite: () -> Unit,
    val onShareInvite: (RemoteGroup, GroupInvite) -> Unit,
    val onCopy: (String) -> Unit,
    val onRetry: () -> Unit,
)

/** [joinToken]: from an invite link or QR (thingsfinder://join/… or https://…/join/…) — offer to join it. */
@Composable
fun GroupsScreen(
    joinToken: String?,
    onBack: () -> Unit,
    vm: GroupsViewModel = viewModel(factory = containerFactory { GroupsViewModel(it.cloudSession, it.cloudApi, it.syncEngine) }),
) {
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    val groups by vm.groups.collectAsStateWithLifecycle()
    val selectedId by vm.selectedId.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val working by vm.working.collectAsStateWithLifecycle()
    val offerSwitch by vm.offerSwitch.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<GroupDialog?>(null) }
    var pendingJoin by rememberSaveable { mutableStateOf(joinToken) }
    LaunchedEffect(vm) { vm.messages.collect { snackbar.showSnackbar(it.resolve(context)) } }
    // Signing in elsewhere (or the first sync naming the group) — load again.
    LaunchedEffect(cloud.signedIn) { if (cloud.signedIn && groups !is UiState.Content) vm.refresh() }

    val copied = stringResource(R.string.action_copied)
    val shareTitle = stringResource(R.string.groups_share_invite)
    val notInvite = stringResource(R.string.groups_error_not_invite)
    val actions = GroupsActions(
        onSelect = vm::select,
        onSwitch = vm::switchTo,
        onDialog = { dialog = it },
        onScanInvite = {
            scope.launch {
                when (val r = CodeScanner.scanQr(context)) {
                    is ScanResult.Scanned -> vm.joinFromText(r.value)
                    is ScanResult.Failed -> snackbar.showSnackbar(r.message)
                    ScanResult.Cancelled -> Unit
                }
            }
        },
        onShareInvite = { group, invite ->
            Sharing.shareText(context, context.getString(R.string.groups_invite_message, group.name, invite.url, InviteKeys.format(invite.key)), shareTitle)
        },
        onCopy = { text ->
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("thingsFinder", text)))
                snackbar.showSnackbar(copied)
            }
        },
        onRetry = vm::refresh,
    )

    GroupsContent(
        cloud = cloud,
        activeId = cloud.activeGroup?.id ?: (groups as? UiState.Content)?.data?.defaultGroupId,
        groups = groups,
        selectedId = selectedId,
        detail = detail,
        working = working,
        snackbar = snackbar,
        onBack = onBack,
        actions = actions,
    )

    pendingJoin?.let { token ->
        if (cloud.signedIn) {
            ConfirmDialog(
                title = stringResource(R.string.groups_join_link_title),
                text = stringResource(R.string.groups_join_link_text),
                confirmLabel = stringResource(R.string.groups_join),
                destructive = false,
                onConfirm = {
                    pendingJoin = null
                    vm.joinWithToken(token)
                },
                onDismiss = { pendingJoin = null },
            )
        }
    }
    offerSwitch?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.groups_switch_title, group.name),
            text = stringResource(R.string.groups_switch_text),
            confirmLabel = stringResource(R.string.groups_switch),
            dismissLabel = stringResource(R.string.action_not_now),
            destructive = false,
            onConfirm = { vm.switchTo(group) },
            onDismiss = vm::dismissSwitchOffer,
        )
    }
    when (val d = dialog) {
        GroupDialog.Create -> NameDialog(
            title = stringResource(R.string.groups_create),
            label = stringResource(R.string.field_group_name),
            placeholder = stringResource(R.string.field_group_name_hint),
            confirmLabel = stringResource(R.string.action_add),
            onConfirm = { vm.create(it); dialog = null },
            onDismiss = { dialog = null },
        )
        GroupDialog.JoinLink -> JoinLinkDialog(
            onJoin = { text ->
                dialog = null
                vm.joinFromText(text)
            },
            notInvite = notInvite,
            onDismiss = { dialog = null },
        )
        GroupDialog.JoinKey -> JoinKeyDialog(
            onJoin = { name, key ->
                dialog = null
                vm.joinWithKey(name, key)
            },
            onDismiss = { dialog = null },
        )
        is GroupDialog.Rename -> NameDialog(
            title = stringResource(R.string.groups_rename),
            label = stringResource(R.string.field_group_name),
            initial = d.group.name,
            confirmLabel = stringResource(R.string.action_rename),
            onConfirm = { vm.rename(d.group.id, it); dialog = null },
            onDismiss = { dialog = null },
        )
        is GroupDialog.ResetInvite -> ConfirmDialog(
            title = stringResource(R.string.groups_reset_invite_title),
            text = stringResource(R.string.groups_reset_invite_text),
            confirmLabel = stringResource(R.string.groups_reset_invite),
            onConfirm = { vm.resetInvite(d.group.id); dialog = null },
            onDismiss = { dialog = null },
        )
        is GroupDialog.Delete -> ConfirmDialog(
            title = stringResource(R.string.groups_delete_title, d.group.name),
            text = stringResource(R.string.groups_delete_text),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = { vm.delete(d.group); dialog = null },
            onDismiss = { dialog = null },
        )
        is GroupDialog.Leave -> ConfirmDialog(
            title = stringResource(R.string.groups_leave_title, d.group.name),
            text = stringResource(R.string.groups_leave_text),
            confirmLabel = stringResource(R.string.groups_leave),
            onConfirm = { vm.leave(d.group); dialog = null },
            onDismiss = { dialog = null },
        )
        is GroupDialog.RemoveMember -> ConfirmDialog(
            title = stringResource(R.string.groups_remove_member_title, d.member.username),
            text = stringResource(R.string.groups_remove_member_text),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = { vm.removeMember(d.groupId, d.member); dialog = null },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupsContent(
    cloud: CloudState,
    activeId: Long?,
    groups: UiState<GroupsData>,
    selectedId: Long?,
    detail: UiState<GroupDetail>?,
    working: Boolean,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    actions: GroupsActions,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.groups_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (cloud.signedIn) {
                        IconButton(onClick = actions.onRetry, enabled = !working) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                !cloud.signedIn -> EmptyState(Icons.Outlined.Groups, stringResource(R.string.groups_sign_in_first))
                groups == UiState.Loading -> LoadingState()
                groups is UiState.Error -> ErrorState(groups.message.text(), onRetry = actions.onRetry)
                groups is UiState.Content -> ContentWidth {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "intro") {
                            Text(
                                stringResource(R.string.groups_intro),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item(key = "list-header") { SectionHeader(stringResource(R.string.groups_yours)) }
                        items(groups.data.groups, key = { "group-${it.id}" }) { g ->
                            GroupRow(g, active = g.id == activeId, selected = g.id == selectedId, onClick = { actions.onSelect(g.id) })
                        }
                        val selected = groups.data.groups.firstOrNull { it.id == selectedId }
                        if (selected != null) {
                            selectedSection(selected, (detail as? UiState.Content)?.data, detail, active = selected.id == activeId, working, actions)
                        }
                        item(key = "add") { AddGroupCard(working, actions) }
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun GroupRow(group: RemoteGroup, active: Boolean, selected: Boolean, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (active) Icons.Filled.CheckCircle else Icons.Outlined.Groups,
                contentDescription = if (active) stringResource(R.string.groups_active) else null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(groupSummary(group, active), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun groupSummary(group: RemoteGroup, active: Boolean): String = buildList {
    if (active) add(stringResource(R.string.groups_on_this_phone))
    add(pluralText(R.plurals.member_count, group.memberCount))
    add(stringResource(if (group.isOwner) R.string.groups_role_owner else R.string.groups_role_member))
    if (group.viewOnly) add(stringResource(R.string.groups_view_only))
}.joinToString(" · ")

/** The selected group: switch to it, its invite, its members, and what you may do with it. */
private fun LazyListScope.selectedSection(
    listed: RemoteGroup,
    loaded: GroupDetail?,
    detail: UiState<GroupDetail>?,
    active: Boolean,
    working: Boolean,
    actions: GroupsActions,
) {
    // Prefer the freshly fetched copy (it has the newest invite); the list's is fine meanwhile.
    val group = loaded?.group?.takeIf { it.id == listed.id } ?: listed
    item(key = "selected-header") { SectionHeader(group.name) }
    item(key = "selected-switch") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (active) {
                Text(stringResource(R.string.groups_active_text), style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(onClick = { actions.onSwitch(group) }, enabled = !working) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.groups_switch))
                }
                Text(
                    stringResource(R.string.groups_switch_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (group.viewOnly) {
                Text(stringResource(R.string.groups_view_only_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    group.invite?.let { invite ->
        item(key = "selected-invite") { InviteCard(group, invite, working, actions) }
    }
    item(key = "members-header") { SectionHeader(stringResource(R.string.groups_members)) }
    when {
        detail == null || detail == UiState.Loading -> item(key = "members-loading") { CircularProgressIndicator(Modifier.size(24.dp)) }
        detail is UiState.Error -> item(key = "members-error") {
            Text(detail.message.text(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        loaded != null -> items(loaded.members, key = { "member-${it.id}" }) { m ->
            MemberRow(m, canRemove = group.isOwner && m.role != RemoteGroup.OWNER && !working) {
                actions.onDialog(GroupDialog.RemoveMember(group.id, m))
            }
        }
    }
    item(key = "selected-actions") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (group.isOwner) {
                OutlinedButton(onClick = { actions.onDialog(GroupDialog.Rename(group)) }, enabled = !working) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_rename))
                }
                TextButton(onClick = { actions.onDialog(GroupDialog.Delete(group)) }, enabled = !working) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.groups_delete), color = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = { actions.onDialog(GroupDialog.Leave(group)) }, enabled = !working) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.groups_leave), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** Invite: QR (the https …/join/{token} link), the link itself, group name + key, share / copy, and a new invite for owners. */
@Composable
private fun InviteCard(group: RemoteGroup, invite: GroupInvite, working: Boolean, actions: GroupsActions) {
    val qr = remember(invite.url) { QrCodes.bitmap(invite.url, 360) }
    val key = InviteKeys.format(invite.key)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.groups_invite_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                QrImage(qr, stringResource(R.string.cd_invite_qr, group.name), Modifier.size(128.dp))
                Spacer(Modifier.width(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.groups_invite_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(stringResource(R.string.groups_invite_name, group.name), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        stringResource(R.string.groups_invite_key, key),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    )
                }
            }
            Text(invite.url, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.onShareInvite(group, invite) }) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_share))
                }
                OutlinedButton(onClick = { actions.onCopy(invite.url) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.groups_copy_link))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { actions.onCopy(key) }) {
                    Icon(Icons.Outlined.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.groups_copy_key))
                }
                if (group.isOwner) {
                    TextButton(onClick = { actions.onDialog(GroupDialog.ResetInvite(group)) }, enabled = !working) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.groups_reset_invite))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(member: GroupMember, canRemove: Boolean, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(member.username, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(
                    stringResource(if (member.role == RemoteGroup.OWNER) R.string.groups_role_owner else R.string.groups_role_member),
                    if (member.permission == RemoteGroup.VIEW) stringResource(R.string.groups_view_only) else null,
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (canRemove) {
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Outlined.PersonRemove,
                    contentDescription = stringResource(R.string.cd_remove_member, member.username),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun AddGroupCard(working: Boolean, actions: GroupsActions) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.groups_add_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.groups_add_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AddButton(Icons.Outlined.QrCodeScanner, R.string.groups_join_scan, working, actions.onScanInvite)
            AddButton(Icons.Outlined.Link, R.string.groups_join_paste, working) { actions.onDialog(GroupDialog.JoinLink) }
            AddButton(Icons.Outlined.Key, R.string.groups_join_key, working) { actions.onDialog(GroupDialog.JoinKey) }
            AddButton(Icons.Outlined.Add, R.string.groups_create, working) { actions.onDialog(GroupDialog.Create) }
        }
    }
}

@Composable
private fun AddButton(icon: ImageVector, label: Int, working: Boolean, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = !working, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(label), modifier = Modifier.weight(1f))
    }
}

/** Paste an invite link (https://…/join/… or thingsfinder://join/…). */
@Composable
private fun JoinLinkDialog(onJoin: (String) -> Unit, notInvite: String, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val valid = BoxLinks.parse(text) is AppLink.JoinGroup
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_join_paste)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.trim() },
                label = { Text(stringResource(R.string.field_invite_link)) },
                placeholder = { Text("https://thingsfinder.xyz/join/…") },
                supportingText = if (text.isNotEmpty() && !valid) ({ Text(notInvite) }) else null,
                isError = text.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onJoin(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onJoin(text) }, enabled = valid) { Text(stringResource(R.string.groups_join)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Group name + invite key ("K7F3-9QX2", dash and case optional). */
@Composable
private fun JoinKeyDialog(onJoin: (name: String, key: String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    val valid = name.isNotBlank() && InviteKeys.normalize(key) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.groups_join_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.groups_join_key_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_group_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { if (it.length <= 12) key = it.uppercase() },
                    label = { Text(stringResource(R.string.field_invite_key)) },
                    placeholder = { Text("K7F3-9QX2") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onJoin(name, key) }),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onJoin(name, key) }, enabled = valid) { Text(stringResource(R.string.groups_join)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

// ---- previews ------------------------------------------------------------

private val previewGroups = listOf(
    RemoteGroup(1, "tiago", RemoteGroup.OWNER, RemoteGroup.EDIT, 1, GroupInvite("https://thingsfinder.xyz/join/abcdef0123456789", "abcdef0123456789", "K7F39QX2")),
    RemoteGroup(2, "Family", RemoteGroup.MEMBER, RemoteGroup.EDIT, 4, GroupInvite("https://thingsfinder.xyz/join/0123456789abcdef", "0123456789abcdef", "AB12CD34")),
    RemoteGroup(3, "Club storage", RemoteGroup.MEMBER, RemoteGroup.VIEW, 12),
)

private val previewActions = GroupsActions({}, {}, {}, {}, { _, _ -> }, {}, {})

@ScreenPreviews
@Composable
private fun GroupsPreview() = ThingsFinderTheme {
    GroupsContent(
        cloud = CloudState(username = "tiago", token = "x"),
        activeId = 2,
        groups = UiState.Content(GroupsData(previewGroups, 1)),
        selectedId = 2,
        detail = UiState.Content(
            GroupDetail(previewGroups[1], listOf(GroupMember(1, "ana", RemoteGroup.OWNER), GroupMember(2, "tiago"), GroupMember(3, "rui", permission = RemoteGroup.VIEW))),
        ),
        working = false,
        snackbar = remember { SnackbarHostState() },
        onBack = {},
        actions = previewActions,
    )
}
