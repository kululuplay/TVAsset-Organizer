# Release checklist

A release opens to every device at once (`UPDATE_ROLLOUT.md`), so what is not
checked before the merge is checked by customers. Three layers, in this order.

## 1. Automatic (CI, every pull request)

Unit tests, lint, the support desk contract, the upgrade check from the last
release, the secret scan. Red means no merge.

## 2. Start check on the emulator (the developer, about two minutes)

```bash
cd IptvPlayer && ./gradlew -PabiFilter=x86 :app:assembleDebug
scripts/emulator_smoke.sh IptvPlayer/app/build/outputs/apk/debug/app-debug.apk emulator-5580
```

Installs over the previous build, starts the app, fails on a crash or when the
app is not in front. It proves the start path only: the emulator has no real
decoder, no account and no remote control.

## 3. Ten steps on real devices (the operator, about ten minutes)

On one Fire TV stick and one Android TV / Google TV set, with the release
candidate APK from the pull request's CI run. Anything that fails stops the
release.

1. Install over the current version. The app opens and is still signed in.
2. Home appears; the channel list and the film rows fill.
3. Live TV: open three channels (a national HD channel, a sports channel, an
   SD channel). Each shows a picture within about five seconds.
4. Zap up and down ten times. No black screen, sound always follows the picture.
5. Leave one channel running for ten minutes. No freeze, no restart.
6. Film: start one, jump ten minutes ahead, pause, resume, leave. Open it
   again: it continues where it stopped.
7. Series: play the end of an episode; the next one starts by itself.
8. Search for a title; add and remove a favourite.
9. Open the adult category: the PIN is asked for.
10. Settings, About: the new version number is shown. Send a test request from
    "request and complaint"; it arrives in the support desk.

Then the features that are new in the release, each once. The release notes
of the version list them.

## After the release

The day after, read the field numbers from the support desk database (start
time, fatal sessions, engine rescues by version) and compare them with the
previous version. Automatic crash reports arrive in the support desk as
diagnostic reports that begin with "Otomatik rapor".
