#!/system/bin/sh
# =============================================================
#  okcam_wifi.sh -- 切换摄像头连哪个 WiFi
# =============================================================
#  用法: okcam_wifi.sh show            看摄像头现在连的是哪个
#        okcam_wifi.sh hotspot         切到手机热点(出门用)
#        okcam_wifi.sh set <名字> <密码>  切到任意 WiFi
#
#  ★ 出门流程:先在手机上把热点打开(名字/密码要和下面 HS_* 一致),
#    确认热点起来了,再执行 hotspot。顺序反了摄像头会连不上而掉线。
#  ★ 摄像头【只记得一个】WiFi,切过去就忘掉原来那个。
#  ★ 实测:切换后 P2P 会断一下,守护进程的看门狗会自己接回来(约 10 秒)。
#  ★ 摄像头【不支持】学校那种要用户名密码的企业网(固件里没有对应参数),
#    所以出门只能走热点这条路。
# =============================================================
M=/data/local/tmp/mcurl
API=http://127.0.0.1:8099/cgi
# 改成你自己手机热点的名字和密码
HS_SSID="${OKAM_HS_SSID:-<YOUR_HOTSPOT_SSID>}"
HS_PSK="${OKAM_HS_PSK:-<YOUR_HOTSPOT_PASSWORD>}"
CAMD=/data/local/tmp/camd/camctl.sh

alive(){ ps -A -o ARGS 2>/dev/null | grep -q "[c]am\.Daemon"; }
ensure(){
    alive && return 0
    echo "守护进程没跑,拉起..."
    sh "$CAMD" start >/dev/null 2>&1
    i=0; while [ $i -lt 30 ]; do sleep 1; i=$((i+1)); grep -q ONLINE /data/local/tmp/camd/log.txt 2>/dev/null && return 0; done
    echo "拉不起来,看 /data/local/tmp/camd/log.txt"; return 1
}
setwifi(){
    S=$1; P=$2
    echo "切换到 \"$S\" ..."
    "$M" -s -m 25 "$API?c=set_wifi.cgi%3Fenable=1%26mode=1%26encrypt=2%26authtype=0%26channel=0%26ssid=$S%26wpa_psk=$P%26" | grep -E 'result=|ok'
    echo "已发出。摄像头会断开重连,等它自己回来(约 10~30 秒):"
    i=0
    while [ $i -lt 12 ]; do
        sleep 5; i=$((i+1))
        if grep -q ONLINE /data/local/tmp/camd/log.txt 2>/dev/null && [ -n "$(tail -3 /data/local/tmp/camd/log.txt | grep ONLINE)" ]; then
            echo "  [$((i*5))s] 已重新连上"; return 0
        fi
        echo "  [$((i*5))s] 等待中..."
    done
    echo "  ⚠ 超时。摄像头可能没连上新网络 —— 用 O-KAM App 重新配网救回来。"
    return 1
}

case "${1:-show}" in
  show)
    ensure || exit 1
    "$M" -s -m 15 "$API?c=get_params.cgi" 2>/dev/null | tr ';' '\n' | grep -E 'wifi_ssid|wifi_enable' | sed 's/^/  /'
    echo "  (摄像头在本机热点上的地址: $(ip neigh show dev wlan1 2>/dev/null | awk '{print $1}' | head -1))"
    ;;
  hotspot)
    ip -4 addr show wlan1 2>/dev/null | grep -q inet || { echo "✗ 热点没开。先在手机上打开热点(名字 $HS_SSID),再跑这条。"; exit 1; }
    ensure || exit 1
    setwifi "$HS_SSID" "$HS_PSK"
    ;;
  set)
    [ -n "${2:-}" ] && [ -n "${3:-}" ] || { echo "用法: $0 set <名字> <密码>"; exit 1; }
    ensure || exit 1
    setwifi "$2" "$3"
    ;;
  *) echo "用法: $0 show | hotspot | set <名字> <密码>"; exit 1;;
esac
