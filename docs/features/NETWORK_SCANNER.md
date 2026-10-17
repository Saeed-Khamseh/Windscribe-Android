# Network Scanner

Mobile entry point: Preferences → Connection → Network Scanner. The scanner uses the existing VPN controller and the signed-in account's available server inventory. It tests one eligible, fixed node per selected datacenter across selected protocol/port combinations. It does not scan LAN devices.

## Workflow

Choose countries/cities, protocol ports, and protocol order. Advanced settings default to target `1.1.1.1`, five ICMP probes, a 20-second connection timeout, and a two-second settle delay. Long-press and drag protocol rows to change priority; up/down buttons provide an accessible alternative. Defaults include WireGuard, TCP, Stealth, and WStunnel; UDP and IKEv2 can be enabled.

Starting requires VPN consent and enabled notifications. A dedicated `specialUse` foreground service holds a partial wake lock and an ongoing progress notification with a Stop action. The scanner continues when the app is minimized or removed from Recents. Normal mobile screens are replaced by the scanner while it owns the VPN; normal controller, shortcut, tile, auto-connect, and network-preference actions cannot take over. Android system VPN controls and force-stop remain available.

Each test disconnects, connects with an explicit connection UUID/location/node/protocol/port, waits for settling, and sends ICMP probes bound to the current VPN network. It verifies the same route and underlying network before and after each measurement. No TCP fallback is included in ICMP results. Split tunneling must be off. Failed connections and unanswered ICMP probes are recorded distinctly. A network change or permission revocation aborts the run rather than mixing results.

Working routes are ranked by packet loss, then average latency among received replies. Results can be shared as CSV or connected directly. Stop on first success leaves that route connected. Otherwise completion or Stop tears down the test connection and restores the prior location selection; it does not reconnect the prior tunnel. This behavior is explained before starting.

## Checkpoints

Settings and completed results are stored in a private Room database, `network-scanner.db`, independent of the existing account/server schema. Resume skips completed combinations only when the underlying network/account, test settings, and complete route plan match. Incomplete tests are not saved. Changes to the network, node inventory, target, sample count, timing, selection, or order require fresh results. Scan again switches resume off. Process death does not automatically restart scanning; saved completed results remain available for a user-initiated resume.

## Validation

The GitHub Actions debug-APK workflow runs `:base:testFdroidDebugUnitTest --tests 'com.windscribe.vpn.scanner.*'` and the scanner Compose tests in `:mobile:testFdroidDebugUnitTest`, then builds the ARM64 F-Droid debug APK. The tests exercise sequential tests, timeouts, cancellation during connect/probe, tunnel loss, network changes, resume filtering, priority, packet loss, CSV escaping, and connection ownership including late launches. Compose tests check valid/empty setup, the scan lock, and connecting the exact ranked route. Engine tests use a fake driver; they do not establish device VPN behavior.

Device acceptance checks, requiring a signed-in account and an Android device:

- Exercise each of the six protocols with an available location; confirm protocol, port, and node in VPN logs match the displayed route.
- Compare results against a blocked target and an unreachable port. Failed routes must not appear as working.
- Stop while connecting, settling, and measuring, from both the app and notification. Verify the tunnel stops, results remain available, and normal controls unlock only after cleanup.
- Minimize, remove from Recents, and reopen the app through the notification. Progress must continue and ordinary connection controls must remain unavailable.
- Rotate/recreate the activity, change Wi-Fi/cellular, revoke VPN permission, and force-stop. Verify checkpoint/resume behavior and absence of invented successes.
- Reject notification permission or disable the scanner channel. Starting must explain how to enable notifications.
- Check light/dark themes, large fonts, long country/city names, multi-country selection, drag/button protocol ordering, and CSV sharing.
