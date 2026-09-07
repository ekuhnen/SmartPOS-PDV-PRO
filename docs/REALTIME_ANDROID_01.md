# REALTIME-ANDROID-01 — Restaurant realtime invalidation

## Contract verification

`contract/REALTIME_OPS_01B.md` contains the complete `ANDROID_FUTURE_CONTRACT`.
The supplied provenance is backend commit `b0fcc68c41b71203381766ce8642472d3f0f4880`,
`docs/REALTIME_OPS_01B.md`. The supplied file was retained unchanged; its SHA-256 is
`D44F299B4465DB07CD91A03129A3ADC77114EF0E9776E5DC37788B29BEFE25DF`.
Accented prose has encoding damage; technical identifiers and the Android block are readable.

- Existing Supabase Realtime SDK `2.4.3`, shared `SupabaseClient`; no custom socket transport.
- Publication `supabase_realtime`, `postgres_changes`, `INSERT`, schema `public`,
  table `restaurant_realtime_events`, filter `owner_user_id=eq.<server-resolved ownerId>`.
- The client channel label is `restaurant-invalidation-<ownerId>`; this is a local
  postgres_changes subscription label, not a backend Broadcast topic or authorization rule.
- Existing terminal JWT from `POS_PREFS/TOKEN`; no fabricated GoTrue session.
  RLS tenant isolation is defined by the supplied backend contract. The client also
  filters and checks `owner_user_id`. No live RLS/security test was performed by Codex.
- Types: `TABLE_CHANGED`, `COMANDA_CHANGED`, `COMANDA_ITEMS_CHANGED`, `PAYMENT_CHANGED`.
- Decoded fields: `id`, `server_seq`, `owner_user_id`, `event_type`, `mesa_id`,
  `comanda_id`, `comanda_versao`, `occurred_at`. Database-internal `tx_id` is not used.
- `mesa_id` and `comanda_id` identify affected resources and may be null. No single
  comanda per Mesa assumption is made. `comanda_versao` is the existing canonical
  revision; `server_seq` is event write ordering, not a global business revision.
  Neither suppresses reads: duplicates and out-of-order events refetch current state.

The stage instruction explicitly preserves the existing Android `GET /api-mesas`
authority, rather than adopting the web `api-restaurant-ops` projection mentioned
in the handoff.

## Audit before edits

| Requested field | Existing Android path |
| --- | --- |
| MESA_SCREEN | `MesaFragment` in `DirectSaleActivity` ViewPager |
| MESA_VIEWMODEL | `MesaViewModel` |
| MESA_REPOSITORY | `TableReadRepository` |
| MESA_NETWORK_CALL | `PosApiService.getMesas()` / `GET /api-mesas` |
| MESA_ROOM_CACHE | `TableDao` / `TableEntity`, atomic `replaceAll` |
| MESA_REFRESH_ENTRYPOINT | `MesaViewModel.fetchTables()` → `refreshTables()` |
| CURRENT_FOREGROUND_REFRESH | Mesa `onResume()`; `TableOrderActivity.onResume()` reads detail |
| CURRENT_PERIODIC_REFRESH | None found for Mesas |
| SUPABASE_ANDROID_DEPENDENCY | Existing `realtime-kt:2.4.3` |
| EXISTING_REALTIME_CLIENT | `SupabaseModule`, used by `DeviceGuardService` |
| AUTH_SESSION_SOURCE | Login JWT in `POS_PREFS/TOKEN`; user in `USER_ID` |
| TENANT_ID_SOURCE | Login response `ownerId` → `TenantBindingStore` |
| NETWORK_CONNECTIVITY_MONITOR | No reactive monitor found; `NetworkUtils` provides retry |
| APP_LIFECYCLE_MONITOR | Existing screen callbacks, no global monitor found |

Mesa list: canonical API → repository → Room Flow → ViewModel LiveData → existing UI.
Mesa detail: canonical `GET api-comandas?id=...` → `ComandaSnapshotRepository` →
existing snapshot hydration and canonical allocation GET → existing ViewModel/UI.
Standalone comanda detail already uses canonical GET → `CommandViewModel` LiveData;
this stage preserves that existing path without inventing a second cache.

## Implementation and boundaries

`RestaurantFreshness` shares one coordinator across lifecycle-bound readers.
`repeatOnLifecycle(RESUMED)` activates Mesa list, Mesa detail and standalone
comanda detail. Offscreen ViewPager tabs, stopped activities and background app
contexts have no active reader. The last reader leaving cancels the restaurant
subscription and jobs. It does not clear Room or disconnect other feature channels.

Session preferences and server tenant binding are observed. Logout, tenant/user
change, token rotation, network loss and screen departure cancel the old context
before another subscription starts. Existing tenant-switch blocking remains intact.
Old readers cannot join a new tenant/user context. No login persistence behavior changes.

