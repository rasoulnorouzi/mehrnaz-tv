package app.bolbol.tv.core.network

import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plausible fake payloads for the account responses: the confirmed shape plus the variants the readers tolerate. */
class AccountParsingTest {

    private fun json(text: String): JsonElement = requireNotNull(AccountParsing.parseJson(text)) { "not JSON: $text" }

    // --- token ---------------------------------------------------------------------------------------

    @Test
    fun `confirmed login envelope gives data token`() {
        val root = json(
            """{"success":true,"data":{"id":7,"username":"ali","email":"a@b.c","display_name":"Ali R","token":"tok-123",
               "vip":{"days_left":23,"expire":"2026-11-03","expire_percent":"74","status":"active"},"show":{}},"message":"ok"}""",
        )
        assertEquals("tok-123", AccountParsing.findToken(root))
    }

    @Test
    fun `token key variants are found`() {
        val variants = listOf(
            """{"token":"t1"}""",
            """{"success":true,"data":{"token":"t1"}}""",
            """{"access_token":"t1","token_type":"bearer"}""",
            """{"data":{"user":{"api_token":"t1"}}}""",
            """{"data":{"Token":"t1"}}""",
            """{"result":[{"auth_token":"t1"}]}""",
            """{"data":{"jwt":"t1"}}""",
        )
        for (v in variants) assertEquals(v, "t1", AccountParsing.findToken(json(v)))
    }

    @Test
    fun `shallow token wins over a nested one and keys have priority order`() {
        assertEquals("outer", AccountParsing.findToken(json("""{"data":{"token":"inner"},"token":"outer"}""")))
        assertEquals("a", AccountParsing.findToken(json("""{"access_token":"b","token":"a"}""")))
    }

    @Test
    fun `non string or blank tokens are ignored`() {
        assertNull(AccountParsing.findToken(json("""{"token":true}""")))
        assertNull(AccountParsing.findToken(json("""{"token":12345}""")))
        assertNull(AccountParsing.findToken(json("""{"token":"  "}""")))
        assertNull(AccountParsing.findToken(json("""{"token":null}""")))
        assertNull(AccountParsing.findToken(json("""{"success":true,"data":[]}""")))
    }

    @Test
    fun `html and plain text are not json`() {
        assertNull(AccountParsing.parseJson("<html>down</html>"))
        assertNull(AccountParsing.parseJson(""))
        assertNull(AccountParsing.parseJson("token=abc"))
        assertNotNull(AccountParsing.parseJson(Char(0xFEFF) + " {\"a\":1}"))
    }

    // --- profile -------------------------------------------------------------------------------------

    @Test
    fun `profile reads display name and vip days`() {
        val p = AccountParsing.parseProfile(
            json("""{"success":true,"data":{"username":"ali","display_name":"Ali R","token":"t","vip":{"days_left":"23","status":"active"}}}"""),
            token = "t",
        )
        assertEquals("Ali R", p.displayName)
        assertEquals(23, p.vipDaysLeft)
    }

    @Test
    fun `the confirmed profile gives every non-secret field`() {
        val p = AccountParsing.parseProfile(
            json(
                """{"success":true,"data":{"id":7,"username":"ali_r","email":"ali@example.com","display_name":"Ali R","token":"tok-123",
                   "vip":{"days_left":23,"expire":"2026-11-03","expire_percent":"74","status":"active"},"show":{}},"message":"ok"}""",
            ),
            token = "tok-123",
        )
        assertEquals(
            RemoteProfile(
                displayName = "Ali R",
                vipDaysLeft = 23,
                username = "ali_r",
                email = "ali@example.com",
                vipExpire = "2026-11-03",
                vipExpirePercent = 74,
                vipStatus = "active",
            ),
            p,
        )
        assertFalse(p.toString().contains("tok-123"))
    }

