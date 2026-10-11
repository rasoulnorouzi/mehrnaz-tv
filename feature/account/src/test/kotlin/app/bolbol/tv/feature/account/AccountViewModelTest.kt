package app.bolbol.tv.feature.account

import androidx.lifecycle.viewmodel.CreationExtras
import app.bolbol.tv.core.model.AccountState
import app.bolbol.tv.core.model.TitleSummary
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
import org.junit.Assert.assertNull
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

    // ---- QA-01: Back from details restores the card that was opened ----

    @Test
    fun `the focused card is remembered for Back from details`() = runTest {
        val vm = viewModel(FakeAccountRepo(), this)
        assertNull(vm.lastFocusedId)

        vm.onCardFocused(7, index = 2)
        vm.onCardFocused(9, index = 4)

        assertEquals(9L, vm.lastFocusedId)
        assertEquals(4, vm.lastFocusedIndex)
    }

    private val list = listOf(title(10), title(11), title(12), title(13))

    @Test
    fun `focus starts on the first card when nothing was focused before`() {
        assertEquals(0, list.initialFocusIndex(null))
    }

    @Test
    fun `focus returns to the card that was opened`() {
        assertEquals(2, list.initialFocusIndex(12, rememberedIndex = 2))
        // It moved in the list meanwhile (a sync added a title before it): follow the id, not the old position.
        assertEquals(3, (listOf(title(99)) + list.take(3)).initialFocusIndex(12, rememberedIndex = 2))
    }

    @Test
    fun `a card removed meanwhile hands focus to the one that slid into its place`() {
        val without12 = listOf(title(10), title(11), title(13))
        assertEquals(2, without12.initialFocusIndex(12, rememberedIndex = 2)) // 13 took its place
        // The removed card was the last one: the new last card.
        assertEquals(2, without12.initialFocusIndex(14, rememberedIndex = 3))
    }

    @Test
    fun `an empty list has no card to focus`() {
        assertEquals(-1, emptyList<TitleSummary>().initialFocusIndex(12, rememberedIndex = 2))
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
