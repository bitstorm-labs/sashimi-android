package dev.bitstorm.sashimi.themesong

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.themesong.themeKeyFor
import dev.bitstorm.sashimi.di.ServiceLocator

/**
 * Reports to [ThemeSongService] that a detail screen for a show is on the back
 * stack. This is the *only* thing the detail screen does about theme songs — it
 * reports intent and owns no decision.
 *
 * ## Why this is a ViewModel and not a DisposableEffect
 *
 * The obvious wiring — `DisposableEffect { appeared(); onDispose { gone() } }`
 * in the detail screen — is wrong here, and it is wrong for a reason specific
 * to Compose navigation rather than anything about theme songs.
 *
 * `NavHost` composes destinations inside an `AnimatedContent` keyed on the back
 * stack entry id (confirmed in navigation-compose 2.8.9's `NavHostKt`). So a
 * push **disposes the outgoing destination** once the transition finishes, and
 * a pop **re-composes it from scratch**; only `rememberSaveable` survives the
 * round trip, via the `SaveableStateProvider` that
 * `NavBackStackEntryProvider` wraps each entry in. A composition-scoped
 * DisposableEffect would therefore report "gone" when the user opens the video
 * player and "appeared" again when they come back — the exact case that has to
 * be silent — and re-registering a lifecycle observer on the entry does not
 * help either, because `LifecycleRegistry` replays its current state to a newly
 * added observer, so the re-entry would double-count.
 *
 * What *does* line up with "is this show still on the back stack" is the entry
 * itself. `NavBackStackEntryProvider` supplies the `NavBackStackEntry` as
 * `LocalViewModelStoreOwner`, so a `viewModel()` obtained inside a
 * `composable<…>` block is created once when the entry is first composed and
 * `onCleared()` exactly once when the entry is popped — surviving both the
 * push/pop composition churn and configuration changes. navigation-compose
 * relies on precisely this mechanism itself (`BackStackEntryIdViewModel`, whose
 * `onCleared` clears the entry's saved state).
 *
 * Net effect on the depth count, which is what the behaviour actually turns on:
 *  - series → season/episode of the same show: 1 → 2, no restart;
 *  - detail → video player → back: stays at 1 throughout, so silence on return;
 *  - leaving the show for good: → 0, stop.
 */
@Composable
fun ThemeSongVisitEffect(item: BaseItemDto?) {
    val tracker: ThemeSongVisitViewModel = viewModel()
    val key = item?.let { themeKeyFor(it.type, it.id, it.seriesId) }
    LaunchedEffect(key) {
        if (key != null) tracker.bind(key)
    }
}

/**
 * Scoped to the `NavBackStackEntry` of one detail destination. Binds once to the
 * show that destination belongs to and releases it when the entry is popped.
 */
class ThemeSongVisitViewModel : ViewModel() {
    private var boundKey: String? = null

    fun bind(seriesId: String) {
        // The key is resolved from the loaded item, so this can be called again
        // on a reload or a re-composition after a pop. One entry, one visit.
        if (boundKey != null) return
        boundKey = seriesId
        ServiceLocator.themeSongs.detailAppeared(seriesId)
    }

    override fun onCleared() {
        boundKey?.let { ServiceLocator.themeSongs.detailDisappeared(it) }
        boundKey = null
    }
}
