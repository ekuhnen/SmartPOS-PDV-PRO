# Fluxo de Pagamento via Deeplink — SmartPOS PDV Pro

Este documento descreve, de forma detalhada e técnica, como o aplicativo **SmartPOS PDV Pro** aciona um app de pagamento externo por meio de **Android Deeplink**, incluindo o ciclo completo: geração do link, passagem de parâmetros, captura do retorno e registro da venda na API.

---

## Visão Geral do Fluxo

```
[Tela de Checkout]
       │
       │  startActivityForResult(PaymentHandlerActivity)
       ▼
[PaymentHandlerActivity]
       │
       │  Intent(ACTION_VIEW, paymentUri)  ← DEEPLINK OUTGOING
       ▼
[App de Pagamento Externo]
       │
       │  smartpos://payment_callback?...  ← DEEPLINK RETORNO (callback)
       ▼
[PaymentHandlerActivity.onNewIntent()]
       │
       │  setResult(RESULT_OK) → finish()
       ▼
[Tela de Checkout — onActivityResult()]
       │
       │  Registra venda na API (se APPROVED)
       ▼
[Venda Registrada / Mesa Fechada]
```

---

## 1. Pontos de Entrada (Quem Inicia o Pagamento)

O pagamento pode ser iniciado a partir de dois contextos diferentes do app:

### 1.1 Venda Direta (`CheckoutActivity`)

**Arquivo:** `android/app/src/main/java/com/smartpos/pdv/ui/sale/CheckoutActivity.kt`

```kotlin
private fun startPaymentFlow() {
    if (sessionId == null) {
        Toast.makeText(this, R.string.cashier_closed_msg, Toast.LENGTH_LONG).show()
        return
    }

    val intent = Intent(this, PaymentHandlerActivity::class.java).apply {
        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, viewModel.finalTotal.value.toString())
        putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, "0")
    }
    startActivityForResult(intent, PAYMENT_REQUEST_CODE) // código 1001
}
```

> **Pré-condição:** O caixa (sessão) precisa estar aberto (`sessionId != null`). Se não estiver, o fluxo é interrompido com uma mensagem ao usuário.

Parâmetros enviados:
| Extra | Valor |
|---|---|
| `EXTRA_AMOUNT` | `viewModel.finalTotal.value` (total final com impostos) |
| `EXTRA_ORDER_ID` | `"0"` (hardcoded para venda direta) |

---

### 1.2 Checkout de Mesa (`TableCheckoutBottomSheet`)

**Arquivo:** `android/app/src/main/java/com/smartpos/pdv/ui/sale/TableCheckoutBottomSheet.kt`

```kotlin
private fun finalizePayment() {
    val state = viewModel.uiState.value
    if (state.currentToPay <= 0) {
        Toast.makeText(context, "Valor inválido", Toast.LENGTH_SHORT).show()
        return
    }

    val cm = CurrencyManager.getInstance()
    val convertedAmount = cm.convert(state.finalToPay)

    val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, convertedAmount.toString())
        putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, "0")
        putExtra(PaymentHandlerActivity.EXTRA_TABLE_NUMBER, table?.number ?: 0)
    }
    startActivityForResult(intent, 1002)
}
```

> **Diferença importante:** O valor do amount já é **convertido para a moeda selecionada** pelo `CurrencyManager` antes de ser enviado. Na venda direta, a conversão ocorre dentro do `PaymentHandlerActivity`.

Parâmetros enviados:
| Extra | Valor |
|---|---|
| `EXTRA_AMOUNT` | `cm.convert(state.finalToPay)` — valor já na moeda selecionada |
| `EXTRA_ORDER_ID` | `"0"` |
| `EXTRA_TABLE_NUMBER` | `table?.number` — número da mesa, usado para montar o callback URI |

---

## 2. `PaymentHandlerActivity` — O Intermediário do Deeplink

**Arquivo:** `android/app/src/main/java/com/smartpos/pdv/ui/sale/PaymentHandlerActivity.kt`

Esta Activity é o **coração da integração** com o app de pagamento. Ela tem tema transparente (o usuário não a vê diretamente), existindo apenas para disparar o deeplink e capturar o retorno.

