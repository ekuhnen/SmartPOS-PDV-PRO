# API — Taxa de Serviço Dinâmica (atualização para o App Smart POS)

Documento de referência para atualizar o app da Smart POS às novas regras da taxa de serviço.
Backend já em produção e retrocompatível: chamadas antigas continuam funcionando (taxa = 0).

---

## 1. Contexto

Antes: a taxa de serviço era enviada como **desconto negativo** para o `api-vendas`, o que poluía o campo `discount` e impedia relatórios/acertos.

Agora: a taxa de serviço tem campos próprios em `sales`, com o **tipo** de cobrança (fixa, manual em %, manual em valor, ou isentada) e o **operador** que cobrou — permitindo repasse futuro (gorjeta) e visão por operador.

Além disso:
- O **owner** pode habilitar/desabilitar globalmente se operadores podem alterar/isentar a taxa fixa no PDV.
- Cada **sub-usuário** pode receber a permissão de visualizar as taxas de serviço que ele mesmo gerou.

---

## 2. `GET /functions/v1/api-taxes`

Agora retorna, além das taxas fiscais, o **bloco `service_fee`** com a configuração do estabelecimento. O app deve ler esse bloco no boot / login para saber o que renderizar no carrinho.

### Resposta (200)

```json
{
  "taxes": [
    { "id": "uuid", "name": "IOF", "percentage": 3.1, "currency": "BRL", "active": true }
  ],
  "service_fee": {
    "fixed_enabled": true,
    "fixed_percent": 10,
    "allow_override": true
  }
}
```

### Campos novos

| Campo | Tipo | Descrição |
|---|---|---|
| `service_fee.fixed_enabled` | boolean | Estabelecimento cobra taxa fixa de serviço |
| `service_fee.fixed_percent` | number | Percentual fixo cadastrado (ex: `10` = 10%) |
| `service_fee.allow_override` | boolean | Se o operador pode alterar / isentar / lançar valor manual no PDV |

### Regras de UI recomendadas

- `fixed_enabled = false` e `allow_override = false` → não mostrar campo de taxa.
- `fixed_enabled = true` e `allow_override = false` → mostrar taxa fixa somente leitura (aplicada automaticamente).
- `allow_override = true` → mostrar botão **"Alterar taxa"** com opções:
  - Aplicar percentual fixo (padrão pré-preenchido com `fixed_percent`)
  - Percentual manual
  - Valor manual
  - **Não cobrar taxa** (isentar)

---

## 3. `POST /functions/v1/api-vendas` — registrar venda

Dois campos novos no body:

| Campo | Tipo | Regras |
|---|---|---|
| `service_fee_amount` | number ≥ 0 | Valor absoluto da taxa de serviço a cobrar. **Nunca enviar como `discount` negativo.** Precisa ser ≤ `subtotal`. Default: `0`. |
| `service_fee_kind` | string | Obrigatório quando `service_fee_amount > 0`. Valores permitidos: `fixed`, `manual_percent`, `manual_value`, `waived`. |

### Semântica de `service_fee_kind`

| Valor | Quando usar | `service_fee_amount` |
|---|---|---|
| `fixed` | Cobrou o percentual fixo cadastrado no perfil (`fixed_percent`) | `subtotal * fixed_percent / 100` |
| `manual_percent` | Operador informou um **percentual** diferente do fixo | `subtotal * percentual_informado / 100` |
| `manual_value` | Operador informou um **valor absoluto** (moeda) | valor digitado |
| `waived` | Operador **isentou** explicitamente a taxa | `0` (envie 0 e o kind `waived` para registrar a isenção) |

### Cálculo do total (server-side, para o app apenas exibir)

```
total = subtotal - discount + service_fee_amount
```

`discount` volta a ser **desconto legítimo positivo apenas**.

### Exemplo de body

```json
{
  "customer_name": "Mesa 4",
  "discount": 5.00,
  "service_fee_amount": 6.49,
  "service_fee_kind": "manual_value",
  "payment_method": "PIX",
  "payment_currency": "BRL",
  "items": [
    { "product_id": "uuid", "quantity": 2 }
  ]
}
```

### Resposta (201)

```json
{
  "sale_id": "uuid",
  "subtotal": 64.90,
  "discount": 5.00,
  "service_fee": 6.49,
  "service_fee_kind": "manual_value",
  "total": 66.39,
  "payment_method": "PIX",
  "payment_currency": "BRL",
  "converted_total": 66.39,
  "items_count": 1,
  "created_at": "..."
}
```

### Erros específicos

| Status | Motivo |
|---|---|
| 400 | `service_fee_kind required when amount > 0` |
| 400 | `service_fee_amount` inválido (negativo ou maior que subtotal) |
| 400 | `service_fee_kind` fora do enum permitido |

### Retrocompatibilidade

Se o app **não enviar** `service_fee_amount` / `service_fee_kind`, a venda é registrada com taxa = 0 (mesmo comportamento anterior). Não quebra clientes antigos.

### Operador (gorjeta)

O `operator_id` e `operator_name` derivados do JWT são copiados para os campos `service_fee_operator_id` / `service_fee_operator_name` da venda. É por eles que o relatório "Minhas taxas" filtra.

---

## 4. `GET /functions/v1/api-vendas?scope=mine_service_fees`

Nova rota para o **operador ver quanto de taxa de serviço ele gerou** (base para gorjeta).

### Autorização

- **Owner**: sempre pode.
- **Sub-usuário**: precisa ter `company_users.can_view_service_fee = true` (configurado pelo owner no Cadastro de Usuários). Caso contrário retorna `403`.

### Filtros

| Query param | Descrição |
|---|---|
| `date=YYYY-MM-DD` | (opcional) filtrar por data |
| `limit=N` | (opcional) padrão 50 |

### Resposta (200)

```json
{
  "total_service_fee": 42.30,
  "count": 5,
  "sales": [
    {
      "id": "uuid",
      "created_at": "2026-07-14T18:22:10Z",
      "service_fee": 6.49,
      "service_fee_kind": "manual_value",
      "total": 66.39,
      "customer_name": "Mesa 4"
    }
  ]
}
```

Retorna somente vendas com `service_fee > 0` do operador autenticado.

---

## 5. `GET /functions/v1/api-vendas` — histórico (campos novos)

O objeto `sale` do histórico agora inclui:

| Campo | Tipo |
|---|---|
| `service_fee` | number |
| `service_fee_kind` | string \| null |

Nenhum campo antigo foi removido.

---

## 6. Checklist para o App Smart POS

- [ ] No boot, ler `service_fee` do `api-taxes` e guardar em estado.
- [ ] Remover o envio de taxa como `discount` negativo.
- [ ] Renderizar o botão "Alterar taxa" no carrinho conforme `allow_override`.
- [ ] Suportar as 4 modalidades: `fixed`, `manual_percent`, `manual_value`, `waived`.
- [ ] Enviar `service_fee_amount` + `service_fee_kind` no POST `api-vendas`.
- [ ] Tratar erros 400 de validação da taxa e mostrar mensagem ao operador.
- [ ] (Opcional) Adicionar tela "Minhas taxas / gorjetas" consumindo `?scope=mine_service_fees`, oculta quando a chamada retorna 403.
- [ ] Exibir na tela de confirmação/recibo: `subtotal`, `discount`, `service_fee`, `total` (separados).

---

## 7. Fora de escopo (não vem do backend ainda)

- Repasse financeiro automático da gorjeta ao operador.
- Backfill retroativo de vendas antigas onde a taxa foi registrada como desconto negativo.
