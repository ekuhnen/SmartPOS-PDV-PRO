# PAYMENT-02A — Provider execution boundary

Status: **FOUNDATION — no checkout behavior changed**  
Branch: `feature/payment-providers-cielo`

## Objective

Introduce the execution boundary that will let the durable payment state machine
invoke different integrations without knowing their protocol.

```text
PaymentHandlerActivity (durable orchestration)
        ↓
PaymentCoordinator
        ↓
PaymentProvider
      ↙          ↘
PlugPay       Cielo Tap (later)
```

## Implemented in 02A

- `PaymentProviderRequest`: provider-neutral request whose monetary authority is
  `amountMinor: Long` plus `currency`.
- `PaymentProvider`: execution contract. It supports both external-app providers
  and future in-process SDK providers.
- `PlugPayProvider`: owns the existing PlugPay URI/package/query contract.
- `PaymentCoordinator`: single provider execution registry. Only PlugPay is
  registered in this APK at this stage.
- Contract tests proving the existing PlugPay BRL/PYG URI shape and proving that
  Cielo is **not** silently redirected to PlugPay before the SDK exists.

## Explicitly NOT changed

- `PaymentHandlerActivity` state machine;
- Room schema/version;
- PREPARED/PENDING/APPROVED/REJECTED/UNKNOWN/CANCELLED semantics;
- persistence-before-action;
- PlugPay callback handling;
- outbox promotion;
- checkout/comanda rules;
- provider selection UI;
- Cielo SDK/dependencies/credentials.

## Why the state machine stays outside providers

Provider adapters are transports/executors, not payment authority. They must not
own durable state, idempotency, checkout allocation or UNKNOWN reconciliation.
Those remain in the existing orchestrator/Room path so changing provider cannot
change financial semantics.

## Next gate — PAYMENT-02B

Wire the existing PlugPay launch points in `PaymentHandlerActivity` through
`PaymentCoordinator` while preserving byte-for-byte equivalent provider inputs
and callback behavior. Only after regression tests and a real PlugPay payment
pass will provider selection/persistence and Cielo execution be introduced.