    @Test
    fun `profile fields accept numbers, odd keys and Persian digits`() {
        val p = AccountParsing.parseProfile(
            json(
                """{"data":{"user_login":12345,"user_email":"a@b.c","display_name":"Ali",
                   "vip":{"days_left":"۲۳","expires":1793750400,"expire_percent":74.6,"status":1}}}""",
            ),
        )
        assertEquals("12345", p.username)
        assertEquals("a@b.c", p.email)
        assertEquals(23, p.vipDaysLeft)
        assertEquals("1793750400", p.vipExpire)
        assertEquals(74, p.vipExpirePercent)
        assertEquals("1", p.vipStatus)

        assertEquals(100, AccountParsing.parseProfile(json("""{"data":{"vip":{"expire_percent":"250"}}}""")).vipExpirePercent)
        assertEquals(50, AccountParsing.parseProfile(json("""{"data":{"vip":{"expire_percent":"۵۰%"}}}""")).vipExpirePercent)
        assertNull(AccountParsing.parseProfile(json("""{"data":{"vip":{"expire_percent":"soon"}}}""")).vipExpirePercent)
    }

    @Test
    fun `missing or blank profile fields stay null and the token is never a field`() {
        val empty = AccountParsing.parseProfile(json("""{"data":{}}"""))
        assertEquals(RemoteProfile(), empty)

        val blank = AccountParsing.parseProfile(json("""{"data":{"username":"  ","email":"","vip":{"expire":"","status":""}}}"""))
        assertEquals(RemoteProfile(), blank)

        val leak = AccountParsing.parseProfile(json("""{"data":{"username":"tok","email":"tok"}}"""), token = "tok")
        assertNull(leak.username)
        assertNull(leak.email)
    }

    @Test
    fun `an inactive plan keeps its dates and status but has no days left`() {
        val p = AccountParsing.parseProfile(
            json("""{"data":{"vip":{"days_left":0,"expire":"2026-01-01","expire_percent":100,"status":"expired"}}}"""),
        )
        assertNull(p.vipDaysLeft)
        assertEquals("2026-01-01", p.vipExpire)
        assertEquals(100, p.vipExpirePercent)
        assertEquals("expired", p.vipStatus)
    }

    @Test
    fun `profile falls back to username and never uses the token as a name`() {
        assertEquals("ali", AccountParsing.parseProfile(json("""{"data":{"username":"ali"}}""")).displayName)
        assertNull(AccountParsing.parseProfile(json("""{"data":{"name":"tok"}}"""), token = "tok").displayName)
    }

    @Test
    fun `vip days are null for inactive plans`() {
        fun days(vip: String) = AccountParsing.parseProfile(json("""{"data":{"vip":$vip}}""")).vipDaysLeft
        assertEquals(5, days("""{"days_left":5}"""))
        assertEquals(5, days("""{"days_left":"۵"}"""))
        assertNull(days("""{"days_left":0,"status":"active"}"""))
        assertNull(days("""{"days_left":30,"status":"expired"}"""))
        assertNull(days("""{"days_left":30,"status":false}"""))
        assertNull(days("""{"days_left":"","status":""}"""))
        assertNull(AccountParsing.parseProfile(json("""{"data":{}}""")).vipDaysLeft)
    }

    // --- login classification --------------------------------------------------------------------------

    @Test
    fun `2xx with a token is a success carrying the profile`() {
        val outcome = AccountParsing.classifyLogin(200, """{"success":true,"data":{"token":"t","display_name":"Ali"}}""")
        outcome as LoginOutcome.Success
        assertEquals("t", outcome.token)
        assertEquals("Ali", outcome.profile.displayName)
    }

    @Test
    fun `success values never print the token`() {
        val outcome = AccountParsing.classifyLogin(200, """{"data":{"token":"SECRET-TOKEN"}}""")
        assertFalse(outcome.toString().contains("SECRET-TOKEN"))
    }

    @Test
    fun `refused credentials are recognised`() {
        val body = """{"success":false,"message":"نام کاربری یا رمز عبور اشتباه است."}"""
        assertEquals(LoginOutcome.WrongCredentials, AccountParsing.classifyLogin(400, body))
        assertEquals(LoginOutcome.WrongCredentials, AccountParsing.classifyLogin(401, body))
        assertEquals(LoginOutcome.WrongCredentials, AccountParsing.classifyLogin(403, body))
        assertEquals(LoginOutcome.WrongCredentials, AccountParsing.classifyLogin(200, body))
    }

