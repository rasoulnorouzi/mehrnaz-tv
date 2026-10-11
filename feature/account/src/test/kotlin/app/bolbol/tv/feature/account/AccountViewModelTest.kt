package app.bolbol.tv.feature.account

import androidx.lifecycle.viewmodel.CreationExtras
import app.bolbol.tv.core.model.AccountRepository
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.LoginResult
import app.bolbol.tv.core.model.TitleSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun title(id: Long) = TitleSummary(
    id = id, type = null, title = "Title $id", faTitle = "", posterUrl = null, backdropUrl = null,
    year = null, imdbRating = null, hasDub = false, hasSubtitle = false,
)

private class FakeAccountRepo(signedIn: Boolean = false) : AccountRepository {
    val accountState = MutableStateFlow(AccountState(signedIn, if (signedIn) "Ali" else null))
    val list = MutableStateFlow<List<TitleSummary>>(emptyList())
    var loginResult: LoginResult = LoginResult.Success
    var loginGate: CompletableDeferred<Unit>? = null
    var loginThrows: Throwable? = null
    val logins = mutableListOf<Pair<String, String>>()
    var syncs = 0
    var logouts = 0

    override val state: Flow<AccountState> = accountState

    override suspend fun login(username: String, password: String): LoginResult {
        logins += username to password
        loginGate?.await()
        loginThrows?.let { throw it }
        if (loginResult == LoginResult.Success) accountState.value = AccountState(true, username)
        return loginResult
    }

    override suspend fun logout() {
        logouts++
        accountState.value = AccountState(false)
    }

    override fun myList(): Flow<List<TitleSummary>> = list
    override fun isInMyList(postId: Long): Flow<Boolean> = flowOf(false)
    override suspend fun setInMyList(title: TitleSummary, inList: Boolean) {}
    override suspend fun syncMyList() {
        syncs++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class AccountViewModelTest {

    private val main = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(main)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Keeps the view model's state flow active (it only runs while someone collects it). */
    private fun TestScope.observe(vm: AccountViewModel) {
        backgroundScope.launch(main) { vm.state.collect { } }
    }

    private fun viewModel(
        repo: FakeAccountRepo,
        vip: Flow<Int?> = flowOf(null),
        scope: TestScope,
    ) = AccountViewModel(repo, vip).also { scope.observe(it) }

    @Test
    fun `state is loaded once the account and the list were read`() = runTest {
        val repo = FakeAccountRepo()
        repo.list.value = listOf(title(1), title(2))
        val vm = AccountViewModel(repo)
        assertFalse(vm.state.value.loaded)
        observe(vm)
        val s = vm.state.value
        assertTrue(s.loaded)
        assertFalse(s.loggedIn)
        assertEquals(listOf(1L, 2L), s.myList.map { it.id })
        assertEquals(LoginPhase.Idle, s.login)
    }

    @Test
    fun `successful login signs in and syncs once`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, scope = this)
        assertEquals(0, repo.syncs)

        vm.login("  ali  ", "p@ss word")

        assertEquals(listOf("ali" to "p@ss word"), repo.logins) // username trimmed, password exactly as typed
        val s = vm.state.value
        assertTrue(s.loggedIn)
        assertEquals("ali", s.displayName)
        assertEquals(LoginPhase.Idle, s.login)
        assertEquals(1, repo.syncs)
    }

    @Test
    fun `blank fields are rejected without a request`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, scope = this)
        vm.login("   ", "pw")
        assertEquals(LoginPhase.Error(MSG_CREDENTIALS_REQUIRED), vm.state.value.login)
        vm.login("ali", "")
        assertEquals(LoginPhase.Error(MSG_CREDENTIALS_REQUIRED), vm.state.value.login)
        assertTrue(repo.logins.isEmpty())
    }

    @Test
    fun `a password made of spaces is a password`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, scope = this)
        vm.login("ali", "   ")
        assertEquals(listOf("ali" to "   "), repo.logins)
    }

    @Test
    fun `wrong credentials show the Persian message and stay signed out`() = runTest {
        val repo = FakeAccountRepo().apply { loginResult = LoginResult.WrongCredentials }
        val vm = viewModel(repo, scope = this)
        vm.login("ali", "bad")
        assertEquals(LoginPhase.Error(MSG_WRONG_CREDENTIALS), vm.state.value.login)
        assertFalse(vm.state.value.loggedIn)
        assertEquals(0, repo.syncs)
    }

    @Test
    fun `network failures show the repository's message, or a generic one`() = runTest {
        val repo = FakeAccountRepo().apply { loginResult = LoginResult.Failed("اتصال اینترنت برقرار نیست") }
        val vm = viewModel(repo, scope = this)
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
        val vm = viewModel(repo, scope = this)

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
        val vm = viewModel(repo, scope = this)
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
        val vm = viewModel(repo, scope = this)
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

    @Test
    fun `an already signed in user syncs when the screen opens`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, scope = this)
        assertTrue(vm.state.value.loggedIn)
        assertEquals("Ali", vm.state.value.displayName)
        assertEquals(1, repo.syncs)
    }

    @Test
    fun `logout signs out and every new login syncs again`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, scope = this)
        vm.logout()
        assertEquals(1, repo.logouts)
        assertFalse(vm.state.value.loggedIn)
        assertEquals(1, repo.syncs)

        vm.login("ali", "pw")
        assertEquals(2, repo.syncs)
    }

    @Test
    fun `the local list is part of the state signed out and signed in`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, scope = this)
        repo.list.value = listOf(title(5))
        assertEquals(listOf(5L), vm.state.value.myList.map { it.id })
        vm.login("ali", "pw")
        repo.list.value = listOf(title(6), title(5))
        assertEquals(listOf(6L, 5L), vm.state.value.myList.map { it.id })
    }

    @Test
    fun `vip days are shown only while signed in`() = runTest {
        val repo = FakeAccountRepo()
        val vip = MutableStateFlow<Int?>(23)
        val vm = viewModel(repo, vip, this)
        assertNull(vm.state.value.vipDaysLeft)
        vm.login("ali", "pw")
        assertEquals(23, vm.state.value.vipDaysLeft)
        vm.logout()
        assertNull(vm.state.value.vipDaysLeft)
    }

    @Test
    fun `vip label`() {
        assertEquals("اشتراک ویژه: ۲۳ روز مانده", vipLabel(23))
        assertEquals("اشتراک ویژه: ۱ روز مانده", vipLabel(1))
        assertNull(vipLabel(0))
        assertNull(vipLabel(-4))
        assertNull(vipLabel(null))
    }

    @Test
    fun `factory builds a view model`() {
        val vm = AccountViewModel.factory(FakeAccountRepo()).create(AccountViewModel::class.java, CreationExtras.Empty)
        assertFalse(vm.state.value.loaded)
    }
}
