# Iris Push

Iris is a small, open push-notification relay — a drop-in alternative to
Firebase Cloud Messaging for apps and devices that can't or don't want to
depend on Google Play Services. It was built for the
[Lethe](https://letheapp.de) ecosystem (Messenger, Media Player, a custom
Android OS build bundling Lethe, [NocTurn](https://letheapp.de) and
[VectoMail](https://letheapp.de) as system apps), but the protocol and this
reference client have no Lethe-specific dependency — any app or server can
use them.

## Why

FCM requires Google Play Services. That's a non-starter for F-Droid/FOSS
builds, custom ROMs, or anywhere you simply don't want a Google dependency.
The usual fallback — a permanent app-local WebSocket + foreground service —
works, but if every app on a device does it independently, you get N
redundant persistent connections and N wakelocks instead of one.

Iris centralizes that into a single device-wide service: one persistent
connection to a server, fanned out locally to whichever installed apps have
registered for pushes.

## How it fits together

```
 Your backend                    Iris server                  Device
┌──────────────┐   POST /push/send   ┌───────────┐   WS    ┌────────────────┐
│ "user X got a │ ───────────────────▶│  Iris     │◀───────▶│ IrisPushService │
│  message"     │                     │  relay    │         │ (this repo)    │
└──────────────┘                     └───────────┘         └───────┬────────┘
                                                                     │ signature-protected
                                                                     │ broadcast
                                                                     ▼
                                                             ┌────────────────┐
                                                             │   Your app     │
                                                             └────────────────┘
```

- **Server** — three endpoints (register, connect, send). The reference
  implementation is the Lethe backend's `/push/register`, `/iris/connect/{token}`
  and `/push/send` (FastAPI, see `PROTOCOL.md`). Nothing about the protocol
  is Lethe-specific — implement the same three endpoints anywhere and this
  client works against it.
- **This repo (`de.letheapp.iris`)** — a single Android service holding the
  one persistent connection per device, broadcasting incoming pushes
  on-device to whichever app registered the token.
- **Your app** — registers itself (package name + optional device ID) via
  `/push/register`, gets a token, and listens for the signature-protected
  broadcast this service sends.

Full wire protocol: [`PROTOCOL.md`](PROTOCOL.md).

## Status

Early reference implementation. The core loop (register → connect → receive
→ broadcast, with reconnect-with-backoff and a 45s keepalive ping) works end
to end, in two forms now:

- **App-level** (`app/`, `IrisPushService`) — works standalone on any
  device, no OS changes needed. This is what you get from a normal
  sideload/F-Droid-style install.
- **OS-integrated native daemon** (`daemon/`, `irisd`) — Tier 2 of
  [`OS_INTEGRATION_PLAN.md`](OS_INTEGRATION_PLAN.md): moves the persistent
  connection into an `init`-managed native process so it survives
  Doze/App-Standby the way FCM/GMS does, with `app/`'s
  `IrisBridgeService` as the thin on-device relay between the daemon and
  the signature-protected broadcast. End-to-end tested against the live
  reference server in standalone mode; not yet built into an actual AOSP
  tree or run under a real `init`/SELinux domain — see `daemon/README.md`
  for exact status and `IrisDelivery.kt` in `app/` for how a device
  automatically prefers the daemon over the app-level client when both
  are present.

Known gaps are listed in `PROTOCOL.md` under "Known limitations" — most
notably: no cross-instance routing on the server side, and no offline
queueing. PRs welcome.

## Using it

### As the receiving app

1. Build and install this APK (or a build of it signed with your own key —
   see "Security model" below for why that matters), configure a server URL
   and your app's package name in its UI, tap "Registrieren" to get a token,
   then start the service.
2. In your app, declare a receiver for the broadcast:

   ```xml
   <receiver android:name=".PushReceiver" android:exported="false">
       <intent-filter>
           <action android:name="de.letheapp.iris.PUSH" />
       </intent-filter>
   </receiver>
   ```

   and read `title` / `body` / `data_json` from the incoming `Intent`'s
   extras (see `PROTOCOL.md` for the exact extra names).
3. Your backend calls `POST /push/send` with the token your app registered,
   whenever it wants to notify that user/device.

### As a server operator

Implement the three endpoints in `PROTOCOL.md` (`/push/register`,
`/iris/connect/{token}`, `/push/send`) in your own backend — there's no
requirement to use Lethe's. The reference server-side implementation lives
in the Lethe backend if you want a working example to read.

## Security model

- Tokens are bearer credentials, exactly like an FCM device token: whoever
  holds one can push to it, and whoever's server issued it can see which
  app/device it belongs to. Treat them with the same care.
- The on-device broadcast (`de.letheapp.iris.PUSH`) requires the
  `de.letheapp.iris.RECEIVE_PUSH` signature permission, so only apps signed
  with the *same key* as this Iris build can receive it. That's intentional:
  it's the right model when Iris and your app ship together in one signed
  image (a custom ROM, an APK bundle you control end to end). If you're
  distributing your app independently and expect it to talk to a
  third-party-signed Iris build, this permission model won't grant access —
  build and sign your own Iris APK instead, keyed to your app.

## Building

Standard Gradle/Android project, no Lethe-specific tooling required:

```bash
./gradlew assembleDebug
```

Needs JDK 17 and the Android SDK (`compileSdk`/`targetSdk` 35, `minSdk` 26).

## License

MIT — see [`LICENSE`](LICENSE).