    @Test
    fun `a required field message means our request was refused and is not a wrong password`() {
        val outcome = AccountParsing.classifyLogin(400, """{"success":false,"message":"نام کاربری و رمز عبور الزامی است."}""")
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_REJECTED), outcome)
    }

    @Test
    fun `server problems are failures and never wrong credentials`() {
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_SERVER), AccountParsing.classifyLogin(503, """{"success":false}"""))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_TOO_MANY), AccountParsing.classifyLogin(429, """{"success":false}"""))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_BAD_RESPONSE), AccountParsing.classifyLogin(404, """{"code":"rest_no_route"}"""))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_BAD_RESPONSE), AccountParsing.classifyLogin(400, "<html>blocked</html>"))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_BAD_RESPONSE), AccountParsing.classifyLogin(200, """{"success":true,"data":{}}"""))
        assertEquals(LoginOutcome.Failed(AccountParsing.MSG_BAD_RESPONSE), AccountParsing.classifyLogin(200, ""))
    }

    // --- favourites ----------------------------------------------------------------------------------------

    private fun favorites(text: String) = AccountParsing.extractFavorites(json(text))

    @Test
    fun `favourites accept a paged envelope with post shaped items`() {
        val items = favorites(
            """{"success":true,"per_page":12,"page":1,"total_page":1,"data":[
               {"id":353371,"title":"فیلم The Naked Gun 2025","fa_title":"اسلحه برهنه","type":"movie","dubbed":true,"subtitle":false,
                "release":2025,"imdb_rate":"6.2","thumbnail":"https://seo2024.ir/wp-content/uploads/2026/07/a.webp"},
               {"id":624236,"title":"Friends","image":"https://seo2024.ir/wp-content/uploads/2026/07/f.webp"}]}""",
        )!!
        assertEquals(listOf(353371L, 624236L), items.map { it.id })
        val first = items[0].summary!!
        assertEquals("The Naked Gun 2025", first.title)
        assertEquals(TitleType.MOVIE, first.type)
        assertEquals(2025, first.year)
        assertTrue(first.hasDub)
        // `image` is used when there is no `thumbnail`.
        assertTrue(items[1].summary!!.posterUrl!!.endsWith("/wp-content/uploads/2026/07/f.webp"))
    }

    @Test
    fun `favourites accept bare ids strings and wrappers`() {
        assertEquals(listOf(1L, 2L, 3L), favorites("""[1,"2",3.0]""")!!.map { it.id })
        assertEquals(listOf(5L, 6L), favorites("""{"success":true,"data":["5","6"]}""")!!.map { it.id })
        assertEquals(listOf(7L), favorites("""{"data":{"posts":[{"post_id":7}]}}""")!!.map { it.id })
        assertEquals(listOf(8L), favorites("""{"data":{"items":[{"post":{"id":8,"title":"X"}}]}}""")!!.map { it.id })
        assertEquals(listOf(9L), favorites("""{"data":[{"post":9}]}""")!!.map { it.id })
        assertEquals(listOf(10L, 11L), favorites("""{"data":{"10":{"title":"A"},"11":{"title":"B"}}}""")!!.map { it.id })
    }

    @Test
    fun `post id wins over a row id`() {
        val item = favorites("""{"data":[{"id":5,"post_id":353371,"title":"Row"}]}""")!!.single()
        assertEquals(353371L, item.id)
        assertEquals(353371L, item.summary!!.id)
    }

    @Test
    fun `an id only entry has no summary`() {
        val item = favorites("""{"data":[{"id":42}]}""")!!.single()
        assertEquals(42L, item.id)
        assertNull(item.summary)
    }

    @Test
    fun `duplicates collapse and an explicit empty list is empty`() {
        assertEquals(listOf(1L), favorites("""{"data":[{"id":1},{"id":1}]}""")!!.map { it.id })
        assertEquals(emptyList<Long>(), favorites("""{"success":true,"data":[]}""")!!.map { it.id })
    }

    @Test
    fun `unreadable shapes are null and never an empty list`() {
        assertNull(favorites("""{"success":true}"""))
        assertNull(favorites("""{"success":true,"data":"none"}"""))
        assertNull(favorites("""{"data":[{"foo":1},{"bar":2}]}"""))
        assertNull(favorites("""{"data":{}}"""))
        assertNull(favorites("""{"data":{"count":3}}"""))
    }

    @Test
    fun `page counts are read from the envelope`() {
        assertEquals(3, AccountParsing.totalPages(json("""{"total_page":3,"data":[]}""")))
        assertEquals(2, AccountParsing.totalPages(json("""{"data":{"total_pages":"2","items":[]}}""")))
        assertNull(AccountParsing.totalPages(json("""{"data":[]}""")))
        assertEquals(12, AccountParsing.perPage(json("""{"per_page":"12","data":[]}""")))
        assertNull(AccountParsing.perPage(json("""[1,2]""")))
    }

    // --- progress ------------------------------------------------------------------------------------------

    private fun progress(text: String) = AccountParsing.extractProgress(json(text))

    @Test
    fun `progress items read strings and numbers`() {
        val items = progress(
            """{"success":true,"data":[
               {"id":"353371","title":"Dune","image":"https://seo2024.ir/wp-content/uploads/2026/07/d.webp","current_time":"1234","duration":"9000","season":"","episode":""},
               {"id":624236,"title":"Friends","image":"","current_time":600.5,"duration":1500,"season":"2","episode":"3"}]}""",
        )!!
        assertEquals(2, items.size)
        val movie = items[0]
        assertEquals(WatchKey(353371), movie.key)
        assertEquals(1_234_000L, movie.positionMs)
        assertEquals(9_000_000L, movie.durationMs)
        assertEquals("Dune", movie.summary!!.title)
        assertNull(movie.summary!!.type)
        val episode = items[1]
        assertEquals(WatchKey(624236, 2, 3), episode.key)
        assertEquals(600_000L, episode.positionMs)
        assertEquals(TitleType.SERIES, episode.summary!!.type)
    }

    @Test
    fun `progress durations tolerate minutes clock strings and nonsense`() {
        fun duration(raw: String, pos: Int = 3000): Long =
            progress("""{"data":[{"id":1,"current_time":$pos,"duration":$raw}]}""")!!.single().durationMs
        assertEquals(7_200_000L, duration("7200"))
        assertEquals(7_200_000L, duration("\"2:00:00\""))
        assertEquals(7_200_000L, duration("120")) // 120 < position: minutes
        assertEquals(0L, duration("5", pos = 3000)) // even as minutes it is shorter than the position
        assertEquals(0L, duration("\"abc\""))
        assertEquals(0L, duration("0"))
    }

    @Test
    fun `progress items without a title or id`() {
        val items = progress("""{"data":[{"id":1,"current_time":"60"},{"title":"No id","current_time":"5"}]}""")!!
        assertEquals(1, items.size)
        assertNull(items[0].summary)
        assertNull(progress("""{"data":[{"title":"x"}]}"""))
        assertEquals(0, progress("""{"data":[]}""")!!.size)
    }

    // --- diagnostics ---------------------------------------------------------------------------------------

    @Test
    fun `shape description has key names and types but no values`() {
        val shape = AccountParsing.describeShape(
            json("""{"success":true,"data":{"token":"SECRET","email":"me@example.com","id":5,"vip":{"days_left":3},"list":[{"id":1},{"id":2}]},"message":null}"""),
        )
        assertEquals("{success:bool,data:{token:str,email:str,id:num,vip:{days_left:num},list:[2x {id:num}]},message:null}", shape)
        assertFalse(shape.contains("SECRET"))
        assertFalse(shape.contains("example.com"))
        assertEquals("unparsable", AccountParsing.describeShape(null))
    }
}
