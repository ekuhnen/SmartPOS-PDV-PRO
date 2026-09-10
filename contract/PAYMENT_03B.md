# PAYMENT-03B — Persist provider before execution

Status: **CANDIDATE — requires local Room migration/build regression**  
Branch: `feature/payment-providers-cielo`  
Depends on: PAYMENT-03A (`3fc6fb9`)

## Objective

Persist the payment provider on `payment_attempts` before the first provider execution so process death, retries and later capability refreshes cannot silently move an in-flight attempt to another integration.

## Durable semantics

`payment_attempts.provider` is nullable only while a newly-created attempt is still `PREPARED` and no provider has ever been executed.

Before `PREPARED -> PENDING`, the activity must:

1. use an already-persisted provider if present;
2. otherwise resolve the current tenant/currency policy;
3. persist the selected provider together with `PENDING`;
4. only then execute the provider.

A non-empty persisted provider is never replaced by a newly-resolved provider. Unknown persisted values fail closed; they never fall back to PlugPay.

For a brand-new non-PREPARED attempt, provider policy is resolved first, then the attempt is inserted as `PENDING` with the selected provider, and only then execution starts.

## Room 12 -> 13

The migration adds nullable `payment_attempts.provider TEXT` and an index on it. All rows that already existed in schema 12 are backfilled to `PLUGPAY`, because PlugPay was the only executable payment provider before this migration.

New `PREPARED` rows may temporarily have `provider = NULL` until their first execution boundary is reached.

## Fail-closed rules

- no authorized/executable provider -> `FAILED_TO_START` before provider execution;
- selection required but no selection UI exists yet -> `FAILED_TO_START` with `PAYMENT_PROVIDER_SELECTION_REQUIRED`;
- unknown non-empty persisted provider -> `FAILED_TO_START` with `PAYMENT_PROVIDER_UNKNOWN`;
- no policy failure is represented as `REJECTED` or `DECLINED`;
- a failed provider resolution never causes Cielo -> PlugPay fallback.

## Preserved

- money remains `Long` minor units + currency;
- idempotency/reference lifecycle is unchanged;
- PREPARED/PENDING/APPROVED/REJECTED/UNKNOWN/CANCELLED semantics are unchanged;
- callback correlation and outbox promotion are unchanged;
- PlugPay transport is unchanged;
- no Cielo SDK, credentials or provider-selection UI is introduced.

## Gate

Before the next stage:

1. `PaymentProviderMigrationTest` PASS;
2. `PaymentProviderResolverTest` PASS;
3. full `testDebugUnitTest` PASS;
4. `assembleDebug` PASS;
5. Room schema `13.json` generated and reviewed;
6. one low-value PlugPay direct-sale payment PASS, including resume/callback back into the PDV.
