# Iris OS Integration Plan

> **Status update:** Tier 2's native daemon now has a first implementation
> at [`daemon/irisd.c`](daemon/irisd.c) — TLS+WebSocket client, config
> directory watched via inotify, local control-socket bridge to the app
> side (`IrisBridgeService.kt` in `app/`). It has been end-to-end tested in
> standalone mode against the live reference server (real token, real
> `/push/send` call round-tripping through a simulated bridge client) and
> compiles cleanly. What's still missing before it's real Tier 2, not just
> a dev-mode rehearsal of it: actually building it into an AOSP tree via
> `daemon/Android.bp`, the `iris` AID, and getting the sepolicy skeleton in
> `daemon/sepolicy/` past real denials on a device (see `daemon/README.md`
> for the exact remaining gaps). The app side already auto-prefers the
> daemon over its own socket whenever one is detected (`IrisDelivery.kt`),
> so no app-side changes are needed once the daemon is actually running
> on-device.

## Goal

Make Iris behave like FCM/GMS from the OS's perspective: one persistent,
device-wide connection that survives Doze/App Standby and isn't torn down or
throttled in the background — without the battery cost that a normal,
sandboxed third-party app pays for holding a socket open.

**Important correction to the earlier framing:** the goal is not to make Iris
"not need a wakelock." FCM/GMS *also* takes wakelocks internally when a
packet arrives — a CPU has to wake up to process a received byte no matter
who you are. The actual difference is that `com.google.android.gms` is a
**trusted system component exempt from the background-execution
restrictions** (Doze network suspension, App Standby buckets, background
service limits) that Android applies to ordinary apps. That exemption, not
wakelock elimination, is what Tier 2 below buys you. A normal app's socket
gets killed/throttled by the OS regardless of how carefully it manages its
own wakelock; a system-allowlisted process's socket doesn't, so the
wakelock it does take becomes brief and rare instead of a constant fight
against being frozen.

This plan has two tiers. Tier 1 is a rebuild of the existing Iris APK with a
handful of manifest/config changes — no native code, deployable as soon as
you have a system partition to install into. Tier 2 replaces the Java
service with a native `init`-managed daemon — a much bigger lift, worth
doing only if Tier 1 turns out insufficient in practice.

---

## Tier 1 — Privileged system app (do this first)

Everything here is a config/manifest change to the existing Kotlin/Compose
app in this repo, plus OS-build-side placement. No rewrite needed.

### 1. Ship Iris as a priv-app, signed with your platform key

- Move the built APK into `/system/priv-app/Iris/Iris.apk` (or
  `/product/priv-app/` depending on your partition layout) instead of
  installing it as a normal user app.
- Sign it with your ROM's **platform certificate** (the same key that signs
  `android` / `system_server`), not a normal release key. `persistent="true"`
  (next step) and several allowlist mechanisms below only take effect for
  apps signed with the platform cert or preinstalled as a priv-app — a
  sideloaded APK, however privileged-looking, won't get them.
- If your OS build has a `privapp-permissions-<oem>.xml` allowlist
  mechanism (AOSP requires explicit allowlisting of privileged permissions
  per priv-app since Android 8), add an entry for
  `de.letheapp.iris` there, even if Iris doesn't request anything exotic —
  some OS builds hard-fail priv-app installation otherwise.

### 2. Mark the process persistent

Add to `AndroidManifest.xml`:

```xml
<application
    android:persistent="true"
    ... >
```

Effect: `system_server` keeps the process at `PROCESS_STATE_PERSISTENT`
oom_adj (same tier as `system_server` itself) and restarts it immediately if
it's ever killed. This only has effect for system-signed apps; on a
non-rooted stock device this attribute is silently ignored, which is exactly
why this is an OS-build step, not something shippable via Play Store/F-Droid.

### 3. Put Iris on the Doze / App Standby / Data Saver allowlists by default

This is the actual "exemption," and it's the part that matters most. Three
independent lists, all settable at build time via sysconfig XML so users
never have to grant anything manually:

