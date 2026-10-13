# OpenDeck Mobile — Clean-Room Functional Specification

This document is the **only** input for the implementation team. It describes observable
behaviour and wire formats of the "VSD2" virtual-Stream-Deck protocol spoken between a
mobile keypad client and the Stream Deck desktop application, as needed for interoperability.
It contains no code from the original application. Implementers must not consult the original
APK, its decompilation, or its native libraries.

Status markers: **[O]** observed in the shipped artifact, **[I]** inferred, **[D]** design
choice for our clone (free to change).

---

## 1. Roles

- **Server**: the desktop application ("workstation"). Each has a stable string *workstation ID*.
- **Client**: our Android app. It presents a grid of keys, shows icons the server pushes, and
  reports key presses.

## 2. Transport [O]

- Plain WebSocket (`ws://host:port/`, path `/`). No TLS.
- Each protocol message is exactly **one binary WebSocket frame** containing one serialized
  protobuf message (see §4). No length prefix.
- The client must answer WebSocket Ping frames with Pong (standard WS behaviour).
- Receiving a *text* frame is a protocol error → close with code 1008.
- Receive timeout: if no frame of any kind (including Ping) arrives within **10 s**, the client
  closes the connection ("receive timeout"). The server normally sends WS pings.
- Client close codes: 1008 for protocol violations / invalid data, 1001 otherwise.
- Suggested timeouts: connect 6 s, discovery 20 s, up to 5 connect attempts across a host's
  known endpoints. RPC replies (open/create/delete device) wait up to 30 s.

## 3. Encryption [O]

Uses NaCl/libsodium `crypto_box` primitives (Curve25519 + XSalsa20-Poly1305).

1. The client owns a persistent Curve25519 keypair (32-byte public, 32-byte secret), generated
   once on first launch and stored on-device.
2. The **first** client message is a plaintext `ClientMessage{hello}`; its
   `HelloFromClient.publicKey` field carries the client's public key.
3. The **first** server message is a plaintext `ServerMessage{hello}`; its
   `HelloFromServer.publicKey` carries the server public key (32 bytes).
   Anything other than a hello as the first server message is a protocol violation.
4. Both sides compute the precomputed shared key `k = crypto_box_beforenm(serverPk, clientSk)`
   (i.e. HSalsa20 over the X25519 shared secret with a zero 16-byte input).
5. Every later frame in both directions is encrypted:
   ```
   frame = nonce(24 random bytes) || crypto_box_easy_afternm(plaintext, nonce, k)
   ```
   `crypto_box_easy` output = 16-byte Poly1305 MAC followed by ciphertext, so
   `len(frame) = len(plaintext) + 40`. Frames shorter than 40 bytes are invalid.
6. Sending a second hello, or a non-hello before the key exists, is a client-side bug.
7. Lenient receive [O]: if decryption fails, the original client attempts to parse the frame as
   plaintext. **[D]** Our clone treats decryption failure as a protocol error.

**Fingerprint** [O]: `hex(BLAKE2b-256(publicKey))` (unkeyed, 32-byte digest, lowercase hex
from libsodium). The server displays the first 6 hex digits of the *client's* fingerprint when
asking the user to trust it; the client shows the same 6 characters **uppercased** so the user
can compare.

## 4. Messages (protobuf, proto3, package `vsd2`)

Field numbers are normative. `opt` = proto3 `optional` (presence tracked).

### 4.1 Common types

| Message | Fields |
|---|---|
| Size | 1 width uint32, 2 height uint32 |
| UIntRange | 1 min uint32, 2 max uint32 |
| Image | 1 format ImageFormat, 2 type ImageType, 3 size Size, 4 rowBytes uint32, 5 image bytes |
| VirtualDevice | 1 id string, 2 name string |
| ServiceEndpoints | 1 addresses repeated string, 2 port uint32 |

Enums:
- `ImageFormat`: LZF=0, LZFSE=1, LZ4=2, BMP=3, JPG=4, PNG=5, Invalid=100
- `ImageType`: Opaque=0, Transparent=1
- `Error.Code`: DeviceNotFound=0, DeviceNameAlreadyTaken=1, ResourceLimitReached=2,
  AuthenticationRejected=3, Cancelled=4, InvalidState=5

### 4.2 Client → server: `ClientMessage` (oneof `payload`)

