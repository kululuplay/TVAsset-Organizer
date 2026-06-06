---
name: Dashboard keyless weather + signal
description: How the dashboard shows real weather without API keys or location permission, and how the network-signal chip is graded.
---

# Keyless weather on the dashboard

The dashboard weather pill is **real** weather with **no API key and no location
permission**: it resolves an approximate location via IP geolocation
(`ipapi.co/json`) then queries Open-Meteo `current_weather` (both HTTPS, keyless).
Result is cached for the session (~30 min). Mapping is weathercode -> condition
string + icon drawable.

**Why:** the app intentionally has NO location permissions in the manifest (TV app),
and we avoid keyed weather services so there is nothing to provision or leak.

**How to apply:** do NOT add `ACCESS_FINE/COARSE_LOCATION` or a keyed weather API to
"improve" this — IP geolocation + Open-Meteo is the deliberate design. Fetch off the
main thread (`Dispatchers.IO`) and fall back to an "unavailable" state on any failure.

# Network-signal chip

`NetworkSignal.current()` grades GOOD/WEAK/OFFLINE from `ConnectivityManager`
capabilities (INTERNET + VALIDATED), refined on Wi-Fi by RSSI bars. Reading Wi-Fi
RSSI needs `ACCESS_WIFI_STATE` in the manifest and must be wrapped in `runCatching`
(some devices/SDKs throw or return null for `connectionInfo`).