- **Doze / "battery optimization" allowlist** — equivalent of what
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` asks the user for, but
  pre-granted by the OS. In AOSP this is driven by
  `frameworks/base/services/core/java/com/android/server/DeviceIdleController.java`
  reading `<allow-in-power-save package="de.letheapp.iris"/>` entries from
  `/etc/sysconfig/*.xml` (or your OS's equivalent overlay mechanism).
- **Data Saver / background data allowlist** — `<allow-in-data-usage-save
  package="de.letheapp.iris"/>` in the same sysconfig file, so a user
  enabling Data Saver doesn't cut the socket.
- **App Standby bucket exemption** — system-signed + persistent apps are
  already excluded from standby bucket throttling in AOSP; no extra config
  needed beyond step 1–2, but verify with
  `adb shell am get-standby-bucket de.letheapp.iris` → should report
  `EXEMPTED`.

**Also allowlist the apps Iris wakes** (e.g. `<allow-in-power-save
package="com.Lethe.app"/>`). Iris wakes them with an explicit broadcast, which
Android delivers even in Doze — but a target app that is *not* on the Doze
allowlist then runs without network access and may not start a foreground
service (incoming call) from the background. FCM avoids this because GMS puts
the target on a temporary allowlist for each high-priority message; without
privileged code in Iris, the permanent sysconfig entry is the equivalent.
On stock devices the target app asks the user for this exemption itself
(`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) — it costs no battery on its
own, since the app no longer runs anything in the background.

Where to put the sysconfig overlay depends on your build structure — either
a new file under `device/<vendor>/<codename>/sysconfig/iris.xml` wired into
`PRODUCT_COPY_FILES`, or wherever your OS already carries its own
`allow-in-power-save` entries if it has any (most OEM AOSP forks do, for
their own system apps — follow that existing pattern).

### 4. Build it into the OS image, not as a separately installed APK

- Create `packages/apps/Iris/` in your AOSP tree (or wherever your OS build
  roots its own apps) containing the app module plus an `Android.bp`:

  ```
  android_app {
      name: "Iris",
      srcs: ["java/**/*.kt"],
      resource_dirs: ["res"],
      manifest: "AndroidManifest.xml",
      platform_apis: true,
      certificate: "platform",
      privileged: true,
  }
  ```

- Add `Iris` to `PRODUCT_PACKAGES` in your device/product makefile so it's
  baked into every build, survives factory reset, and ships at the same
  trust level as other system apps on day one — no post-install step a user
  (or F-Droid) could skip.
- This also means: updates to Iris ship with your OS's own OTA mechanism,
  not via the public `iris-push` GitHub releases — keep the public repo as
  the upstream source, vendor/mirror it into your OS tree, don't point the
  OS build directly at a GitHub artifact URL.

### 5. Verify

```
adb shell dumpsys deviceidle whitelist          # Iris should be listed
adb shell am get-standby-bucket de.letheapp.iris  # should be EXEMPTED
adb shell dumpsys activity processes | grep -A3 de.letheapp.iris  # oom_adj should be persistent-tier
adb shell dumpsys power                         # confirm wakelock durations stay short/rare, not held continuously
```

Battery-wise, this should already get Iris roughly to FCM parity: the
process survives Doze maintenance windows, isn't standby-throttled, and the
only CPU wake per message is the same brief wake any socket read causes.

---

## Tier 2 — Native `init`-managed daemon (only if Tier 1 isn't enough)

A step further: move the persistent connection out of a Java/ART process
entirely into a small native daemon started directly by `init`, the same
tier as e.g. `netd` or `rild`. This buys you:

- No ART/Zygote overhead; the process exists before `system_server` has even
  finished starting other things, and can reconnect across early-boot states
  a Java app can't observe.
- A dedicated SELinux domain scoped to exactly the permissions it needs
  (raw socket, nothing else), rather than inheriting the general system-app
  sandbox.
- Full control over exactly when the CPU wakes — an `epoll_wait()` loop in
  C has no ART GC, no Binder/JobScheduler machinery between "packet arrived"
  and "process it."

This is a real rewrite, not a config change, and should only be pursued if
you measure Tier 1 and find it insufficient:

1. **Rewrite the WS client in C/C++ (or Rust)** — this repo's
   `IrisConnection`/OkHttp-based logic becomes the native daemon's spec, not
   its code; OkHttp isn't usable outside the JVM. A small WebSocket client
   library (e.g. `libwebsockets`) plus TLS (`BoringSSL`, already in AOSP)
   covers the protocol in `PROTOCOL.md` without much code.
2. **Add an `init.rc` service entry**, e.g.:
   ```
   service iris_daemon /system/bin/irisd
       class core
       user iris
       group iris inet
       seclabel u:r:iris_daemon:s0
   ```
   `class core` starts it early and keeps it running across most of the
   boot lifecycle.
3. **Define a dedicated SELinux domain** (`iris_daemon.te` + context file)
   granting exactly: network socket create/connect, a wake lock
   (`/sys/power/wake_lock` write), and the Binder/AIDL call needed to
   broadcast the signature-protected `de.letheapp.iris.PUSH` intent to
   installed apps (requires talking to `system_server`'s `ActivityManager`,
   so the domain needs the relevant `binder_call` rule to
   `system_server`).
4. **Create a dedicated `iris` AID** (`system/core/libcutils` /
   `private/android_filesystem_config.h` in older AOSP, or your OS's
   equivalent AID table) so the daemon runs as its own low-privilege Linux
   user, not `system` or `root`.
5. **Bridge native → app broadcast**: since the actual deliverable is still
   an `Intent` broadcast to installed Android apps (`PushReceiver` per
   `PROTOCOL.md`), the daemon needs a minimal path into Binder — either via
   `libbinder_ndk` directly, or by keeping one tiny privileged Java
   component (not persistent, started on-demand by the daemon via a Binder
   call) whose only job is `sendBroadcast()`. Fully bypassing the Android
   broadcast system and writing directly to each app's process is not
   realistic — apps only understand `Intent`s.
6. **Testing**: `adb shell ps -Z | grep iris_daemon` (confirm domain),
   `dmesg | grep avc.*denied` during a push cycle (confirm no SELinux
   denials), battery historian across a multi-hour idle soak with
   real pushes arriving.

Honest scope estimate: Tier 1 is an afternoon once you have any system
partition to flash into. Tier 2 is a multi-week systems-programming project
(native WS/TLS client, SELinux policy authoring, AID allocation, Binder
bridge) — don't start it speculatively; start it only after Tier 1 is
flashed, measured, and found wanting.

## What you need to do, concretely, right now

Assuming you're building your own OS (the "own Betriebssystem" bundling
Nocturn/Lethe/Media Player/F-Droid/VectoMail as system apps mentioned
earlier) on top of AOSP or an existing AOSP-based ROM source tree:

1. Get `de.letheapp.iris` (this repo) building as a module inside that
   source tree under `packages/apps/Iris/` with an `Android.bp` as sketched
   above, signed with your platform key, added to `PRODUCT_PACKAGES`.
2. Add the `persistent="true"` manifest attribute.
3. Add an `allow-in-power-save` / `allow-in-data-usage-save` sysconfig entry
   for its package name, wired into your product makefile.
4. Rebuild, flash, run the `adb shell dumpsys deviceidle whitelist` /
   `get-standby-bucket` checks above to confirm the exemptions actually took
   effect on your specific build (OEM AOSP forks sometimes have their own
   additional battery managers layered on top — Samsung/Xiaomi-style "extra
   Doze" — that would need their own allowlist entries too, if your base is
   one of those rather than clean AOSP).
5. Only after that's flashed and soak-tested: decide whether Tier 2 is
   worth it, based on real battery numbers rather than a theoretical worry.
