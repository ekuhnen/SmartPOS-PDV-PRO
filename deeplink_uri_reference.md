# Referência de URIs do Deeplink — PixPlug SmartPOS

Documentação completa de todas as URIs possíveis disparadas pelo app **PixPlug** (`com.br.plugpay`) de volta ao PDV (`com.plugpdv.pdv`) via deeplink.

---

## 1. URI de entrada (PDV → PixPlug)

O PDV inicia o fluxo chamando o PixPlug com este deeplink:

```
plugpay://pay
  ?amount=<valor na moeda selecionada>
  &selected_currency=<código da moeda selecionada>
  &amounts=<JSON com valores em todas as moedas disponíveis>
  &request_id=<id-da-requisicao>
  &callback_uri=<uri-de-retorno-url-encoded>
  [&email=<email>]
  [&password=<senha>]
```

| Parâmetro | Obrigatório | Descrição |
|---|---|---|
| `amount` | ✅ | Valor da transação na moeda selecionada (ex: `23195` para ARS, `92.78` para BRL) |
| `selected_currency` | ✅ | Código ISO da moeda selecionada pelo operador (ex: `ARS`, `BRL`, `PYG`) |
| `amounts` | ✅ | JSON com o valor pré-calculado em cada moeda disponível (URL-encoded) |
| `request_id` | ✅ | ID único da requisição para rastreio |
| `callback_uri` | ✅ | URI de retorno do PDV (URL-encoded) |
| `email` | ❌ | Credencial para auto-login, se o usuário não estiver logado |
| `password` | ❌ | Credencial para auto-login, se o usuário não estiver logado |

### Estrutura do parâmetro `amounts`

O JSON contém o valor convertido pela taxa de câmbio para **cada moeda** configurada no sistema:

```json
{
  "BRL": "92.78",
  "ARS": "23195",
  "PYG": "125253",
  "USD": "16.70",
  "EUR": "14.84",
  "BOB": "115.98"
}
```

> **Regras de formatação:**
> - **ARS** e **PYG**: sem decimais, arredondamento para cima (`Math.ceil`)
> - Demais moedas: 2 casas decimais
> - Separador decimal: ponto (`.`), locale `US`

**Exemplo completo (moeda selecionada = ARS):**
```
plugpay://pay?amount=23195&selected_currency=ARS&amounts=%7B%22BRL%22%3A%2292.78%22%2C%22ARS%22%3A%2223195%22%2C%22PYG%22%3A%22125253%22%2C%22USD%22%3A%2216.70%22%2C%22EUR%22%3A%2214.84%22%2C%22BOB%22%3A%22115.98%22%7D&request_id=abc123&callback_uri=plugpdv%3A%2F%2Fpayment_callback
```

---

## 2. URIs de retorno (PixPlug → PDV)

A `<callback_uri>` é o valor passado pelo PDV no parâmetro `callback_uri` acima (ex: `smartpos://payment_callback`).

O PixPlug appenda os parâmetros de resultado nessa URI base.

---

### ✅ Status: APPROVED

Disparado em `TransactionReceiptActivity` quando o usuário toca em **Voltar** (ou no botão fechar) após ver o comprovante.

#### PIX
```
smartpos://payment_callback?status=APPROVED&payment_id=<referenciaInterna>&method=PIX&message=Aprovado
```

#### Cartão Parcelado (Crédito)
```
smartpos://payment_callback?status=APPROVED&payment_id=<referenciaInterna>&method=CREDIT_INSTALLMENTS&message=Aprovado
```

#### Débito (CARD)
```
smartpos://payment_callback?status=APPROVED&payment_id=<referenciaInterna>&method=DEBIT&message=Aprovado
```

#### QR Paraguai (PYG)
```
smartpos://payment_callback?status=APPROVED&payment_id=<referenciaInterna>&method=QR_PYG&message=Aprovado
```

#### QR Argentina (ARS)
```
smartpos://payment_callback?status=APPROVED&payment_id=<referenciaInterna>&method=QR_ARS&message=Aprovado
```

---

### ❌ Status: CANCELLED

Disparado em vários pontos quando o usuário abandona o fluxo antes de concluir.

| Origem | `method` | `payment_id` | `message` |
|---|---|---|---|
| `NFCActivity` — back antes do pagamento | `CREDIT` | `""` (vazio) | `Cancelado` |
| `NFCActivity` — timeout/falha NFC | `null` (omitido) | `null` (omitido) | `Cancelado` |
| `SaleValueActivity` — back na tela de valor | `""` (vazio) | `""` (vazio) | `Cancelado` |
| `PaymentQrCodeActivity` — back no QR | `""` (vazio) | `""` (vazio) | `Cancelado` |
| `HomeFragment` — back no menu principal | `""` (vazio) | `""` (vazio) | `Cancelado pelo usuário no menu` |

**Exemplo típico de CANCELLED:**
```
smartpos://payment_callback?status=CANCELLED&payment_id=&method=&message=Cancelado
```

**Exemplo de CANCELLED com payment_id e method omitidos (quando null):**
```
smartpos://payment_callback?status=CANCELLED&message=Cancelado
```

---

### ⛔ Status: ERROR

Disparado em `DeeplinkEntryActivity` quando há falha de autenticação.

#### Usuário não logado e sem credenciais
```
smartpos://payment_callback?status=ERROR&message=Not+logged+in
```

#### Auto-login com credenciais inválidas
```
smartpos://payment_callback?status=ERROR&message=Authentication+failed
```

---

## 3. Tabela resumo de todos os status

| Status | Quando ocorre |
|---|---|
| `APPROVED` | Pagamento confirmado, usuário tocou em Voltar/Fechar no comprovante |
| `CANCELLED` | Usuário abandonou o fluxo antes de concluir |
| `ERROR` | Falha de autenticação na entrada do deeplink |

---

## 4. Tabela de `method` por tipo de pagamento

| Tipo de pagamento | Valor de `method` |
|---|---|
| PIX | `PIX` |
| Cartão crédito parcelado | `CREDIT_INSTALLMENTS` |
| Débito | `DEBIT` |
| QR Paraguai (PYG) | `QR_PYG` |
| QR Argentina (ARS) | `QR_ARS` |
| NFC (cartão físico) | `CREDIT` |
| Fallback / desconhecido | `PIX` |

> [!NOTE]
> Os valores de `method` nos callbacks `CANCELLED` originados de `NFCActivity` ainda usam `CREDIT` ou são omitidos (`null`), pois o pagamento não chegou a ser concluído. Apenas o callback `APPROVED` resolve o método dinamicamente via `resolvePaymentMethod()`.

---

## 5. Parâmetros do callback — referência completa

| Parâmetro | Tipo | Presente em | Descrição |
|---|---|---|---|
| `status` | String | Sempre | `APPROVED`, `CANCELLED` ou `ERROR` |
| `payment_id` | String | APPROVED, alguns CANCELLED | Referência interna da transação |
| `method` | String | APPROVED, alguns CANCELLED | Tipo de pagamento |
| `message` | String | Sempre | Mensagem legível do resultado |
