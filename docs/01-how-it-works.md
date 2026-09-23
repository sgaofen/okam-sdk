# How it works

## The stack

```
your phone  ──(vendor private P2P, "PPCS"/CS2Network)──  camera
```

The camera speaks a proprietary UDP P2P protocol from CS2Network (marketed as PPCS). It is *not*
TUTK/Kalay despite similar shape — same family of ideas, incompatible wire format.

A session is one UDP association carrying several logical **channels**:

| Channel | Carries |
|---|---|
| 0 | control — CGI request/response, framed |
| 1 | video |
| 2 | audio |

The vendor ships `libOKSMARTPPCS.so` (P2P transport) and `libOKSMARTPLAY.so` (a full ffmpeg, H.264/H.265).
Their Java layer (`com.vstarcam.JNIApi`) wraps the transport, but **exposes no read for channel 1** —
video never crosses into Java. That is why people conclude "the video isn't arriving."

## The mistake that cost a month

A headless daemon can connect, log in, and run every control command successfully — and still see no
video. The natural reading is a transport failure: maybe the AV channel never hole-punched, maybe the
session got downgraded to relay. Packet captures even *support* that story: the vendor app opens an
extra UDP flow that the daemon doesn't.

All of it was wrong. Two calls settle it:

```c
st_PPCS_Session info;
PPCS_Check(session, &info);          // info.bMode: 0 = P2P direct, 1 = relay
PPCS_Check_Buffer(session, 1, &w, &r);  // r = bytes waiting to be read on channel 1
```

Measured on a session that "had no video":

```
bMode     = 0            -> P2P DIRECT. Not relay. That hypothesis was dead.
readAvail = 131630       -> ~35 seconds of video sitting unread in the buffer.
```

**The video was always arriving.** The vendor's player is written to render to a Surface; with no
display consumer its read loop never starts, so nothing drains channel 1 and it just fills up.

The fix is to stop asking the player and read the socket yourself:

```c
PPCS_Read(session, /*channel=*/1, buf, &len, timeout_ms);
```

`PPCS_Read`, `PPCS_Write`, `PPCS_Check`, `PPCS_Check_Buffer` are exported from `libOKSMARTPPCS.so`
(4-byte veneers that jump to the real `P2P_*` implementations). They are callable directly — from
frida, or from any native code you inject.

**Session handles are small integers, not the `long` pointer the Java layer hands you.** Enumerate
them: call `PPCS_Check(s, &info)` for `s` in `0..15` and take the one that returns 0.

## Getting frames out

Channel 1 is a byte stream of length-prefixed frames — see [02-frame-format.md](02-frame-format.md).
Read a 32-byte header, read the declared payload, repeat. The payload is already clean Annex-B H.264,
so recording is:

```
read frames  ->  concatenate payloads  ->  ffmpeg -f h264 -r <measured> -i - -c copy out.mp4
```

Zero re-encode. The bytes in the MP4 are the bytes the camera's encoder produced.

**Drain the backlog before you start recording.** The buffer accumulates while nobody reads
(~35 s observed). Without draining, your "30 second clip" begins with half a minute of stale footage.

## Offline operation

At startup the daemon looks up its internal UID from `https://vuid.eye4.cn?vuid=<device id>`
(unauthenticated, public). If that fails it falls back to the UID in the config file — and
**the P2P session still establishes over pure LAN**. Verified with DNS dead and no route to the
internet: connect + login + streaming in ~5 seconds.

So a camera and a phone on the same local network (including a phone hotspot) are a self-sufficient
pair. No vendor cloud, no internet.
