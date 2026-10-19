# Implementation notes

The implementation team wrote these notes from `SPEC.md` alone. Each entry is a choice made
where the spec was silent or ambiguous, or a place where the spec could not be followed
literally.

## Transport (spec §2)

- **Receive timeout.** OkHttp answers server WebSocket pings itself and never reports them to
  the listener, so a "no frame including Ping for 10 s" timer cannot see pings. The client uses
  OkHttp's `pingInterval(5 s)` instead. If a pong doesn't arrive before the next ping is due,
  the socket fails, so a dead link is detected in about 5–10 s. This relies on the server
  answering pings, as RFC 6455 requires.
- **Close codes.** When the server starts the close handshake, the client echoes 1001.
  Protocol errors use 1008, and every other client-initiated close uses 1001.
- **Connect attempts.** The app tries at most 5 distinct endpoints, each once, in this order:
  freshly discovered addresses, then saved addresses, with the most recently successful first.
  It does not cycle through a single endpoint 5 times.
- **Hello wait.** After the socket opens, the app waits up to 6 s (the connect timeout) for
  the server hello.
- **Cleartext.** The manifest sets `usesCleartextTraffic="true"` because `ws://` is required
  (spec §2). All payloads after the hello are encrypted with `crypto_box`.

## Crypto (spec §3)

- X25519 comes from BouncyCastle (`math.ec.rfc7748.X25519`). HSalsa20 is implemented in
  `CryptoBox.hsalsa20`. XSalsa20 and Poly1305 also come from BouncyCastle. Secretbox follows
  NaCl: the first 32 keystream bytes are the Poly1305 key, and the message is encrypted with
  the keystream that follows.
- `beforenm` rejects an all-zero shared secret (low-order server key) and treats it as a
  protocol error.
