---
name: Favorites id namespaces
description: Favorites table mixes three id types; the channel-only query silently drops the others.
---
The single `favorites` table stores ids in three namespaces: raw channel id, `vod_<id>` (movies), `series_<id>` (series). The toggle is set in VodDetailActivity / SeriesDetailActivity.

**Why:** `FavoriteDao.observeFavoriteChannels()` INNER JOINs the `channels` table, so favorited movies/series are silently excluded — that's why they never appeared in any channel-backed favorites list.

**How to apply:** Any feature that lists "all favorites" must read raw favorite rows (`observeAll()`), strip the prefix, and resolve via the matching DAO (vod/series/channel). For UI gating, carry `categoryName` through so parental (adult) checks match the `item.isAdult()` pattern used by VodActivity/SeriesActivity/SearchActivity.
