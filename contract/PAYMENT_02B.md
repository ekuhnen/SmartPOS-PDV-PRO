# PAYMENT-02B — Wire PlugPay through PaymentCoordinator

Status: **CANDIDATE — requires local regression + real PlugPay payment**  
Branch: `feature/payment-providers-cielo`  
Depends on: PAYMENT-02A (`6fb8904`)

## Objective

Route the two existing PlugPay launch points through the provider execution boundary without changing the durable payment state machine.

```text
PaymentHandlerActivity
        ↓
PaymentCoordinator
        ↓
PlugPayProvider
        ↓
plugpay://pay
```

## Changed

- `PaymentHandlerActivity` receives `PaymentCoordinator` through Hilt.
- Direct construction of the PlugPay scheme/host/package was removed from the Activity.
- New and PREPARED attempts are translated to `PaymentProviderRequest`.
- Both launch points explicitly request `PaymentProviderType.PLUGPAY`.
- Provider start failures are mapped back to the existing `FAILED_TO_START` path.

## Preserved

- Room persistence occurs before provider execution.
- `amount` authority stays `Long` minor units + currency.
- Existing request reference/idempotency lifecycle is unchanged.
- Callback remains `plugpdv://payment_callback` with the same request/table correlation.
- PREPARED/PENDING/APPROVED/REJECTED/UNKNOWN/CANCELLED behavior is unchanged.
- APPROVED precedence and late-approval reconciliation are unchanged.
- Outbox promotion on APPROVED is unchanged.
- Comanda/direct-sale checkout behavior is unchanged.
- No Room schema/version migration.
- No provider selection UI.
- No Cielo SDK or credentials.

## Regression gate

Before PAYMENT-03:

1. `PlugPayProviderContractTest` PASS.
2. `PaymentCoordinatorTest` PASS.
3. Full `testDebugUnitTest` PASS.
4. `assembleDebug` PASS.
5. Real low-value PlugPay payment PASS, confirming:
   - PlugPay opens;
   - amount/currency are correct;
   - callback returns to Plug PDV;
   - attempt becomes APPROVED;
   - checkout/outbox completes exactly as before.

Do not start provider selection or Cielo execution until this gate is green.