### 2.1 Constantes Fundamentais

```kotlin
companion object {
    // Extras recebidos de quem a inicia
    const val EXTRA_REQUEST_ID    = "request_id"
    const val EXTRA_ORDER_ID      = "order_id"
    const val EXTRA_AMOUNT        = "amount"
    const val EXTRA_DESCRIPTION   = "description"
    const val EXTRA_TABLE_NUMBER  = "table_number"
    const val EXTRA_IS_TABLE      = "is_table"

    // Deeplink de saída → App de Pagamento
    private const val PAYMENT_APP_SCHEME  = "payment-app"
    private const val PAYMENT_APP_HOST    = "checkout"

    // Deeplink de retorno → SmartPOS
    private const val CALLBACK_SCHEME = "smartpos"
    private const val CALLBACK_HOST   = "payment_callback"
}
```

### 2.2 Ciclo de Vida e Despacho

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    handleIntent(intent)
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleIntent(intent)
}
```

O `onNewIntent` é essencial porque a Activity está configurada com `launchMode="singleTop"` no Manifest. Quando o app de pagamento retorna via deeplink para `smartpos://payment_callback`, o Android re-entrega a intent na mesma instância da Activity já em memória — em vez de criar uma nova.

### 2.3 Lógica de Despacho (`handleIntent`)

```kotlin
private fun handleIntent(intent: Intent?) {
    intent ?: return
    val data = intent.data

    if (data != null && CALLBACK_SCHEME == data.scheme && CALLBACK_HOST == data.host) {
        // É um retorno do app de pagamento → processa callback
        handlePaymentCallback(data)
    } else {
        // É uma nova solicitação de pagamento
        if (!isWaitingForCallback) {
            startPayment(intent)
        }
    }
}
```

O flag `isWaitingForCallback` evita que a Activity dispare múltiplas vezes caso o usuário tente voltar e o sistema reactive o `onCreate`.

---

## 3. Geração do Deeplink de Saída

**Método:** `startPayment(intent: Intent)`

```kotlin
private fun startPayment(intent: Intent) {
    // 1. Lê os parâmetros recebidos por extras
    val requestId   = intent.getStringExtra(EXTRA_REQUEST_ID) ?: System.currentTimeMillis().toString()
    val orderId     = intent.getStringExtra(EXTRA_ORDER_ID) ?: "0"
    val amountStr   = intent.getStringExtra(EXTRA_AMOUNT)
    val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: "Payment"
    val tableNumber = intent.getIntExtra(EXTRA_TABLE_NUMBER, -1)

    // 2. Formata o valor em US locale (ponto decimal, sem vírgula)
    val amount          = amountStr?.toDoubleOrNull() ?: 0.0
    val formattedAmount = String.format(Locale.US, "%.2f", amount)

    // 3. Obtém a moeda ativa
    val currencyCode = CurrencyManager.getInstance().selectedCurrency
                           .takeIf { it.isNotEmpty() } ?: "BRL"

    // 4. Monta a URI de callback (para o app de pagamento saber para onde retornar)
    var callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST"
    if (tableNumber != -1) {
        callbackUri += "?table_number=$tableNumber"
    }
    // Resultado: "smartpos://payment_callback" ou "smartpos://payment_callback?table_number=5"

    // 5. Constrói a URI do deeplink de pagamento
    val paymentUri = Uri.Builder()
        .scheme(PAYMENT_APP_SCHEME)          // "payment-app"
        .authority(PAYMENT_APP_HOST)         // "checkout"
        .appendQueryParameter("request_id",  requestId)
        .appendQueryParameter("order_id",    orderId)
        .appendQueryParameter("amount",      formattedAmount)
        .appendQueryParameter("currency",    currencyCode)
        .appendQueryParameter("callback_uri", callbackUri)
        .appendQueryParameter("merchant_id", "merchant123")
        .appendQueryParameter("description", description)
        .build()

    // URI final exemplo:
    // payment-app://checkout?request_id=1713200000000&order_id=0
    //     &amount=150.00&currency=BRL&callback_uri=smartpos%3A%2F%2Fpayment_callback
    //     &merchant_id=merchant123&description=Payment

    // 6. Dispara a Intent ACTION_VIEW com o deeplink
    val paymentIntent = Intent(Intent.ACTION_VIEW, paymentUri)
    try {
        startActivity(paymentIntent)
        isWaitingForCallback = true  // sinaliza que está aguardando retorno
    } catch (e: Exception) {
        // App de pagamento não instalado
        Toast.makeText(this, "Aplicativo de pagamento não encontrado.", Toast.LENGTH_LONG).show()
        val result = Intent().apply {
            putExtra("status", "ERROR")
            putExtra("message", "Payment app not found")
        }
        setResult(Activity.RESULT_CANCELED, result)
        finish()
    }
}
```

