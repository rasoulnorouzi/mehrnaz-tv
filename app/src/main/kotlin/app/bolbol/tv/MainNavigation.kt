package app.bolbol.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.model.Category
import app.bolbol.tv.core.model.ListQuery
import app.bolbol.tv.core.model.WatchKey
import app.bolbol.tv.core.lanremote.PhoneRemotePanel
import app.bolbol.tv.feature.search.SearchScreen
import app.bolbol.tv.feature.search.SearchViewModel
import app.bolbol.tv.feature.account.AccountScreen
import app.bolbol.tv.feature.account.AccountViewModel
import app.bolbol.tv.feature.settings.SettingsScreen
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.bolbol.tv.feature.settings.SettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import app.bolbol.tv.feature.browse.BrowseScreen
import app.bolbol.tv.feature.browse.BrowseViewModel
import app.bolbol.tv.feature.details.DetailsScreen
import app.bolbol.tv.feature.details.DetailsViewModel
import app.bolbol.tv.feature.home.HomeDestination
import app.bolbol.tv.feature.home.HomeScreen
import app.bolbol.tv.feature.home.HomeViewModel

/**
 * The only place that knows the back stack (docs/decisions.md §Architecture).
 * Each entry gets its own ViewModelStore, so e.g. two stacked details screens keep separate state.
 */
@Composable
fun MainNavigation(app: BolbolApp, pendingDeepLink: MutableStateFlow<WatchKey?>) {
    val backStack = rememberNavBackStack(HomeRoute)
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }
    val open: (NavKey) -> Unit = { backStack.add(it) }
    val data = app.data

    // Watch Next card pressed on the TV home screen → play it directly (resume prompt decides the position).
    val deepLink by pendingDeepLink.collectAsState()
    LaunchedEffect(deepLink) {
        val key = deepLink ?: return@LaunchedEffect
        pendingDeepLink.value = null
        open(PlayerRoute(key.postId, key.season, key.episode))
    }

    NavDisplay(
        backStack = backStack,
        onBack = { back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeRoute> {
                HomeScreen(
                    viewModel = viewModel(factory = HomeViewModel.factory(data.catalog, data.watch)),
                    onOpenTitle = { open(DetailsRoute(it.id)) },
                    onResume = { item ->
                        val k = item.progress.key
                        open(PlayerRoute(k.postId, k.season, k.episode, item.progress.positionMs))
                    },
                    onViewAll = { q, title ->
                        open(BrowseRoute(title, q.category.name, q.country, q.genre, q.imdbTop250))
                    },
                    onNavigate = { dest ->
                        when (dest) {
                            HomeDestination.MOVIES -> open(BrowseRoute("فیلم‌ها", Category.MOVIES.name))
                            HomeDestination.SERIES -> open(BrowseRoute("سریال‌ها", Category.SERIES.name))
                            HomeDestination.SEARCH -> open(SearchRoute)
                            HomeDestination.MY_LIST -> open(AccountRoute)
                            HomeDestination.SETTINGS -> open(SettingsRoute)
                        }
                    },
                )
            }
            entry<DetailsRoute> { route ->
                DetailsScreen(
                    viewModel = viewModel(
                        factory = DetailsViewModel.factory(
                            route.titleId, data.catalog, data.watch, data.prefs, myList = app.myListActions,
                        ),
                    ),
                    onPlay = { key, start -> open(PlayerRoute(key.postId, key.season, key.episode, start)) },
                    onOpenTitle = { open(DetailsRoute(it.id)) },
                )
            }
            entry<BrowseRoute> { route ->
                val query = ListQuery(
                    category = Category.valueOf(route.category),
                    country = route.country,
                    genre = route.genre,
                    imdbTop250 = route.imdbTop250,
                )
                BrowseScreen(
                    viewModel = viewModel(factory = BrowseViewModel.factory(query, route.title, data.catalog)),
                    onOpenTitle = { open(DetailsRoute(it.id)) },
                )
            }
            entry<PlayerRoute> { route ->
                PlayerEntry(
                    app, route,
                    onExit = { app.pushProgressAfterPlayback(route.postId, route.season, route.episode); back() },
                    // Auto-next: replace (not push) the player entry so exactly one ExoPlayer exists
                    // and Back returns to details, not to the previous episode.
                    onPlayNext = { next ->
                        app.pushProgressAfterPlayback(route.postId, route.season, route.episode)
                        backStack[backStack.lastIndex] = PlayerRoute(next.postId, next.season, next.episode, 0)
                    },
                )
            }
            entry<SearchRoute> {
                // Text typed on a phone (QR remote) flows into the search query.
                val phoneText = remember {
                    MutableSharedFlow<String>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
                }
                val context = LocalContext.current
                SearchScreen(
                    viewModel = viewModel(factory = SearchViewModel.factory(data.catalog, context.applicationContext)),
                    onOpenTitle = { open(DetailsRoute(it.id)) },
                    externalInput = phoneText,
                    phoneRemoteContent = { PhoneRemotePanel(onText = { phoneText.tryEmit(it) }) },
                )
            }
            entry<AccountRoute> {
                AccountScreen(
                    viewModel = viewModel(factory = AccountViewModel.factory(data.account, data.accountVipDaysLeft)),
                    onOpenTitle = { open(DetailsRoute(it.id)) },
                )
            }
            entry<SettingsRoute> {
                SettingsScreen(
                    viewModel = viewModel(
                        factory = SettingsViewModel.factory(
                            prefs = data.prefs,
                            appSettings = app.appSettings,
                            currentMirror = { data.currentMirror },
                            resetMirrors = { data.resetServers() },
                            versionName = app.versionName,
                        ),
                    ),
                )
            }
            entry<ComingSoonRoute> { route -> ComingSoon(route.title) }
        },
    )
}

@Composable
fun ComingSoon(title: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BolbolText("$title · به‌زودی")
    }
}
