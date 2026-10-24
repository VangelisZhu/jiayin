#!/bin/sh
set -eu
if [ "$(id -u)" = "0" ]; then
    mkdir -p "$JIAYIN_DATA_ROOT"
    chown "${PUID:-1000}:${PGID:-1000}" "$JIAYIN_DATA_ROOT"
    chmod 700 "$JIAYIN_DATA_ROOT"
    exec gosu "${PUID:-1000}:${PGID:-1000}" "$@"
fi
exec "$@"
