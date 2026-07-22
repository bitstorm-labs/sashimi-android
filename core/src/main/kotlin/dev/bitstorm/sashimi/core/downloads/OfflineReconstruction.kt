package dev.bitstorm.sashimi.core.downloads

import dev.bitstorm.sashimi.core.model.BaseItemDto
import dev.bitstorm.sashimi.core.model.ItemType
import dev.bitstorm.sashimi.core.model.UserItemDataDto

/**
 * A downloaded series grouped for the offline Home / detail views (Swift
 * `seriesGroups`): a representative episode plus the full episode list.
 */
data class OfflineSeriesGroup(
    val seriesId: String,
    val seriesName: String,
    val representative: DownloadedItemEntity,
    val episodes: List<DownloadedItemEntity>,
)

/**
 * Rebuilds browsable structure from the local download store when offline,
 * ported from the Swift `loadOfflineSeriesContent` / `OfflineHomeView.seriesGroups`.
 * Pure so the synthetic-season logic is unit-testable.
 */
object OfflineReconstruction {
    /** Only completed downloads are browsable offline. */
    fun completed(items: List<DownloadedItemEntity>): List<DownloadedItemEntity> = items.filter { it.isComplete }

    fun movies(items: List<DownloadedItemEntity>): List<DownloadedItemEntity> =
        completed(items).filter { it.downloadItemType == ItemType.MOVIE }

    /** Downloaded episodes with local resume progress, for offline Continue Watching. */
    fun continueWatching(items: List<DownloadedItemEntity>): List<DownloadedItemEntity> =
        completed(items).filter { it.localPositionTicks > 0 }

    /** Groups downloaded episodes by series, episodes sorted by (season, episode). */
    fun seriesGroups(items: List<DownloadedItemEntity>): List<OfflineSeriesGroup> =
        completed(items)
            .filter { it.downloadItemType == ItemType.EPISODE }
            .groupBy { it.seriesName ?: "Unknown" }
            .toSortedMap()
            .map { (seriesName, eps) ->
                val sorted = eps.sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))
                val rep = sorted.first()
                OfflineSeriesGroup(
                    seriesId = rep.seriesId ?: rep.itemId,
                    seriesName = seriesName,
                    representative = rep,
                    episodes = sorted,
                )
            }

    /** Downloaded episodes for a given series (matching id or, fallback, name). */
    fun episodesForSeries(
        items: List<DownloadedItemEntity>,
        seriesId: String,
        seriesName: String?,
    ): List<DownloadedItemEntity> =
        completed(items)
            .filter { it.downloadItemType == ItemType.EPISODE }
            .filter { it.seriesId == seriesId || (seriesName != null && it.seriesName == seriesName) }
            .sortedWith(compareBy({ it.seasonNumber ?: 0 }, { it.episodeNumber ?: 0 }))

    /**
     * Synthetic season items (`offline-season-{n}`) covering the distinct season
     * numbers among the given downloaded episodes, ordered ascending. Mirrors the
     * Swift synthetic-season DTOs used to render the offline season tabs.
     */
    fun syntheticSeasons(
        episodes: List<DownloadedItemEntity>,
        seriesId: String,
    ): List<BaseItemDto> =
        episodes
            .mapNotNull { it.seasonNumber }
            .distinct()
            .sorted()
            .map { num ->
                BaseItemDto(
                    id = "offline-season-$num",
                    name = "Season $num",
                    type = ItemType.SEASON,
                    seriesId = seriesId,
                    indexNumber = num,
                )
            }

    /** Reconstructs a playable [BaseItemDto] from a downloaded row (Swift asBaseItemDto). */
    fun asBaseItemDto(entity: DownloadedItemEntity): BaseItemDto =
        BaseItemDto(
            id = entity.itemId,
            name = entity.name,
            type = entity.downloadItemType,
            seriesName = entity.seriesName,
            seriesId = entity.seriesId,
            seasonId = entity.seasonId,
            indexNumber = entity.episodeNumber,
            parentIndexNumber = entity.seasonNumber,
            overview = entity.overview,
            runTimeTicks = entity.runTimeTicks,
            productionYear = entity.productionYear,
            userData =
                if (entity.localPositionTicks > 0) {
                    UserItemDataDto(playbackPositionTicks = entity.localPositionTicks)
                } else {
                    null
                },
        )

    /** Minimal series [BaseItemDto] for navigating into an offline series detail. */
    fun asSeriesDto(entity: DownloadedItemEntity): BaseItemDto =
        BaseItemDto(
            id = entity.seriesId ?: entity.itemId,
            name = entity.seriesName ?: entity.name,
            type = ItemType.SERIES,
        )
}

/** The item's Jellyfin [ItemType] parsed from its stored wire name. */
val DownloadedItemEntity.downloadItemType: ItemType
    get() = itemType?.let { ItemType.fromWire(it) } ?: ItemType.UNKNOWN
