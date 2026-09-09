# PAYMENT-01B — Autoridade de providers de pagamento no backend

Status: **DEPLOYED E AUDITADO em 2026-09-09**  
Branch Android: `feature/payment-providers-cielo`  
Dependência Android: `PAYMENT-01A` (`1dcf9e0`)

## 1. Superfície real implantada

A superfície pública de capabilities do terminal é:

```http
POST /terminal-sync
Authorization: Bearer <token>
X-Device-Id: <device-id>
X-Api-Version: <version>
X-App-Version: <version>
Content-Type: application/json

{"action":"capabilities"}
```

Com base URL Supabase:

```text
https://<project>.supabase.co/functions/v1/
```

**Não existe** `GET /api/v2/terminal/capabilities`. Uma chamada a esse path foi
verificada em produção e retorna 404 `NOT_FOUND`.

Cadeia de autoridade auditada:

```text
Android POST /functions/v1/terminal-sync
  body {"action":"capabilities"}
        ↓
supabase/functions/terminal-sync/index.ts
        ↓
autenticação + getClaims
        ↓
company_users.owner_user_id (fallback para o próprio usuário)
        ↓
enforceDeviceGuard + enforceVersionGate
        ↓
db.rpc("terminal_capabilities", { p_owner: ownerId, p_device: deviceId })
        ↓
public.terminal_capabilities(...)
        ↓
public.company_payment_provider_policy(owner)
```

O terminal nunca envia `owner_id` para escolher a empresa.

## 2. Objetivo

A escolha de integração de pagamento é política da empresa no backend, não regra
autorada no APK.

Providers inicialmente conhecidos pelo APK:

- `PLUGPAY`
- `CIELO_TAP`

O wire permanece extensível para providers futuros. O banco usa `text`, não ENUM
fechado.

A política informa:

- providers autorizados;
- moedas autorizadas por provider;
- provider padrão;
- se o operador pode escolher entre providers.

O Android é responsável apenas por compor essa autorização com capacidade de
execução do dispositivo (NFC, Android compatível, SDK pronto etc.).

## 3. Persistência implantada

### `company_payment_settings`

```text
owner_user_id              PK
default_provider           nullable
allow_operator_selection   boolean
created_at
updated_at
```

### `company_payment_providers`

```text
owner_user_id
provider                   text normalizado uppercase
enabled                    boolean
supported_currencies       text[] nullable
created_at
updated_at
PK (owner_user_id, provider)
```

A implementação é aditiva. Providers como `STONE`, `REDE_TAP`, `PAGBANK` podem
ser armazenados sem migration de ENUM.

## 4. Extensão de capabilities

Quando existe política explícita, `terminal_capabilities` adiciona ao payload
legado:

```json
{
  "payment_providers": [
    {
      "provider": "PLUGPAY",
      "enabled": true
    },
    {
      "provider": "CIELO_TAP",
      "enabled": true,
      "supported_currencies": ["BRL"]
    }
  ],
  "default_payment_provider": "CIELO_TAP",
  "allow_payment_provider_selection": true
}
```

Os campos existentes de capabilities permanecem inalterados.

## 5. Semântica normativa: legacy vs configuração explícita

### 5.1 Empresa legacy — sem linha em `company_payment_settings`

Os três campos novos são **omitidos**:

```text
payment_providers
unset

default_payment_provider
unset

allow_payment_provider_selection
unset
```

Isso significa que a empresa ainda não migrou para autoridade multiprovider.
O Android mantém compatibilidade PlugPay legado.

### 5.2 Política explícita sem provider habilitado

```json
{
  "payment_providers": [],
  "default_payment_provider": null,
  "allow_payment_provider_selection": false
}
```

Lista vazia é autoridade explícita e **não** autoriza fallback local para
PlugPay.

Essa distinção é obrigatória.

## 6. Providers publicados

Somente providers habilitados são publicados ao terminal.

Um provider desabilitado no painel administrativo não precisa ser conhecido pelo
PDV. Portanto, a ausência de um provider na lista explícita significa que ele
não está autorizado.