### Anatomia Completa da URI Gerada

```
payment-app://checkout
    ?request_id=1713200000000
    &order_id=0
    &amount=150.00
    &currency=BRL
    &callback_uri=smartpos%3A%2F%2Fpayment_callback%3Ftable_number%3D5
    &merchant_id=merchant123
    &description=Payment
```

| Parâmetro | Origem | Descrição |
|---|---|---|
| `request_id` | `EXTRA_REQUEST_ID` ou timestamp | ID único da requisição |
| `order_id` | `EXTRA_ORDER_ID` | ID do pedido (atualmente fixo em `"0"`) |
| `amount` | `EXTRA_AMOUNT` formatado (`%.2f`) | Valor a cobrar, locale US (ponto decimal) |
| `currency` | `CurrencyManager.selectedCurrency` | Código ISO 4217 da moeda (ex: `BRL`, `USD`, `PYG`) |
| `callback_uri` | Montado internamente | URI que o app de pagamento deve chamar ao finalizar |
| `merchant_id` | Hardcoded `"merchant123"` | Identificador do estabelecimento ⚠️ |
| `description` | `EXTRA_DESCRIPTION` ou `"Payment"` | Descrição legível do pagamento |

> ⚠️ **Atenção:** O `merchant_id` está atualmente hardcoded como `"merchant123"`. Em produção, este valor deve ser dinâmico, vindo das configurações do estabelecimento.

---

## 4. Registro do Callback no AndroidManifest

Para que o app de pagamento consiga navegar de volta ao SmartPOS, a `PaymentHandlerActivity` está registrada no `AndroidManifest.xml` como receptora do deeplink `smartpos://payment_callback`:

```xml
<!-- android/app/src/main/AndroidManifest.xml, linhas 77-88 -->

<activity
    android:name=".ui.sale.PaymentHandlerActivity"
    android:exported="true"
    android:launchMode="singleTop"
    android:theme="@style/Theme.SmartPosPdvPro.Transparent">

    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />
        <data android:scheme="smartpos" android:host="payment_callback" />
    </intent-filter>
</activity>
```

| Atributo | Valor | Significado |
|---|---|---|
| `android:exported="true"` | true | Permite que outros apps (o app de pagamento) disparem esta Activity |
| `android:launchMode="singleTop"` | singleTop | Reutiliza a instância existente; entrega via `onNewIntent` |
| `android:scheme` | `smartpos` | Esquema da URI de callback |
| `android:host` | `payment_callback` | Host da URI de callback |

---

## 5. Processamento do Retorno (Callback)

**Método:** `handlePaymentCallback(uri: Uri)`

Quando o app de pagamento finaliza, ele abre o deeplink `smartpos://payment_callback?status=APPROVED&payment_id=abc&method=PIX`. O Android entrega isso via `onNewIntent`.

