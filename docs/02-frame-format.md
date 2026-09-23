# Channel-1 frame format

Every frame on the video channel starts with a 32-byte header.

```
offset  size  meaning
------  ----  ---------------------------------------------------------------
 0       4    magic  55 aa 15 a8   (0xa815aa55 little-endian)
 4       1    frame type: 0 = keyframe (IDR), 1 = non-key
12       4    per-frame timestamp, u32 LE (camera's own clock)
16       4    payload length, u32 LE
32       -    payload begins here — already clean Annex-B H.264
```

Verified against a live device: at offset 32 you find `00 00 00 01 67 64 00 28` —
Annex-B start code, NAL type 0x67 (SPS), profile_idc 0x64 (High), level_idc 0x28 (4.0).

## Reading loop

```js
readExact(hdr, 32)                    // header
assert(magic(hdr))
const len = hdr.readU32(16)
readExact(body, len)                  // payload
writeToFile(body)                     // no transformation needed
```

`PPCS_Read` can short-read; loop until you have the full count. Return code `0` means data,
negative means timeout/empty.

## Framerate — the trap

The camera is **variable frame rate** (observed 15–37 fps depending on scene motion), and the raw
stream has **no container timestamps**. Two consequences:

1. `ffmpeg -f h264 -framerate N -i ...` is **silently overridden** by timing in the SPS VUI. Use
   **`-r N`** as an input option instead. Measured: `-framerate 867/30` produced a 57.8 s file from a
   30 s recording; `-r 867/30` produced 30.03 s. Same data.

2. Do not reconstruct per-frame times by assuming a constant rate. An attempt to score frames live
   during capture (mapping a fake constant-rate timeline onto the real one) drifted progressively and
   selected measurably worse frames — 1.2 % lower average edge energy, with the error growing toward
   the end of the clip. If you need real per-frame times, use the timestamp at header offset 12
   rather than any linear model.

The safe approach `rawrec.sh` uses: count frames and wall-clock the capture, then mux with
`-r <frames>/<seconds>`.