| # | Field | Message type and its fields |
|---|---|---|
| 1 | hello | HelloFromClient: 1 publicKey bytes, 2 clientInformation ClientInformation |
| 2 | authenticate | Authenticate: 1 requestID uint32 |
| 3 | cancelAuthenticate | CancelAuthenticate: 1 requestID uint32 |
| 10 | createVirtualDevice | CreateVirtualDevice: 1 requestID uint32, 2 name string, 3 keypadConfig KeypadConfig, 4 skipUniquenessOfNameValidation bool |
| 11 | openVirtualDevice | OpenVirtualDevice: 1 requestID uint32, 2 deviceID string, 3 keypadConfig KeypadConfig |
| 12 | deleteVirtualDevice | DeleteVirtualDevice: (empty) |
| 20 | keyPress | KeyPress: 1 index uint32, 2 pressed bool |
| 21 | nextPage | NextPage: (empty) |
| 22 | previousPage | PreviousPage: (empty) |
| 23 | pageByIndex | PageByIndex: 1 index uint32 |
| 30 | updateKeypadConfig | UpdateKeypadConfig: 1 iconSize opt Size, 2 pressedIconSize opt Size, 3 highDPI opt bool, 4 layoutCrop opt Size |
| 31 | assignShortcut | AssignShortcut: 1 profileID string, 2 shortcutID string, 3 index uint32 |
| 32 | removeShortcut | RemoveShortcut: 1 requestID uint32, 2 shortcutID string |
| 33 | triggerShortcut | TriggerShortcut: 1 deviceID string, 2 shortcutID string |
| 100 | clientCapabilities | ClientCapabilities: 1 isUserPro bool |

`ClientInformation`: 1 name string, 2 userAgent string, 3 protocolVersion uint32,
4 deviceModel opt string, 5 osName opt string, 6 osVersion opt string.

`KeypadConfig`: 1 supportedIconImageFormats repeated ImageFormat, 2 iconSize Size,
3 pressedIconSize opt Size, 4 highDPI bool, 5 overscale bool, 6 layoutCrop opt Size,
7 enableContextAPI bool.

### 4.3 Server → client: `ServerMessage` (oneof `payload`)

| # | Field | Message type and its fields |
|---|---|---|
| 1 | error | Error: 1 requestID uint32, 2 code Error.Code, 3 reason opt string |
| 2 | ok | Ok: 1 requestID uint32 |
| 10 | hello | HelloFromServer: 1 workstationID string, 2 hostname string, 3 version string, 4 protocolVersion uint32, 5 publicKey bytes, 6 clientNeedsAuthentication bool, 7 osName opt string, 8 osVersion opt string, 9 endpoints opt ServiceEndpoints |
| 20 | virtualDeviceList | VirtualDeviceList: 1 devices repeated VirtualDevice |
| 21 | virtualDeviceOpened | VirtualDeviceOpened: 1 requestID uint32, 2 device VirtualDevice |
| 30 | context | Context: 1 profileID string, 2 totalPages uint32, 3 currentPage uint32, 4 actions repeated Action |
| 31 | contextUpdate | ContextUpdate: 1 profileID string, 2 replace repeated Action, 3 remove repeated uint32 |
| 32 | shortcuts | Shortcuts: 1 shortcuts repeated Shortcut |
| 33 | shortcutsUpdate | ShortcutsUpdate: 1 replace repeated Shortcut, 2 remove repeated string |
| 40 | icon | Icon: 1 index uint32, 2 image Image |

`Action`: 1 index uint32, 2 title string, 3 name string, 4 category string, 5 shortcutID opt string.
`Shortcut`: 1 id string, 2 title string, 3 name string, 4 category string, 5 icon opt Image.

Shortcut messages are not needed for the keypad and may be ignored.

## 5. Session flow

### 5.1 Handshake
1. Connect WebSocket.
2. Send `hello` with ClientInformation:
   - name: user-visible device name (Android `Settings.Global.DEVICE_NAME`, fallback `Build.MODEL`)
   - userAgent: **[D]** `"OpenDeckMobile/<version>"` (original used its own product string)
   - protocolVersion: **2** [O]
   - deviceModel `Build.MODEL`, osName `"Android"`, osVersion `Build.VERSION.RELEASE`
3. Receive server `hello`. If we connected expecting a specific workstation ID and the received
   `workstationID` differs → fail ("unexpected workstation"). Record hostname, version,
   protocolVersion, and `endpoints` (each address + port is remembered as an extra endpoint
   for that workstation).
