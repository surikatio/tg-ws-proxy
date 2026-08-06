# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Проект

TG WS Proxy — локальный MTProto-прокси для Telegram Desktop, который принимает MTProto-подключения на `127.0.0.1:1443` и переносит трафик к дата-центрам Telegram через WebSocket (`wss://kws{DC}.web.telegram.org/apiws`). Ядро (`proxy/`) не зависит от GUI; вокруг него собраны tray-приложения для Windows/macOS/Linux.

Документация пользователя — в `docs/` (на русском), точка входа `docs/README.md`. Версия проекта хранится в `proxy/__init__.py` (`__version__`), pyproject берёт её оттуда через `[tool.hatch.version]`.

## Команды

```bash
pip install -e .          # установка в dev-режиме (Python >= 3.8)

tg-ws-proxy               # консольный прокси (proxy.tg_ws_proxy:main)
tg-ws-proxy --lan         # то же, но на всю локальную сеть + постоянный секрет
tg-ws-proxy-tray-win      # tray: windows.py
tg-ws-proxy-tray-macos    # tray: macos.py
tg-ws-proxy-tray-linux    # tray: linux.py

python proxy/tg_ws_proxy.py -v   # запуск модуля напрямую (есть sys.path-шим для __main__)
```

Полезные флаги при отладке: `-v` (DEBUG), `--dc-ip DC:IP` (повторяемый), `--no-cfproxy`, `--cfproxy-worker-domain`, `--fake-tls-domain`, `--pool-size 0` (отключить пул), `--force-test-dc`. Полный список — `docs/BuildFromSource.md`.

Сборка бинарников — PyInstaller: `pyinstaller packaging/windows.spec --noconfirm` (аналогично `macos.spec` / `linux.spec`). CI (`.github/workflows/build.yml`) запускается только вручную (`workflow_dispatch`).

Тестов в репозитории нет. Линтер — ruff, настройки в `pyproject.toml` (`ignore = ["F403", "F405"]`, потому что модули `proxy/` используют `from .utils import *`).

## Архитектура

### Поток одного подключения (`proxy/tg_ws_proxy.py`)

`_run()` поднимает `asyncio.start_server`, каждое соединение обрабатывает `_handle_client`:

1. `_read_client_init` — читает 64-байтный obfuscated init. Если включён Fake TLS (`--fake-tls-domain`), сначала разбирается ClientHello: валидный — отвечаем ServerHello и дальше работаем через `FakeTlsStream`; невалидный — трафик проксируется на маскировочный домен, не-TLS первый байт — HTTP 301.
2. `_try_handshake` — расшифровывает init ключом `SHA256(prekey + secret)`, извлекает proto tag и `dc_idx` (отрицательный = media, `>= 10000` = test DC).
3. `_generate_relay_init` — генерирует новый init для аплинка (обязателен обход зарезервированных префиксов).
4. `_build_crypto_ctx` — два независимых набора AES-CTR: клиентский (с секретом) и релейный (сырой ключ). Мост **перешифровывает** поток, а не туннелирует его как есть.
5. Выбор транспорта: `ws_pool.get()` → прямой WSS по доменам из `ws_domains()` → `do_fallback`.
6. `bridge_ws_reencrypt` — двунаправленная перекачка с перешифровкой.

### Лестница фолбэков

Порядок попыток при недоступности WS (`proxy/bridge.py::do_fallback`): CF Worker (`--cfproxy-worker-domain`) → CF proxy (домены `kws{dc}.{base_domain}` из `balancer`) → прямой TCP на `DC_DEFAULT_IPS`.

Fronting (WSS с подменённым SNI `sprinthost.ru`) инкапсулирован в `_WsPool._connect_one`: при таймауте обычного коннекта пробуется fronted-вариант, а флаг `try_fronting_first` запоминает удачу и в следующий раз начинает сразу с него. Отдельного глобального состояния в `tg_ws_proxy.py` для этого больше нет.

Состояние деградации хранится в модульных словарях `tg_ws_proxy.py`: `ws_blacklist` (DC, где все домены отдали 302 — навсегда до перезапуска), `dc_fail_until` (кулдаун 60 с), `ip_fail_until` (кулдаун 1 ч на целевой IP), `fronting_until` (30 мин). `_run()` очищает их при каждом старте — это важно, потому что tray перезапускает прокси в том же процессе.

### Ключевые модули `proxy/`

