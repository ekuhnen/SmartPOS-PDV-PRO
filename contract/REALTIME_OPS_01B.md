# REALTIME-OPS-01B ÔÇö Restaurant Operational Realtime Foundation

Realtime ├® **invalida├º├úo**, nunca autoridade de neg├│cio. O evento diz apenas
"algo mudou; releia o estado can├┤nico".

## EVENT_TABLE
`public.restaurant_realtime_events`

| coluna | significado |
| --- | --- |
| `id` uuid | identidade do envelope (dedupe no cliente, se quiser) |
| `server_seq` bigserial | ordem de grava├º├úo (n├úo ├® rel├│gio l├│gico global de neg├│cio) |
| `owner_user_id` uuid NOT NULL | tenant, derivado do banco (`comandas.user_id` / `mesas.user_id`) |
| `event_type` text | ver EVENT_TYPES (CHECK constraint) |
| `mesa_id` uuid null | mesa afetada quando conhecida |
| `comanda_id` uuid null | comanda afetada quando conhecida |
| `comanda_versao` int null | `comandas.versao` (revis├úo can├┤nica existente) |
| `tx_id` bigint | `txid_current()`, usado s├│ para coalescer a transa├º├úo |
| `occurred_at` timestamptz | momento do commit l├│gico |

Sem dinheiro, sem imposto, sem taxa, sem desconto, sem c├ómbio, sem caixa,
sem resultado de autoriza├º├úo, sem dado fiscal do cliente, sem descri├º├úo de
produto, sem observa├º├úo.

## EVENT_TYPES
`TABLE_CHANGED`, `COMANDA_CHANGED`, `COMANDA_ITEMS_CHANGED`, `PAYMENT_CHANGED`.

## EVENT_CREATION_AUTHORITY
Banco de dados. ├Ünica porta: `public.restaurant_realtime_emit(owner, type, mesa_id, comanda_id, versao)`
(SECURITY DEFINER, EXECUTE revogado de PUBLIC/anon/authenticated).
Clientes s├│ leem/assinam.

## Trigger sources
- `TABLE_EVENT_TRIGGER_SOURCE`: `zzz_realtime_mesas` AFTER INSERT/UPDATE/DELETE em `public.mesas`
  (UPDATE s├│ emite quando `status`, `numero` ou `ativo` mudam) + espelho a partir de `comandas`.
- `COMANDA_EVENT_TRIGGER_SOURCE`: `zzz_realtime_comandas` AFTER INSERT/UPDATE/DELETE em `public.comandas`
  (emite `COMANDA_CHANGED` + `TABLE_CHANGED` do `mesa_id`).
- `ITEM_EVENT_TRIGGER_SOURCE`: `zzz_realtime_itens_comanda` AFTER INSERT/UPDATE/DELETE em `public.itens_comanda`
  (owner/mesa/vers├úo lidos de `comandas`).
- `PAYMENT_EVENT_TRIGGER_SOURCE`: `zzz_realtime_pagamentos_comanda` AFTER INSERT/UPDATE/DELETE em `public.pagamentos_comanda`.

Nenhuma RPC, Edge Function, API ou fluxo Android foi alterado: os gatilhos ficam
nas tabelas autoritativas, ent├úo qualquer caminho can├┤nico (terminal, card├ípio
digital, ERP web, job) emite evento automaticamente.

### Anti-tempestade
├ìndice ├║nico `restaurant_realtime_events_tx_uniq` em
`(tx_id, owner_user_id, event_type, comanda_id, mesa_id)` + `ON CONFLICT DO NOTHING`:
uma opera├º├úo de neg├│cio que toca 20 itens gera **um** `COMANDA_ITEMS_CHANGED`.

## AFTER_COMMIT_GUARANTEE
Gatilho AFTER na mesma transa├º├úo da muta├º├úo can├┤nica. Supabase Realtime l├¬ o WAL
**depois do commit**, portanto o cliente nunca v├¬ evento de estado revertido.
Teste de rollback: PASS (nenhum evento, nenhum estado).

## TENANT_RLS
RLS habilitada; ├║nica policy, apenas de leitura:
`owner_user_id = auth.uid()` OR `owner_user_id = public.effective_owner()` OR
subusu├írio ativo em `company_users`. `anon` sem nenhum privil├®gio;
`authenticated` s├│ `SELECT`; `service_role` completo.

## REALTIME_PUBLICATION / SUBSCRIPTION_FILTER
`ALTER PUBLICATION supabase_realtime ADD TABLE public.restaurant_realtime_events;`
Assinatura: `postgres_changes`, `event: INSERT`, `schema: public`,
`table: restaurant_realtime_events`, `filter: owner_user_id=eq.<ownerId>`.
O filtro ├® conveni├¬ncia; a fronteira real ├® a RLS.

