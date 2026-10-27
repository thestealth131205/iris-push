# irisd control socket protocol

`/dev/socket/iris_daemon` (reserved namespace `iris_daemon`), stream socket,
newline-terminated lines. The OS image and the Iris APK update independently,
so this protocol is **additive only**: unknown commands are ignored, unknown
JSON fields must be ignored, and a client checks `protocol_version` from
`STATUS` before sending anything newer than `STATUS`. `protocol_version` is
bumped only for an incompatible change (which should not happen).

Every connection is subscribed to the push fan-out by default (compat with
the v1 bridge). One-shot command clients send `UNSUBSCRIBE` first and skip
reply lines that are not theirs.

## Daemon -> client (unprompted)

```json
{"package_name": "...", "title": "...", "body": "...", "data_json": "{...}"}
```

## Client -> daemon

| Line | Reply | Notes |
|---|---|---|
| `STATUS` | `{"type":"status","daemon_version","protocol_version","override","apps":[{"package_name","connected"}]}` | v1 daemons omit `protocol_version`; treat as 1. `override` is true when running a stored update instead of the image build. |
| `UNSUBSCRIBE` | ack | stop push fan-out on this connection |
| `REGISTER <package> <server_url> <token>` | ack | daemon writes `tokens/<package>.conf` (atomic), connects. Re-registering with a changed server/token reconnects. Charset-validated. |
| `UNREGISTER <package>` | ack | removes the config, closes the connection |
| `INSTALL <size> <version> <sig-hex>` + `<size>` raw bytes | ack | stores a signed update; see below |
| `RELOAD` | ack, then exits | init restarts irisd, which picks up a stored update |

Ack: `{"type":"ack","cmd":"<CMD>","ok":true}` or
`{"type":"ack","cmd":"<CMD>","ok":false,"error":"<code>"}`. INSTALL errors:
`updates_disabled`, `bad_args`, `bad_size`, `bad_version`, `bad_signature_format`,
`signature_invalid`, `not_newer`, `write_failed`, `short_payload`.

## Updating the daemon without an OS update

1. The image ships `/system/bin/irisd` (a floor version) and the Iris APK.
2. A newer build, signed with the release key, is bundled in the Iris APK
   (`assets/irisd/VERSION`, `assets/irisd/<abi>/irisd`, `.../irisd.sig`).
   `tools/sign-irisd.sh` produces the signature.
3. When the bridge connects, `IrisDaemonSync` compares versions, sends
   `INSTALL`, then `RELOAD`.
4. The daemon verifies Ed25519 over `"irisd <version>\n" || binary` against
   the public key compiled into the image build
   (`-DIRISD_UPDATE_PUBKEY_HEX=...`). No key compiled in -> updates disabled.
5. On start the image copy `exec`s `bin/irisd` if its stored version is newer
   than itself and the signature still verifies. A start counter drops the
   update after 3 starts that did not stay up for 60 s, falling back to the
   image build.

Registrations are replayed by the app on every bridge connect, so a
restarted or updated daemon is repopulated; the daemon also keeps them on
disk across restarts.

## Registration from other apps

Apps register with the Iris app (not with irisd directly) via the
`de.letheapp.iris.action.REGISTER` broadcast described in PROTOCOL.md
("Registration on-device"); the app forwards it here as `REGISTER`.

## Delivery (irisd >= 1.3.0)

irisd hands each push straight to the target app by exec'ing
`/system/bin/cmd activity broadcast -a de.letheapp.iris.PUSH -p <pkg> ...`
(no shell, payload passed as separate argv), which starts the target app's
process like an FCM message would. Only if that fails (e.g. SELinux denials
before sepolicy/iris_daemon.te is complete) does it fall back to writing the
push line to connected bridge clients; that line now also carries `token`.
On a server close with code 4001 irisd broadcasts
`de.letheapp.iris.action.UNREGISTERED` to the app and backs off 5 minutes.
