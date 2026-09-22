# Optional on-device LAN control

The Android App may explicitly enable a separate HTTP listener on port 8788 after
an admin PIN unlock. The renderer's `127.0.0.1:8787/api/v2` remains private and is
never forwarded. No remote Hermes server, cloud relay or provider is added. LAN
HTTP is unencrypted; enable it only on the intended trusted local network.

The listener accepts RFC1918 peers and an exact Host using an initial device LAN
address and port 8788. It has no CORS policy granting other sites access. Mutation
Origin must equal `http://<that literal private IPv4>:8788`; GET Origin may be
absent or that exact origin. Requests cannot change the motion permission,
settings, provider configuration, admin PIN or API key.

| Method/path | Request | Successful response |
| --- | --- | --- |
| GET `/` or `/remote-control.html` | None | Public pairing/control page |
| POST `/remote/pair` | `{code: "<8 digits>"}` | Envelope data `{csrfToken,status}` and remote session cookie |
| GET `/remote/status` | Remote session cookie | Envelope with device status |
| GET `/remote/frame` | Remote session cookie | JPEG bytes, no-store |
| POST `/remote/action` | `{action: follow|stop|forward|backward|left|right}` | Envelope with action receipt |
| POST `/remote/heartbeat` | `{}` | Controller lease renewed |
| POST `/remote/logout` | `{}` | Controller authority revoked; motion stopped |

All POST routes require JSON and exact Origin. After pairing, all POST routes also
require the HttpOnly `zenbo_remote_session` cookie and matching `X-CSRF-Token`.
The 64-hex-character cookie uses `SameSite=Strict; Path=/remote; Max-Age=600`; the
CSRF token is a separate 64-hex-character value. Both are temporary credentials,
never logged or sent to Hermes. LAN status strips pairing values and URLs, exposing
only `remote: {enabled,connected}` in that sub-object.

There is one controller. An eight-digit pairing code expires after 120 seconds
and is consumed on success. Failed pairing attempts use exponential throttling
from 1 to 30 seconds. The controller sends a heartbeat every 500 ms. A 100 ms
watchdog expires its lease after 1,750 ms without a heartbeat (nominal stop within
1,850 ms); status polling and camera frames do not renew motion authority.
Expiry/logout invalidates the session and stops app-owned movement. Actions must
never be automatically retried. Enabling LAN control opens a pairing QR containing
only a validated Native RFC1918 home-page URL and `#pair=<8 digits>`. The remote
page synchronously removes this fragment with `history.replaceState` before
sending exactly one POST `/remote/pair`; it never puts the code in a query,
request URL, storage, logs or external QR service. The admin PIN and Hermes
credentials are never QR data. Failed/expired/consumed codes require a fresh QR;
there is no automatic pairing retry or physical action. Manual code entry remains
an optional fallback.

The App's explicit “regenerate pairing QR” operation unlocks with the admin PIN
and reuses PUT `/api/v2/device/remote` with `enabled:true`. It stops app-owned motion
and revokes the previous controller before creating a fresh 120-second code.
Ordinary status polling never rotates codes or disconnects a paired controller.

Client action deadlines cover the complete Native SDK chain: stopping existing
attention may take 2,000 ms, avoidance setup 1,500 ms, then follow acquisition
8,000 ms (up to 5,000 ms initialization and 3,000 ms face acquisition) or a
bounded move 2,000 ms. Both the loopback renderer and LAN page allow
12,500 ms for `follow`, 6,500 ms for each move, and 3,000 ms for `stop`, including
1,000 ms beyond each Native maximum for scheduling and the HTTP response. Stop
can be sent immediately while another action is awaiting its result.

LAN heartbeat, status, and image requests retain their independent 1,800 ms
deadline. Heartbeats continue while actions await SDK confirmation; an action
deadline does not extend the controller lease. A LAN action timeout, transport
failure, or invalid response sends one best-effort stop, disconnects, clears
imagery and requires explicit reconnection. A loopback action with an
uncertain response also sends one stop, bounded to 3,000 ms, before surfacing the
original error. This also covers an uncertain stop delivery; the fallback never
recursively retries itself. A validated Native error envelope remains an authoritative
rejection and does not trigger this fallback. Neither uncertain nor interrupted
actions are replayed. The page withholds start
following while camera status is `releasing` or reports `CAMERA_RELEASE_FAILED`,
even after the desired `cameraEnabled` setting becomes false.

The same Native motion/camera switches, app foreground state, permission checks,
fixed low-speed movement bounds and SDK safety apply. A successful HTTP receipt
does not establish physical movement, obstacle avoidance, speaker-direction or
camera compatibility on a particular model/firmware; those require device tests.
