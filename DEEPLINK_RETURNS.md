# Documentação de Retornos via Deeplink — PlugPay

Este documento descreve os possíveis estados e parâmetros que o aplicativo **PlugPay** retorna para o aplicativo de PDV chamador após a conclusão ou cancelamento de uma transação.

## Estrutura da URL de Retorno

O retorno é feito via um Intent `ACTION_VIEW` utilizando a `callback_uri` fornecida no deeplink original. Os parâmetros são passados via **Query Parameters**.

**Exemplo:**
`seu_esquema://callback?status=SUCCESS&payment_id=12345&method=PIX&message=Aprovado`

---

## 1. Parâmetros de Retorno

| Parâmetro | Tipo | Descrição |
| :--- | :--- | :--- |
| `status` | String | O estado final da operação. Possíveis valores: `SUCCESS`, `CANCELLED`, `ERROR`. |
| `payment_id` | String | A Referência Interna ou ID da transação gerada no PlugPay. (Vazio se não gerado). |
| `method` | String | O método de pagamento selecionado ou utilizado (`PIX`, `CREDIT`, `QR`). |
| `message` | String | Descrição legível do status para logs ou exibição ao operador. |

---

## 2. Estados (Status) detalhados

### SUCCESS (Pagamento Aprovado)
Enviado quando o pagamento é processado com sucesso pelo gateway e o usuário **fecha a tela de comprovante** no PlugPay.
- **Ação sugerida no PDV**: Finalizar a venda e imprimir o cupom fiscal.

### CANCELLED (Operação Cancelada)
Enviado quando o usuário desiste da operação de forma explícita. Ocorre em dois cenários:
1.  O usuário clica em "Voltar" ou "Fechar" na tela de entrada de valor (`SaleValueActivity`).
2.  O usuário clica no botão **"PLUG PDV"** no menu principal enquanto uma sessão de deeplink está ativa.
- **Ação sugerida no PDV**: Liberar a tela de pagamento para nova tentativa ou troca de método.

### ERROR (Falha na Operação)
Enviado em caso de erros críticos de infraestrutura ou integração antes do início da transação.
- **Cenário Comum**: Falha no login automático decorrente de credenciais inválidas enviadas no deeplink.
- **Ação sugerida no PDV**: Notificar o erro ao operador e verificar as configurações de integração.

---

## 3. Comportamento de Persistência

O PlugPay agora implementa **Persistência de Sessão**. Isso significa que:
- Se o usuário navegar entre telas dentro do PlugPay (ex: vai para o PIX, volta para o menu e escolhe Cartão), **o callback de CANCELLED não é enviado**. 
- O valor injetado permanece bloqueado e persistente até que ocorra um um dos gatilhos de finalização (`SUCCESS` ou `CANCELLED` via botão PDV).

> [!TIP]
> Para uma melhor experiência de usuário (UX), seu PDV deve estar preparado para receber o retorno mesmo que o app PlugPay tenha ficado em segundo plano por alguns instantes.