- `config.py` — глобальный синглтон `proxy_config` (dataclass `ProxyConfig`). Также содержит обфусцированный список CF-доменов (`_dd()`) и фоновый поток обновления списка с GitHub раз в час.
- `balancer.py` — синглтон `balancer`: закрепляет за каждым DC активный CF-домен, остальные отдаёт в случайном порядке.
- `pool.py` — синглтоны `ws_pool` и `cf_worker_pool`: прогретые WS-соединения на DC (TTL 120/100 с), дозаполняются в фоне при каждом `get()`. Здесь же фоновая ротация протухших соединений (`_rotate`), экспоненциальный backoff на неудачных дозаливках (60 с → 1 ч) и вся fronting-логика.
- `raw_websocket.py` — собственная минимальная реализация WS-клиента поверх `asyncio` + TLS без верификации сертификата (`CERT_NONE`, `check_hostname=False`) — намеренно, так как подключение идёт по IP с произвольным SNI.
- `bridge.py::MsgSplitter` — режет TCP-поток на отдельные MTProto-пакеты, чтобы каждый ушёл своим WS-фреймом; для этого держит теневой дешифратор релейного потока. При непонятной длине пакета сам себя отключает (`_disabled`) и дальше пропускает данные как есть.
- `_aes.py` — шим AES-CTR, выбирает бэкенд в порядке `cryptography` → `javax.crypto` (Android/Chaquopy, колеса `cryptography` для Android не существует) → ctypes-обёртка над системным `libcrypto` (роутеры/embedded). Импортировать AES только отсюда.
- `stats.py` — синглтон `stats`, дампится в лог раз в минуту.
- `utils.py` — протокольные константы, таблицы IP DC, `ws_domains()`, HTTPS-opener с пиннингом IP GitHub (обход блокировок при проверке обновлений).

Глобальные синглтоны (`proxy_config`, `stats`, `balancer`, `ws_pool`, `cf_worker_pool`) — сознательное решение: конфиг мутируется снаружи (CLI-парсером или tray) до вызова `_run()`.

### Tray-приложения

`utils/tray_common.py` — вся общая логика: определение каталога данных (`%APPDATA%/TgWsProxy`, `~/Library/Application Support/TgWsProxy`, `~/.config/TgWsProxy`), portable-режим (`TgWsProxy_data` рядом с exe или `--portable`), single-instance lock через pid-файлы + psutil, загрузка/сохранение `config.json`, логирование, запуск `proxy.tg_ws_proxy._run()` в отдельном потоке со своим event loop, `stop_proxy()` через `loop.call_soon_threadsafe`.

`windows.py` / `linux.py` / `macos.py` — платформенные обёртки с одинаковым набором пунктов меню (открыть в Telegram, скопировать ссылку, перезапустить, настройки, логи, выход). Windows и Linux используют pystray + CustomTkinter (`ui/ctk_tray_ui.py` — общая форма настроек), macOS — rumps со своим нативным UI. У Windows дополнительно есть автозапуск через реестр и самообновление (`_perform_update`).

`ui/i18n` — простой JSON-словарь (`ru.json`, `en.json`), функция `t(key, **kwargs)`; язык определяется из системной локали при импорте. Новые строки UI добавлять в оба файла.

Ключи `config.json` описаны в `docs/TrayConfig.md`; дефолты — в `utils/default_config.py`, оттуда они переносятся в `proxy_config` через `apply_proxy_config()`.

### Серверное развёртывание

`--lan` = `--host 0.0.0.0` + постоянный секрет через `load_or_create_secret()` (`config.py`, файл `0600`), чтобы выданные устройствам ссылки переживали рестарт. Готовый systemd-юнит — `packaging/tg-ws-proxy.service` (DynamicUser + StateDirectory), инструкция — `docs/LanServer.md`.

Ссылки строит `build_proxy_links()` (`proxy/utils.py`) и возвращает пару: `tg://proxy` для локального открытия и `https://t.me/proxy` для пересылки — вторая, в отличие от первой, остаётся кликабельной в чате. В трее «Скопировать ссылку» отдаёт t.me-вариант (`share_proxy_url`), «Открыть в Telegram» — `tg_proxy_url`.

`--transparent` обслуживает клиентов, перенаправленных netfilter'ом и ничего не знающих о прокси: их init зашифрован без секрета, поэтому `_try_handshake()` и `_build_crypto_ctx()` принимают `secret=None` и используют сырой prekey (та же схема, что и для аплинка). Номер DC берётся из самого init. Соединения, не похожие на MTProto (веб/CDN в тех же подсетях), пробрасываются функцией `passthrough()` на адрес из `SO_ORIGINAL_DST` — без этого прозрачный перехват ломал бы их. Правила — `packaging/transparent-nft.sh`, описание — `docs/Transparent.md`.

Ограничители, важные на публичном порту: `proxy_config.max_connections` (проверяется в `client_cb` до создания таска) и `_drain_bad_client()` — ограниченный по времени и объёму дренаж клиента с неверным секретом (мгновенный разрыв выдал бы сканеру наличие прокси, а бесконечный занимал бы слот).

## Конвенции

- Никаких внешних HTTP/WS-библиотек в `proxy/` — только stdlib + `cryptography`. Docker-образ копирует только `proxy/`, поэтому ядро не должно импортировать `ui/` или `utils/` на уровне модуля (`tg_ws_proxy.main` импортирует `utils.logging_setup` лениво, внутри функции).
- Логгер прокси — `logging.getLogger('tg-mtproto-proxy')`, tray — `'tg-ws-tray'`. Формат сообщений: `[%s] ...` с меткой `ip:port` клиента.
- В сетевых путях исключения гасятся широко (`except Exception` с логом уровня warning/debug) — обрыв одного соединения не должен ронять сервер; следуйте этому стилю.
- Комментарии в коде — на английском, документация и строки UI — на русском и английском.
