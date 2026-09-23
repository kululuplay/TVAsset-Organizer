package com.iptv.player.playback.core

/** The support service evolves independently of the legacy QoE spool. Never forward new fields implicitly. */
object PlaybackSupportFields {
    private val allowed = setOf(
        "schema", "session_id", "content_kind", "started_at_epoch_ms", "initial_engine", "final_engine",
        "transport", "capability_fingerprint", "ended_at_epoch_ms", "end_reason", "session_duration_ms",
        "time_to_ready_ms", "time_to_first_frame_ms", "rebuffer_count", "rebuffer_duration_ms", "engine_switch_count",
        "state", "state_duration_ms", "paused_duration_ms", "frames_known", "rendered_frames", "dropped_frames",
        "last_frame_age_ms", "current_buffer_ms", "video_codec", "video_decoder", "video_width", "video_height",
        "frame_rate", "source_open_ms", "time_to_first_byte_ms", "first_byte_to_first_frame_ms", "source_timing_scope",
        "failure_codes", "failure_categories", "failure_phases", "failure_components", "failure_retry_advice",
        "failure_http_statuses", "audio_failure_codecs", "audio_failure_decoders", "audio_failure_sink_events",
        "audio_failure_output_modes", "discarded_failure_count", "final",
    )
    fun from(record: PlaybackQoeRecord): Map<String, Any> = project(record.toSafeFields())
    internal fun project(fields: Map<String, Any>): Map<String, Any> = fields.filter { (key, value) ->
        key in allowed && (value is String || value is Number || value is Boolean)
    }
}
