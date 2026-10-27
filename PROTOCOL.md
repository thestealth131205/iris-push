# Iris wire protocol

Iris is a minimal, open alternative to FCM for apps/devices that can't or
don't want to depend on Google Play Services (custom Android builds, FOSS
apps, self-hosted backends). It has three HTTP/WS endpoints. Any server can
implement them; the reference implementation lives in the
[Lethe](https://github.com/thestealth131205) backend (`main.py`).

## 1. Register a device

```
POST /push/register
Content-Type: application/json

{"package_name": "de.letheapp.lethe", "device_id": "<optional, stable per device>"}
```

- No authentication required — `package_name` identifies the receiving app.
- Rate-limited (reference server: 20 requests/hour per IP).
- `device_id` makes registration idempotent: the same `package_name` +
  `device_id` pair gets the same token back on repeated calls instead of a
  new one each time. Omit it to always mint a fresh token.

Response:

```json
{"token": "<url-safe opaque string>", "package_name": "de.letheapp.lethe", "created": true}
```

Store `token` — it is both your identity on the WebSocket (step 2) and what
senders need to push to you (step 3).

## 2. Receive pushes (device → server, persistent)

```
GET /iris/connect/{token}      (upgrade: websocket)
```

- Server closes with code `4001` if the token is unknown or revoked.
- Client should send the text frame `"ping"` roughly every 45s to keep the
  connection (and any reverse proxy in between) alive; server replies
  `"pong"`.
- Incoming push, as a JSON text frame:

```json
{"type": "push", "title": "...", "body": "...", "data": {"key": "value"}}
```

There is currently **no offline queue** — if no device is connected for a
token when `/push/send` is called, the push is dropped (`delivered: false`).
A server could add persistence/retry on top of this without changing the
protocol.

## 3. Send a push (any sender → server)

```
POST /push/send
Content-Type: application/json

{"token": "<recipient token>", "title": "...", "body": "...", "data": {"key": "value"}}
```

- No authentication beyond knowing the recipient's token — same trust model
  as an FCM server key + device token pair. Keep tokens as confidential as
  you would an FCM token.
- Response: `{"delivered": true|false}`. `true` only means a WebSocket frame
  was handed to a currently-connected client, not that the receiving app
  acted on it.

## Registration on-device

An app hands Iris its token itself (no user step), after obtaining it from
`/push/register` (or, for Lethe, `/push/tokens`):

```kotlin
sendBroadcast(Intent("de.letheapp.iris.action.REGISTER").apply {
    setPackage("de.letheapp.iris")
    putExtra("package_name", packageName)
    putExtra("token", token)
    // Proof of identity: the system fills in the creator package of a
    // PendingIntent, so Iris can verify package_name wasn't spoofed.
    putExtra("app", PendingIntent.getBroadcast(this, 0, Intent(this, MyIrisReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
})
```

Iris answers with `de.letheapp.iris.action.REGISTERED` (extras
`package_name`, `token`) once it is actually receiving for that app. If the
server later rejects the token (WebSocket close `4001`), Iris drops the
registration and sends `de.letheapp.iris.action.UNREGISTERED` - fetch a new
token and register again. `de.letheapp.iris.action.UNREGISTER` (same extras
plus `app`) removes a registration.

The app needs `<queries><package android:name="de.letheapp.iris"/></queries>`
(Android 11+) to see and reach Iris.

## Delivery to the target app on-device

On receiving a push over the WebSocket, Iris (or, on OS builds, the native
`irisd` daemon directly via ActivityManager) sends an explicit broadcast to
the target package:

- Action: `de.letheapp.iris.PUSH`
- Flags: `FLAG_RECEIVER_FOREGROUND | FLAG_INCLUDE_STOPPED_PACKAGES`
- Extras: `package_name`, `token`, `title`, `body`, `data_json` (the `data`
  object, re-serialized to a JSON string)

Like an FCM message, this **starts the target app's process** for its
manifest receiver - the app does not need any running service or
connection of its own. A receiving app needs to:

1. Declare an exported `<receiver>` with an `<intent-filter>` for
   `de.letheapp.iris.PUSH` (and `REGISTERED` / `UNREGISTERED`).
2. **Drop every PUSH whose `token` extra isn't the token it registered.** The
   receiver is necessarily exported, so any app could send it a fake PUSH;
   the token is only known to the app, its server and Iris.
3. Turn the extras into whatever local notification/UI update it needs,
   within the ~10s a receiver may run (`goAsync()`).

The broadcast deliberately carries no permission: a signature permission
would only work for apps signed with the same key as Iris, which rules out
apps installed from F-Droid/Play.

## Known limitations (v1)

- **Multi-instance routing needs a shared layer.** The reference server keeps
  WebSocket connections in local memory per backend process. The Lethe
  backend routes across instances via Redis pub/sub (`iris_instance` hash +
  `iris:instance:<id>` channels); a custom server behind a load balancer
  needs something equivalent.
- **No offline queueing.** A push sent while the device is disconnected is
  simply dropped; there's no store-and-forward.
- **No delivery receipts.** The server only knows a frame was written to the
  socket, not that the app handled it.

Contributions tightening any of the above are welcome.
