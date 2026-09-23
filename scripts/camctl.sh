#!/system/bin/sh
# =============================================================
#  camctl.sh -- 摄像头守护进程(cam.Daemon)的启停 + 闲置自动收工
# =============================================================
#  2026-09-10:用户要求「我不开的时候不需要一直检查」。
#  以前是 一个每 60 秒轮询、永远跑着的看门狗。现在改成:
#    · 守护【按需启动】—— rawrec.sh 要录像时发现没跑就拉起(含会话重连)
#    · 守护【闲置自收】—— 起来时附带一个收工员,连续 IDLE_MIN 分钟没人录像
#      就把守护和自己一起结束掉。于是关掉扫描之后,最多十几分钟手机就全静了。
#  所以【没有任何进程需要长期轮询】。
# =============================================================
D=/data/local/tmp/camd
USEF=/data/local/tmp/okcam_last_use          # 最后一次录像的时间戳(rawrec 写)
IDLE_MIN=${OKCAM_IDLE_MIN:-15}               # 闲置多少分钟后收工
LOG=/data/local/tmp/okcam_rec.log

# ★ 不能用 pgrep -f cam.Daemon:调用方命令行里带这个字符串就会自匹配
#   (pgrep -f 会匹配调用方自己的命令行)。一律用方括号法。
dpid(){ ps -A -o PID,ARGS 2>/dev/null | grep "[c]am\.Daemon" | awk '{print $1}' | head -1; }
rpid(){ ps -A -o PID,ARGS 2>/dev/null | grep "[c]amctl\.sh reap" | awk '{print $1}' | head -1; }
log(){ echo "$(date '+%m-%d %H:%M:%S') camd: $*" >> "$LOG"; }

stop(){
    P=$(dpid); [ -n "$P" ] && kill -9 $P 2>/dev/null
    R=$(rpid); [ -n "$R" ] && kill -9 $R 2>/dev/null
    sleep 1
}
start(){
    cd "$D" || exit 1
    setsid env LD_LIBRARY_PATH="$D" CLASSPATH="$D/cam.jar" \
        app_process -Djava.library.path="$D" "$D" cam.Daemon </dev/null >"$D/log.txt" 2>&1 &
    date +%s > "$USEF"
    # 收工员:自己也会退出,不是常驻看门狗
    [ -z "$(rpid)" ] && setsid sh "$0" reap </dev/null >/dev/null 2>&1 &
}

case "$1" in
  stop)    stop; echo stopped;;
  start)   stop; start; echo started;;
  status)
    P=$(dpid)
    echo "守护进程 : $([ -n "$P" ] && echo "在跑 (pid $P)" || echo 没跑)"
    echo "收工员   : $([ -n "$(rpid)" ] && echo 在岗 || echo 无)"
    if [ -s "$USEF" ]; then
        A=$(( $(date +%s) - $(cat "$USEF") ))
        echo "闲置     : ${A}s (超过 $((IDLE_MIN*60))s 就自动收工)"
    fi
    ;;
  reap)
    # 每分钟看一眼;连续闲置够久就把守护和自己一起结束
    while :; do
        sleep 60
        [ -z "$(dpid)" ] && exit 0                 # 守护已经不在,收工员也没必要留着
        [ -s "$USEF" ] || { date +%s > "$USEF"; continue; }
        A=$(( $(date +%s) - $(cat "$USEF") ))
        if [ "$A" -ge $((IDLE_MIN*60)) ]; then
            log "闲置 ${A}s,守护自动收工(下次要录时会自己再起来)"
            P=$(dpid); [ -n "$P" ] && kill -9 $P 2>/dev/null
            exit 0
        fi
    done
    ;;
  *) echo "usage: $0 start|stop|status";;
esac
