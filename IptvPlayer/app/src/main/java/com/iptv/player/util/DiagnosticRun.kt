package com.iptv.player.util

import java.util.UUID

/** A process marker avoids presenting an old file tail as evidence of this run. */
internal object DiagnosticRun {
    val id: String = UUID.randomUUID().toString()
    val marker: String = "[run=$id]"

    fun currentText(text: String, expectedMarker: String = marker): String {
        var current = false
        return text.lineSequence().filter { line ->
            if ("[run=" in line) current = expectedMarker in line
            current
        }.joinToString("\n")
    }
}
