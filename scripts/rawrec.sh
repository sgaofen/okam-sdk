#!/system/bin/sh
# =============================================================
#  okcam_rawrec.sh <秒数>  —— App 无关的原生码流录制
# =============================================================
# Record N seconds of the camera's own H.264 stream, straight off the P2P transport.
#  原理:守护进程(cam.Daemon)持有 PPCS 会话;本脚本用 frida 注入,
#        直接 PPCS_Read 通道1 拿 STREAMHEAD 裸帧 -> 剥壳 Annex-B -> ffmpeg -c copy 封 MP4。
#  零重编码、1080p、完全不碰 O-KAM App(不怕广告/折叠面板/离线弹窗/锁屏)。
#  成功时最后一行 stdout = MP4 绝对路径,失败时以 ERR 开头。
# =============================================================
SEC="${1:-30}"
D=/data/local/tmp/okre
CAMD=/data/local/tmp/camd
FF=/data/local/tmp/ffmpeg
FRIDA=/data/local/tmp/frida-inject
M=/data/local/tmp/mcurl
TPL="$D/rawrec.js.tpl"
JS="$D/rawrec.run.js"
RAW="$D/rawrec.h264"
STATF="$D/rawrec.stat"
LOG="$D/rawrec.log"
OUTDIR=/sdcard/DCIM/OKCam
mkdir -p "$OUTDIR" "$D"

log(){ echo "$(date '+%m-%d %H:%M:%S') rawrec: $*" >> /data/local/tmp/okcam_rec.log; }

# --- 1) 守护进程在跑吗 ---
PID=$(ps -A -o PID,ARGS 2>/dev/null | grep "[c]am\.Daemon" | awk '{print $1}' | head -1)
if [ -z "$PID" ]; then
    log "守护进程没跑,拉起"
    sh "$CAMD/camctl.sh" start >/dev/null 2>&1
    i=0
    while [ $i -lt 25 ]; do
        sleep 1; i=$((i+1))
        grep -q "ONLINE" "$CAMD/log.txt" 2>/dev/null && break
    done
    PID=$(ps -A -o PID,ARGS 2>/dev/null | grep "[c]am\.Daemon" | awk '{print $1}' | head -1)
fi
[ -z "$PID" ] && { echo "ERR 守护进程起不来,看 $CAMD/log.txt"; log "守护起不来"; exit 1; }
grep -q "ONLINE" "$CAMD/log.txt" 2>/dev/null || { echo "ERR 守护未连上摄像头(离线?)"; log "未连上"; exit 1; }

# --- 2) 确保摄像头在推流(主码流) ---
"$M" -s -m 8 "http://127.0.0.1:8099/cgi?c=livestream.cgi%3Fstreamid=10%26substream=0%26" >/dev/null 2>&1

# --- 3) 生成脚本并注入录制 ---
[ -f "$TPL" ] || { echo "ERR 缺模板 $TPL"; exit 1; }
DUR=$((SEC * 1000))
sed -e "s#__DUR__#$DUR#" -e "s#__OUT__#$RAW#" -e "s#__STAT__#$STATF#" "$TPL" > "$JS"
rm -f "$STATF"
: > "$RAW"
TMO=$((SEC + 30))
date +%s > /data/local/tmp/okcam_last_use   # 告诉收工员"还在用",别把守护收了

# --- Optional: score frames while recording (DISABLED BY DEFAULT) -----------
#  Same ffmpeg filter graph a downstream consumer would use, fed from the growing
#  raw file via `tail -f` so that if the scorer dies only tail sees the broken pipe
#  and the recording itself is untouched.
#  Left off by default: this camera is variable-frame-rate and the raw stream has no
#  timestamps, so any linear time reconstruction drifts (measured: progressively worse
#  frame choices toward the end of a clip). See docs/02-frame-format.md.

LIVE=${OKCAM_LIVESCORE:-0}
LW=/data/local/tmp/okre/live
rm -rf "$LW"; mkdir -p "$LW"
SCORER=""
if [ "$LIVE" = 1 ] && [ -x "$FF" ]; then
    ( tail -c +1 -f "$RAW" 2>/dev/null | taskset 80 "$FF" -v quiet -threads 2 -f h264 -r 25 -i pipe:0 \
        -filter_complex "[0:v]scale=480:270,crop=360:202:60:34,split=2[a][b];\
[a]convolution=0m=0 1 0 1 -4 1 0 1 0,signalstats,metadata=print:key=lavfi.signalstats.YAVG:file=$LW/pe[o1];\
[b]scale=120:68,signalstats,metadata=print:key=lavfi.signalstats.YMIN:file=$LW/pm,metadata=print:key=lavfi.signalstats.YMAX:file=$LW/px[o2]" \
        -map "[o1]" -f null - -map "[o2]" -f null - >/dev/null 2>&1 ) &
    SCORER=$!
fi

log "开录 ${SEC}s (pid=$PID)"
date +%s > /data/local/tmp/okcam_recstart   # ★ 真正开录的时刻,报时器等这个再开始数
timeout "$TMO" "$FRIDA" -p "$PID" -s "$JS" > "$LOG" 2>&1

# ★ 守护进程"在跑但 P2P 会话已经掉了"(摄像头断电重连后会这样):重启守护再试一次。
#   不这么做的话,摄像头一掉线整条自动扫描链就永久走不通,只能等人工干预。
if grep -q "ERR nosession\|ERR nolib" "$LOG" 2>/dev/null; then
    log "会话不可用,重启守护后重试一次"
    sh "$CAMD/camctl.sh" start >/dev/null 2>&1
    i=0
    while [ $i -lt 25 ]; do
        sleep 1; i=$((i+1))
        grep -q "ONLINE" "$CAMD/log.txt" 2>/dev/null && break
    done
    PID=$(ps -A -o PID,ARGS 2>/dev/null | grep "[c]am\.Daemon" | awk '{print $1}' | head -1)
    [ -z "$PID" ] && { echo "ERR 守护重启后仍起不来"; log "守护重启失败"; exit 2; }
    "$M" -s -m 8 "http://127.0.0.1:8099/cgi?c=livestream.cgi%3Fstreamid=10%26substream=0%26" >/dev/null 2>&1
    : > "$RAW"; rm -f "$STATF"
    timeout "$TMO" "$FRIDA" -p "$PID" -s "$JS" > "$LOG" 2>&1
    grep -q "ERR nosession\|ERR nolib" "$LOG" 2>/dev/null && { echo "ERR 重启后会话仍不可用: $(head -3 "$LOG")"; log "重启后仍无会话"; exit 2; }
fi
BYTES=$(wc -c < "$RAW" 2>/dev/null)
[ "${BYTES:-0}" -lt 20000 ] && { echo "ERR 码流太小($BYTES 字节),看 $LOG"; log "码流太小 $BYTES"; tail -3 "$LOG"; exit 2; }

# --- 4) 零重编码封装(用实测帧率,不写死) ---
# ★ 摄像头真实帧率不是 SPS 里写的 15,必须按"实录帧数/实录秒数"算,
#   否则 30 秒录像会被封成 55 秒慢放(2026-09-09 实测踩过)。
# --- Reap the side scorer and rescale its timeline -------------------------
#  伴随进程按假定的 25fps 编时间,第 i 帧的假时间 = i/25。真实每帧时长 = 实录秒数/总帧数,
#  所以 真时间 = 假时间 * 25 * 实录秒数 / 总帧数。两边都是"帧号 / 帧率",换算是精确的。
PRESCORED=""
if [ -n "$SCORER" ]; then
    sleep 1                       # 给它把管道里剩的帧吃完
    T=$(ps -A -o PID,ARGS 2>/dev/null | grep "[t]ail -c +1 -f $RAW" | awk '{print $1}')
    [ -n "$T" ] && kill $T 2>/dev/null
    sleep 1
    kill $SCORER 2>/dev/null
fi

STAT=$(cat "$STATF" 2>/dev/null)
NF=$(echo "$STAT" | sed -n 's/.*frames=\([0-9]*\).*/\1/p')
EL=$(echo "$STAT" | sed -n 's/.*elapsed_ms=\([0-9]*\).*/\1/p')
case "$NF" in ''|*[!0-9]*) NF=0;; esac
case "$EL" in ''|*[!0-9]*) EL=0;; esac
if [ "$NF" -gt 10 ] && [ "$EL" -gt 1000 ]; then
    ELS=$((EL / 1000))
    [ "$ELS" -lt 1 ] && ELS=1
    FPS="$NF/$ELS"
else
    FPS=25          # 兜底
fi
# With the real frame count and duration known, rescale the scorer's assumed
# timeline to real time and emit the three-column format a consumer expects.
if [ -s "$LW/pe" ] && [ "$NF" -gt 10 ] && [ "$EL" -gt 1000 ]; then
    awk -v nf="$NF" -v elms="$EL" -v mnf="$LW/pm" -v mxf="$LW/px" '
      BEGIN{ while((getline l<mnf)>0) if(l~/YMIN=/){split(l,fa,"=");nn++;mn[nn]=fa[2]+0}
             while((getline l<mxf)>0) if(l~/YMAX=/){split(l,fa,"=");nx++;mx[nx]=fa[2]+0}
             scale = (elms/1000.0) * 25.0 / nf }
      /pts_time:/{ split($0,fp,"pts_time:"); t=fp[2]+0 }
      /YAVG=/{ split($0,fc,"="); k++; if(k<=nx) printf "%.3f %.4f %d\n", t*scale, fc[2], mx[k]-mn[k] }
    ' "$LW/pe" > "$LW/scores.txt" 2>/dev/null
    SC=$(wc -l < "$LW/scores.txt" 2>/dev/null)
    case "$SC" in ''|*[!0-9]*) SC=0;; esac
    if [ "$SC" -gt 30 ]; then
        PRESCORED="$LW/scores.txt"
        log "逐帧评分: $SC 帧已就位"
    else
        log "逐帧评分只拿到 $SC 帧,作废,交由下游自行评分"
    fi
fi

TS=$(date +%Y%m%d_%H%M%S)
MP4="$OUTDIR/raw_${TS}.mp4"
"$FF" -hide_banner -y -f h264 -r "$FPS" -i "$RAW" -c copy -movflags +faststart "$MP4" >"$D/remux.log" 2>&1
MB=$(wc -c < "$MP4" 2>/dev/null)
[ "${MB:-0}" -lt 10000 ] && { echo "ERR 封装失败,看 $D/remux.log"; log "封装失败"; exit 3; }
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$MP4" >/dev/null 2>&1

[ -n "$PRESCORED" ] && echo "$PRESCORED" > /data/local/tmp/okcam_prescored
[ -z "$PRESCORED" ] && rm -f /data/local/tmp/okcam_prescored
log "完成 $MB 字节 fps=$FPS -> $MP4 | $STAT"
echo "fps=$FPS $STAT"
echo "$MP4"
exit 0
