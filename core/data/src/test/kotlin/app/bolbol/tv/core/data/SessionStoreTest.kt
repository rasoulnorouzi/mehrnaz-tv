package app.bolbol.tv.core.data

import app.bolbol.tv.core.model.AccountState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A profile with only the given fields set. */
private fun details(
    displayName: String? = null,
    vipDaysLeft: Int? = null,
    username: String? = null,
    email: String? = null,
    vipExpire: String? = null,
    vipExpirePercent: Int? = null,
    vipStatus: String? = null,
) = AccountProfileDetails(username, email, displayName, vipDaysLeft, vipExpire, vipExpirePercent, vipStatus)

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
        assertNull(store.profile.value)
    }

    @Test
    fun `sign in persists and publishes, sign out forgets`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.signIn("tok", details("Ali", vipDaysLeft = 7))
        assertEquals("tok", storage.stored!!.token)
        assertEquals(AccountState(true, "Ali"), store.state.value)
        assertEquals(7, store.vipDaysLeft.value)

        store.signOut()
        assertNull(storage.stored)
        assertNull(store.token())
        assertEquals(AccountState(false), store.state.value)
        assertNull(store.vipDaysLeft.value)
        assertNull(store.profile.value)
    }

    @Test
    fun `a profile refresh keeps the token and ignores a signed out store`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.updateProfile(details("Nobody", vipDaysLeft = 1))
        assertNull(storage.stored)

        store.signIn("tok", details("Ali"))
        store.updateProfile(details(vipDaysLeft = 9)) // no new name: keep the old one
        assertEquals(StoredSession("tok", "Ali", 9), storage.stored)
        assertEquals(AccountState(true, "Ali"), store.state.value)

        val writes = storage.saves
        store.updateProfile(details("Ali", vipDaysLeft = 9)) // nothing changed: no disk write
        assertEquals(writes, storage.saves)
    }

    @Test
    fun `the token never appears in toString`() {
        val text = StoredSession("SECRET-TOKEN", "Ali", email = "a@b.c").toString()
        assertFalse(text.contains("SECRET-TOKEN"))
        assertTrue(text.contains("Ali"))
    }

    // --- the full profile -------------------------------------------------------------------------------------

    private val full = AccountProfileDetails(
        username = "ali_r",
        email = "ali@example.com",
        displayName = "Ali R",
        vipDaysLeft = 23,
        vipExpire = "2026-11-03",
        vipExpirePercent = 74,
        vipStatus = "active",
    )

    @Test
    fun `every profile field is published, persisted and restored after a restart`() {
        val storage = InMemorySessionStorage()
        val store = SessionStore(storage)
        store.ensureLoaded()
        assertNull(store.profile.value)

        store.signIn("tok", full)
        assertEquals(full, store.profile.value)
        assertEquals(
            StoredSession("tok", "Ali R", 23, "ali_r", "ali@example.com", "2026-11-03", 74, "active"),
            storage.stored,
        )

        val restarted = SessionStore(storage)
        restarted.ensureLoaded()
        assertEquals(full, restarted.profile.value)
        assertEquals("tok", restarted.token())
    }

    @Test
    fun `nothing is split out of what the user typed, missing fields stay null`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("tok", details("ali@example.com"))
        assertEquals(details("ali@example.com"), store.profile.value)
        assertNull(store.profile.value!!.username)
        assertNull(store.profile.value!!.email)
    }

    @Test
    fun `a refresh fills in missing fields and never erases a known one`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("tok", details("Ali", username = "ali_r"))

        store.updateProfile(details(email = "ali@example.com"))
        assertEquals(details("Ali", username = "ali_r", email = "ali@example.com"), store.profile.value)

        // An answer that lacks the name, username and e-mail keeps them.
        store.updateProfile(details())
        assertEquals(details("Ali", username = "ali_r", email = "ali@example.com"), store.profile.value)

        // A new value replaces the old one.
        store.updateProfile(details("Ali New", username = "ali_new"))
        assertEquals(details("Ali New", username = "ali_new", email = "ali@example.com"), store.profile.value)
    }

    @Test
    fun `the vip plan is replaced as a group, so an ended plan leaves nothing behind`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("tok", full)

        store.updateProfile(details(vipStatus = "expired", vipExpire = "2026-01-01", vipExpirePercent = 100))
        val p = store.profile.value!!
        assertNull(p.vipDaysLeft)
        assertEquals("2026-01-01", p.vipExpire)
        assertEquals(100, p.vipExpirePercent)
        assertEquals("expired", p.vipStatus)
        assertEquals("ali_r", p.username) // identity is untouched

        store.updateProfile(details())
        assertEquals(details("Ali R", username = "ali_r", email = "ali@example.com"), store.profile.value)
    }

    @Test
    fun `the profile never shows the token`() {
        val store = SessionStore(InMemorySessionStorage())
        store.signIn("SECRET-TOKEN", full)
        assertFalse(store.profile.value.toString().contains("SECRET-TOKEN"))
    }
}
