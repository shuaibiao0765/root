#!/system/bin/sh
# root dispatcher (runs with the fake cred, consumes chan/run scripts).
# 自适应降频：跑的时候 1 秒一轮，空闲后 5 秒、30 秒一轮，挂机几乎零唤醒。
# 绝对不要 kill 这个进程或 timer98260（fake cred 页要一直钉到重启）。
# 用 /system/bin/usleep 而不是内建 sleep：exploit fork 出来的 mksh 里
# sleep 会退化成用户态忙等，把 CPU 烧满还会拖慢 plant。
PATH=/system/bin:/system/xbin:/vendor/bin:/sbin
export PATH
cd /data/local/tmp 2>/dev/null || exit 1
mkdir -p chan 2>/dev/null
echo "[fake] dispatcher ready"
n=0
while true; do
  if [ -f chan/run ]; then
    n=0
    mv chan/run chan/busy 2>/dev/null
    if [ -f chan/busy ]; then
      /system/bin/sh chan/busy
      rm -f chan/busy
    fi
    /system/bin/usleep 1000000
    continue
  fi
  n=$((n+1))
  if [ $n -lt 1200 ]; then s=1000000; elif [ $n -lt 1920 ]; then s=5000000; else s=30000000; fi
  /system/bin/usleep $s
done
