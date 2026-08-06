#!/bin/sh
# Redirect Telegram-bound traffic passing through this host into a local
# tg-ws-proxy running with --transparent. Everything else is untouched.
#
#   ./transparent-nft.sh apply    # install rules
#   ./transparent-nft.sh delete   # remove them
#   ./transparent-nft.sh show     # print current rules
#
# Requires nftables and IP forwarding. Clients must route Telegram subnets
# through this host — see docs/Transparent.md.
set -eu

PORT="${TG_WS_PROXY_PORT:-1443}"
TABLE="tgwsproxy"

# Telegram datacenter ranges. Verify against
# https://core.telegram.org/resources/cidr.txt — they do change.
V4_NETS="91.105.192.0/23,
         91.108.4.0/22,
         91.108.8.0/22,
         91.108.12.0/22,
         91.108.16.0/22,
         91.108.20.0/22,
         91.108.56.0/22,
         95.161.64.0/20,
         149.154.160.0/20,
         185.76.151.0/24"

# MTProto is served on these; leaving it narrow keeps unrelated traffic out.
PORTS="443, 80, 5222"

usage() {
    echo "usage: $0 {apply|delete|show}" >&2
    exit 1
}

[ $# -eq 1 ] || usage

case "$1" in
apply)
    command -v nft >/dev/null || {
        echo "nft not found: install nftables" >&2
        exit 1
    }
    [ "$(id -u)" -eq 0 ] || {
        echo "must run as root" >&2
        exit 1
    }

    if [ "$(cat /proc/sys/net/ipv4/ip_forward)" != "1" ]; then
        echo "note: net.ipv4.ip_forward is 0 — traffic from other hosts will" >&2
        echo "      not be forwarded. Enable it to make this work:" >&2
        echo "      sysctl -w net.ipv4.ip_forward=1" >&2
    fi

    nft delete table ip "$TABLE" 2>/dev/null || true
    nft -f - <<EOF
table ip $TABLE {
    set telegram {
        type ipv4_addr
        flags interval
        elements = { $V4_NETS }
    }

    chain prerouting {
        type nat hook prerouting priority dstnat; policy accept;
        ip daddr @telegram tcp dport { $PORTS } redirect to :$PORT
    }
}
EOF
    echo "Rules installed: Telegram subnets -> 127.0.0.1:$PORT"
    echo "Start the proxy with:  tg-ws-proxy --transparent --port $PORT"
    ;;
delete)
    nft delete table ip "$TABLE" 2>/dev/null && echo "Rules removed" \
        || echo "No rules to remove"
    ;;
show)
    nft list table ip "$TABLE" 2>/dev/null || echo "No rules installed"
    ;;
*)
    usage
    ;;
esac
