package app.bolbol.tv.feature.account

import androidx.lifecycle.viewmodel.CreationExtras
import app.bolbol.tv.core.model.AccountState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

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

    private fun viewModel(repo: FakeAccountRepo, scope: TestScope) = AccountViewModel(repo).also { scope.observe(it) }

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
    }

    @Test
    fun `an already signed in user syncs when the screen opens`() = runTest {
        val repo = FakeAccountRepo(signedIn = true)
        val vm = viewModel(repo, this)
        assertTrue(vm.state.value.loggedIn)
        assertEquals(1, repo.syncs)
    }

    @Test
    fun `a signed out user does not sync, and every new sign in syncs again`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this)
        assertEquals(0, repo.syncs)
        assertFalse(vm.state.value.loggedIn)

        repo.accountState.value = AccountState(true, "ali") // signed in on the Profile screen
        assertTrue(vm.state.value.loggedIn)
        assertEquals(1, repo.syncs)

        repo.accountState.value = AccountState(false)
        assertFalse(vm.state.value.loggedIn)
        repo.accountState.value = AccountState(true, "ali")
        assertEquals(2, repo.syncs)
    }

    @Test
    fun `the local list is part of the state signed out and signed in`() = runTest {
        val repo = FakeAccountRepo()
        val vm = viewModel(repo, this)
        repo.list.value = listOf(title(5))
        assertEquals(listOf(5L), vm.state.value.myList.map { it.id })
        repo.accountState.value = AccountState(true, "ali")
        repo.list.value = listOf(title(6), title(5))
        assertEquals(listOf(6L, 5L), vm.state.value.myList.map { it.id })
    }

    @Test
    fun `factory builds a view model, with or without the old vip flow`() {
        val repo = FakeAccountRepo()
        val plain = AccountViewModel.factory(repo).create(AccountViewModel::class.java, CreationExtras.Empty)
        assertFalse(plain.state.value.loaded)
        val withVip = AccountViewModel.factory(repo, flowOf(23)).create(AccountViewModel::class.java, CreationExtras.Empty)
        assertFalse(withVip.state.value.loaded)
    }
}
