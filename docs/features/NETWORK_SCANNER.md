# Network Scanner

Finds server / protocol / port / tweak combinations that actually work on a censored network
(Iran, China), ranks them, lets the user connect to one, and remembers them per network.

Fork-only feature (not upstream). Mobile app only; the engine lives in `base` so TV can reuse it.

## Problem

On filtered networks most connection attempts either never complete the handshake, or complete it
and then pass no traffic. Which combinations survive depends on ISP, server IP, protocol, port and
obfuscation settings. Trying them by hand is slow.

## Approach: probe, then verify

1. **Probe** (VPN off, parallel, cheap): for every candidate, test whether the endpoint answers.
2. **Verify** (sequential, real tunnel): for the best candidates, connect for real and measure
   traffic through the tunnel. Android allows one VPN at a time, so this stage is capped.

A candidate is `city -> node -> protocol -> port -> tweak`.

## Decisions

| Topic | Decision |
|---|---|
| Scan space | Cities, individual nodes, all 6 protocols, their ports, anti-censorship tweaks |
| Scope | **Quick scan** preset (one tap) + **Custom** picker |
| Run mode | Foreground service, progress notification with Stop |
| Results | Ranked list, tap to connect; saved per network |
| Saved routes | On later connects on that network: tried first, then normal failover |
| Entry points | Anti-Censorship settings row; "Scan for working servers" in the all-protocols-failed dialog |
| Assumption | User is logged in and the server list / port map are cached (API reachable) |

## Hard constraints

- Kotlin, coroutines/Flow, Hilt. No RxJava, no Retrofit/OkHttp. `./gradlew ktlintFormat` clean.
- **Do not touch the main Room schema** (`WindscribeDatabase`, version 43). Upstream owns its
  migration numbers; a fork migration would collide. Scanner data lives in its own Room database
  `scanner.db` (own `@Database`, version 1).
- Keep edits to existing upstream files minimal and localized (the fork is rebased on upstream
  regularly). Prefer new files in new packages plus small hook points.
- A scan must leave the app as it found it: any preference changed for a verification is restored
  in `finally`, and originals are persisted first so an interrupted scan is repaired on next start.
- English strings only. No TV UI.

## Architecture

Package `com.windscribe.vpn.scanner` (module `base`).

### Model

- `ScanCandidate(cityId, cityName, nodeHostname, nodeIp, protocol, port, tweak)`
  - `protocol` uses the existing `PreferencesKeyConstants.PROTO_*` values.
  - `nodeIp` is the address that protocol really dials on that node (nodes expose several IPs;
    follow what `VPNProfileCreator` uses per protocol).
- `ScanTweak` (sealed): the existing Anti-Censorship options, modelled from how
  `VPNProfileCreator` / the backends consume them: AmneziaWG preset (WireGuard), extra TLS padding
  and custom TLS server name (Stealth / WStunnel), alternate server routing. `None` is always the
  first tweak tried.
- `ProbeResult(reachable: Boolean?, latencyMs, detail)`; `reachable == null` means the protocol has
  no honest unauthenticated probe.
- `VerifyResult(connected, connectMs, trafficOk, latencyMs, downloadKbps, error)`
- `ScanResult(candidate, probe, verify, score, timestampMs, networkName)`
- `ScanRequest`: `Quick` or `Custom(cityIds, protocols, ports, tweaks, nodesPerCity)`, plus
  `ScanLimits(maxProbes, maxVerifications, timeBudgetMs)`.
- `ScanState`: `Idle`, `Probing(done, total)`, `Verifying(index, total, current)`,
  `Finished(results)`, `Cancelled(results)`, `Failed(reason)`.

### Components

| Class | Role |
|---|---|
| `ScanCandidateGenerator` | Pure function of (cities+nodes, port map, tweaks, request) -> ordered candidates. Unit-tested. |
| `EndpointProber` | Interface. `TcpTlsProber` (OpenVPN TCP: TCP connect; Stealth/WStunnel: TCP connect + TLS handshake with the configured SNI), `IkeProber` (IKE_SA_INIT to UDP 500/4500, any reply counts). WireGuard and OpenVPN UDP return `reachable = null`. |
| `ProbeRunner` | Runs probes with bounded concurrency (16) and a per-probe timeout (4 s). |
| `ScanRanker` | Chooses which candidates go to verification and scores verified results. Unit-tested. |
| `RouteVerifier` | Connects through `WindVpnController.connect(source, protocolInformation = ..., hostname = ...)`, waits for connected / error / timeout (20 s), measures traffic through the tunnel, disconnects. |
| `ScanResultStore` | Own Room DB `scanner.db`; results keyed by network name (same key the app uses for `NetworkInfo`); keeps the newest 50 per network. |
| `NetworkScanner` | `@Singleton` orchestrator. `state: StateFlow<ScanState>`, `start(request)`, `stop()`. Disconnects the VPN first, restores everything at the end. |
| `ScanSession` | Process-wide "scan in progress" flag read by the hook points below. |
| `ScanService` | Foreground service (phase 2) that owns the running scan and its notification. |

