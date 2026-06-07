---
name: EPG channel↔program id matching
description: How to make XMLTV EPG actually show up for live channels
---

EPG "not showing despite data existing" is almost always a channel-id↔program-id
mismatch, not missing data. Three rules:

1. **Normalize on BOTH sides.** Store program `epgChannelId` trimmed+lowercased,
   and normalize the channel's resolved epg id the same way before querying.
   SQLite `=` on TEXT is case-sensitive; providers vary case/whitespace
   (`CNN.us` vs `cnn.us`, trailing spaces).

2. **Name fallback.** When a channel's tvg-id is missing or matches no programs,
   fall back to matching the channel's display name against the XMLTV
   `<channel><display-name>` entries. Build that name→id index **in memory**
   during the guide refresh — NOT a new Room table: the DB uses
   `fallbackToDestructiveMigration()`, so adding a table wipes all user data
   (favorites, resume). Cold-start gap is acceptable because the launch prefetch
   re-runs the EPG refresh and rebuilds the index.

3. **parseTime must tolerate glued offsets.** XMLTV times are
   `yyyyMMddHHmmss ±HHMM`, but the offset is sometimes glued without a space
   (`20240101120000+0300`). A naive "contains space?" check fails the glued form,
   returns 0, and the program is dropped (stop>start guard). Split off any
   trailing +/-HHMM offset regardless of the space.

**Resolve precedence:** manual override > direct tvg-id (if it has programs) >
name fallback (if it has programs) > direct id as last resort.
