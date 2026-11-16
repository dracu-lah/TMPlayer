# Per-thread CPU over one held-Down run (HX76): snapshots /proc/<pid>/task/*/stat before and
# after hold0.sh (same directory, same event device) and prints each thread's utime+stime delta,
# converted from jiffies to ms at USER_HZ=100 (Android's value; /proc/stat's first line checked
# below in case a device differs). Usage: sh threads0.sh <pid>
PID=$1
if [ -z "$PID" ]; then echo "usage: threads0.sh <pid>"; exit 1; fi
TASKS=/proc/$PID/task
HZ=100

snap() {
  for t in "$TASKS"/*/stat; do
    [ -f "$t" ] || continue
    tid=$(basename "$(dirname "$t")")
    # Thread names can hold spaces and parens ("Jit thread pool"), which also sit inside the
    # stat file's own (comm) field and shift every fixed position after it; awk splits on the
    # last ')' first so utime/stime (fields 14/15 overall, 12/13 of the rest) land right.
    comm=$(cat "$TASKS/$tid/comm" 2>/dev/null)
    rest=$(sed 's/.*) //' "$t" 2>/dev/null)
    set -- $rest
    utime=${12}; stime=${13}
    echo "$tid|$comm|$utime|$stime"
  done
}

before=$(snap)
sh "$(dirname "$0")/hold0.sh"
# hold0.sh only queues the key events; the jump, compose and draw it triggers run over the
# following frames, so the "after" snapshot waits for them to settle first.
sleep 2
after=$(snap)

echo "$before" | while IFS='|' read -r tid comm u1 s1; do
  [ -z "$tid" ] && continue
  line=$(echo "$after" | awk -F'|' -v t="$tid" '$1==t{print $3"|"$4}')
  [ -z "$line" ] && continue
  u2=$(echo "$line" | cut -d'|' -f1)
  s2=$(echo "$line" | cut -d'|' -f2)
  du=$((u2 - u1)); ds=$((s2 - s1))
  total=$((du + ds))
  [ "$total" -gt 0 ] && echo "$comm (tid $tid): $((total * 1000 / HZ)) ms (user $((du * 1000 / HZ)), sys $((ds * 1000 / HZ)))"
done | sort -t: -k2 -rn
