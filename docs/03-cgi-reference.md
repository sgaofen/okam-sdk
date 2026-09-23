# CGI reference (as observed on one VE-series firmware)

CGI goes over channel 0. With the daemon running you can send any command through its HTTP shim:

```sh
curl "http://127.0.0.1:8099/cgi?c=get_params.cgi"
# URL-encode inner ampersands as %26
curl "http://127.0.0.1:8099/cgi?c=set_wifi.cgi%3Fenable=1%26ssid=foo%26wpa_psk=bar%26"
```

## Works

| CGI | Notes |
|---|---|
| `get_params.cgi` | large dump: wifi\_\*, resolution, timezone, … |
| `get_status.cgi` | capability table, battery, firmware |
| `get_camera_params.cgi` | `cameratype`, `resolution` |
| `get_wifi_scan_result.cgi` | AP list: ssid / mac / security / channel / rssi |
| `set_wifi.cgi` | `enable, mode, encrypt, authtype, channel, ssid, wpa_psk` |
| `livestream.cgi` | `streamid=10` start, `streamid=16` stop; `substream=0` main, `1` sub |
| `trans_cmd_string.cgi` | general command channel |
| `decoder_control.cgi` | PTZ |
| `reboot.cgi` | |

## Gutted on this firmware

| CGI / feature | Behaviour |
|---|---|
| `snapshot.cgi` | accepted, response silently discarded (9/9 attempts) |
| `wifi_scan.cgi`, `get_wifi_list.cgi` | `var cgi=not support` |
| RTSP | `set_rtsp.cgi` opens port 10554, which then emits non-RTSP bytes |
| ONVIF | `set_onvif.cgi` returns ok; no ONVIF port ever opens |

## Gotchas

- **The camera refuses some commands while streaming.** `get_wifi_scan_result.cgi` succeeded once,
  then returned `not support` on later calls with an AV session active — a Wi-Fi scan requires
  leaving the operating channel.
- **`set_wifi.cgi` drops the P2P session** while the radio re-associates. Expect a reconnect; a
  watchdog should handle it (observed back online in ~5 s when the target network is already up).
- **After the target AP disappears and returns, the camera takes ~90 s to rejoin.** Do not cycle a
  hotspot between recordings; leave it up.
- Both substreams are 1920x1080 on this device; `substream=0` ≈ 173 kb/s, `substream=1` ≈ 161 kb/s.
  Picture quality is indistinguishable.
- Responses are read from a "last command response" slot after a short delay. **Do not pipeline
  commands** — fire one, wait, read, then fire the next, or responses get attributed to the wrong call.

## Wi-Fi fields (why enterprise networks are impossible)

`get_params.cgi` returns exactly:

```
wifi_enable, wifi_mode, wifi_encrypt, wifi_authtype, wifi_channel, wifi_ssid, wifi_wpa_psk
```

One pre-shared key. **No identity, no EAP method, no certificate fields.** WPA2-Enterprise / 802.1X
is not a configuration you are missing — the firmware has nowhere to put it.

Note also that `wifi_wpa_psk` is returned **in plaintext** over the control channel.
