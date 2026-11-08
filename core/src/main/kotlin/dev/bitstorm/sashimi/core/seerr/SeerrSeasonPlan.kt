package dev.bitstorm.sashimi.core.seerr

import java.time.Instant

/**
 * The season picker. Every season not already on the server or asked for
 * starts ticked; seasons on the server are listed but can't be picked;
 * season 0 (specials) never appears. Port of sashimi-apple SeerrSeasonPlan.
 */
data class SeerrSeasonPlan(
    val seasons: List<Season>,
    val selected: Set<Int>,
    /** Off on the server: a show is requested whole, every missing season. */
    val partialRequestsEnabled: Boolean,
) {
    enum class State {
        /** Aired, not on the server, nobody's asked: ticked by default. */
        REQUESTABLE,

        /** Pickable, but not ticked by default. */
        NOT_AIRED,

        /** Someone already asked (pending or on its way). */
        REQUESTED,

        /** Some episodes are on the server. */
        PARTIALLY_AVAILABLE,
        ON_SERVER,
        ;

        val isPickable: Boolean get() = this == REQUESTABLE || this == NOT_AIRED
    }

    data class Season(
        val number: Int,
        val episodeCount: Int?,
        val state: State,
    )

    /** One line of the sheet: a season, or a run of seasons on the server ("Seasons 1–9 · On server"). */
    data class Row(
        val id: String,
        val title: String,
        val detail: String,
        val seasons: List<Int>,
        val isPickable: Boolean,
    )

    /** Aired seasons nobody has asked for and the server doesn't have. */
    val missing: List<Int> get() = seasons.filter { it.state == State.REQUESTABLE }.map { it.number }

    val hasMissingSeasons: Boolean get() = missing.isNotEmpty()

    val allMissingSelected: Boolean get() = missing.isNotEmpty() && selected.containsAll(missing)

    /** What `POST /request` gets: sorted, never 0. */
    val requestSeasons: List<Int> get() = selected.filter { it > 0 }.sorted()

    val buttonTitle: String
        get() = requestSeasons.size.let { if (it == 1) "Request 1 season" else "Request $it seasons" }

    val rows: List<Row>
        get() {
            val rows = mutableListOf<Row>()
            val run = mutableListOf<Season>()

            fun flushRun() {
                val first = run.firstOrNull() ?: return
                val last = run.last()
                val title = if (run.size == 1) "Season ${first.number}" else "Seasons ${first.number}–${last.number}"
                rows += Row("server-${first.number}", title, "On server", run.map { it.number }, isPickable = false)
                run.clear()
            }
            for (season in seasons) {
                if (season.state == State.ON_SERVER) {
                    run += season
                    continue
                }
                flushRun()
                rows +=
                    Row(
                        id = "season-${season.number}",
                        title = "Season ${season.number}",
                        detail = detail(season),
                        seasons = listOf(season.number),
                        isPickable = partialRequestsEnabled && season.state.isPickable,
                    )
            }
            flushRun()
            return rows
        }

    fun toggle(number: Int): SeerrSeasonPlan {
        val season = seasons.firstOrNull { it.number == number } ?: return this
        if (!season.state.isPickable) return this
        if (!partialRequestsEnabled) return copy(selected = missing.toSet())
        return copy(selected = if (number in selected) selected - number else selected + number)
    }

    fun setAllMissing(on: Boolean): SeerrSeasonPlan = copy(selected = if (on) selected + missing else emptySet())

    companion object {
        fun of(
            details: SeerrTitleDetails,
            now: Instant = Instant.now(),
            partialRequestsEnabled: Boolean = true,
        ): SeerrSeasonPlan {
            val statuses = HashMap<Int, Int?>()
            details.mediaInfo
                ?.seasons
                .orEmpty()
                .forEach { statuses.putIfAbsent(it.seasonNumber, it.status) }
            val active =
                details.mediaInfo?.requests.orEmpty().filter {
                    it.requestStatus != SeerrRequestStatus.DECLINED && it.requestStatus != SeerrRequestStatus.FAILED
                }
            val requested = active.flatMap { it.seasons.orEmpty() }.map { it.seasonNumber }.toSet()
            val seasons =
                details.seasons
                    .filter { it.seasonNumber > 0 }
                    .sortedBy { it.seasonNumber }
                    .map { season ->
                        val state =
                            when (SeerrMediaStatus.from(statuses[season.seasonNumber])) {
                                SeerrMediaStatus.AVAILABLE -> State.ON_SERVER
                                SeerrMediaStatus.PARTIALLY_AVAILABLE -> State.PARTIALLY_AVAILABLE
                                SeerrMediaStatus.PENDING, SeerrMediaStatus.PROCESSING -> State.REQUESTED
                                else -> {
                                    val aired = SeerrDates.parse(season.airDate)
                                    when {
                                        season.seasonNumber in requested -> State.REQUESTED
                                        aired != null && !aired.isAfter(now) -> State.REQUESTABLE
                                        else -> State.NOT_AIRED
                                    }
                                }
                            }
                        Season(season.seasonNumber, season.episodeCount, state)
                    }
            val missing = seasons.filter { it.state == State.REQUESTABLE }.map { it.number }.toSet()
            return SeerrSeasonPlan(seasons, missing, partialRequestsEnabled)
        }

        private fun detail(season: Season): String =
            when (season.state) {
                State.REQUESTABLE -> season.episodeCount?.takeIf { it > 0 }?.let { if (it == 1) "1 episode" else "$it episodes" } ?: ""
                State.NOT_AIRED -> "Not aired"
                State.REQUESTED -> "Requested"
                State.PARTIALLY_AVAILABLE -> "Partly on server"
                State.ON_SERVER -> "On server"
            }
    }
}
