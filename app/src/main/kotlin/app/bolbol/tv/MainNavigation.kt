package app.bolbol.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.bolbol.tv.core.designsystem.BolbolText
import app.bolbol.tv.core.designsystem.TvButton

@Composable
fun MainNavigation(app: BolbolApp) {
    val backStack = rememberNavBackStack(HomeRoute)
    val back: () -> Unit = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }

    NavDisplay(
        backStack = backStack,
        onBack = { back() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        entryProvider = entryProvider {
            entry<HomeRoute> {
                // TEMP (M1 integration): replaced by HomeScreen when :feature:home lands.
                TempHome(onPlay = { backStack.add(PlayerRoute(353371)) })
            }
            entry<DetailsRoute> { route ->
                ComingSoon("جزئیات ${route.titleId}")
            }
            entry<PlayerRoute> { route ->
                PlayerEntry(app, route, onExit = back)
            }
            entry<ComingSoonRoute> { route -> ComingSoon(route.title) }
        },
    )
}

@Composable
private fun TempHome(onPlay: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(
        Modifier.fillMaxSize().padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BolbolText("بلبل تی‌وی (ادغام آزمایشی)")
        TvButton(text = "پخش آزمایشی: اسلحه برهنه", onClick = onPlay, modifier = Modifier.focusRequester(focus))
    }
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
