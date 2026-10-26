# 🧹 How I debloated my Xiaomi Mi TV (phone-only ADB)

How I removed the bloatware from my Xiaomi Mi TV on 2026-10-22 — **no laptop, no root,
nothing uninstalled**. Every change below uses `pm disable-user`, which only *disables*
an app. Anything disabled here can be brought back with one `pm enable` command.

> ⚠️ This guide is for **my TV: Xiaomi Mi TV, Android TV 9, India firmware**.
> Package names differ between brands, models and firmware versions. Never blindly
> paste a disable list onto a different TV — discover your own packages first
> (see [Step 3](#step-3--discover-before-you-disable)).

## ✅ Requirements

| Requirement | My setup |
|---|---|
| TV | Xiaomi Mi TV, Android TV 9 |
| Laptop | **None** — everything was done from my phone |
| Phone + TV network | Both on the **same Wi-Fi** (mine: SSID `Samsung A22_5G`) |
| TV IP address | `192.168.1.40` (find yours in TV Settings → Network) |
| App on phone | **Remote ADB Shell** (connects to the TV over ADB) |
| App on phone + TV | **Send Files to TV** (used to install the launcher APK) |
| TV setting | Wireless debugging / USB debugging **ON** (Developer options) |
| ADB port | `5555` |

## ⚠️ Rules I followed (read first)

1. **Disable, never uninstall.** `pm disable-user --user 0 <package>` only. Uninstalling
   system apps can break updates and is hard to undo; disabling is one command to reverse.
2. **Stock launcher goes last.** Disable the stock launcher only *after* the new launcher
   is installed and stable — otherwise the TV has no home screen.
3. **Never touch these:** remote/Bluetooth services, keyboard, TV inputs, Play Store /
   Play Services, the Google TV app, or the apps you actually use.
4. **"Not found" errors are fine.** If a package doesn't exist on your TV, the command
   errors and nothing happens. Ignore it and move on.
5. **The remote keeps working.** Bluetooth/IR remotes are hardware/system level and are
   not affected. The one known risk is the remote's **mic/voice button** (see
   [Known issues](#known-issues--still-open)).

## 🪜 Full working process

### Step 0 — Backup first

Before changing anything, save the current state so you can compare and restore:

```sh
pm list packages > /sdcard/packages-before.txt
```

(The android-tv-makeover project also has a `backup.sh` that saves package lists,
system properties, settings and a screenshot before any change.)

### Step 1 — Connect the phone to the TV over ADB

1. On the TV, turn on **Developer options** and enable **Wireless debugging**
   (or USB debugging).
2. Make sure the phone is on the **same Wi-Fi** as the TV.
3. Open **Remote ADB Shell** on the phone and connect to:

```
192.168.1.40:5555
```

4. Accept the debugging prompt on the TV if it appears. You now have a shell on the TV.

### Step 2 — Make the new launcher the default home (do this first)

Just setting the home activity was **not** enough on my TV — pressing the remote's
Home button kept reopening the stock launcher. What actually worked:

```sh
cmd package set-home-activity com.kulwant.atvhome/.HomeActivity
pm disable-user --user 0 com.google.android.apps.tv.launcherx
```

If the first launcher package doesn't exist on your TV, use the fallback:

```sh
pm disable-user --user 0 com.google.android.tvlauncher
```

Reversible any time with `pm enable` on the same package.

### Step 3 — Discover before you disable

Don't guess package names — ask the TV. Examples I actually used:

```sh
pm list packages | grep -i gallery
pm list packages | grep -i -E "patchwall|cast|wfd|player|miplay"
pm list packages -d | grep -i mitv
```

(The `-d` flag lists only disabled packages — this is how you verify your work.)
My first guess for the Gallery app (`com.miui.gallery`) was wrong and errored with a
`PackageManager` stack trace — harmless, wrong name only. The real name came from the
grep: `com.mitv.gallery`.

### Step 4 — Round 1: the main debloat list

Pasted as one block into Remote ADB Shell on 2026-10-22 (~18:33 IST). One command
per package:

```sh
pm disable-user --user 0 com.miui.tv.analytics
pm disable-user --user 0 com.xiaomi.statistic
pm disable-user --user 0 com.google.android.feedback
pm disable-user --user 0 com.google.android.tv.bugreportsender
pm disable-user --user 0 com.xiaomi.android.TV.audio
pm disable-user --user 0 com.mitv.tvhome.atv
pm disable-user --user 0 com.xm.webcontent
pm disable-user --user 0 com.xiaomi.mitv.handbook
pm disable-user --user 0 com.google.android.leanbacklauncher
pm disable-user --user 0 com.google.android.leanbacklauncher.recommendations
pm disable-user --user 0 com.google.android.play.games
pm disable-user --user 0 com.xiaomi.mitv.tvmanager
pm disable-user --user 0 com.android.printspooler
pm disable-user --user 0 com.android.camera2
pm disable-user --user 0 com.google.android.marvin.talkback
pm disable-user --user 0 com.netflix.ninja
pm disable-user --user 0 com.amazon.amazonvideo.livingroom
pm disable-user --user 0 in.startv.hotstar
pm disable-user --user 0 com.google.android.youtube.tv
pm disable-user --user 0 com.google.android.youtube.tvmusic
pm disable-user --user 0 com.google.android.tvlauncher
pm disable-user --user 0 com.google.android.tvrecommendations
```

### Step 5 — Round 2: the apps still showing afterwards

After round 1, some apps were still visible (Gallery, Miracast, PatchWall,
TV Self-diagnosis, User Manual, Media Player). Their real package names came from my
own greps — PatchWall turned out to have **no** single `com.mitv.tvhome` package on
this TV; it is split into four pieces:

```sh
pm disable-user --user 0 com.mitv.gallery
pm disable-user --user 0 com.xiaomi.mitv.manualhelp
pm disable-user --user 0 com.mitv.tvhome.oemtab
pm disable-user --user 0 com.mitv.tvhome.michannel
pm disable-user --user 0 com.mitv.tvhome.mitvplus
pm disable-user --user 0 com.mitv.tvhome.atv
pm disable-user --user 0 com.mitv.videoplayer
pm disable-user --user 0 com.xiaomi.wfdsinkhelperservice
```

Notes from round 2:
- `com.mitv.tvhome.atv` was deliberately held back at first (it was a suspect in the
  voice-button issue), then disabled too — confirmed `new state: disabled-user`.
- No package matching `miracast` or `diagnos*` exists on this TV. Miracast is
  `com.xiaomi.wfdsinkhelperservice` (WFD sink helper); Self-diagnosis has no separate
  package and most likely lives inside the already-disabled TV Manager.
- The Media Player disable (`com.mitv.videoplayer`) was later confirmed disabled in
  a `pm list packages -d` screenshot.

### Step 6 — Verify, restart, test

```sh
pm list packages -d
pm list packages -d | grep -i mitv
```

Then restart the TV and test: remote buttons, Home button (should open the new
launcher), mic/voice button, and the apps you kept.

## 📦 What each package is

### Round 1 — telemetry / analytics

| Package | What it is |
|---|---|
| `com.miui.tv.analytics` | Xiaomi TV analytics / telemetry |
| `com.xiaomi.statistic` | Xiaomi statistics / telemetry |
| `com.google.android.feedback` | Google feedback agent |
| `com.google.android.tv.bugreportsender` | TV bug-report sender |
| `com.xiaomi.android.TV.audio` | Xiaomi TV audio service (also a mic/voice suspect — see below) |

### Round 1 — manufacturer content / ads / recommendations

| Package | What it is |
|---|---|
| `com.mitv.tvhome.atv` | PatchWall — the main live piece (also a mic/voice suspect; can re-enable itself) |
| `com.xm.webcontent` | Xiaomi web-content service |
| `com.xiaomi.mitv.handbook` | Xiaomi handbook content |
| `com.google.android.leanbacklauncher` | Old stock Leanback launcher |
| `com.google.android.leanbacklauncher.recommendations` | Stock recommendations row |
| `com.google.android.play.games` | Play Games |

### Round 1 — background services / unused preloaded apps

| Package | What it is |
|---|---|
| `com.xiaomi.mitv.tvmanager` | Xiaomi TV manager (background services) |
| `com.android.printspooler` | Print service (unused on a TV) |
| `com.android.camera2` | Camera service (no camera on a TV) |
| `com.google.android.marvin.talkback` | TalkBack accessibility (unused) |

### Round 1 — streaming apps I disabled myself (kept disabled)

| Package | App |
|---|---|
| `com.netflix.ninja` | Netflix |
| `com.amazon.amazonvideo.livingroom` | Prime Video |
| `in.startv.hotstar` | Hotstar |
| `com.google.android.youtube.tv` | YouTube (stock TV app) |
| `com.google.android.youtube.tvmusic` | YouTube Music |

### Round 1 — stock launcher (only after the new launcher was stable)

| Package | What it is |
|---|---|
| `com.google.android.tvlauncher` | Stock Google TV launcher |
| `com.google.android.tvrecommendations` | Stock recommendations service |

### Round 2 — leftover visible apps

| Package | App |
|---|---|
| `com.mitv.gallery` | Gallery |
| `com.xiaomi.mitv.manualhelp` | User Manual |
| `com.mitv.tvhome.oemtab` | PatchWall piece |
| `com.mitv.tvhome.michannel` | PatchWall piece |
| `com.mitv.tvhome.mitvplus` | PatchWall piece |
| `com.mitv.tvhome.atv` | PatchWall main piece |
| `com.mitv.videoplayer` | Media Player |
| `com.xiaomi.wfdsinkhelperservice` | Miracast (WFD sink helper) |

## 🙅 What I deliberately did NOT disable

| Kept | Why |
|---|---|
| `com.google.android.videos` (Google TV) | The only streaming app I keep |
| TizenTube (ad-free YouTube client) | My YouTube replacement — appears as "YouTube" in the launcher |
| AkhileshTV (`com.pro.akhileshtv`) | My own live TV app |
| Remote / Bluetooth / keyboard / TV-input services | The physical remote must keep working |
| Play Store / Play Services | Needed for apps and updates |
| `com.google.android.katniss` (Google Assistant) | Never disabled — voice was meant to keep working |

## ↩️ Undo / restore

Re-enable any package with:

```sh
pm enable <package>
```

To undo everything, re-enable each package from the lists above. If you also want
the stock launcher back as Home:

```sh
pm enable com.google.android.tvlauncher
cmd package set-home-activity com.google.android.tvlauncher/.MainActivity
```

(The android-tv-makeover project has a `restore.sh` that does this for a whole
profile at once, including giving Home back to the stock launcher.)

## ⚠️ Known issues / still open

- **PatchWall re-enabled itself.** After all four tvhome pieces showed
  `disabled-user`, pressing the remote's PatchWall button opened PatchWall again —
  `com.mitv.tvhome.atv` was back in the enabled list (cause unknown: the button
  press itself, or Xiaomi firmware self-restore). Fix: re-run
  `pm disable-user --user 0 com.mitv.tvhome.atv`, then press the PatchWall button
  again to see whether it resurrects itself.
- **Mic/voice button.** The remote's voice button stopped listening after the
  debloat. Google Assistant (`katniss`) was never disabled, so the prime suspects
  are `com.xiaomi.android.TV.audio` and `com.mitv.tvhome.atv`. Test: re-enable both,
  restart the TV, and test the mic — if it works, the PatchWall-button-vs-voice
  trade-off is your call.
- **Miracast confirmation pending.** The disable command for
  `com.xiaomi.wfdsinkhelperservice` was handed over but its disabled state was never
  re-confirmed on screen.

---

*No root, no uninstall, no laptop — just ADB from my phone, and everything above
is one `pm enable` away from coming back.*
