#!/system/bin/sh
# 在 fake root shell（u:r:kernel:s0）里跑：install → late-load → 回读验证。
# ksud 自己把日志写进 /data/adb/ksu/log/，stdout 基本是空的，
# 所以这里每一步都把 rc、ksud 日志、dmesg 尾巴全部打出来。
D=${PD_D:-/data/local/tmp}
PKG=${PD_PKG:-me.weishu.kernelsu}
KMI=${PD_KMI:-android16-6.12}

echo "[ksu] start uid=$(id -u) selinux=$(getenforce)"
grep -E '^(CapEff|Seccomp)' /proc/self/status

APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
KSUD=''
if [ -n "$APK" ]; then KSUD="$(dirname "$APK")/lib/arm64/libksud.so"; fi
[ -x "$KSUD" ] || KSUD=$(find /data/app -path "*/$PKG*/lib/arm64/libksud.so" 2>/dev/null | head -1)
[ -x "$KSUD" ] || KSUD=$D/ksud
[ -x "$KSUD" ] || KSUD=/data/adb/ksu/bin/ksud
[ -x "$D/ksud" ] && KSUD=$D/ksud
if [ ! -x "$KSUD" ]; then echo '[ksu] ksud missing'; exit 1; fi
echo "[ksu] ksud=$KSUD"
echo "[ksu] version=$("$KSUD" --version 2>&1 | head -1)"
ls -l "$D/ksud"

mkdir -p /data/adb 2>/dev/null
echo "[ksu] kall 行数=$(wc -l < /data/adb/kall 2>/dev/null)"

"$KSUD" install > "$D/install.log" 2>&1
echo "[ksu] install rc=$?"
sed -n '1,20p' "$D/install.log" 2>/dev/null

"$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$PKG" > "$D/late.log" 2>&1
RC=$?
echo "[ksu] late-load rc=$RC"
sed -n '1,40p' "$D/late.log" 2>/dev/null

if ! grep -q '^kernelsu ' /proc/modules; then
  "$KSUD" late-load --allow-shell --package-name "$PKG" > "$D/late2.log" 2>&1
  echo "[ksu] late-load2 rc=$?"
  sed -n '1,40p' "$D/late2.log" 2>/dev/null
fi

i=0
while [ $i -lt 20 ]; do
  grep -q '^kernelsu ' /proc/modules && break
  i=$((i+1)); /system/bin/usleep 200000
done

if ! grep -q '^kernelsu ' /proc/modules; then
  echo "[ksu] ---- ksud 自己的日志 ----"
  ls -l /data/adb/ksu/log 2>&1 | head -10
  for f in /data/adb/ksu/log/*; do
    [ -f "$f" ] && { echo "--- $f ---"; tail -n 40 "$f"; }
  done
  echo "[ksu] ---- /data/adb/ksu 结构 ----"
  ls -l /data/adb/ksu 2>&1 | head -20
  echo "[ksu] ---- 直接 insmod 内置 LKM ----"
  if [ -f "$D/ksu.ko" ]; then
    insmod "$D/ksu.ko" 2>&1
    echo "[ksu] insmod rc=$?"
    /system/bin/usleep 500000
  else
    echo "[ksu] 没有 $D/ksu.ko"
  fi
  echo "[ksu] ---- dmesg 尾巴（vermagi/signature 错误都在这）----"
  dmesg 2>&1 | tail -n 40
fi

if grep -q '^kernelsu ' /proc/modules; then
  echo KSU_LOADED
else
  echo KSU_MISSING
fi
echo "[ksu] enforce=$(getenforce)"
echo "[ksu] whoami=$(id)"
