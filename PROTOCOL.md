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

## Delivery to the target app on-device

This repo's reference client (`IrisPushService`) is deliberately generic: it
doesn't know anything about the app it delivers to beyond its package name.
On receiving a push over the WebSocket, it broadcasts:

- Action: `de.letheapp.iris.PUSH`
- Protected by permission: `de.letheapp.iris.RECEIVE_PUSH` (`protectionLevel="signature"`)
- Extras: `package_name`, `title`, `body`, `data_json` (the `data` object, re-serialized to a JSON string)

A receiving app needs to:

1. Hold `de.letheapp.iris.RECEIVE_PUSH` — which, because it's
   signature-protected, means it must be **signed with the same key** as the
   Iris build it talks to. This is the security boundary: arbitrary installed
   apps cannot register a receiver for pushes meant for someone else. It's
   the right fit for apps bundled into the same custom-OS/ROM image, signed
   with one key; it's the wrong fit for an app you expect strangers to
   install standalone — for that, run your own Iris build signed with your
   own key and ship it alongside your app.
2. Declare a `<receiver>` with an `<intent-filter>` for
   `de.letheapp.iris.PUSH`, and turn the extras into whatever local
   notification/UI update it needs.

## Known limitations (v1)

- **Single-instance delivery.** The reference server keeps WebSocket
  connections in local memory per backend process. If you run Iris behind a
  load balancer with multiple instances, a `/push/send` call only succeeds if
  it happens to land on the instance holding that device's connection.
  Fixing this generically needs a shared routing layer (e.g. Redis pub/sub)
  across instances — out of scope for this reference implementation, but
  straightforward to add if you're already running one.
- **No offline queueing.** A push sent while the device is disconnected is
  simply dropped; there's no store-and-forward.
- **No delivery receipts.** The server only knows a frame was written to the
  socket, not that the app handled it.

Contributions tightening any of the above are welcome.