### Ordering rules

- **Quick scan**: cities = favourites, then lowest stored ping; 8 cities x 2 nodes. Protocol order
  by censorship resistance: WStunnel, Stealth, OpenVPN TCP, WireGuard (plain, then AmneziaWG
  presets), IKEv2, OpenVPN UDP. Port 443 first where offered, then the port map order.
  Limits: 600 probes, 20 verifications, 6 minutes.
- **Probe -> verify selection**: `reachable == true` first, sorted by probe latency;
  `reachable == null` candidates next, but only on node IPs where some other probe succeeded, then
  the rest; `reachable == false` never verified. Spread across nodes and protocols (round-robin)
  so one good node does not consume the whole verification budget.
- **Score**: verified + traffic OK beats everything; then lower latency and higher throughput.
  Connected-but-no-traffic is recorded as failed with reason, never listed as working.

### Traffic test (inside the tunnel)

After the app reports connected: 3 TCP connect timings to a public anycast host and one small
(~256 KB) HTTPS download with a 10 s cap, using plain `java.net` (this is a measurement, not an
API call). `trafficOk` requires the download to complete.

### Hook points in existing code (keep small)

- `AutoConnectionManager` / `WindVpnController`: while `ScanSession.active`, a failed attempt must
  not start failover, show dialogs, or schedule reconnects; scanner-initiated disconnects must not
  set the auto-secure whitelist. Use the existing `ConnectSource` mechanism (add a scanner source).
- Phase 3: when building the protocol list for a connect on a network with saved routes, move the
  best saved protocol/port to the front and apply its tweak; if the selected city has a saved node,
  pin it via the existing `hostname` parameter. Normal failover continues after it.

## Phases

1. **Engine** (`base`): model, generator, probers, runner, ranker, verifier, store, orchestrator,
   hook points, Hilt wiring, unit tests (generator, ranker, probers against local sockets).
2. **Service + UI** (`base` service, `mobile` Compose): `ScanService`, scanner screen (Quick scan,
   Custom picker, live progress, ranked results, tap to connect), the two entry points.
3. **Saved routes**: try-first logic on connect, with tests.
4. **Field test**: tune timeouts, ordering, scoring from in-country logs (log tag `scanner`).

## Status

Phases 1-3 are implemented. They compile for `mobile` and `tv` (fdroid flavour) and the `base`
unit tests pass. Nothing has been run on a device or emulator yet; phase 4 is the first real run.

Behaviour worth knowing when testing:

- A scan disconnects the VPN, and the kill switch does not block traffic while a scan runs
  (probes need the plain network). Blocking returns through the normal policy when the scan ends.
- A scan needs split tunneling to leave Windscribe inside the tunnel; otherwise every route is
  reported as "connected but could not measure".
- Tapping a result connects with that city, node, protocol and port first and keeps its tweak.
- "Try saved routes first" (scanner screen, default on): later connects on the same network try
  the best saved route first. Skipped for custom configs, static IPs, manual protocol mode,
  per-network preferred protocol and decoy traffic. A route that fails twice in a row as the first
  attempt is ignored until a new scan verifies it. Results older than 14 days are ignored.
- Tweaks applied automatically by a saved route are undone on the next connect that uses no saved
  route, unless the user changed the Anti-Censorship settings in between.

## Field test checklist (phase 4)

1. Install the APK from the `Build Android debug APK` workflow run (artifact
   `windscribe-fdroid-debug-apk`, ARM64).
2. Log in, let the server list load, then open Preferences > Connection > Anti-Censorship >
   Find working servers and run Quick scan.
3. Note: how long it took, how many routes were probed / tested / working, and whether a tapped
   route really connects and carries traffic.
4. Send the debug log lines from the `scanner` logger (filter on `scanner`), not the full log.

## Local verification

```bash
export ANDROID_HOME=/opt/android-sdk
./gradlew :base:compileFdroidDebugKotlin -q
./gradlew :base:testFdroidDebugUnitTest --tests 'com.windscribe.vpn.scanner.*' -q
./gradlew :mobile:compileFdroidDebugKotlin -q
./gradlew ktlintFormat -q
```

The APK is built by the `Build Android debug APK` GitHub Actions workflow (manual dispatch).

## Known limits

- Verification costs 10-20 s per candidate; a scan can only verify a few dozen routes.
- WireGuard verification needs the API (key exchange per connect).
- Probe heuristics are unproven until tested in-country.
