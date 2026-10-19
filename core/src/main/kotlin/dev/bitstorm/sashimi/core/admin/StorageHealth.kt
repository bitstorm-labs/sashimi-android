package dev.bitstorm.sashimi.core.admin

/** One filesystem's fill level. [fraction] is null for a zero-size filesystem. */
data class StorageRow(
    val label: String,
    val usedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Double? get() = if (totalBytes > 0) usedBytes.toDouble() / totalBytes else null
    val isWarning: Boolean get() = StorageHealth.isWarning(fraction)
}

object StorageHealth {
    /** Above this used fraction a filesystem shows in the warning colour. */
    const val WARNING_FRACTION = 0.85

    fun isWarning(fraction: Double?): Boolean = fraction != null && fraction > WARNING_FRACTION

    const val CACHE_LABEL = "Transcode & cache"

    /**
     * One row per filesystem. The server reports each folder separately, and
     * folders on one filesystem carry identical free/used numbers, so that pair
     * is the filesystem key (DeviceId is only the mount point inside the
     * container). Library filesystems come first, named after their libraries;
     * the transcode/cache filesystem is named [CACHE_LABEL]. No response, no rows.
     */
    fun rows(storage: SystemStorageDto?): List<StorageRow> {
        if (storage == null) return emptyList()
        val labels = linkedMapOf<Pair<Long, Long>, MutableList<String>>()
        val cacheKeys = listOfNotNull(storage.transcodingTempFolder, storage.cacheFolder).map(::key).toSet()
        storage.libraries.forEach { library ->
            library.folders
                .map(::key)
                .distinct()
                .forEach { k -> labels.getOrPut(k) { mutableListOf() }.add(library.name) }
        }
        cacheKeys.forEach { labels.getOrPut(it) { mutableListOf() } }
        return labels
            .mapNotNull { (k, names) ->
                val (free, used) = k
                if (free + used <= 0) return@mapNotNull null
                val label = if (k in cacheKeys) CACHE_LABEL else names.distinct().joinToString(", ")
                StorageRow(label, usedBytes = used, totalBytes = used + free)
            }.sortedBy { it.label == CACHE_LABEL }
    }

    private fun key(folder: FolderStorageDto) = folder.freeSpace to folder.usedSpace
}
