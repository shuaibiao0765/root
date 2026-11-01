#!/system/bin/sh
# Root 酱 dispatcher（照抄参考机 2026-11-01 12:xx 版本）
# 为什么不用 sleep / FIFO：
#   1) mksh 的 sleep 内建在 exploit fork 出来的进程里会退化成用户态忙等（100% CPU）；
#   2) 命名管道 chan/wake 会被 rm/mkfifo 反复重建，inode 一坏 dispatcher 就空转。
# 所以只用绝对路径的 usleep 做 1 秒轮询，既不空转也不依赖 wake。
PATH=/system/bin:/system/xbin:/vendor/bin:/sbin
export PATH
cd /data/local/tmp 2>/dev/null || exit 1
mkdir -p chan 2>/dev/null
echo "[fake] dispatcher ready"
while true; do
  if [ -f chan/run ]; then
    mv chan/run chan/busy 2>/dev/null
    if [ -f chan/busy ]; then
      /system/bin/sh chan/busy
      rm -f chan/busy
    fi
    continue
  fi
  /system/bin/usleep 1000000
done