## EVENT_REVISION_SOURCE / OUT_OF_ORDER_STRATEGY
`comandas.versao` (revis├úo can├┤nica j├í existente) + `server_seq` como ordem de
grava├º├úo. N├úo existe rel├│gio l├│gico global. Estrat├®gia: o cliente ignora ordem ÔÇö
todo evento apenas dispara releitura can├┤nica; duplicado, atrasado ou fora de
ordem ├® inofensivo.

## EVENT_RETENTION / EVENT_CLEANUP_AUTHORITY
6 horas. `public.restaurant_realtime_events_cleanup()` (SECURITY DEFINER,
somente `service_role`), agendada por pg_cron a cada hora quando dispon├¡vel.
A limpeza nunca toca estado de neg├│cio; o cliente jamais depende de replay.

## ERP web
`src/hooks/useRestaurantRealtime.ts` ÔÇö assina, coalesce 250 ms e invalida:
- `TABLE_CHANGED` ÔåÆ `ops-tables`, `ops-search`, `mesas`
- `COMANDA_CHANGED` ÔåÆ o acima + `comandas`, `comandas-overview`, `itens_comanda`
- `COMANDA_ITEMS_CHANGED` ÔåÆ `comandas*`, `itens_comanda`, `ops-tables`
- `PAYMENT_CHANGED` ÔåÆ `pagamentos_comanda`, `comanda-money`, `comandas*`, `ops-tables`

Ligado em `src/pages/Comandas.tsx` e `src/components/restaurant/RestaurantScreen.tsx`.

- `WEB_TABLE_REFRESH_SOURCE`: `api-restaurant-ops?view=tables` (`restaurant_ops_tables`) e `useMesas`
- `WEB_COMANDA_REFRESH_SOURCE`: `restaurant_ops_comanda_shape` / `restaurant_ops_search`, `useComandasOverview`, `useItensComanda`
- `WEB_FINANCIAL_REFRESH_SOURCE`: `comanda_money_summary` via `api-restaurant-ops` / `api-comandas`

O payload do evento nunca ├® aplicado como estado.

## Rede de seguran├ºa
`WEB_SAFETY_POLL_INTERVAL`: 60 s (era 15 s), mantido em `useOpsTables`,
`useComandasOverview`, `useMesas`. Preservados: releitura ao entrar na tela,
releitura ap├│s muta├º├úo local, releitura em `visibilitychange` e em `online`,
e releitura can├┤nica quando a assinatura reconecta ap├│s erro (sem replay).

## ANDROID_FUTURE_CONTRACT (REALTIME-ANDROID-01)
```
Transport : Supabase Realtime (postgres_changes) ÔÇö nenhum socket novo
Auth      : o JWT do terminal j├í existente (Authorization: Bearer <access_token>)
Table     : public.restaurant_realtime_events
Event     : INSERT apenas
Filter    : owner_user_id=eq.<ownerId resolvido pelo servidor>
Payload   : { id, server_seq, owner_user_id, event_type, mesa_id, comanda_id,
              comanda_versao, occurred_at }
Types     : TABLE_CHANGED | COMANDA_CHANGED | COMANDA_ITEMS_CHANGED | PAYMENT_CHANGED
Semantics : evento = invalida├º├úo. NUNCA autoridade. Nunca aprova pagamento,
            nunca fecha comanda, nunca libera mesa, nunca calcula saldo.
Re-fetch  : TABLE_CHANGED            -> GET api-restaurant-ops?view=tables[&mesa_id]
            COMANDA_CHANGED          -> tables + GET api-comandas?id=<comanda_id>
            COMANDA_ITEMS_CHANGED    -> GET api-comandas?id=<comanda_id>
            PAYMENT_CHANGED          -> GET api-comandas?id=<comanda_id> (dinheiro can├┤nico)
Mesa/comanda: mesa_id = mesa afetada; comanda_id = comanda afetada (null quando
            o aviso ├® s├│ da mesa). V├írias comandas por mesa: um aviso de uma
            comanda N├âO implica mudan├ºa nas outras.
Reconnect : ao reconectar, releitura can├┤nica. N├úo reproduzir eventos perdidos.
Duplicate : dedupe opcional por `id`; reprocessar ├® inofensivo.
Retention : 6 h; nunca dependa de hist├│rico.
KDS       : inalterado ÔÇö continua com queue_version/ETag/since_version.
```

## Resultados
- Testes 01B: **23/23 PASS**
- Regress├úo (`harness_gate_run`): **152/152 PASS** (0 falhas)
- Linter de seguran├ºa: 46 avisos ÔÇö igual ├á linha de base anterior
- KDS modificado: NO ┬À Android modificado: NO ┬À autoridade financeira modificada: NO
