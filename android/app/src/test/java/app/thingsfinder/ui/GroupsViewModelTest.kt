package app.thingsfinder.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.thingsfinder.R
import app.thingsfinder.data.InventoryRepository
import app.thingsfinder.data.db.ThingsFinderDatabase
import app.thingsfinder.data.inMemoryDb
import app.thingsfinder.sync.ActiveGroup
import app.thingsfinder.sync.ApiResult
import app.thingsfinder.sync.CloudState
import app.thingsfinder.sync.FakeCloudApi
import app.thingsfinder.sync.FakeSession
import app.thingsfinder.sync.GroupInvite
import app.thingsfinder.sync.RemoteGroup
import app.thingsfinder.sync.SyncEngine
import app.thingsfinder.sync.SyncGroup
import app.thingsfinder.sync.SyncResponse
import app.thingsfinder.ui.common.UiState
import app.thingsfinder.ui.groups.GroupsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = android.app.Application::class)
class GroupsViewModelTest {
    private lateinit var db: ThingsFinderDatabase
    private lateinit var api: FakeCloudApi
    private lateinit var session: FakeSession
    private lateinit var vm: GroupsViewModel
    private val family = RemoteGroup(2, "Family", invite = GroupInvite("https://example.test/join/famfamfam", "famfamfam", "AB12CD34"))

    @Before fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = inMemoryDb()
        api = FakeCloudApi { req -> ApiResult.Success(SyncResponse(serverTime = 1, group = SyncGroup(req.groupId ?: api.defaultGroupId, "g"))) }
        api.joinable += family
        session = FakeSession(CloudState(serverUrl = "https://example.test", username = "tiago", token = "tok", activeGroup = ActiveGroup(1, "tiago")))
        vm = GroupsViewModel(session, api, SyncEngine(db, api, session))
    }

    @After fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun <T> kotlinx.coroutines.flow.Flow<T>.firstMatching(predicate: (T) -> Boolean): T = withTimeout(5_000) { first(predicate) }

    @Test fun `lists groups and selects the active one`() = runBlocking {
        val groups = vm.groups.firstMatching { it is UiState.Content } as UiState.Content
        assertEquals(listOf("tiago"), groups.data.groups.map { it.name })
        assertEquals(1L, vm.selectedId.value)
        assertTrue(vm.detail.firstMatching { it is UiState.Content } is UiState.Content)
    }

    @Test fun `joining by key accepts the dashed lower-case form and offers to switch`() = runBlocking {
        vm.groups.firstMatching { it is UiState.Content }
        vm.joinWithKey(" Family ", "ab12-cd34")
        assertEquals(family, vm.offerSwitch.firstMatching { it != null })
        assertEquals(R.string.groups_joined, vm.messages.first().res)
        vm.switchTo(family)
        assertEquals(ActiveGroup(2, "g"), session.flow.firstMatching { it.activeGroup?.id == 2L }.activeGroup)
        assertEquals(listOf(1L, 2L), api.requests.map { it.groupId })
    }

    @Test fun `a bad link or unknown invite is an error, nothing joined`() = runBlocking {
        vm.groups.firstMatching { it is UiState.Content }
        vm.joinFromText("https://example.test/view/0123456789abcdef")
        assertEquals(R.string.groups_error_not_invite, vm.messages.first().res)
        vm.joinFromText("thingsfinder://join/nobodyhasthis")
        assertEquals(R.string.groups_error_invite_not_found, vm.messages.first().res)
        assertEquals(1, api.groups.size)
    }

    @Test fun `leaving the group on this phone switches away first`() = runBlocking {
        api.groups += family
        session.setActiveGroup(ActiveGroup(2, "Family"))
        InventoryRepository(db).createPlace("Family garage")
        vm.groups.firstMatching { it is UiState.Content && it.data.groups.size == 2 }
        vm.leave(family)
        session.flow.firstMatching { it.activeGroup?.id == 1L }
        vm.working.firstMatching { !it }
        assertEquals(listOf("leave 2"), api.calls)
        // Pushed to Family, then pulled the default group.
        assertEquals(listOf(2L, 1L), api.requests.map { it.groupId })
        assertTrue(db.placeDao().getAll().isEmpty())
    }

    @Test fun `if pending changes can't be sent, leaving is refused`() = runBlocking {
        api.groups += family
        session.setActiveGroup(ActiveGroup(2, "Family"))
        api.next = { ApiResult.NetworkError(IOException("offline")) }
        vm.groups.firstMatching { it is UiState.Content && it.data.groups.size == 2 }
        vm.leave(family)
        assertEquals(R.string.groups_switch_refused, vm.messages.first().res)
        assertTrue(api.calls.isEmpty())
        assertEquals(2L, session.flow.value.activeGroup?.id)
    }
}