Every accepted event conservatively invalidates the Mesa aggregate and all visible
restaurant details, using existing read methods. This intentionally also refreshes
Mesa summaries for items and payment events. Events cannot call mutation APIs,
the transfer outbox, checkout, receipt printing or payment decisions.
The preexisting manual/entry `fetchTables()` path remains; automatic invalidation
calls only `TableReadRepository`, avoiding that method's transfer-queue side effect.

A bounded 250 ms coalescing window merges bursts. One serialized read cycle runs
at a time. Events arriving during HTTP leave one trailing refresh. An active
context also refreshes every 60,000 ms. Initial/foreground/network-return/session
activation and every subscription join/rejoin request a canonical refresh, without
replay. Subscription failures retry with 1–30 s backoff independently of safety reads.
Join/rejoin/connection waits are bounded at 20 s; teardown is bounded at 2 s and
removes the SDK's rejoin registration even offline.

The shared SDK's Realtime JWT configuration supplies the existing terminal JWT
on joins and automatic rejoins. Only the restaurant channel is removed during its
teardown. SDK payload debug logging is disabled, since SDK join logs can expose JWTs.
No event payloads, access tokens or financial data are added to application logs.

Repository and detail read mutexes serialize canonical reads. Session and coroutine
cancellation checks reject responses completed after session changes. No status,
items, totals, payment results, occupancy calculations or fiscal rules are derived
from events. Existing cache acceptance and financial projection rules are unchanged.

## Technical validation

Command: `android/gradlew.bat :app:testDebugUnitTest :app:assembleDebug --console=plain`
from `android/`. Only the ordinary Debug build output is generated; no RC, release
build or protected root APK is touched.

- 420/420 JVM tests pass, no errors, failures or skips.
- 26 new tests: 18 coordinator tests, 3 contract-envelope tests, 5 Room/session tests.
- Existing 394 tests remain green, including reconciliation, payment, Mesa and comanda tests.
- Burst: 20 invalidations produce one additional Mesa read. Fifteen events during
  a blocked read produce one trailing read. Multiple readers share one aggregate read.
- Fake transport lifecycle tests measure maximum concurrent restaurant subscriptions = 1.
- Room tests preserve the cache on rejected responses, reject logout/tenant changes
  during HTTP, reject old tokens and verify occupied → occupied → free from canonical reads.
- Localization audit consistency, placeholders and semantic regression: PASS.
  Existing baseline still lacks 220 English and 298 Guarani keys. Full translation
  coverage is not claimed. No new semantic findings, strings or resource changes;
  preexisting local audit reports are preserved. Line-number-only finding IDs were
  compared semantically, excluding `id` and `line`.
- `git diff --check`: PASS for stage files.

Static checks, scoped to the added realtime path and reviewed Android diff:

```text
DIRECT_REALTIME_MESA_STATE_MUTATION: 0
DIRECT_REALTIME_PAYMENT_MUTATION: 0
SERVICE_ROLE_SECRET: 0
FAKE_REALTIME_USER: 0
NEW_FINANCIAL_CALCULATION: 0
BACKEND_CHANGES: 0
ANDROID FINANCIAL AUTHORITY MODIFIED: NO
```

Security checks include decoding embedded source JWT roles without printing tokens;
no service-role JWT was found. The existing public anon key is not a service-role key.
No backend changes, payment-authority changes, KDS changes, printer changes or
Architecture 2B work are included. Unrelated preexisting build, icon, localization
report and protected APK changes are excluded from this stage's commit.

Technical tests use fakes and local Room; they do not measure delivery latency from
the live backend or prove physical acceptance on P8. Physical validation remains human-owned.

## Human physical test plan — P8 NEO

TEST 1 — OPEN

P8:
Mesa 5 initially free.

Phone:
scan Mesa 5 QR
open Evandro comanda
place order.

Expected P8:
Mesa 5 becomes occupied automatically within a few seconds.

No navigation/relogin.

TEST 2 — CLOSE

Close Evandro externally.

Expected P8:
Mesa becomes free automatically if no other open comanda remains.

TEST 3 — MULTIPLE COMANDAS

Evandro + Jana open on Mesa 5.

Close Evandro.

Expected:
Mesa remains occupied.

Close Jana.

Expected:
Mesa becomes free automatically.

TEST 4 — RECONNECT

Disconnect P8 network.

Change Mesa from phone/web.

Reconnect P8.

Expected:
canonical refresh converges to current Mesa state.

TEST 5 — FOREGROUND

Put Plug PDV in background.

Change Mesa externally.

Return to Plug PDV.

Expected:
canonical refresh shows current state.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX
