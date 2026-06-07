---
name: In-app YouTube trailer playback
description: How trailers play inside the app (TV) and the parsing/fallback rules that keep it robust.
---

Trailers play INSIDE the app, never via an external ACTION_VIEW kick-out.

- Engine: `com.pierfrancescosoffritti.androidyoutubeplayer:core` WebView IFrame
  player. **Why:** needs no Play-services and no YouTube app, so it works on bare
  Android TV boxes; the official YouTube Android Player API is dead.
- **How to apply:** a dedicated full-screen activity adds the player view as a
  lifecycle observer and autoplays on `onReady`; Back exits.

YouTube-id detection must require a genuine YouTube **host** (youtube.com /
youtu.be / youtube-nocookie.com) or a bare 11-char id — do NOT generic-match a
`v=`/`/embed/` token on any host.
- **Why:** a direct (non-YouTube) media trailer URL can contain a `v=...` token
  and would be misrouted into the IFrame player instead of the in-app VOD player.
- **How to apply:** non-YouTube URLs fall through to the in-app VOD player
  (libVLC/Media3); only real YouTube refs use the IFrame player.

Robustness: inflating the IFrame player constructs a WebView, which **throws on
devices with no/disabled WebView provider** — guard inflation and, like the
player `onError`, fall back ONCE to opening the canonical
`https://www.youtube.com/watch?v=<id>` externally so the user never gets a black
screen.
