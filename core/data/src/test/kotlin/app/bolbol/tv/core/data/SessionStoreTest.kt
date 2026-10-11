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
        val text = StoredSession("SECRET-TOKEN", "Ali", email = "a@b.c").toString()
        assertFalse(text.contains("SECRET-TOKEN"))
        assertTrue(text.contains("Ali"))
    }

    @Test
    fun `the login name becomes the username or the email`() {
        assertEquals(null to null, identityOf(null))
        assertEquals(null to null, identityOf("   "))
        assertEquals("ali_r" to null, identityOf("  ali_r "))
        assertEquals(null to "ali@example.com", identityOf("ali@example.com"))
    }

    @Test
    fun `the profile is published with the session, persisted and cleared on sign out`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.ensureLoaded()
        assertNull(store.profile.value)

        store.signIn("tok", "Ali R", 23, loginName = "ali_r")
        val expected = AccountProfileDetails(
            username = "ali_r", email = null, displayName = "Ali R", vipDaysLeft = 23, vipExpire = null,
        )
        assertEquals(expected, store.profile.value)
        assertEquals("ali_r", storage.stored!!.username)

        // A fresh store reading the same storage (app restart) sees the same profile.
        val restarted = SessionStore(storage)
        restarted.ensureLoaded()
        assertEquals(expected, restarted.profile.value)

        store.signOut()
        assertNull(store.profile.value)
        assertNull(storage.stored)
    }

    @Test
    fun `an email login fills the email and server fields win over the typed name`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("tok", "Ali", null, loginName = "ali@example.com")
        assertEquals(AccountProfileDetails(null, "ali@example.com", "Ali", null, null), store.profile.value)

        store.signIn("tok", "Ali", 5, loginName = "ali@example.com", username = "ali_r", vipExpire = "2026-11-02")
        assertEquals(
            AccountProfileDetails("ali_r", "ali@example.com", "Ali", 5, "2026-11-02"),
            store.profile.value,
        )
    }

    @Test
    fun `a profile refresh keeps the username and email and replaces the plan`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("tok", "Ali", 5, loginName = "ali_r", vipExpire = "2026-11-02")

        store.updateProfile("Ali New", null)
        assertEquals(AccountProfileDetails("ali_r", null, "Ali New", null, null), store.profile.value)

        store.updateProfile(null, 9, vipExpire = "2027-01-01", email = "ali@example.com")
        assertEquals(AccountProfileDetails("ali_r", "ali@example.com", "Ali New", 9, "2027-01-01"), store.profile.value)
    }
}
