#!/system/bin/sh
echo "[fake] dispatcher ready"
while true; do
  if [ -f /data/local/tmp/chan/run ]; then
    mv /data/local/tmp/chan/run /data/local/tmp/chan/busy 2>/dev/null
    /system/bin/sh /data/local/tmp/chan/busy
    rm -f /data/local/tmp/chan/busy
  fi
  sleep 1
done
