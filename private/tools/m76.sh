P=$(pidof com.tmplayer.hx76)
t(){ awk '{print $14+$15}' /proc/$P/stat; }
a=$(t); sleep 10; b=$(t); echo "idle ticks/10s: $((b-a))"
dumpsys gfxinfo com.tmplayer.hx76 reset >/dev/null
a=$(t); sh /data/local/tmp/hold0.sh; sleep 1; b=$(t); echo "hold ticks: $((b-a))"
dumpsys gfxinfo com.tmplayer.hx76 | grep -E "Total frames|Janky frames|90th|95th"
