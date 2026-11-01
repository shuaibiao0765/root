#!/system/bin/sh
# 在 fake root shell（u:r:kernel:s0 → 写入后 u:r:ksu:s0）里跑：
#   ksud install → late-load → 等 /proc/modules 出现 kernelsu
# 参考机（iQOO PD2546）2026-11-01 那轮的驱动方式，原样搬过来。
D=${PD_D:-/data/local/tmp}
PKG=${PD_PKG:-me.weishu.kernelsu}
KMI=${PD_KMI:-android16-6.12}

echo "[ksu] start uid=$(id -u) selinux=$(getenforce)"
grep -E '^(Cap|Seccomp)' /proc/self/status

# ksud 优先用管理器自己带的（版本必须和管理器一致，否则管理器会显示异常）
APK=$(pm path "$PKG" 2>/dev/null | head -1 | cut -d: -f2)
KSUD=''
if [ -n "$APK" ]; then KSUD="$(dirname "$APK")/lib/arm64/libksud.so"; fi
[ -x "$KSUD" ] || KSUD=$(find /data/app -path "*/$PKG*/lib/arm64/libksud.so" 2>/dev/null | head -1)
[ -x "$KSUD" ] || KSUD=$D/ksud
[ -x "$KSUD" ] || KSUD=/data/adb/ksu/bin/ksud
[ -x "$D/ksud" ] && KSUD=$D/ksud
if [ ! -x "$KSUD" ]; then echo '[ksu] ksud missing'; exit 1; fi
echo "[ksu] ksud=$KSUD version=$("$KSUD" --version 2>&1 | head -1)"

# install 不能省：它建 /data/adb/ksu 的目录结构和模块表，
# 少了这步 late-load 出来的内核模块管理器认不出来（会显示异常/无 su）。
"$KSUD" install
echo "[ksu] install rc=$?"

"$KSUD" late-load --kmi "$KMI" --allow-shell --package-name "$PKG"
echo "[ksu] late-load rc=$?"
if ! grep -q '^kernelsu ' /proc/modules; then
  "$KSUD" late-load --allow-shell --package-name "$PKG"
  echo "[ksu] late-load2 rc=$?"
fi
i=0
while [ $i -lt 50 ]; do
  grep -q '^kernelsu ' /proc/modules && break
  i=$((i+1)); sleep 0.1
done
if grep -q '^kernelsu ' /proc/modules; then echo KSU_LOADED; else echo KSU_MISSING; fi
echo "[ksu] enforce=$(getenforce)"
echo "[ksu] whoami=$(id)"
