# Hermes Zenbo plugin

One drop-in Python plugin adds eight allowlisted Zenbo tools and speech routes to
Hermes' existing API process. It creates no server or database and changes no
model/provider settings. Native omits model overrides and uses the selected
profile's model, STT and TTS configuration.

The normative wire and tool schemas are in
[`contracts/hermes-zenbo`](../../contracts/hermes-zenbo/README.md).
The bundled `device-tools.json` is checked against that contract by tests.

## Enable and compatibility

1. Configure a Hermes profile with a working conversation provider, STT and TTS.
   Obtain its API access key and HTTPS profile URL.
2. Install this directory as the `zenbo` plugin package using Hermes' plugin
   installation layout. Enable it in the profile that owns the API listener and
   in the selected conversation profile; if these are the same profile, one
   installation is sufficient.
3. Enable the `zenbo` toolset in the selected profile's API tool policy, then
   restart the existing API-owning gateway. Keep a single API listener.
4. Configure the HTTPS entry point to forward WebSocket and SSE streams, then
   enter the profile URL and key in the Zenbo App and test the connection.

The plugin uses Hermes' existing dependencies and never installs packages or
updates Hermes. Unloading it revokes device authority; applying a new plugin
version requires a gateway restart.

Hermes' version string alone does not establish compatibility. At route mounting,
`compat.py` checks profile/auth/run helpers and verifies that the API run's
approval session key carries the real `run_id`. Unsupported implementations fail
closed with a visible Hermes log error. There is no `task_id` guess, default
profile fallback, or alternative authentication scheme. Review those checks when
upgrading Hermes. Reference: official
[plugin API](https://hermes-agent.nousresearch.com/docs/developer-guide/plugins),
[API server](https://github.com/NousResearch/hermes-agent/blob/main/gateway/platforms/api_server.py),
and [run execution](https://github.com/NousResearch/hermes-agent/blob/main/gateway/platforms/api_server_runs.py).
Public `main` is a reference, not proof of the installed revision.

The current package is 1.1.0 with the eight-tool `hermes-zenbo-2` manifest.
The matching App rejects a six-tool plugin during discovery; upgrade this plugin
and restart the existing Hermes gateway together with the App update. The device
channel wire version remains 1.0.

`capture_camera` receives one bounded JPEG from the authenticated Native device.
The plugin checks its base64, digest and JPEG dimensions/framing in memory, then
returns Hermes' native multimodal tool envelope when the installed implementation
and profile model support it. Unsupported configurations return
`image_not_delivered_to_model` with metadata, without image bytes or a new provider.
See the [camera contract](../../contracts/hermes-zenbo/README.md#camera-and-short-movement-extension)
for limits, compatibility checks and the distinction between local capture and
model delivery. `move_robot` is a fixed short Native movement; it never accepts
arbitrary velocity, distance or safety overrides.

## Source package

From a fresh repository checkout, build the drop-in ZIP with the tracked helper:

```sh
python scripts/package-hermes-zenbo.py --output .local/zenbo-plugin.zip
```

The archive contains the runtime Python modules (including `camera.py`), plugin
manifest, device contract, README and license under `zenbo/`. The helper checks
contract parity, all relative runtime imports including deferred route imports,
and ZIP CRCs before replacing the output. Tests, caches, credentials, APKs and
vendor JARs are excluded. An existing local read-only compatibility helper can
be included explicitly with `--preflight /path/to/preflight.py`; it is optional
and not required to build from a fresh checkout.

The package test extracts the archive in a temporary directory, imports its
runtime modules and registers all eight tools with synthetic Hermes callbacks.
This verifies package completeness; installed Hermes compatibility and device
behavior still require their separate checks below.

## Routes and device authority

For an example profile API base of
`https://hermes.example.com/hermes-api/p/robot/v1`, the plugin root is
`https://hermes.example.com/hermes-api/zenbo/robot/v1`.
Replace the host and sample profile `robot` with your installation's values.
`/hermes-api` is an optional reverse-proxy prefix, preserved by Native for all
routes; omit or replace it to match your proxy. The plugin itself mounts
`/zenbo/{profile}/v1` in the existing aiohttp app, reusing Hermes profile
middleware and `_check_auth`. HTTPS and WSS share the configured origin.

All routes require the profile Bearer key and `X-Zenbo-Device-Id`.
Capabilities are available before device binding.

| Method | Suffix | Purpose |
| --- | --- | --- |
| GET | `/capabilities` | Eight tools and configured speech metadata |
| WSS | `/device-channel` | Bind device/session, activate/revoke run, tool calls/results |
| POST | `/audio/transcriptions` | Multipart PCM16 WAV to text |
| POST | `/audio/speech` | Completed answer to ordered audio artifacts |
| GET | `/audio/{artifactId}` | Scoped bytes, MIME, size and digest |

The binding includes authenticated profile/key scope, device, session, run and
Native turn UUID. Activation verifies Hermes' owned run status and session.
Tool handlers derive execution identity from Hermes context; model arguments
cannot choose a device. Profile-specific plugin imports share one process-local
broker. Each device/session has one active run. Cancellation or disconnect
revokes authority and fails pending calls. The next run waits for authoritative
terminal status and pending-call cleanup. Tools never replay. `accepted` is a
receipt; the handler waits for a valid terminal result within the manifest's
total deadline, including activation and transport time: 7,500 ms for following,
6,500 ms for moving, and 5,000 ms for all other tools, including stopping.
Native activation wakes waiting tool calls immediately; a bounded interrupt
check remains while waiting, within the same original deadline.

If a run has already completed before its first activation, `run.active` only
acknowledges speech correlation; device tools remain disabled. Failed, cancelled,
stopping or previously bound runs cannot be activated again. Each binding retains
at most 4,096 run IDs; after that, start a new session instead of forgetting old
revocations.

## Speech and cleanup

STT accepts mono 16 kHz PCM16 WAV, up to 2 MiB and 30 seconds. Its original file
is deleted in `finally`. Speech functions run with `asyncio.to_thread`, preserving
Hermes profile context. `language` is request metadata; provider language/voice
policy stays in the profile.

TTS accepts at most 8,000 characters and preserves ordered multi-file output.
Only WAV/MP3 files generated inside a private temporary directory under a
Hermes-policy-approved output root
are accepted; they are deleted before responding. Responses expose no paths,
provider exceptions or keys. UUID artifacts remain in memory, scoped to
profile/key/device, with SHA-256 and a 30-minute maximum TTL. Limits: 10 MiB per
artifact, 32 MiB per response, 64 MiB total. Restart discards artifacts/bindings.
Audio validation and SHA-256 run once in the existing TTS worker; publication
and downloads reuse that immutable digest. The HTTP loop owns artifact storage
and rechecks run authority before publication. Downloads inspect only their
requested artifact; publication and the periodic sweep reclaim other expired
artifacts.

At most two speech workers run concurrently. HTTP waits are bounded to 90 seconds
for STT and 120 seconds for TTS. Python cannot kill an in-flight provider thread:
on timeout/disconnect its slot remains occupied until provider completion and
temporary-file cleanup; late results are discarded. Hermes provider timeouts
still apply. Configured speech metadata is not a provider or hardware test result.

## Tests

Using Python with Hermes' existing `aiohttp` dependency, from the repository root:

```sh
python -m unittest discover -s integrations/hermes-zenbo/tests -v
```

Without `aiohttp`, HTTP/WS and extracted-package import tests explicitly skip;
complete acceptance requires no skips. Tests exercise local aiohttp HTTP/WS with
synthetic Hermes callbacks, tool/schema/manifest consistency, profile/device isolation, cancellation,
deadline/no-replay behavior, multipart/audio validation, chunk ordering, artifact
scope/TTL/hash, and cleanup/error privacy. Deployment revision, actual speech
providers, TLS proxy, Android 6 and Zenbo hardware require separate checks.

For a local, synthetic hot-path comparison against a chosen Git revision:

```sh
python integrations/hermes-zenbo/tests/perf_probe.py --baseline-ref HEAD
```

Run before committing to compare the working tree with `HEAD`, or supply a
previous revision. It measures 32 MiB artifact publication, repeated 8 MiB
artifact reads and activation-to-tool dispatch; it never invokes speech
providers or connects to Hermes. Timing varies by host and does not establish
end-to-end voice or device latency.
