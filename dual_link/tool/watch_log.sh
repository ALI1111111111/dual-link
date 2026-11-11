#!/usr/bin/env bash
# Watches a log written by a long-running job and prints one line per event worth acting on:
#   - lines matching ALERT (failures, timeouts, progress markers)
#   - "STALL ..." when the log has not changed for STALL_SEC (job hung)
#   - "DONE ..." and exit when DONE matches
# Handles UTF-16 logs from Windows PowerShell redirection.
# Usage: watch_log.sh <logfile> [stall_sec=180] [done_regex='passed'] [alert_regex='^\[FAIL|timeout|STALL']
f="$1"; stall="${2:-180}"; done_re="${3:-passed}"; alert_re="${4:-^\[FAIL|timeout|Exception|error}"
[ -z "$f" ] && { echo "usage: watch_log.sh <logfile> [stall_sec] [done_regex] [alert_regex]"; exit 2; }
seen=0; quiet=0; warned=0
while true; do
  txt=$(tr -d '\0\r' < "$f" 2>/dev/null)
  n=$(printf '%s\n' "$txt" | wc -l)
  if [ "$n" -gt "$seen" ]; then
    printf '%s\n' "$txt" | tail -n +$((seen + 1)) | grep -aE "$alert_re"
    seen=$n; quiet=0; warned=0
  else
    quiet=$((quiet + 5))
    if [ "$quiet" -ge "$stall" ] && [ "$warned" -eq 0 ]; then
      echo "STALL: $f unchanged for ${quiet}s. Last line: $(printf '%s\n' "$txt" | tail -n 1)"; warned=1
    fi
  fi
  if printf '%s\n' "$txt" | grep -aqE "$done_re"; then
    echo "DONE: $(printf '%s\n' "$txt" | grep -aE "$done_re" | tail -n 1)"; exit 0
  fi
  sleep 5
done
