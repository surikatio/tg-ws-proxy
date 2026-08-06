#!/bin/sh
# Build the argument list positionally: every value stays a single quoted
# argument, so a hostile env var cannot break out into a shell command.
set -eu

set -- --host "${TG_WS_PROXY_HOST}" --port "${TG_WS_PROXY_PORT}"

# Word splitting is intentional here: DC_IPS is a space separated list.
# shellcheck disable=SC2086
for dc in ${TG_WS_PROXY_DC_IPS}; do
    set -- "$@" --dc-ip "$dc"
done

if [ -n "${TG_WS_PROXY_SECRET}" ]; then
    set -- "$@" --secret "${TG_WS_PROXY_SECRET}"
fi

# shellcheck disable=SC2086
for worker in ${TG_WS_PROXY_CF_WORKER}; do
    set -- "$@" --cfproxy-worker-domain "$worker"
done

if [ -n "${TG_WS_PROXY_MAX_CONNECTIONS}" ]; then
    set -- "$@" --max-connections "${TG_WS_PROXY_MAX_CONNECTIONS}"
fi

exec /opt/venv/bin/python -u proxy/tg_ws_proxy.py "$@"
