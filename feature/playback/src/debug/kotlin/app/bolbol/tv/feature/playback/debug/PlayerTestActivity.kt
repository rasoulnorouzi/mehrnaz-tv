package app.bolbol.tv.feature.playback.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import app.bolbol.tv.core.designsystem.BolbolTheme
import app.bolbol.tv.core.model.AudioType
import app.bolbol.tv.core.model.PlaybackPrefs
import app.bolbol.tv.core.model.PlaybackRequest
import app.bolbol.tv.core.model.StreamOption
import app.bolbol.tv.core.model.TitleSummary
import app.bolbol.tv.core.model.TitleType
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.player.PlayerFactory
import app.bolbol.tv.feature.playback.PlayerScreen
import app.bolbol.tv.feature.playback.PlayerViewModel
import okhttp3.OkHttpClient

/**
 * Debug-only harness: plays "The Naked Gun 2025" (post 353371) from the real CDN with in-memory
 * repositories, so the player can be checked without the rest of the app.
 *
 * ```
 * adb shell am start -n app.bolbol.tv/app.bolbol.tv.feature.playback.debug.PlayerTestActivity \
 *     [--ei quality 720] [--es audio dub|sub] [--el start_ms 600000] [--ez diag false] [--ez reset true]
 * adb logcat -s BolbolPerf BolbolPlayer BolbolTest
 * ```
 * Defaults: 480p SUBTITLED (the emulator decodes in software), diagnostics on. Progress and prefs
 * live for the process, so BACK then relaunch shows the resume prompt (`--ez reset true` clears).
 */
class PlayerTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val quality = intent.getIntExtra("quality", 480)
        val audio = if (intent.getStringExtra("audio") == "dub") AudioType.DUBBED else AudioType.SUBTITLED
        val startMs = intent.getLongExtra("start_ms", -1L).takeIf { it >= 0 }
        val diagnostics = intent.getBooleanExtra("diag", true)
        if (savedInstanceState == null) {
            val reset = intent.getBooleanExtra("reset", false)
            if (reset) Fakes.watch.clear()
            // An explicit audio/quality (or reset) replaces whatever was remembered in this process.
            if (reset || intent.hasExtra("quality") || intent.hasExtra("audio")) {
                Fakes.prefs.reset(PlaybackPrefs(audio, quality))
            }
        }
        val request = NakedGun.request(startMs)
        val playerFactory = PlayerFactory(applicationContext, Fakes.http)

        setContent {
            BolbolTheme {
                val vm: PlayerViewModel = viewModel(
                    factory = PlayerViewModel.factory(
                        request = request,
                        createPlayer = { playerFactory.create() },
                        watch = Fakes.watch,
                        prefs = Fakes.prefs,
                    ),
                )
                PlayerScreen(viewModel = vm, onExit = ::finish, showDiagnostics = diagnostics)
            }
        }
    }
}

/** Process-wide fakes (survive activity relaunches, like the real Room/DataStore would). */
private object Fakes {
    val http: OkHttpClient by lazy { OkHttpClient() }
    val watch = InMemoryWatchRepository()
    val prefs = InMemoryPrefsRepository(PlaybackPrefs(audio = AudioType.SUBTITLED, quality = 480))
}

/** research/webapp/api/movie_353371.json → data.player_links (+ sizes from download_links). */
private object NakedGun {
    private const val BASE = "zr/stream_movies/stream7/2025/The.N.a.k.e.d.Gun.2025/The.N.a.k.e.d.Gun.2025"

    private fun sub(host: String, q: Int, size: String) = StreamOption(
        quality = q,
        audio = AudioType.SUBTITLED,
        url = "https://$host/$BASE.${q}p.BluRay.x264.Pahe.SoftSub.mp4",
        subtitleUrl = "https://$host/$BASE.${q}p.BluRay.x264.Pahe.SoftSub.vtt",
        label = "BluRay ${q}p",
        sizeText = size,
    )

    private fun dub(host: String, q: Int, size: String) = StreamOption(
        quality = q,
        audio = AudioType.DUBBED,
        url = "https://$host/$BASE.${q}p.WEB-DL.Filimo.Dubbed.mp4",
        subtitleUrl = null,
        label = "WEB-DL ${q}p",
        sizeText = size,
    )

    private val streams = listOf(
        sub("dl20.acenteri.ir", 1080, "2.42 GB"),
        sub("dl2.ecenteri.ir", 720, "780 MB"),
        sub("dl20.acenteri.ir", 480, "390 MB"),
        dub("dl20.acenteri.ir", 1080, "1.42 GB"),
        dub("dl2.ecenteri.ir", 720, "770 MB"),
        dub("dl20.acenteri.ir", 480, "380 MB"),
    )

    private val title = TitleSummary(
        id = 353371,
        type = TitleType.MOVIE,
        title = "The Naked Gun 2025",
        faTitle = "اسلحه برهنه",
        posterUrl = null,
        backdropUrl = null,
        year = 2025,
        imdbRating = null,
        hasDub = true,
        hasSubtitle = true,
    )

    fun request(startPositionMs: Long?) = PlaybackRequest(
        key = WatchKey(353371),
        title = title,
        subtitleLine = null,
        streams = streams,
        startPositionMs = startPositionMs,
        next = null,
    )
}