- If decryption fails, the session closes with 1008. The app never falls back to plaintext
  (the spec's **[D]** choice).
- The `crypto_box` test vector was checked against an independent from-scratch Java reference
  (JDK `XDH` plus hand-written Salsa20/Poly1305). That check ran in a scratch directory and is
  not part of the repo.

## Protobuf (spec §4)

- The codec is hand-written in `core/.../ProtoWire.kt` and `Messages.kt`.
- Proto3 scalars equal to their default are omitted. Fields marked `opt` are written whenever
  they are non-null. Message fields are written whenever they are non-null.
- Repeated enums and repeated uint32 are **written packed**. The decoder reads both packed and
  unpacked forms.
- Unknown fields with wire types 0, 1, 2 and 5 are skipped. Group wire types (3 and 4) are
  rejected as invalid, because proto3 never produces them.
- A known field that arrives with the wrong wire type is treated as invalid data, and the
  session closes with 1008.
- If a `ServerMessage` has no known payload, it decodes as `ServerMessage.Unknown` and is
  ignored. This keeps the client compatible with newer servers.
- `UIntRange` is defined in the spec but no listed message uses it, so it is not implemented.
- Enums are kept as raw `Int` constants, so unknown values survive decoding.

## Session (spec §5)

- **Request IDs.** IDs come from one counter per connection, starting at 0, and every
  request uses one: `authenticate`, `createVirtualDevice` and `openVirtualDevice`.
- **Authenticate.** Waiting for the user to accept has no timeout; the user can cancel instead.
  Cancel sends `cancelAuthenticate` and also fails the pending request locally, so the UI never
  hangs if the server stays silent. After `ok`, the app waits up to 20 s for
  `virtualDeviceList`.
- **createVirtualDevice reply.** The spec doesn't say which message answers it. The app treats
  `ok` or `virtualDeviceOpened` as success and `error` as failure. After creating the default
  device, it waits up to 20 s for a non-empty `virtualDeviceList`.
- **Default device.** "OpenDeck" (`skip=true`) is created whenever the device list is empty
  after authentication, not only right after the first pairing.
- **openVirtualDevice.** If the reply is `ok` instead of `virtualDeviceOpened`, it still counts
  as success.
- **clientCapabilities.** It is sent once, right after a successful open, and only when the
  server's protocolVersion is 3 or higher. The value is always `isUserPro=false`: this
  client has no paid subscription.
- **Second server hello.** A second hello after the handshake is treated as a protocol
  violation (1008).
- **Key input.** Key presses, page changes and `updateKeypadConfig` are sent only while a
  device is open.
- **Hello metadata.** The workstation hostname is used as the display name when no other name
  is known (manual entry or a QR link). Version and protocolVersion are kept in memory
  (`Session.serverHello`) and are not saved to disk.
- **Advertised endpoints.** Each address the hello advertises is stored with the advertised
  port. If the port is 0, the default port 28198 is used. Up to 8 endpoints are kept per host.

## Pages (spec §5.7)

- The spec doesn't say whether `Context.currentPage` is 0-based or 1-based. The app assumes
  0-based and shows `current+1 / total`. If `current == total`, it shows the value unchanged,
  as a 1-based fallback. This is marked as a `shortcut:` in `PageInfo` and needs checking
  against a real server.
- Icons are not cleared when the page changes, because the order of icon and context messages
  isn't specified. Icons are cleared when a device is opened or the layout changes.
- Action titles from `context` and `contextUpdate` are used only as accessibility labels.

## Icons (spec §6)

- Only LZ4 and LZF are advertised (`[LZ4, LZF]`). Other formats are logged and ignored.
- The decoded size must be exactly width×height×4, or the icon is rejected. Each dimension is
  capped at 1024 px, which also keeps buffers bounded.
- Pixels go into an `ARGB_8888` bitmap with `copyPixelsFromBuffer`, as the spec says.
  Android treats that memory as premultiplied alpha. Non-opaque, non-black pixels can
  therefore look slightly off if the server sends straight (non-premultiplied) alpha.

## Layout (spec §6.3, §6.4)

- The layout dialog offers the presets 5×3, 3×2 and 8×4, and custom steppers for 1–8 columns
  and 1–8 rows. It also offers icon sizes 72, 96, 144 and 192 px. 144 px is the default.
- Rotation is not implemented. The grid follows the screen orientation, and key indices
  always follow the logical grid.

## Discovery and entry (spec §7)

- NSD resolves are serialized (older Android allows only one at a time).
  `resolveService`/`host` are deprecated on API 34+, but the app still uses them there and
  reads `hostAddresses` on API 34 and above.
- IPv6 link-local addresses are dropped, because their zone IDs can't be put in a `ws://` URL.
  IPv4 addresses are tried first.
- Services with the same `VSDSV` workstation ID are merged into one entry.
- Discovery runs while the hosts screen is visible and stops after 20 s. A **Search again**
  button restarts it. The app holds a Wi-Fi multicast lock while discovery is running.
- **Add host** accepts a QR link or `host[:port]`. Supported forms are `host`, `host:port`,
  `[v6]`, `[v6]:port` and a bare IPv6 literal. With manual entry, the workstation ID comes
  from the hello.
- The QR host match ignores case. Any URL scheme is accepted, and `w` is required. If `av2`
  is present, legacy `a` entries are ignored.

## UX and lifecycle (spec §9)

- **Resume.** "Reconnect automatically on resume" works like this: if the keypad is visible
  when the activity stops, the app disconnects and remembers the host and device. On the next
  `onStart` it reconnects and reopens that device. A cold start does not auto-connect.
- **Unexpected disconnect.** The app returns to the hosts screen and shows the reason. It does
  not retry automatically.
- **Back button.** Back from the keypad returns to the device list and keeps the session
  open; no protocol message exists for closing a device. Back from any other connected
  screen disconnects.
- **Deleting a device.** `deleteVirtualDevice` is implemented in core (`deleteOpenDevice`) but
  has no UI, because the spec's UX list doesn't include it.
- **Shortcuts.** Shortcut messages are decoded and then ignored, as the spec allows.

## Persistence (spec §8)

- The app stores one JSON file, `filesDir/opendeck.json`, written atomically (temp file, then
  rename). It holds the keypair as hex, the hosts with their endpoints, `isAuthenticated` and
  devices (id, name, cols, rows, iconSize), plus the last host and device.
- If the file can't be read, it is renamed to `opendeck.json.corrupt` and the app starts
  fresh. The fresh start has a new keypair, so the phone must be paired again.
- Store writes happen on the main thread. The file is small, so this is acceptable.

## Post-implementation fixes from live testing

- `Session.createDevice` now returns the opened device (server replies `virtualDeviceOpened`)
  and moves the session to `DeviceOpen`; the app goes straight to the keypad instead of sending a
  second `openVirtualDevice` (which the server rejects with ResourceLimitReached).
- Placeholder `context` with `totalPages = 0xFFFFFFFF` is ignored for paging; `currentPage`
  confirmed 0-based.
- Fake server in `SessionTest` updated to mirror these behaviours; added `deviceNameClash` test.
- `LiveProbeTest` (skipped unless `VSD2_LIVE` is set) drives a real desktop:
  `VSD2_LIVE=ws://127.0.0.1:28198 VSD2_STEP=hello|pair|open [VSD2_ICON_DIR=dir] ./gradlew :core:cleanTest :core:test --tests '*LiveProbe*' -i`
- A newly paired phone always creates its own device ("OpenDeck (<phone name>)" when others
  exist) instead of offering the existing ones: the desktop gives a device to one connection
  only, so a second phone opening the first phone's device disconnects it ("Connection replaced").
