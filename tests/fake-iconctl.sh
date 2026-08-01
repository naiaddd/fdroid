#!/bin/sh
set -eu

: "${ICONCTL_LOG:?ICONCTL_LOG must name a log file}"
printf '%s\n' "$*" >"$ICONCTL_LOG"
exit "${ICONCTL_STATUS:-0}"
