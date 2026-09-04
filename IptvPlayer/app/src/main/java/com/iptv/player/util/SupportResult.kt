package com.iptv.player.util

enum class SupportFailureKind {
    INVALID_INPUT, NETWORK, DNS, TIMEOUT, TLS, HTTP, INVALID_RESPONSE, STORAGE, UNKNOWN,
}

sealed interface SupportResult {
    val userMessage: String

    data class Success(val id: Long, val code: String) : SupportResult {
        override val userMessage: String get() = "Erfolgreich gesendet. Ihre Support-Nummer: $code"
    }

    data class Failure(
        val kind: SupportFailureKind,
        val httpStatus: Int? = null,
    ) : SupportResult {
        // Never echo a server response, URL, exception message or credential into the UI.
        override val userMessage: String get() = when (kind) {
            SupportFailureKind.INVALID_INPUT -> "Bitte prüfen Sie Ihre Nachricht und versuchen Sie es erneut."
            SupportFailureKind.NETWORK, SupportFailureKind.DNS ->
                "Der Support ist nicht erreichbar. Bitte prüfen Sie Ihre Internetverbindung und versuchen Sie es erneut."
            SupportFailureKind.TIMEOUT ->
                "Die Übertragung dauert zu lange. Bitte erneut versuchen; doppelte Meldungen werden vermieden."
            SupportFailureKind.TLS ->
                "Die sichere Verbindung zum Support ist fehlgeschlagen. Bitte prüfen Sie Datum und Uhrzeit Ihres Geräts."
            SupportFailureKind.STORAGE ->
                "Die Support-Anfrage konnte auf diesem Gerät nicht vorbereitet werden. Bitte prüfen Sie den freien Speicher."
            SupportFailureKind.INVALID_RESPONSE ->
                "Der Support hat keine gültige Empfangsbestätigung gesendet. Bitte erneut versuchen."
            SupportFailureKind.UNKNOWN -> "Senden derzeit nicht möglich. Bitte versuchen Sie es erneut."
            SupportFailureKind.HTTP -> when (httpStatus) {
                400 -> "HTTP 400: Die Anfrage konnte nicht verarbeitet werden. Bitte prüfen Sie Ihre Nachricht."
                401 -> "HTTP 401: Die Geräteanmeldung beim Support ist fehlgeschlagen. Bitte erneut versuchen."
                403 -> "HTTP 403: Der Zugriff auf den Support wurde verweigert."
                404 -> "HTTP 404: Der Support-Dienst ist derzeit nicht verfügbar."
                409 -> "HTTP 409: Die Support-Geräteanmeldung konnte nicht bestätigt werden. Bitte den Support kontaktieren."
                413 -> "HTTP 413: Der Bericht ist zu groß. Bitte eine kürzere Nachricht senden."
                429 -> "HTTP 429: Zu viele Anfragen. Bitte später erneut versuchen."
                in 500..599 -> "HTTP $httpStatus: Der Support-Dienst ist vorübergehend nicht verfügbar. Bitte später erneut versuchen."
                else -> "HTTP ${httpStatus ?: "–"}: Senden fehlgeschlagen. Bitte erneut versuchen."
            }
        }
    }
}
