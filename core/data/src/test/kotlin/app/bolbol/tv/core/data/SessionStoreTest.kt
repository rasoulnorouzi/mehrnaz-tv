package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.AccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreTest {

    @Test
    fun `construction does no I-O and the first read loads the stored session`() {
        val storage = InMemorySessionStorage(StoredSession("tok", "Ali", 3))
        val store = SessionStore(storage)
        assertEquals(0, storage.loads)
        assertEquals("tok", store.token())
        store.token()
        assertEquals(1, storage.loads)
        assertEquals(3, store.vipDaysLeft.value)
    }

    @Test
    fun `signed out by default`() {
        val store = SessionStore(InMemorySessionStorage())
        assertNull(store.token())
        assertFalse(store.isLoggedIn)
        store.ensureLoaded()
        assertEquals(AccountState(false), store.state.value)
    }

    @Test
    fun `sign in persists and publishes, sign out forgets`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.signIn("tok", "Ali", 7)
        assertEquals("tok", storage.stored!!.token)
        assertEquals(AccountState(true, "Ali"), store.state.value)
        assertEquals(7, store.vipDaysLeft.value)

        store.signOut()
        assertNull(storage.stored)
        assertNull(store.token())
        assertEquals(AccountState(false), store.state.value)
        assertNull(store.vipDaysLeft.value)
    }

    @Test
    fun `a profile refresh keeps the token and ignores a signed out store`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.updateProfile("Nobody", 1)
        assertNull(storage.stored)

        store.signIn("tok", "Ali", null)
        store.updateProfile(null, 9) // no new name: keep the old one
        assertEquals(StoredSession("tok", "Ali", 9), storage.stored)
        assertEquals(AccountState(true, "Ali"), store.state.value)

        val writes = storage.saves
        store.updateProfile("Ali", 9) // nothing changed: no disk write
        assertEquals(writes, storage.saves)
    }

    @Test
    fun `the token never appears in toString`() {
        val text = StoredSession("SECRET-TOKEN", "Ali").toString()
        assertFalse(text.contains("SECRET-TOKEN"))
        assertTrue(text.contains("Ali"))
    }
}
