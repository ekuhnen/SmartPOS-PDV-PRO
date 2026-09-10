# PAYMENT-03A — Provider policy resolution

Status: **CANDIDATE — no checkout execution change**  
Branch: `feature/payment-providers-cielo`  
Depends on: PAYMENT-02B (`8bb38f4`)

## Objective

Compose the company payment-provider policy already cached from terminal capabilities with the providers executable by the current APK, without yet changing a payment attempt or Room schema.

```text
terminal capabilities
        ↓
PaymentProviderCapabilitiesStore
        ↓
PaymentProviderPolicy
        ↓
PaymentCoordinator.resolve(...)
        ↓
PaymentProviderResolver
        ↓
Selected | SelectionRequired | Blocked
```

## Existing capabilities wiring verified

`CurrencyManager.applyCapabilities(context, ownerId, response)` already forwards the same authoritative response to `PaymentProviderCapabilitiesStore.applyCapabilities(...)`. No second network call and no duplicate login path are introduced.

## Resolution rules

1. Legacy policy continues selecting `PLUGPAY` while PlugPay is executable.
2. Explicit `payment_providers: []` fails closed; legacy fallback is never invented.
3. A requested provider must be both authorized by capabilities and executable by this APK.
4. `CIELO_TAP`-only policy does not fall back to PlugPay while Cielo is not implemented.
5. If operator selection is allowed but only one authorized provider is executable, that provider can be selected automatically.
6. If operator selection is disabled and the configured default is not executable, resolution is blocked instead of silently replacing the server default.
7. Once multiple providers are executable and operator selection is allowed, the resolver returns `SelectionRequired` with the executable list and an executable default when available.
8. The resolver works only with provider authorization/execution. Financial state, idempotency, Room transitions and callbacks remain outside it.

## Explicitly NOT changed in 03A

- `PaymentHandlerActivity`;
- PlugPay execution;
- callback handling;
- Room schema/version;
- payment attempt persistence;
- provider picker UI;
- Cielo SDK/credentials;
- checkout/comanda behavior.

## Gate

Before PAYMENT-03B:

- `PaymentProviderResolverTest` PASS;
- `PaymentCoordinatorTest` PASS;
- full `testDebugUnitTest` PASS;
- `assembleDebug` PASS.

PAYMENT-03B will persist the resolved provider in `payment_attempts` with a Room migration and only then wire policy resolution into payment execution. This prevents a process restart or policy refresh from changing provider mid-attempt.
