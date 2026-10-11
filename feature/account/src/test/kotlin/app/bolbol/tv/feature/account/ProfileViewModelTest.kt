package app.bolbol.tv.feature.account

import androidx.lifecycle.viewmodel.CreationExtras
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.LoginResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private val ALI = AccountProfile(
    username = "ali_r",
    email = "ali@example.com",
    displayName = "Ali Rezaei",
    vipDaysLeft = 23,
    vipExpire = "2026-11-02",
)

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Keeps the view model's state flow active (it only runs while someone collects it). */
    private fun TestScope.observe(vm: ProfileViewModel) {
        backgroundScope.launch(main) { vm.state.collect { } }
    }

    private fun viewModel(
        repo: FakeAccountRepo,
        scope: TestScope,
        profile: Flow<AccountProfile?> = flowOf(null),
    ) = ProfileViewModel(repo, profile).also { scope.observe(it) }

    // --- signed out / signed in -----------------------------------------------------------------------------

    @Test
    fun `signed out the state is loaded with the login form and no profile`() = runTest {
        val repo = FakeAccountRepo()
        val vm = ProfileViewModel(repo, flowOf(null))
        assertFalse(vm.state.value.loaded)
        observe(vm)

        val s = vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.loggedIn)
        assertNull(s.profile)
        assertEquals(LoginPhase.Idle, s.login)
        assertEquals(SyncPhase.Idle, s.sync)
        assertFalse(s.confirmingLogout)
        assertEquals(0, repo.syncs)
    }

    @Test
    fun `signed in the stored profile is shown and My List syncs once`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))

        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertEquals(ALI, s.profile)
        assertEquals(1, repo.syncs)
    }

    @Test
    fun `profile changes flow through, for example a VIP plan that ends`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val stored = MutableStateFlow<AccountProfile?>(ALI)
        val vm = viewModel(repo, this, stored)
        assertEquals(23, vm.state.value.profile?.vipDaysLeft)

        stored.value = ALI.copy(vipDaysLeft = null, vipExpire = null)
        assertNull(vm.state.value.profile?.vipDaysLeft)
        assertEquals("Ali Rezaei", vm.state.value.profile?.displayName)
    }

    @Test
    fun `the account name fills in when the stored profile has none yet`() = runTest {
        val repo = FakeAccountRepo(signedIn = true) // account state name: "Ali"
        val vm = viewModel(repo, this, flowOf(null))
        assertEquals(AccountProfile(displayName = "Ali"), vm.state.value.profile)

        val withoutName = ALI.copy(displayName = null)
        val vm2 = viewModel(repo, this, flowOf(withoutName))
        assertEquals(withoutName.copy(displayName = "Ali"), vm2.state.value.profile)
    }

    @Test
    fun `a stored profile is never shown while signed out`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this, flowOf(ALI)) // the flow may lag a moment behind the session
        assertFalse(vm.state.value.loggedIn)
        assertNull(vm.state.value.profile)
    }

    // --- login ----------------------------------------------------------------------------------------------

    @Test
    fun `successful login switches to the signed in view and syncs once`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this)
        assertEquals(0, repo.syncs)

        vm.login("  ali  ", "p@ss word")

        assertEquals(listOf("ali" to "p@ss word"), repo.logins) // username trimmed, password exactly as typed
        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertEquals("ali", s.profile?.displayName)
        assertEquals(LoginPhase.Idle, s.login)
        assertEquals(1, repo.syncs)
    }

    @Test
    fun `blank fields are rejected without a request`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this)
        vm.login("   ", "pw")
        assertEquals(LoginPhase.Error(MSG_CREDENTIALS_REQUIRED), vm.state.value.login)
        vm.login("ali", "")
        assertEquals(LoginPhase.Error(MSG_CREDENTIALS_REQUIRED), vm.state.value.login)
        assertTrue(repo.logins.isEmpty())
    }

    @Test
    fun `a password made of spaces is a password`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this)
        vm.login("ali", "   ")
        assertEquals(listOf("ali" to "   "), repo.logins)
    }

    @Test
    fun `wrong credentials show the Persian message and stay signed out`() = runTest {
        val repo = FakeAccountRepo().apply { loginResult = LoginResult.WrongCredentials }
        val vm = viewModel(repo, this)
        vm.login("ali", "bad")
        assertEquals(LoginPhase.Error(MSG_WRONG_CREDENTIALS), vm.state.value.login)
        assertFalse(vm.state.value.loggedIn)
        assertEquals(0, repo.syncs)
    }

    @Test
    fun `network failures show the repository's message, or a generic one`() = runTest {
        val repo = FakeAccountRepo().apply { loginResult = LoginResult.Failed("اتصال اینترنت برقرار نیست") }
        val vm = viewModel(repo, this)
        vm.login("ali", "pw")
        assertEquals(LoginPhase.Error("اتصال اینترنت برقرار نیست"), vm.state.value.login)

        repo.loginResult = LoginResult.Failed("")
        vm.login("ali", "pw")
        assertEquals(LoginPhase.Error(MSG_LOGIN_FAILED), vm.state.value.login)
    }

    @Test
    fun `a second press while a login runs is ignored`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeAccountRepo().apply { loginGate = gate }
        val vm = viewModel(repo, this)

        vm.login("ali", "pw")
        assertEquals(LoginPhase.Submitting, vm.state.value.login)
        vm.login("ali", "pw")
        vm.login("ali", "pw")
        assertEquals(1, repo.logins.size)

        gate.complete(Unit)
        assertEquals(LoginPhase.Idle, vm.state.value.login)
        assertTrue(vm.state.value.loggedIn)
    }

    @Test
    fun `an unexpected exception never leaves the form stuck`() = runTest {
        val repo = FakeAccountRepo().apply { loginThrows = IllegalStateException("boom") }
        val vm = viewModel(repo, this)
        vm.login("ali", "pw")
        assertEquals(LoginPhase.Error(MSG_LOGIN_FAILED), vm.state.value.login)

        repo.loginThrows = null
        vm.login("ali", "pw") // can try again
        assertTrue(vm.state.value.loggedIn)
    }

    @Test
    fun `typing clears an error but not a running login`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repo = FakeAccountRepo().apply { loginResult = LoginResult.WrongCredentials }
        val vm = viewModel(repo, this)
        vm.login("ali", "bad")
        assertTrue(vm.state.value.login is LoginPhase.Error)
        vm.onInputChanged()
        assertEquals(LoginPhase.Idle, vm.state.value.login)

        repo.loginGate = gate
        vm.login("ali", "bad")
        vm.onInputChanged()
        assertEquals(LoginPhase.Submitting, vm.state.value.login)
        gate.complete(Unit)
    }

    // --- logout confirmation --------------------------------------------------------------------------------

    @Test
    fun `logout asks first, and cancel keeps the session`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        assertFalse(vm.state.value.confirmingLogout)

        vm.requestLogout()
        assertTrue(vm.state.value.confirmingLogout)
        assertEquals(0, repo.logouts) // nothing happens before the answer

        vm.cancelLogout()
        assertFalse(vm.state.value.confirmingLogout)
        assertTrue(vm.state.value.loggedIn)
        assertEquals(0, repo.logouts)
    }

    @Test
    fun `confirming signs out, closes the dialog and brings the login form back`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        repo.list.value = listOf(title(7))
        val vm = viewModel(repo, this, flowOf(ALI))

        vm.requestLogout()
        vm.confirmLogout()

        assertEquals(1, repo.logouts)
        val s = vm.state.value
        assertFalse(s.loggedIn)
        assertNull(s.profile)
        assertFalse(s.confirmingLogout)
        assertEquals(listOf(7L), repo.list.value.map { it.id }) // the local My List stays
    }

    @Test
    fun `confirming without the dialog open does nothing, and a double confirm logs out once`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))

        vm.confirmLogout()
        assertEquals(0, repo.logouts)
        assertTrue(vm.state.value.loggedIn)

        vm.requestLogout()
        vm.confirmLogout()
        vm.confirmLogout()
        assertEquals(1, repo.logouts)
    }

    @Test
    fun `a failing logout closes the dialog and keeps the session`() = runTest {
        val repo = FakeAccountRepo(signedIn = true).apply { logoutThrows = IllegalStateException("disk") }
        val vm = viewModel(repo, this, flowOf(ALI))
        vm.requestLogout()
        vm.confirmLogout()
        assertEquals(1, repo.logouts)
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.confirmingLogout)
    }

    @Test
    fun `a session that ends while the dialog is open never brings the dialog back`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        vm.requestLogout()

        repo.accountState.value = AccountState(false) // expired on the server side
        assertFalse(vm.state.value.confirmingLogout)

        vm.login("ali", "pw")
        assertTrue(vm.state.value.loggedIn)
        assertFalse(vm.state.value.confirmingLogout)
    }

    @Test
    fun `logging in again after a logout works and syncs again`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        vm.requestLogout()
        vm.confirmLogout()
        assertEquals(1, repo.syncs)

        vm.login("ali", "pw")
        assertTrue(vm.state.value.loggedIn)
        assertEquals(2, repo.syncs)
    }

    // --- manual sync ----------------------------------------------------------------------------------------

    @Test
    fun `sync again shows progress, then done, which clears itself`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        val gate = CompletableDeferred<Unit>()
        repo.syncGate = gate
        val before = repo.syncs

        vm.syncNow()
        assertEquals(SyncPhase.Syncing, vm.state.value.sync)
        assertEquals(before + 1, repo.syncs)

        gate.complete(Unit)
        assertEquals(SyncPhase.Done, vm.state.value.sync)

        advanceTimeBy(SYNC_DONE_VISIBLE_MS - 1)
        assertEquals(SyncPhase.Done, vm.state.value.sync)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SyncPhase.Idle, vm.state.value.sync)
    }

    @Test
    fun `a press while syncing is ignored`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        val gate = CompletableDeferred<Unit>()
        repo.syncGate = gate
        val before = repo.syncs

        vm.syncNow()
        vm.syncNow()
        vm.syncNow()
        assertEquals(before + 1, repo.syncs)
        gate.complete(Unit)
    }

    @Test
    fun `a failed sync shows the failure until the next try succeeds`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        repo.syncThrows = IllegalStateException("offline")

        vm.syncNow()
        assertEquals(SyncPhase.Failed, vm.state.value.sync)
        advanceTimeBy(SYNC_DONE_VISIBLE_MS * 3) // an error does not clear itself
        assertEquals(SyncPhase.Failed, vm.state.value.sync)

        repo.syncThrows = null
        vm.syncNow()
        assertEquals(SyncPhase.Done, vm.state.value.sync)
    }

    @Test
    fun `signing out wipes the sync result`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this, flowOf(ALI))
        vm.syncNow()
        assertEquals(SyncPhase.Done, vm.state.value.sync)

        repo.accountState.value = AccountState(false)
        assertEquals(SyncPhase.Idle, vm.state.value.sync)
        repo.accountState.value = AccountState(true, "ali")
        assertEquals(SyncPhase.Idle, vm.state.value.sync)
    }

    // --- texts ----------------------------------------------------------------------------------------------

    @Test
    fun `vip label and status line`() {
        assertEquals("اشتراک ویژه: ۲۳ روز مانده", vipLabel(23))
        assertEquals("اشتراک ویژه: ۱ روز مانده", vipLabel(1))
        assertNull(vipLabel(0))
        assertNull(vipLabel(-4))
        assertNull(vipLabel(null))

        assertEquals("اشتراک ویژه: ۲۳ روز مانده", vipStatusText(23))
        assertEquals("بدون اشتراک ویژه", vipStatusText(null))
        assertEquals("بدون اشتراک ویژه", vipStatusText(0))
    }

    @Test
    fun `the avatar shows the first letter of the name`() {
        assertEquals("A", avatarInitial("ali"))
        assertEquals("A", avatarInitial("  Ali Rezaei"))
        assertEquals("ع", avatarInitial("علی رضایی"))
        assertEquals("۳", avatarInitial("۳۳ نفر"))
        assertEquals("A", avatarInitial("@ali")) // skips symbols
        assertEquals("ب", avatarInitial(null))
        assertEquals("ب", avatarInitial("   "))
        assertEquals("ب", avatarInitial("@#!"))
    }

    @Test
    fun `the shown name is the display name, then the username, then the e-mail`() {
        assertEquals("Ali Rezaei", ALI.shownName)
        assertEquals("ali_r", ALI.copy(displayName = "  ").shownName)
        assertEquals("ali_r", ALI.copy(displayName = null).shownName)
        assertEquals("ali@example.com", ALI.copy(displayName = null, username = null).shownName)
        assertNull(AccountProfile().shownName)
    }

    @Test
    fun `factory builds a view model`() {
        val vm = ProfileViewModel.factory(FakeAccountRepo(), flowOf(null))
            .create(ProfileViewModel::class.java, CreationExtras.Empty)
        assertFalse(vm.state.value.loaded)
    }
}