```kotlin
private fun handlePaymentCallback(uri: Uri) {
    // 1. Extrai os parâmetros do callback
    val status    = uri.getQueryParameter("status") ?: "ERROR"
    val paymentId = uri.getQueryParameter("payment_id")
    val method    = uri.getQueryParameter("method")
    val message   = uri.getQueryParameter("message")

    // 2. Monta o Intent de resultado para retornar ao chamador
    val result = Intent().apply {
        putExtra("status",     status)
        putExtra("payment_id", paymentId)
        putExtra("method",     method)
        putExtra("message",    message)
    }

    // 3. Define o resultado (RESULT_OK ou RESULT_CANCELED)
    if (status.equals("APPROVED", ignoreCase = true)) {
        setResult(Activity.RESULT_OK, result)
    } else {
        setResult(Activity.RESULT_CANCELED, result)
    }

    // 4. Caso especial: app de pagamento deixou o SmartPOS em segundo plano
    //    (isTaskRoot = true significa que esta Activity é a raiz da task)
    if (isTaskRoot) {
        val tableNum = uri.getQueryParameter("table_number")
        val restartIntent = if (tableNum != null) {
            Intent(this, TableOrderActivity::class.java).apply {
                putExtra("TABLE_NUMBER", tableNum.toInt())
                putExtra("AUTO_CHECKOUT", true)
            }
        } else {
            Intent(this, com.smartpos.pdv.ui.auth.LoginActivity::class.java)
        }
        restartIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(restartIntent)
    }

    finish()
}
```

### Parâmetros Esperados no Callback

| Query Param | Obrigatório | Valores possíveis | Descrição |
|---|---|---|---|
| `status` | Sim | `APPROVED`, `REJECTED`, `CANCELLED`, `ERROR` | Status da transação |
| `payment_id` | Não | String livre | ID único gerado pelo app de pagamento |
| `method` | Não | `PIX`, `CREDIT`, `DEBIT`, `CASH` | Método de pagamento usado |
| `message` | Não | String livre | Mensagem de erro ou descrição |
| `table_number` | Não | Integer | Presente apenas quando o pagamento foi de uma mesa |

### Exemplo de URI de Callback

```
# Aprovado (Venda Direta)
smartpos://payment_callback?status=APPROVED&payment_id=txn_789&method=PIX

# Aprovado (Mesa 5)
smartpos://payment_callback?status=APPROVED&payment_id=txn_789&method=CREDIT&table_number=5

# Rejeitado
smartpos://payment_callback?status=REJECTED&message=Saldo+insuficiente
```

---

## 6. Processamento do Resultado pelo Chamador

### 6.1 Em `CheckoutActivity` (Venda Direta)

**Request Code:** `1001`

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == PAYMENT_REQUEST_CODE) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val status = data.getStringExtra("status")
            val method = data.getStringExtra("method") ?: "PIX"
            if (status == "APPROVED") {
                token?.let {
                    viewModel.finishSale(it, mapToApiMethod(method), sessionId ?: "", operatorId, operatorName)
                }
            } else {
                Toast.makeText(this, "Pagamento não aprovado: $status", Toast.LENGTH_LONG).show()
            }
        }
    }
}

private fun mapToApiMethod(method: String): String {
    return when {
        method.contains("CREDIT", true) || method.contains("CREDITO", true) -> "CREDITO"
        method.contains("DEBIT",  true) || method.contains("DEBITO",  true) -> "DEBITO"
        method.contains("CASH",   true) || method.contains("DINHEIRO", true) -> "DINHEIRO"
        else -> "PIX"
    }
}
```

### 6.2 Em `TableCheckoutBottomSheet` (Mesa)

**Request Code:** `1002`

```kotlin
override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == 1002) {
        if (resultCode == Activity.RESULT_OK && data != null) {
            val status    = data.getStringExtra("status")
            val methodStr = data.getStringExtra("method") ?: "PIX"
            if ("APPROVED" == status) {
                val method = PaymentMethod.fromString(methodStr)
                viewModel.finalizePayment(method) // registra na API e atualiza estado local
            } else {
                Toast.makeText(context, "Pagamento não aprovado: $status", Toast.LENGTH_LONG).show()
            }
        } else if (resultCode == Activity.RESULT_CANCELED) {
            val message = data?.getStringExtra("message") ?: "Cancelado"
            Toast.makeText(context, "Pagamento Cancelado/Erro: $message", Toast.LENGTH_LONG).show()
        }
    }
}
```

---

## 7. Pós-Pagamento: Registro na API

Após o callback ser APPROVED, a lógica de pós-pagamento diverge conforme o contexto.

### 7.1 Venda Direta → `DirectCheckoutViewModel.finishSale()`

Registra a venda no servidor via `POST /api/vendas`.

### 7.2 Mesa → `CheckoutViewModel.finalizePayment(method)`

Executa **três etapas sequenciais**:

```
1. POST /api/vendas           → registra a venda no sistema fiscal/estoque
2. POST /api/comanda (action=add_pagamento) → registra pagamento parcial na comanda
3. [se saldo zerado] POST /api/comanda (action=fechar) → fecha a comanda/mesa
```

O método de pagamento é mapeado para o formato da API da comanda:

```kotlin
paymentForm = when (method) {
    PaymentMethod.CASH  -> "DINHEIRO"
    PaymentMethod.PIX   -> "PIX_TRANSFERENCIA"
    else                -> "CARTAO"
}
```

---

## 8. Métodos de Pagamento Suportados

Definidos em `Constants.kt`:

```kotlin
enum class PaymentMethod(val apiValue: String) {
    CREDIT("CREDITO"),
    DEBIT("DEBITO"),
    CASH("DINHEIRO"),
    PIX("PIX");

