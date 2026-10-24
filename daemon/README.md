# irisd - native Iris daemon (Tier 2)

First working version of the `init`-managed native daemon described in
[`OS_INTEGRATION_PLAN.md`](../OS_INTEGRATION_PLAN.md). Replaces the
persistent TLS/WebSocket connection that `IrisPushService` (the app-level
Kotlin client in `../app/`) otherwise has to hold itself, moving it into a
small C process that `init` starts and keeps alive at the same privilege
tier as `netd`/`rild` - so it isn't subject to the Doze/App-Standby
restrictions an ordinary app's socket is.

**Status: functional, end-to-end tested against the live reference server
(letheapp.de) in standalone/dev mode. Not yet built into any AOSP tree, not
yet running under a real `init`/SELinux domain on a device.** Treat this as
the Tier 2 starting point the integration plan calls for, not a finished,
hardened daemon - see "Known simplifications" below before relying on it.

## What it does

- Reads one small config file per registered app from a directory (default
  `/data/misc/iris/tokens/`, override via `IRIS_CONFIG_DIR`), each holding
  `server_url`, `token`, `package_name` (same token you get back from
  `POST /push/register`, see `../PROTOCOL.md`).
- Opens one TLS + WebSocket connection per config file to
  `/iris/connect/{token}`, sends `"ping"` every 45s, reconnects with a fixed
  5s backoff on any failure.
- Watches the config directory via inotify, so apps can be
  registered/unregistered without restarting the daemon.
- On an incoming `{"type":"push",...}` frame, turns it into one
  newline-terminated JSON line (`package_name`/`title`/`body`/`data_json`)
  and writes it to every client connected to its local control socket
  (`iris_daemon`) - that's `IrisBridgeService` in the app module, which
  turns it into the same `de.letheapp.iris.PUSH` signature-protected
  broadcast `IrisPushService` sends when it's the one holding the
  connection directly.

## Building

**Standalone (dev/test only, this is how it was validated):**

```
cd daemon
make                      # needs libssl-dev
IRIS_CONFIG_DIR=/path/to/tokens IRIS_BRIDGE_SOCKET_PATH=/tmp/iris_daemon.sock ./irisd
```

In this mode it creates its own plain `AF_UNIX` socket at
`IRIS_BRIDGE_SOCKET_PATH` (default `/tmp/iris_daemon.sock`) instead of
expecting an init-provided fd - useful for exercising the protocol logic
against a real Iris server without any device/AOSP tree at all. This is
exactly how it was smoke-tested: a config file pointed at a token from the
real `letheapp.de` backend, a small script acting as the `IrisBridgeService`
stand-in connected to the socket, and a real `/push/send` call round-tripped
through to that script's stdout.

**On-device (the actual point of Tier 2):** see `Android.bp` - add this
directory as a module in your AOSP tree, add `irisd` to `PRODUCT_PACKAGES`,
and work through `init/iris_daemon.rc` + `sepolicy/` as described in
`OS_INTEGRATION_PLAN.md`. None of that has been exercised on a real device
yet - the `ANDROID_BUILD`-gated code path (reading
`ANDROID_SOCKET_iris_daemon` from init) is written to the documented AOSP
`socket` option behavior but hasn't been run under a real `init`.

## Known simplifications (v1 - tighten before production use)

- **Trusted-input JSON parsing.** `json_find_string`/`json_find_raw_object`
  are minimal field extractors, not a general parser - fine for payloads
  from your own backend, not something to point at untrusted senders
  without first swapping in a real JSON library.
- **Thread-per-connection, not a single `epoll` loop.** Each registered
  app's connection runs in its own blocking pthread; the plan's "ideal"
  version is one thread multiplexing every socket via `epoll_wait()`.
  Battery/CPU-wise this is close enough (a blocked thread costs nothing
  while idle either way) but it's a reasonable follow-up if you want the
  absolute minimum footprint.
- **No modify-in-place config reload.** Adding/removing a `*.conf` file is
  picked up automatically; editing one in place is not (delete+recreate
  works, in-place edit doesn't trigger a reconnect with the new values).
- **No offline queue / no delivery receipts** - same limitations as the
  reference server overall, see `../PROTOCOL.md`.
- **TLS certificate validation uses the system default trust store only**
  (`SSL_CTX_set_default_verify_paths`) - fine on both a normal Linux dev box
  and a real Android build (which has its own CA bundle at the usual path),
  but no certificate pinning.
- **Outbound frames assume payload < 65536 bytes** (always true today -
  the only outbound frame is `"ping"`) and control frames (`pong` echo)
  assume a small payload (`<=128` bytes here); a WebSocket intermediary
  sending odd oversized control frames isn't something this handles.
