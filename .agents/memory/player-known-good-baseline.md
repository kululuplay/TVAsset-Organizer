---
name: Player known-good baseline (v1.0.1 / commit c8e9eac)
description: The live-TV + VOD player state the user considers regression-free, and what reverting to it requires.
---

# Player "known-good" baseline

The user treats the player as it was at **git tag v1.0.1 (commit c8e9eac)** as the
last regression-free live-TV + VOD playback. c8e9eac itself is ONLY a versionName
bump — the working player is that commit's *tree*, restore the files from it.

## Player module is self-contained
The player module (`player/` engines + `ui/player/`) has a small, clean external
surface, so it can be reverted to an old commit in isolation:
- Engines/controller only reference each other + `data/model` (PlayerMode, Channel,
  ResumeMeta, NowNext/Program, ResumeKind) and `ServiceLocator.repository/settings`.
- Repo methods it needs: getResume / saveResume / getNowNext / observeChannels /
  getChannel / toggleFavorite / markWatched.

## Integration drift to fix when reverting the player to baseline
Newer screens were built against the *newer* player, so a clean revert must also:
1. **Keep `PlayerActivity.EXTRA_CATEGORY_ID`** — `HomeActivity` and
   `FavoritesActivity` pass a category to scope channel zapping. The baseline
   player has no category-scoped zap, but the constant must exist or those two
   files fail to compile. Baseline player simply ignores the extra (zaps the full
   live list).
2. **Delete files that didn't exist at baseline** and their tests, else they fail
   to compile against the old engine seams: `player/PlayerScheduler.kt`,
   `ui/player/VodPlaybackCoordinator.kt`, and the player unit tests
   (PlayerControllerConnectionTest, PlayerTestDoubles, VodPlaybackCoordinatorTest).

## What a baseline revert sacrifices
Reverting drops every post-v1.0.1 player fix recorded in other memory notes
(single-connection contract, ExoPlayer→libVLC audio fallback, green-screen
TextureView fix, paging-refresh focus guard, category-scoped zap). If those bugs
resurface, they must be re-applied on top of the baseline rather than reverted away
again.