    companion object {
        // Resolve tanto "PIX" quanto "PIX_TRANSFERENCIA", "CREDIT", "CREDITO", etc.
        fun fromString(value: String?): PaymentMethod {
            return entries.find {
                it.name.equals(value, ignoreCase = true) ||
                it.apiValue.equals(value, ignoreCase = true)
            } ?: PIX // default: PIX
        }
    }
}
```

---

## 9. Tratamento de Erros

| Situação | Comportamento |
|---|---|
| App de pagamento não instalado | `ActivityNotFoundException` capturada → Toast de aviso → `RESULT_CANCELED` |
| Callback com `status` ≠ `APPROVED` | `RESULT_CANCELED` → Toast com mensagem de erro exibido ao usuário |
| `isWaitingForCallback = true` ao receber nova intent | Ignora o disparo duplicado do deeplink |
| `isTaskRoot = true` no callback | Reconstrói a pilha de atividades, navegando para a tela correta |

---

## 10. Diagrama Completo de Arquivos Envolvidos

```
android/app/src/main/
├── AndroidManifest.xml
│   └── PaymentHandlerActivity (exported=true, launchMode=singleTop)
│       └── intent-filter: smartpos://payment_callback
│
└── java/com/smartpos/pdv/
    ├── ui/sale/
    │   ├── CheckoutActivity.kt          → inicia pagamento (req code 1001)
    │   ├── TableCheckoutBottomSheet.kt  → inicia pagamento (req code 1002)
    │   ├── PaymentHandlerActivity.kt    → dispara deeplink + processa callback
    │   └── CheckoutViewModel.kt         → finalizePayment() → API calls
    └── utils/
        ├── Constants.kt                 → enum PaymentMethod, PaymentStatus
        └── CurrencyManager.kt           → conversão de moeda antes de enviar o amount
```

---

## 11. Pontos de Atenção para Implementação / Melhorias Futuras

> ⚠️ **`merchant_id` hardcoded:** O valor `"merchant123"` deve ser substituído por um identificador real vindo das configurações do estabelecimento (ex.: SharedPreferences ou resposta de API de autenticação).

> ⚠️ **`order_id` fixo em `"0"`:** Ambos os pontos de entrada enviam `EXTRA_ORDER_ID = "0"`. Para rastreabilidade e conciliação financeira, este campo deve ter o ID real da venda ou comanda.

> ℹ️ **`EXTRA_DESCRIPTION` não utilizado pelos chamadores:** Nenhum dos dois pontos de entrada (`CheckoutActivity`, `TableCheckoutBottomSheet`) popula o `EXTRA_DESCRIPTION`. O app de pagamento sempre receberá `description=Payment` (valor padrão).

> ℹ️ **`startActivityForResult` deprecated:** O Android moderno recomenda a `Activity Result API` (`registerForActivityResult`). A implementação atual usa a API legada, funcional mas candidata a refatoração.

> ℹ️ **Verificação do app de pagamento:** O `try/catch` em `startActivity` captura a exceção genericamente. Para melhor UX, pode-se verificar previamente a existência do app usando `packageManager.resolveActivity()` ou declarar o `<queries>` adequado no Manifest.