O campo `enabled` é preservado por extensibilidade e compatibilidade do contrato.

## 7. Moedas

`provider.supported_currencies` possui estas semânticas:

- campo ausente / `null`: sem restrição adicional publicada pelo provider;
- lista explícita: somente aquelas moedas são autorizadas;
- lista vazia: nenhuma moeda autorizada.

A lista publicada é intersectada com as moedas habilitadas para a empresa.

Não existe conversão automática para tornar um provider elegível. Exemplo:
Cielo autorizada apenas para BRL não pode processar venda PYG por conversão
silenciosa.

## 8. Provider padrão

`default_payment_provider` só é publicado como valor quando o provider existe na
política e está habilitado.

Configuração inconsistente produz `null` e warning de observabilidade. Gravação
administrativa inválida é recusada com `INVALID_DEFAULT_PROVIDER`.

Nenhum provider substituto é escolhido silenciosamente.

## 9. Escolha do operador

`allow_payment_provider_selection: true` autoriza o Android a apresentar escolha
quando houver mais de um provider elegível para a transação.

A elegibilidade final ainda precisa combinar:

```text
política da empresa
+ moeda da transação
+ suporte do APK
+ capacidade do dispositivo
+ disponibilidade do provider
```

## 10. Tenant e segurança

O tenant é derivado do token autenticado no backend.

RLS e funções de administração preservam isolamento tenant-scoped. Empresa A não
pode ler política da Empresa B.

Capabilities contém somente política. Nunca contém:

- client secret;
- API secret;
- access token;
- senha;
- private key;
- credencial PlugPay/Cielo;
- taxa de plataforma.

Credenciais de adquirentes permanecem fora desta superfície.

## 11. Administração

O painel administrativo possui configuração de pagamentos por empresa com:

- habilitar/desabilitar provider;
- moedas permitidas;
- provider padrão;
- permitir escolha pelo operador.

Configuração com default inválido não pode ser salva.

## 12. Compatibilidade

Nenhuma empresa existente foi migrada automaticamente. Sem política explícita,
o payload continua legacy e o Android mantém PlugPay.

Nenhuma regra de checkout, comanda, caixa, pagamento parcial, idempotência,
UNKNOWN ou reconciliação foi alterada pelo PAYMENT-01B.

## 13. Evidência de auditoria

Foram executados testes reais autenticados contra a superfície implantada:

```text
GET /functions/v1/api/v2/terminal/capabilities
→ 404 NOT_FOUND

POST /functions/v1/terminal-sync
{"action":"capabilities"}
→ 200
```

Empresa legacy retornou o payload normal sem os três campos novos.

Empresa temporária configurada com PlugPay + Cielo Tap/BRL retornou:

```json
{
  "payment_providers": [
    {"provider":"PLUGPAY","enabled":true},
    {"provider":"CIELO_TAP","enabled":true,"supported_currencies":["BRL"]}
  ],
  "default_payment_provider":"CIELO_TAP",
  "allow_payment_provider_selection":true
}
```

Os dados temporários foram removidos e nova consulta confirmou retorno ao estado
legacy.

A suíte backend reportada para PAYMENT-01B passou 15/15 testes e o typecheck
reportou zero erros.

## 14. Android

O Android deve usar exatamente:

```kotlin
@POST("terminal-sync")
suspend fun getCapabilities(
    @Header("Authorization") token: String,
    @Body request: CapabilitiesRequest = CapabilitiesRequest()
): CapabilitiesResponse
```

com:

```kotlin
data class CapabilitiesRequest(
    @SerializedName("action") val action: String = "capabilities"
)
```

`AppHeadersInterceptor` continua responsável por `X-App-Version`,
`X-Api-Version` e `X-Device-Id`.

## 15. Próximo estágio

Com o transporte Android alinhado e validado, PAYMENT-01B está concluído.

O próximo estágio é `PAYMENT-02 — PaymentProvider + PaymentCoordinator`, ainda
sem adicionar o SDK Cielo: primeiro o fluxo PlugPay existente deve ser
encapsulado e passar por regressão sem mudança funcional.
