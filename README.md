# okam-sdk

Talk to your own O-KAM / VStarcam IP camera directly — get the **full-resolution H.264 stream**, send
control commands, change its Wi-Fi — **without the vendor app, without their cloud, and without any
internet connection at all.**

This is interoperability tooling for hardware you own. It does not attack anything: it uses the
camera's own protocol with the camera's own credentials, which you extract from your own device.

## Why this exists

These cameras (O-KAM Pro / Eye4 / VStarcam VE-series and many rebrands) are locked down hard.
On the firmware this was developed against:

| Standard way in | Status |
|---|---|
| RTSP | port opens, then emits non-RTSP bytes. Dead. |
| ONVIF | ports never open. Dead. |
| HTTP CGI on :80 / :81 | closed. |
| `snapshot.cgi` | accepted, silently discarded. |

The vendor's private P2P protocol is the only door. This SDK walks through it.

## The one thing worth knowing

The hard part was believed to be "the video won't come through the P2P tunnel." **That was wrong.**

Video arrives on **channel 1** of the P2P session and just *sits there* if nobody reads it — the
vendor's player refuses to run without a screen to draw on, so a headless daemon appears to receive
nothing. Ask the buffer directly and you find it stuffed:

```
PPCS_Check_Buffer(session, ch=1)  ->  readAvail = 131630 bytes
PPCS_Read(session, ch=1, ...)     ->  55 aa 15 a8 ... 00 00 00 01 67 64 00 28
                                      ^ frame header   ^ H.264 Annex-B + SPS
```

`PPCS_Read` / `PPCS_Write` / `PPCS_Check` / `PPCS_Check_Buffer` are **real exported symbols** in
`libOKSMARTPPCS.so`. Call them directly and you bypass the player, the JNI wrapper, and the app.

Full write-up: [docs/01-how-it-works.md](docs/01-how-it-works.md).

## What you get

- **1080p H.264, zero re-encode.** Byte-identical to what the vendor app records — verified by
  comparing SPS/PPS between an app recording and ours: identical, byte for byte.
- **Works fully offline.** Verified: with no internet at all (cloud lookup fails, DNS dead), a fresh
  session still connects in ~5 s using the baked-in UID, over pure LAN.
- **Works headless.** No app running, screen off, device dozing. Verified.
- **Control channel.** Every CGI the firmware still supports, including Wi-Fi reconfiguration.

## Layout

```
daemon/     cam.Daemon — holds the P2P session, exposes HTTP on :8099 (jpeg / status / cgi)
scripts/    rawrec.sh  — record N seconds of raw H.264 -> MP4 (ffmpeg -c copy)
            camctl.sh  — start/stop/supervise the daemon
            wifi.sh    — read / change which Wi-Fi the camera joins
            frida/     — the frame reader and the diagnostic probe
docs/       how it works, frame format, CGI reference, credential extraction
```

## Requirements

- An Android phone, **rooted** (the daemon runs via `app_process`, and frida injects into it)
- `frida-inject`, a static `ffmpeg`, and a static `curl` on the device
  (the scripts expect the curl binary at `/data/local/tmp/mcurl` — adjust the `M=` line if yours is named differently)
- The camera's vendor `.so` files, pulled from the app you already have installed:
  `libOKSMARTPPCS.so`, `libOKSMARTPLAY.so`, `libvp_log.so`, `libc++_shared.so`, `libyuv.so`
- Your own camera's credentials — see [docs/04-extract-credentials.md](docs/04-extract-credentials.md)

## Quick start

```sh
cp daemon/camd.conf.example /data/local/tmp/camd/camd.conf   # fill in YOUR credentials
sh scripts/camctl.sh start                                   # connect
sh scripts/rawrec.sh 30                                      # record 30s -> prints the mp4 path
```

## Scope and limits

- **The daemon's HTTP interface has no authentication and binds `0.0.0.0:8099`.** Anyone on the same
  Wi-Fi can pull frames from your camera, send it CGI commands, and change its Wi-Fi settings. Only
  run this on a network you control, or bind it to loopback and reach it through `adb forward`.

- **Enterprise Wi-Fi (802.1X / WPA2-Enterprise) is impossible.** The firmware has exactly one
  credential field (`wifi_wpa_psk`) and no identity/EAP fields at all. If you need the camera on a
  campus network, put it on a phone hotspot instead — see `scripts/wifi.sh`.
- The camera is **variable frame rate** and the raw stream carries no container timestamps. Do not
  reconstruct a timeline by assuming a constant rate; `rawrec.sh` measures the real rate and muxes
  with `-r frames/seconds`. (`-framerate` is silently overridden by the SPS VUI — a real trap.)
- Credentials **rotate** when the camera re-registers or updates firmware. Re-extract when that happens.
- Developed against one VE-series device. Other models/firmwares will differ; read status before you
  write anything.

## License

MIT. See [LICENSE](LICENSE).