4. If `clientNeedsAuthentication` is true → state **Unauthenticated**; else **Ready**.

### 5.2 Pairing (authentication)
- In Unauthenticated, the user taps "Pair". Client sends `authenticate{requestID}` and shows
  "Confirm code XXXXXX on your computer" (6 uppercase hex chars of own fingerprint).
- Server replies `ok{requestID}` (user accepted) or `error{requestID, code}`:
  AuthenticationRejected, Cancelled, ResourceLimitReached (another pairing in progress).
- User can cancel → `cancelAuthenticate{requestID}`.
- [I] After `ok` the server pushes `virtualDeviceList`; treat the host as authenticated
  (persist `isAuthenticated=true`) and move to Ready. Wait up to 20 s.

### 5.3 Request IDs
Per-connection counter starting at 0 and incrementing per request. Replies (`ok`, `error`,
`virtualDeviceOpened`) are matched by requestID.

### 5.4 Virtual devices
- The server sends `virtualDeviceList` (full snapshot of devices belonging to this client on
  this workstation). Merge into local storage: add new IDs with default layout, update names,
  drop devices no longer listed.
- If the list is empty after pairing, create one: `createVirtualDevice{name:"Stream Deck Mobile"…}`
  — **[D]** our clone uses the name "OpenDeck" and `skipUniquenessOfNameValidation=true`.
  User-created extra devices use `skip=false`; on `DeviceNameAlreadyTaken` ask for another name.
- To use a device: `openVirtualDevice{requestID, deviceID, keypadConfig}` → reply
  `virtualDeviceOpened{requestID, device}` → state **VirtualDeviceOpen**. Then the server
  streams `context` and `icon` messages.
- `deleteVirtualDevice{}` deletes the currently open device; the server then closes the socket.

### 5.5 KeypadConfig contents (what the original sends)
- supportedIconImageFormats: original `[LZFSE, LZF, LZ4]`. **[D]** our clone sends `[LZ4, LZF]`
  (LZFSE not implemented). The server picks a format from this list.
- iconSize: chosen icon pixel size, default 144×144 [O]
- pressedIconSize: unset. **[D]** our clone sends pressedIconSize = iconSize. Observed live: with it
  unset, icons sent while a key is held are darkened and corner-masked; with it set they are only
  corner-masked (each corner ~15% of the side made transparent with a dark antialiased rim).
- highDPI: true; overscale: false; enableContextAPI: true
- layoutCrop: `Size(width = columns, height = rows)` — this is how the grid dimensions are
  communicated.

When the user changes layout or icon size while a device is open, send `updateKeypadConfig`
with iconSize, highDPI=true and layoutCrop=(cols, rows).

### 5.6 Capabilities [O]
Only when the device is open **and** server protocolVersion ≥ 3, send
`clientCapabilities{isUserPro}`, which says whether the user has the paid subscription.
**[D]** Our clone has no subscription and always sends `isUserPro=false`.

### 5.7 Keys and pages
- Key index = `row * columns + column` (row-major, 0-based), relative to the logical grid.
  Visual rotation is client-only; indices don't change.
- Touch down → `keyPress{index, pressed:true}`; touch up → `pressed:false`.
  **[D]** On gesture cancel also send `pressed:false` so keys never stick.
- Page navigation: `nextPage{}` / `previousPage{}`. Current/total pages come from `context`.
  `pageByIndex` is optional.
- `context` replaces the per-key action metadata (title/name/category) for the profile;
  `contextUpdate` replaces listed actions and removes listed key indices.

## 6. Icons

### 6.1 Decoding [O]
- `icon.image.format` is LZ4 (raw LZ4 *block* format, no frame header, decompressed size not
  stored — decompress into a buffer of `width*height*4`), or LZF (classic liblzf
  block format). Other formats: ignore/log.
- `rowBytes` and `type` are not needed by the original decoder; pixels are tightly packed,
  4 bytes per pixel, `size.width × size.height`.
- Decompressed pixel byte order is **B, G, R, A**. Post-processing per pixel:
  - if B == G == R == 0 → set A = 0 (pure black becomes fully transparent);
  - otherwise swap bytes 0 and 2 → R, G, B, A.
  The result is RGBA bytes (Android `ARGB_8888` bitmap memory layout, use
  `copyPixelsFromBuffer`).
