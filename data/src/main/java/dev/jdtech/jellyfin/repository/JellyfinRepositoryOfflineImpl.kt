package dev.jdtech.jellyfin.repository

import android.content.Context
import androidx.paging.PagingData
import dev.jdtech.jellyfin.api.JellyfinApi
import dev.jdtech.jellyfin.data.R as DataR
import dev.jdtech.jellyfin.database.ServerDatabaseDao
import dev.jdtech.jellyfin.models.CollectionType
import dev.jdtech.jellyfin.models.FindroidCollection
import dev.jdtech.jellyfin.models.FindroidEpisode
import dev.jdtech.jellyfin.models.FindroidImages
import dev.jdtech.jellyfin.models.FindroidItem
import dev.jdtech.jellyfin.models.FindroidMovie
import dev.jdtech.jellyfin.models.FindroidPerson
import dev.jdtech.jellyfin.models.FindroidSeason
import dev.jdtech.jellyfin.models.FindroidSegment
import dev.jdtech.jellyfin.models.FindroidShow
import dev.jdtech.jellyfin.models.FindroidSource
import dev.jdtech.jellyfin.models.SortBy
import dev.jdtech.jellyfin.models.SortOrder
import dev.jdtech.jellyfin.models.toFindroidEpisode
import dev.jdtech.jellyfin.models.toFindroidMovie
import dev.jdtech.jellyfin.models.toFindroidSeason
import dev.jdtech.jellyfin.models.toFindroidSegment
import dev.jdtech.jellyfin.models.toFindroidShow
import dev.jdtech.jellyfin.models.toFindroidSource
import dev.jdtech.jellyfin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType as SdkCollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

class JellyfinRepositoryOfflineImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
) : JellyfinRepository {

    companion object {
        val VIRTUAL_VIEW_MOVIES: UUID =
            UUID.fromString("a1b2c3d4-0000-0000-0000-000000000001")
        val VIRTUAL_VIEW_SHOWS: UUID =
            UUID.fromString("a1b2c3d4-0000-0000-0000-000000000002")
    }

    private val currentUserId: UUID
        get() = jellyfinApi.userId ?: throw IllegalStateException("No user ID available in offline mode")

    private fun currentServerId(): String? =
        appPreferences.getValue(appPreferences.currentServer)

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        throw Exception("System info not available in offline mode")
    }

    override suspend fun getUserViews(): List<BaseItemDto> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val views = mutableListOf<BaseItemDto>()
            if (database.countMoviesByServerId(serverId) > 0) {
                views.add(
                    BaseItemDto(
                        id = VIRTUAL_VIEW_MOVIES,
                        name = context.getString(DataR.string.offline_view_movies),
                        type = BaseItemKind.COLLECTION_FOLDER,
                        collectionType = SdkCollectionType.MOVIES,
                    )
                )
            }
            if (database.countShowsByServerId(serverId) > 0) {
                views.add(
                    BaseItemDto(
                        id = VIRTUAL_VIEW_SHOWS,
                        name = context.getString(DataR.string.offline_view_shows),
                        type = BaseItemKind.COLLECTION_FOLDER,
                        collectionType = SdkCollectionType.TVSHOWS,
                    )
                )
            }
            views
        }
    }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            database.getMovie(itemId).toFindroidMovie(database, currentUserId)
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            database.getShow(itemId).toFindroidShow(database, currentUserId)
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            database.getSeason(itemId).toFindroidSeason(database, currentUserId)
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            database.getEpisode(itemId).toFindroidEpisode(database, currentUserId)
        }

    override suspend fun getLibraries(): List<FindroidCollection> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val collections = mutableListOf<FindroidCollection>()
            if (database.countMoviesByServerId(serverId) > 0) {
                collections.add(
                    FindroidCollection(
                        id = VIRTUAL_VIEW_MOVIES,
                        name = context.getString(DataR.string.offline_view_movies),
                        type = CollectionType.Movies,
                        images = FindroidImages(),
                    )
                )
            }
            if (database.countShowsByServerId(serverId) > 0) {
                collections.add(
                    FindroidCollection(
                        id = VIRTUAL_VIEW_SHOWS,
                        name = context.getString(DataR.string.offline_view_shows),
                        type = CollectionType.TvShows,
                        images = FindroidImages(),
                    )
                )
            }
            collections
        }
    }

    override suspend fun getItem(itemId: UUID): FindroidItem? {
        return withContext(Dispatchers.IO) {
            database.getMovieOrNull(itemId)?.toFindroidMovie(database, currentUserId)
                ?: database.getShowOrNull(itemId)?.toFindroidShow(database, currentUserId)
                ?: database.getEpisodeOrNull(itemId)?.toFindroidEpisode(database, currentUserId)
        }
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
    ): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            val wantMovies = parentId == VIRTUAL_VIEW_MOVIES ||
                includeTypes?.contains(BaseItemKind.MOVIE) == true
            val wantShows = parentId == VIRTUAL_VIEW_SHOWS ||
                includeTypes?.contains(BaseItemKind.SERIES) == true
            if (wantMovies) {
                items.addAll(
                    database.getMoviesByServerId(serverId)
                        .map { it.toFindroidMovie(database, currentUserId) }
                )
            }
            if (wantShows) {
                items.addAll(
                    database.getShowsByServerId(serverId)
                        .map { it.toFindroidShow(database, currentUserId) }
                )
            }
            val sorted = when (sortBy) {
                SortBy.NAME -> items.sortedBy { it.name }
                SortBy.IMDB_RATING -> items.sortedByDescending {
                    (it as? FindroidMovie)?.communityRating
                        ?: (it as? FindroidShow)?.communityRating
                }
                SortBy.RELEASE_DATE -> items.sortedByDescending {
                    (it as? FindroidMovie)?.premiereDate
                        ?: (it as? FindroidShow)?.productionYear?.let {
                            java.time.LocalDateTime.of(it, 1, 1, 0, 0)
                        }
                }
                else -> items
            }
            val ordered = if (sortOrder == SortOrder.DESCENDING) sorted.reversed() else sorted
            val start = startIndex ?: 0
            val result = ordered.drop(start)
            if (limit != null) result.take(limit) else result
        }
    }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): Flow<PagingData<FindroidItem>> {
        TODO("Not yet implemented")
    }

    override suspend fun getPerson(personId: UUID): FindroidPerson {
        TODO("Not yet implemented")
    }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> {
        TODO("Not yet implemented")
    }

    override suspend fun getFavoriteItems(): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database.getMoviesByServerId(serverId)
                    .map { it.toFindroidMovie(database, currentUserId) }
                    .filter { it.favorite }
            )
            items.addAll(
                database.getShowsByServerId(serverId)
                    .map { it.toFindroidShow(database, currentUserId) }
                    .filter { it.favorite }
            )
            items.addAll(
                database.getEpisodesByServerId(serverId)
                    .map { it.toFindroidEpisode(database, currentUserId) }
                    .filter { it.favorite }
            )
            items
        }
    }

    override suspend fun getSearchItems(query: String): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val movies =
                database
                    .searchMovies(serverId, query)
                    .map { it.toFindroidMovie(database, currentUserId) }
            val shows =
                database
                    .searchShows(serverId, query)
                    .map { it.toFindroidShow(database, currentUserId) }
            val episodes =
                database
                    .searchEpisodes(serverId, query)
                    .map { it.toFindroidEpisode(database, currentUserId) }
            movies + shows + episodes
        }
    }

    override suspend fun getSuggestions(): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database.getMoviesByServerId(serverId)
                    .map { it.toFindroidMovie(database, currentUserId) }
            )
            items.addAll(
                database.getShowsByServerId(serverId)
                    .map { it.toFindroidShow(database, currentUserId) }
            )
            items.shuffled().take(10)
        }
    }

    override suspend fun getResumeItems(): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val movies =
                database
                    .getMoviesByServerId(serverId)
                    .map { it.toFindroidMovie(database, currentUserId) }
                    .filter { it.playbackPositionTicks > 0 }
            val episodes =
                database
                    .getEpisodesByServerId(serverId)
                    .map { it.toFindroidEpisode(database, currentUserId) }
                    .filter { it.playbackPositionTicks > 0 }
            movies + episodes
        }
    }

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            when (parentId) {
                VIRTUAL_VIEW_MOVIES -> {
                    database.getMoviesByServerId(serverId)
                        .map { it.toFindroidMovie(database, currentUserId) }
                }
                VIRTUAL_VIEW_SHOWS -> {
                    database.getShowsByServerId(serverId)
                        .map { it.toFindroidShow(database, currentUserId) }
                }
                else -> emptyList()
            }
        }
    }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            database.getSeasonsByShowId(seriesId).map {
                it.toFindroidSeason(database, currentUserId)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> {
        return withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val result = mutableListOf<FindroidEpisode>()
            val shows =
                database
                    .getShowsByServerId(serverId)
                    .filter { if (seriesId != null) it.id == seriesId else true }
            for (show in shows) {
                val episodes =
                    database.getEpisodesByShowId(show.id).map {
                        it.toFindroidEpisode(database, currentUserId)
                    }
                if (episodes.isEmpty()) continue
                val indexOfLastPlayed = episodes.indexOfLast { it.played }
                if (indexOfLastPlayed == -1) {
                    result.add(episodes.first())
                } else {
                    episodes.getOrNull(indexOfLastPlayed + 1)?.let { result.add(it) }
                }
            }
            result.filter { it.playbackPositionTicks == 0L }
        }
    }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            val items =
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toFindroidEpisode(database, currentUserId)
                }
            if (startItemId != null) return@withContext items.dropWhile { it.id != startItemId }
            items
        }

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            database.getSources(itemId).map { it.toFindroidSource(database) }
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String {
        TODO("Not yet implemented")
    }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) { database.getSegments(itemId).map { it.toFindroidSegment() } }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sources =
                    File(context.filesDir, "trickplay/$itemId").listFiles()
                        ?: return@withContext null
                File(sources.first(), index.toString()).readBytes()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun postCapabilities() {}

    override suspend fun postPlaybackStart(itemId: UUID) {}

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        withContext(Dispatchers.IO) {
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, currentUserId, 0)
                    database.setPlayed(currentUserId, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, currentUserId, 0)
                    database.setPlayed(currentUserId, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, currentUserId, positionTicks)
                    database.setPlayed(currentUserId, itemId, false)
                }
            }
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, currentUserId, positionTicks)
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(currentUserId, itemId, true)
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(currentUserId, itemId, false)
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(currentUserId, itemId, true)
            database.setPlaybackPositionTicks(itemId, currentUserId, 0)
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setPlayed(currentUserId, itemId, false)
            database.setUserDataToBeSynced(currentUserId, itemId, true)
        }
    }

    override fun getBaseUrl(): String {
        return ""
    }

    override suspend fun updateDeviceName(name: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getUserConfiguration(): UserConfiguration? {
        return null
    }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val serverId = currentServerId() ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database
                    .getMoviesByServerId(serverId)
                    .map { it.toFindroidMovie(database, currentUserId) }
            )
            items.addAll(
                database
                    .getShowsByServerId(serverId)
                    .map { it.toFindroidShow(database, currentUserId) }
            )
            items
        }

    override fun getUserId(): UUID {
        return currentUserId
    }
}
