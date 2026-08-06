"""
Bridge between the Android service and the shared proxy core.

Kotlin cannot drive asyncio directly, so this module owns the event loop and
exposes plain blocking calls: start(), stop(), status(). The proxy itself is
the unmodified proxy/ package from the repository root.
"""
from __future__ import annotations

import asyncio
import logging
import os
import threading
from typing import Optional

from proxy.config import proxy_config
from proxy.stats import stats
from proxy.tg_ws_proxy import _run
from proxy.utils import build_proxy_links, human_bytes

log = logging.getLogger("tg-ws-proxy-android")

_thread: Optional[threading.Thread] = None
_loop: Optional[asyncio.AbstractEventLoop] = None
_stop_event: Optional[asyncio.Event] = None
_last_error: str = ""


def _configure(port: int, secret: str) -> None:
    # Phones only ever serve Telegram running on the same device.
    proxy_config.host = "127.0.0.1"
    proxy_config.port = port
    proxy_config.secret = secret
    # A phone carries a handful of connections, not thousands; a smaller pool
    # means fewer idle TLS sessions kept alive on battery.
    proxy_config.pool_size = 2
    proxy_config.buffer_size = 128 * 1024
    proxy_config.max_connections = 64


def _serve() -> None:
    global _loop, _stop_event, _last_error

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    _loop = loop
    _stop_event = asyncio.Event()

    try:
        loop.run_until_complete(_run(stop_event=_stop_event))
    except Exception as exc:
        _last_error = repr(exc)
        log.error("proxy stopped with an error: %s", _last_error)
    finally:
        try:
            loop.close()
        finally:
            _loop = None
            _stop_event = None


def start(port: int, secret: str) -> str:
    """Start the proxy and return the tg:// link Telegram should be given."""
    global _thread, _last_error

    if is_running():
        return link(port, secret)

    _last_error = ""
    _configure(port, secret)

    _thread = threading.Thread(target=_serve, name="tg-ws-proxy", daemon=True)
    _thread.start()
    return link(port, secret)


def stop() -> None:
    global _thread

    loop, stop_event = _loop, _stop_event
    if loop is not None and stop_event is not None:
        loop.call_soon_threadsafe(stop_event.set)

    thread = _thread
    if thread is not None:
        thread.join(timeout=5)
    _thread = None


def is_running() -> bool:
    return _thread is not None and _thread.is_alive()


def generate_secret() -> str:
    return os.urandom(16).hex()


def link(port: int, secret: str) -> str:
    tg_link, _ = build_proxy_links("127.0.0.1", port, secret)
    return tg_link


def status() -> str:
    """One-line summary for the notification."""
    if _last_error:
        return f"error: {_last_error}"
    if not is_running():
        return "stopped"
    return (f"active {stats.connections_active} · "
            f"↑{human_bytes(stats.bytes_up)} ↓{human_bytes(stats.bytes_down)}")
