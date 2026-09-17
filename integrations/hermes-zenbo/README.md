# Hermes Zenbo plugin

One drop-in Python plugin adds six allowlisted Zenbo tools and speech routes to
Hermes' existing API process. It creates no server or database and changes no
model/provider settings. Native omits model overrides and uses the `grok`
profile's existing model, STT and TTS configuration.

The normative wire and tool schemas are in
[`contracts/hermes-zenbo`](../../contracts/hermes-zenbo/README.md).
The bundled `device-tools.json` is checked against that contract by tests.

## Enable and compatibility

Install the same `zenbo` package in the listener-owner and `grok` profile plugin
directories. Enable the plugin in both profiles and the `zenbo` toolset in
`grok`'s API tool policy, then restart the existing API-owning gateway. Do not
start a second listener. The plugin uses existing Hermes dependencies and never
installs packages or updates Hermes. Unloading the plugin revokes device
authority; applying a new plugin version requires a gateway restart.

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

## Routes and device authority

The profile API base is
`https://hermes.internal.c3land.org/hermes-api/p/grok/v1`.
The plugin root is
`https://hermes.internal.c3land.org/hermes-api/zenbo/grok/v1` (TLS port 443).
The proxy owns `/hermes-api`; the plugin mounts `/zenbo/{profile}/v1` in the
existing aiohttp app, reusing Hermes profile middleware and `_check_auth`.

All routes require the profile Bearer key and `X-Zenbo-Device-Id`.
Capabilities are available before device binding.

| Method | Suffix | Purpose |
| --- | --- | --- |
| GET | `/capabilities` | Six tools and configured speech metadata |
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
receipt; the handler waits for a valid terminal result within five seconds,
including activation and transport time.

## Speech and cleanup

STT accepts mono 16 kHz PCM16 WAV, up to 2 MiB and 30 seconds. Its original file
is deleted in `finally`. Speech functions run with `asyncio.to_thread`, preserving
Hermes profile context. `language` is request metadata; provider language/voice
policy stays in the profile.

TTS accepts at most 8,000 characters and preserves ordered multi-file output.
Only WAV/MP3 files generated inside the request's private temporary directory
are accepted; they are deleted before responding. Responses expose no paths,
provider exceptions or keys. UUID artifacts remain in memory, scoped to
profile/key/device, with SHA-256 and a 30-minute maximum TTL. Limits: 10 MiB per
artifact, 32 MiB per response, 64 MiB total. Restart discards artifacts/bindings.

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

Without `aiohttp`, HTTP/WS tests explicitly skip; complete acceptance requires
no skips. Tests exercise local aiohttp HTTP/WS with synthetic Hermes callbacks,
tool/schema/manifest consistency, profile/device isolation, cancellation,
deadline/no-replay behavior, multipart/audio validation, chunk ordering, artifact
scope/TTL/hash, and cleanup/error privacy. Deployment revision, actual speech
providers, TLS proxy, Android 6 and Zenbo hardware require separate checks.
