# core:lanremote: type on your phone (M3.5)

Typing on a TV remote is slow. The search screen shows a QR code; the phone scans it, opens a tiny page,
and whatever is typed there appears in the TV's search box. Depends on `:core:designsystem` only.

## API (package `app.bolbol.tv.core.lanremote`)
| | |
|---|---|
| `PhoneRemotePanel(modifier, onText)` | Drop it in the search screen. Starts the server while composed, shows QR + address + Persian hint, calls `onText(fullText)` on each phone edit (replace, not append), stops on dispose. Offline: message + retry button (the only focusable element). |
| `QrCode(content, sizeDp, modifier)` | zxing to `ImageBitmap`, black on white, 4-module quiet zone, whole pixels per module, cached with `remember`. |
| `PhoneRemoteServer(context)` | `start(): PhoneRemoteSession?` (null when offline), `stop()`, `text: SharedFlow<String>`. `PhoneRemoteSession.url` = `http://192.168.x.y:port/?t=token`. |

## Rules (all enforced in `RemoteRequestHandler`, covered by unit tests)
- NanoHTTPD binds to the LAN IPv4 only (ConnectivityManager Wi-Fi/Ethernet link addresses, then `NetworkInterface`; loopback,
  link-local, IPv6, VPN, cellular and tunnels skipped; private ranges preferred), random free port 8000-8999.
- Random 128-bit token per start. Every request needs it (`?t=` or `"t"` in the JSON body), else 403. Compared in constant time.
- Only `GET /` (page) and `POST /text` (`{"text": "..."}`); anything else 404. Body over 2 KB: 413. About 20 requests per second per
  client, else 429. Every reply is `Connection: close`, `no-store`, with a strict CSP; at most 6 worker threads.
- Text is normalised: Arabic yeh/kaf become Persian ی/ک, line breaks become spaces, control and bidi marks dropped, ZWNJ kept, max 200 chars.
- Runs only while `PhoneRemotePanel` is on screen. Nothing is stored or logged.

## The phone page (`RemotePage`)
Self-contained RTL Persian HTML (no external resources, ES5, light/dark). Big autofocus input with `enterkeyhint="search"`;
sends with `fetch` 250 ms after the last keystroke and on Enter, one request in flight at a time; shows "ارسال شد ✓".

## Testing
`tools/gw.sh :core:lanremote:testDebugUnitTest` (JVM only). `PhoneRemoteServerTest` starts the real server on 127.0.0.1 and talks HTTP to it;
`QrMatrixTest` decodes the generated QR with zxing's reader.

## On the emulator
The emulator's "LAN" is its private 10.0.2.x network behind NAT, so a phone cannot reach it. The server listens on that address only
(not on loopback), and `adb forward tcp:PORT tcp:PORT` connects to the device's `127.0.0.1`, so the forward alone does not reach it.
Check from the host side with the device shell instead (PORT and token are in the URL shown on screen):
`adb shell 'printf "GET /?t=TOKEN HTTP/1.0\r\n\r\n" | toybox nc 10.0.2.15 PORT'`. On the real TV a phone on the same Wi-Fi just works.
