---
name: VLC skiploopfilter macroblocking
description: Why avcodec-skiploopfilter=all causes macroblocking on the software decode path, and the balanced value to use.
---

`--avcodec-skiploopfilter=all` drops the H.264/H.265 in-loop **deblocking**
filter on every frame. Block errors on reference frames then accumulate and
propagate -> visible macroblocking / "rain"/breakup on some channels, while
**audio stays perfectly fine** (it's a video-only quality loss). Only affects
libVLC's **software** avcodec path (HW decoder deblocks itself).

**Rule:** use `--avcodec-skiploopfilter=nonref`, not `all`. `nonref` keeps
deblocking on reference frames (no error build-up) and only skips disposable
non-reference frames, preserving most of the CPU savings on weak Amlogic boxes.

**Why:** users reported live-TV (and the same risk on VOD) macroblocking with
stable audio; the only code cause that converts a marginal stream into visible
blocking is the skipped deblocking filter.

**How to apply:** this flag lives in BOTH libVLC option sets — `VlcPlayerEngine`
(live) and `VodPlayerActivity.buildPlayer()` (VOD); keep them in lockstep. If a
weak box stutters in SOFTWARE mode on extreme-bitrate streams, consider gating
back to `all` only for that mode rather than reverting everywhere.
