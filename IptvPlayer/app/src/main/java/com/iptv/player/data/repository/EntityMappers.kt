/*
 * EntityMappers.kt
 * Room entity <-> domain model mapping shared by the domain repositories.
 * Implemented as an interface with member extensions so each repository keeps
 * calling `entity.toModel()` unchanged; only the field-level codec is abstract.
 */
package com.iptv.player.data.repository

import com.iptv.player.data.local.entity.ChannelEntity
import com.iptv.player.data.local.entity.EpisodeEntity
import com.iptv.player.data.local.entity.ProfileEntity
import com.iptv.player.data.local.entity.ProgramEntity
import com.iptv.player.data.local.entity.ResumeEntity
import com.iptv.player.data.local.entity.SeriesEntity
import com.iptv.player.data.local.entity.VodEntity
import com.iptv.player.data.model.Channel
import com.iptv.player.data.model.ContentType
import com.iptv.player.data.model.ContinueItem
import com.iptv.player.data.model.Episode
import com.iptv.player.data.model.Profile
import com.iptv.player.data.model.Program
import com.iptv.player.data.model.ResumeKind
import com.iptv.player.data.model.Series
import com.iptv.player.data.model.SourceConfig
import com.iptv.player.data.model.SourceType
import com.iptv.player.data.model.VodItem
import com.iptv.player.data.remote.MetadataPolicy
import com.iptv.player.security.SecureValueCodec
import com.iptv.player.util.KululuEndpoint

internal interface EntityMappers {
    val secureValues: SecureValueCodec

    // ---- Mapping helpers ------------------------------------------------

    fun ChannelEntity.toModel(isFav: Boolean = false) = Channel(
        id = id,
        name = name,
        streamUrl = KululuEndpoint.migrateLegacyAssetUrl(secureValues.decrypt(streamUrl)).orEmpty(),
        logoUrl = KululuEndpoint.migrateLegacyAssetUrl(logoUrl),
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number,
        type = ContentType.valueOf(type), isFavorite = isFav, catchupDays = catchupDays,
        position = position, categoryPosition = categoryPosition, isRadio = isRadio
    )

    fun Channel.toEntity() = ChannelEntity(
        id = id, name = name, streamUrl = secureValues.encrypt(streamUrl), logoUrl = logoUrl,
        categoryId = categoryId, categoryName = categoryName,
        epgChannelId = epgChannelId, number = number, type = type.name,
        catchupDays = catchupDays, position = position, categoryPosition = categoryPosition,
        isRadio = type == ContentType.LIVE && isRadioCategory(categoryName)
    )

    fun VodEntity.toModel() = VodItem(
        id = id,
        name = name,
        streamUrl = KululuEndpoint.migrateLegacyAssetUrl(secureValues.decrypt(streamUrl)).orEmpty(),
        posterUrl = KululuEndpoint.migrateLegacyAssetUrl(posterUrl),
        backdropUrl = KululuEndpoint.migrateLegacyAssetUrl(backdropUrl),
        categoryId = categoryId, categoryName = categoryName, rating = rating,
        plot = plot, cast = cast, director = director, genre = genre,
        releaseDate = releaseDate, durationSecs = durationSecs,
        trailerUrl = KululuEndpoint.migrateLegacyAssetUrl(trailerUrl), tmdbId = tmdbId, addedAt = addedAt
    )

    fun SeriesEntity.toModel() = Series(
        id = id,
        name = name,
        posterUrl = KululuEndpoint.migrateLegacyAssetUrl(posterUrl),
        backdropUrl = KululuEndpoint.migrateLegacyAssetUrl(backdropUrl),
        categoryId = categoryId,
        categoryName = categoryName, rating = rating, plot = plot, cast = cast,
        director = director, genre = genre, releaseDate = releaseDate,
        trailerUrl = KululuEndpoint.migrateLegacyAssetUrl(trailerUrl), tmdbId = tmdbId,
        addedAt = MetadataPolicy.seriesFreshness(addedAt, latestEpisodeAt)
    )

    fun EpisodeEntity.toModel() = Episode(
        id = id, seriesId = seriesId, seasonNumber = seasonNumber,
        episodeNumber = episodeNumber, title = title,
        streamUrl = KululuEndpoint.migrateLegacyAssetUrl(secureValues.decrypt(streamUrl)).orEmpty(),
        plot = plot,
        durationSecs = durationSecs,
        posterUrl = KululuEndpoint.migrateLegacyAssetUrl(posterUrl),
    )

    fun ProgramEntity.toModel() = Program(
        epgChannelId = epgChannelId, title = title, description = description,
        startMs = startMs, stopMs = stopMs
    )

    fun ProfileEntity.toModel() = Profile(
        id = id,
        name = name,
        config = SourceConfig(
            type = runCatching { SourceType.valueOf(sourceType) }.getOrDefault(SourceType.XTREAM),
            serverUrl = KululuEndpoint.migrateLegacyServerUrl(
                secureValues.decrypt(serverUrl),
            ),
            username = secureValues.decrypt(username),
            password = secureValues.decrypt(password),
            m3uUrl = secureValues.decrypt(m3uUrl),
        ),
        lockAdult = lockAdult
    )

    fun ResumeEntity.toContinueItem() = ContinueItem(
        contentId = contentId,
        kind = ResumeKind.fromRaw(type),
        title = title,
        posterUrl = KululuEndpoint.migrateLegacyAssetUrl(posterUrl),
        streamUrl = KululuEndpoint.migrateLegacyAssetUrl(secureValues.decrypt(streamUrl)).orEmpty(),
        positionMs = positionMs,
        durationMs = durationMs,
        vodId = vodId,
        seriesId = seriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber
    )
}

/**
 * Heuristic radio classifier. Providers don't flag radio explicitly, so a
 * channel is treated as radio when its category name reads like one
 * (e.g. "Radio", "RADYO", "FM Radio"). Centralized so the Live TV page and
 * the Radio section stay in lockstep with the DB back-fill migration.
 */
internal fun isRadioCategory(name: String?): Boolean {
    val n = name?.lowercase() ?: return false
    return n.contains("radio") || n.contains("radyo")
}