- Buffers: icons are at most ~500 kB decompressed.

### 6.2 Rendering [D]
- Draw icons scaled into each rounded-square key cell on a dark background (so transparent
  black looks black). Keys without an icon render empty.
- **[D]** The server sends an icon for every key, including keys with no action (normally a solid
  black image, which §6.1 turns fully transparent). Our clone treats a uniform image that is
  transparent or near black as "no icon": the cell, its dark background and border are not drawn,
  so the app's background colour shows through.
- Visual press feedback: slight shrink/dim while pressed.

### 6.3 Free-tier layout limit [O]
The original client locks every key outside the top-left 3 columns × 2 rows unless the user
has the paid subscription. **[D]** Our clone does not offer the subscription and reports
`isUserPro=false` (see 5.6).

### 6.4 Layouts [O]
Presets: 5×3 (default), 3×2, 8×4 (columns×rows). Custom: 1–8 columns and 1–8 rows.

## 7. Discovery

### 7.1 mDNS [O]
- Service type `_vstreamdeck2._tcp`. TXT key **`VSDSV`** = workstation ID (UTF-8). Ignore
  services without it.
- Service instance name = display name. Use the resolved host address(es) and SRV port.

### 7.2 Manual / QR entry [O]
- QR payload is a URL whose host is `streamdeck.elgato.com`, with query parameters:
  - `w` = workstation ID (required)
  - `av2` = `host:port` (repeatable); default port **28198**
  - legacy `a` = `host:port` (repeatable) used only if `av2` is absent; default port 28197
- **[D]** Our clone also offers manual entry of `host[:port]` (default 28198). With manual
  entry the workstation ID is learned from the server hello.

### 7.3 Vendor cloud discovery [O]
The original also polls a vendor HTTPS endpoint. **[D]** Not implemented in our clone
(LAN-only, no third-party network calls).

## 8. Local persistence [D]
Store: own keypair; known hosts (workstation ID, display name, endpoints, isAuthenticated,
devices); per device: id, name, rows, cols, iconSize; last opened device ID. Any format
(JSON in app-private storage is fine).

## 9. Clone UX [D]
1. **Hosts screen**: discovered (mDNS) + saved hosts; "Add host" (manual / QR text).
2. **Connect** → if unauthenticated show pairing card with the 6-char code and Pair/Cancel.
3. **Devices**: list of virtual devices; open one; "new device".
4. **Keypad**: full-screen grid; page prev/next buttons and "page x/y"; layout menu
   (presets + custom); disconnect.
5. Keep screen on while keypad visible. Reconnect automatically to the last device on resume.

## 10. Test vectors to use
- crypto_box: the standard NaCl test vector (Bernstein, "Cryptography in NaCl", alice/bob
  keys, 24-byte nonce, 131-byte message) for beforenm + XSalsa20-Poly1305.
- BLAKE2b-256 of the empty string = `0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8`.
- LZ4 / LZF round-trips with independently written compressors (test-only) or known samples.

---

## 11. Black-box verification against a live desktop server (2026-10-12)

Observed by running the clean-room `core` against a real Stream Deck desktop on
`ws://127.0.0.1:28198` (see `LiveProbeTest`). These are runtime observations and override the
inferred items above.

- Handshake, encryption, fingerprint (desktop prompt code matched the client's 6-char code),
  and `authenticate → ok` all work as specified. After `ok` the server immediately sends
  `virtualDeviceList` — confirms §5.2.
- The device list contains **all** virtual devices on the workstation (including ones created by
  other clients), not just this client's.
- **`createVirtualDevice` is answered with `virtualDeviceOpened`** (then an updated
  `virtualDeviceList`): creating a device also opens it on this connection.
- Only one device per connection: a second create/open is answered with
  `error{code: ResourceLimitReached, reason: "Client already requested a virtual device"}`.
- Opening a device that another client currently holds takes it over; the other client is closed
  with code 1000, reason "Connection replaced".
- The first `context` after creating a device can be a placeholder: nil profile UUID and
  `totalPages`/`currentPage` = 0xFFFFFFFF. Ignore page info unless `totalPages > 0`.
- `context.currentPage` is **0-based**.
- Icons arrive as LZ4 (format 2), 144×144 by default; the §6.1 BGRA→RGBA rule produces correct
  colours. Icons may arrive before `virtualDeviceOpened`.
