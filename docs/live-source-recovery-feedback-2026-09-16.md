# Live source failures and recovery feedback

## Incident evidence

A customer reported live channels remaining on the loading indicator while the
catalogue and EPG were visible, then reported that playback opened again. The
current installed version and incident-time client trace have not been supplied.
Those observations alone do not establish an application, account, network or
provider root cause. No customer credentials or personal identifiers are needed
in this code change or this report.

Main already includes the v1.5.89 bounded startup/recovery change: 45 seconds per
attempt, 90 seconds per controller episode and a separate provider-drain bound.
This patch preserves those limits and the one-provider-connection ownership gate.
It does not restart provider services/channels or remove account restrictions.

## Additional gaps found in source

- Media3 source exceptions lost DNS/TLS/timeout details at the engine boundary.
- A repeated known source error entered the generic startup compatibility ladder,
  potentially changing decoders even when the provider explicitly returned 503.
- Structured failures reached telemetry, but both preview and fullscreen showed
  a generic playback failure instead of the known cause.
- Connectivity return was watched on the dashboard, not on playback screens.
  A network failure that exhausted its budget could therefore remain at manual
  retry after the device network returned.

## Resulting behavior

Known source failures now retain typed, credential-free evidence. HTTP access
denials and ordinary terminal 4xx responses stop. 408/429/5xx and transport errors
use the same engine and the existing finite reconnect budget. A 429 waits at
least 30 seconds instead of the first one-second backoff. Unknown native errors
remain unknown; parser/decoder faults retain bounded player recovery. A generic
timeout cannot erase a previous concrete server response from the displayed cause.
Disguised HLS rejection is a terminal format error and never enables HLS playback.

Preview/fullscreen display localized messages for access denial, missing stream,
rate limiting, server failure, connectivity, secure-connection failure, format,
player output and timeout. Messages are provided in English, German, Turkish,
French, Dutch and Arabic. Raw URLs, exception text and server bodies are excluded.
Terminal outcomes remove the spinner and retain the explicit retry action.

A real observed offline-to-online transition may request one new foreground
attempt after a recoverable network error, including after its earlier terminal
outcome. An initial online callback, repeated online callbacks, healthy buffered
playback, a denial, or a server-side HTTP response do not cause this retry.
The Activities still apply their Cast, audio-focus and provider-drain ownership
gates. Pending channel changes take precedence. Pause/background/release disable
the gate; callbacks belonging to an unregistered network watcher are ignored.

Connectivity capability is not proof that the provider is reachable. If a provider
outage outlasts the finite budget without a device network transition, the user
gets the specific error and manual retry, not an endless hidden polling loop.
The app cannot force a denied or unavailable upstream stream to become playable.

## Validation scope

Regression fixtures cover terminal denials, repeated transient source failures,
rate-limit backoff, player failures, typed Media3 exceptions, preserved source
cause, absolute deadlines, initial offline state, one retry per network return,
foreground/pause/release gates, healthy playback and replacement requests.
These do not reproduce the customer's incident or certify hardware output.

The existing device-measured buffering work is retained underneath this branch.
Physical stick validation remains pending because its ADB endpoint was unavailable.
Customers require a newly built and released APK to receive this behavior; code
changes cannot modify an already installed 1.5.88/1.5.89 binary. This patch does
not publish a production release or change rollout configuration.
