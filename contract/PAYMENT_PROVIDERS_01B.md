# PAYMENT-01B — Autoridade de providers de pagamento no backend

Status: **contrato alvo / ainda não declarado como deployed**  
Branch de integração: `feature/payment-providers-cielo`  
Dependência Android: `PAYMENT-01A` (`1dcf9e0`)  

> Este arquivo define o comportamento que o backend deve implementar antes de
> os novos campos serem incorporados ao `openapi-terminal-v2.yaml`, que descreve
> somente comportamento comprovadamente implantado. Não é uma declaração de
> que a Edge Function atual já implementa estas regras.

## 1. Objetivo

Transformar a escolha de integração de pagamento em **política da empresa**, e
não em regra autorada no APK.

O terminal deve descobrir pela API:

- quais providers de pagamento estão autorizados para a empresa;
- quais moedas cada provider pode processar;
- qual é o provider padrão;
- se o operador pode escolher entre mais de um provider.

O Android continua responsável apenas por verificar se consegue **executar** um
provider autorizado no dispositivo (SDK instalado/pronto, NFC, versão Android,
etc.). Autorização comercial/configuração de empresa é responsabilidade do
backend.

## 2. Invariantes

1. O tenant é derivado do token autenticado. O terminal **não envia `owner_id`**
   para escolher a configuração.
2. `capabilities` é a autoridade; cache local nunca cria uma autorização.
3. Credenciais, tokens, secrets, chaves privadas e material de autenticação de
   adquirente **nunca** aparecem em `capabilities`.
4. Provider desconhecido é permitido no wire e não deve quebrar clientes
   antigos.
5. Moeda é ISO-4217 em maiúsculas. Não existe conversão automática para tornar
   um provider elegível.
6. `payment_providers` ausente e `payment_providers: []` têm semânticas
   diferentes e essa diferença é normativa.
7. Configuração de provider não altera as regras de idempotência, persistência
   prévia, `UNKNOWN` ou reconciliação do pagamento.
8. O payload do terminal não pode expor taxa de plataforma.

## 3. Extensão aditiva de `GET /api/v2/terminal/capabilities`

O payload já existente permanece inalterado. O backend adiciona, quando houver
autoridade explícita para o tenant:

```json
{
  "payment_providers": [
    {
      "provider": "PLUGPAY",
      "enabled": true,
      "supported_currencies": ["BRL", "PYG"]
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

### 3.1 `payment_providers`

Tipo: array opcional de objetos.

Cada item:

```yaml
provider: string              # código opaco, ex. PLUGPAY, CIELO_TAP
enabled: boolean
supported_currencies:         # opcional
  - string                    # ISO-4217 uppercase
```

Semântica de `supported_currencies`:

- campo **ausente/null**: o provider não publica uma restrição adicional de
  moeda neste contrato; continuam valendo as moedas autorizadas da empresa e a
  validação do provider no backend;
- `[]`: provider explicitamente não está elegível para nenhuma moeda;
- `['BRL']`: elegível somente para BRL;
- a lista nunca autoriza uma moeda que não esteja autorizada em `currencies` da
  própria empresa.

O backend deve normalizar códigos de moeda para maiúsculas, remover duplicados
e rejeitar valores que não tenham formato ISO de 3 letras no caminho de
administração/configuração.

### 3.2 `default_payment_provider`

Tipo: string opcional/null.

Só pode apontar para um provider que:

- esteja presente em `payment_providers`;
- tenha `enabled = true`;
- seja conhecido pela configuração do backend.

Se a configuração persistida estiver inconsistente, o endpoint deve retornar
`null` neste campo, registrar a inconsistência para operação/suporte e **não
inventar outro default**.

A elegibilidade final por moeda continua sendo calculada no momento da
transação. Portanto um default pode deixar de ser elegível para uma moeda
específica; nesse caso o terminal não deve utilizá-lo para aquela cobrança.

### 3.3 `allow_payment_provider_selection`

Tipo: boolean opcional.

- `true`: a empresa permite que o operador escolha entre providers elegíveis;
- `false`/ausente: nenhuma permissão de escolha é concedida.

A UI só deve oferecer escolha quando houver pelo menos dois providers
executáveis e elegíveis para a moeda da transação.

## 4. Semântica de compatibilidade

### 4.1 Tenant ainda não migrado

Resposta mantém o contrato legado e **omite** os três novos campos:

```json
{
  "currencies": { "BRL": {} },
  "payment_methods": []
}
```

Para o APK que contém PAYMENT-01A isso significa:

```text
sem autoridade explícita de provider -> fallback legado PLUGPAY
```

Esse comportamento existe apenas para uma migração segura. O backend não deve
usar `[]` para representar tenant legado.

### 4.2 Tenant explicitamente configurado sem provider

```json
{
  "payment_providers": [],
  "default_payment_provider": null,
  "allow_payment_provider_selection": false
}
```

Semântica:

```text
autoridade explícita -> nenhum provider autorizado
```

O terminal não pode reativar PlugPay localmente.

### 4.3 Tenant PlugPay explícito

```json
{
  "payment_providers": [
    {
      "provider": "PLUGPAY",
      "enabled": true,
      "supported_currencies": ["BRL", "PYG"]
    }
  ],
  "default_payment_provider": "PLUGPAY",
  "allow_payment_provider_selection": false
}
```

### 4.4 Tenant Cielo Tap explícito

Exemplo inicial para a operação brasileira; a lista real deve refletir a
homologação/acordo do tenant, nunca uma constante no APK:

```json
{
  "payment_providers": [
    {
      "provider": "CIELO_TAP",
      "enabled": true,
      "supported_currencies": ["BRL"]
    }
  ],
  "default_payment_provider": "CIELO_TAP",
  "allow_payment_provider_selection": false
}
```

### 4.5 Ambos

```json
{
  "payment_providers": [
    {
      "provider": "PLUGPAY",
      "enabled": true,
      "supported_currencies": ["BRL", "PYG"]
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

Resultado esperado:

```text
BRL -> PLUGPAY + CIELO_TAP
PYG -> PLUGPAY
```

Não há conversão PYG -> BRL para tornar Cielo elegível.

## 5. Persistência de referência

Os nomes físicos podem ser adaptados ao backend existente, mas a separação de
responsabilidades abaixo é normativa.

### 5.1 Configuração global do tenant

```sql
create table company_payment_settings (
    owner_id uuid primary key,
    default_provider text null,
    allow_operator_selection boolean not null default false,
    updated_at timestamptz not null default now()
);
```

### 5.2 Providers autorizados

```sql
create table company_payment_providers (
    owner_id uuid not null,
    provider text not null,
    enabled boolean not null default false,
    supported_currencies text[] null,
    updated_at timestamptz not null default now(),
    primary key (owner_id, provider)
);
```

Regras de escrita:

- `provider` normalizado em maiúsculas;
- `supported_currencies` normalizada para códigos ISO em maiúsculas;
- a administração não deve aceitar `default_provider` apontando para provider
  inexistente/desabilitado;
- uma alteração de tenant jamais pode afetar registros de outro tenant.

### 5.3 Credenciais

Credenciais do provider ficam em storage de segredo/backend apropriado. Se a
base precisar relacioná-las, persista somente uma referência opaca, por
exemplo `credential_ref`, em uma tabela não exposta à API do terminal.

É proibido retornar no payload do terminal campos como:

```text
client_secret
access_token
private_key
merchant_secret
api_secret
credential
password
```

Um identificador público exigido pelo SDK no dispositivo só pode ser exposto
quando a documentação oficial do provider confirmar que ele não é segredo e
quando houver necessidade real no contrato de inicialização do SDK. Isso será
tratado na etapa de integração do SDK, não neste contrato.

## 6. Resolução no endpoint

Pseudofluxo:

```text
1. autenticar Bearer token
2. resolver owner/tenant canônico
3. aplicar os mesmos gates de usuário/dispositivo/versão do terminal
4. carregar currencies e demais capabilities existentes
5. verificar se existe configuração EXPLÍCITA de payment provider para owner

   não existe:
      omitir payment_providers
      omitir default_payment_provider
      omitir allow_payment_provider_selection

   existe:
      carregar providers apenas do owner autenticado
      normalizar payload
      validar/sanitizar default
      retornar os três campos, inclusive payment_providers=[] quando vazio

6. retornar payload sem qualquer segredo
```

A presença de `company_payment_settings` pode ser usada como marcador de
migração explícita do tenant. Outra representação é aceitável desde que seja
possível distinguir sem ambiguidade:

```text
TENANT_LEGACY != TENANT_EXPLICITAMENTE_SEM_PROVIDER
```

## 7. Autoridade de moeda

A escolha de provider é uma segunda restrição, nunca uma nova fonte de moedas.

Para uma transação em moeda `M`:

```text
company_allows(M)
AND provider.enabled
AND (
     provider.supported_currencies is null
     OR M in provider.supported_currencies
)
```

Se qualquer condição falhar, o provider não é elegível.

O backend que futuramente registrar intenção/resultado de pagamento deve
revalidar a mesma política; confiar somente no filtro do APK seria uma falha de
autorização.

## 8. Administração

A escrita dessa configuração é uma superfície de backoffice/admin, não uma
função do terminal.

O terminal:

- lê capabilities;
- não habilita provider;
- não troca default da empresa;
- não grava moedas suportadas;
- não recebe credenciais administrativas.

A futura tela administrativa deve permitir, por empresa:

```text
PLUGPAY       habilitado/desabilitado
CIELO_TAP     habilitado/desabilitado
provider padrão
permitir escolha pelo operador
moedas permitidas por provider
```

Toda mutação deve ser auditável (ator, tenant, antes/depois, timestamp do
servidor).

## 9. Rollout seguro

### Fase A — backend compatível

Implantar suporte aos novos campos, mas **omiti-los** para tenants ainda não
migrados.

Nenhum terminal existente muda de comportamento.

### Fase B — explicitar tenants PlugPay existentes

Criar configuração explícita por tenant preservando exatamente o que já
funciona hoje.

Exemplo:

```text
provider = PLUGPAY
enabled = true
default = PLUGPAY
```

Só depois de validar o retorno de capabilities o tenant deixa de depender do
fallback legado.

### Fase C — piloto Cielo

Habilitar `CIELO_TAP` somente nos tenants de homologação/piloto. Não habilitar
em massa antes do SDK e da reconciliação estarem prontos.

### Fase D — ambos

Liberar escolha/default por tenant depois que `PaymentCoordinator` e os dois
adapters estiverem homologados.

### Fase E — remoção futura do fallback

Somente quando 100% dos tenants ativos possuírem configuração explícita poderá
ser planejada uma versão futura que remova o fallback legado PlugPay.

PAYMENT-01B **não remove esse fallback**.

## 10. Testes obrigatórios do backend

1. **legacy**: tenant sem configuração -> campos de provider ausentes.
2. **explicit-empty**: tenant migrado sem providers -> `payment_providers: []`.
3. **plugpay-only**: somente PLUGPAY habilitado e default válido.
4. **cielo-only**: somente CIELO_TAP habilitado e default válido.
5. **both**: ambos habilitados, seleção conforme configuração.
6. **currency-filter**: `CIELO_TAP=[BRL]` não fica elegível para PYG.
7. **no-fx**: backend não converte moeda para satisfazer provider.
8. **invalid-default**: configuração inconsistente retorna default null e gera
   observabilidade; não inventa fallback.
9. **unknown-provider**: código novo pode aparecer no wire sem alterar os
   campos antigos do payload.
10. **tenant-isolation**: token do tenant A nunca lê configuração do tenant B.
11. **no-secrets**: snapshot/JSON do endpoint não contém material secreto.
12. **legacy-payload**: moedas, meios de pagamento, taxas e offline policies
    existentes permanecem byte/semanticamente compatíveis fora dos campos
    aditivos.

## 11. Critério de pronto do PAYMENT-01B

PAYMENT-01B só pode ser marcado como concluído quando:

- [ ] persistência tenant-scoped de provider estiver implantada;
- [ ] `GET /api/v2/terminal/capabilities` produzir os três campos conforme este
      contrato;
- [ ] a distinção AUSENTE vs `[]` estiver testada;
- [ ] tenants legados continuarem operando com PlugPay;
- [ ] não houver segredo no payload;
- [ ] testes de isolamento por tenant passarem;
- [ ] um tenant de homologação retornar CIELO_TAP de forma explícita;
- [ ] o APK PAYMENT-01A consumir o payload real e manter os testes verdes;
- [ ] só então `openapi-terminal-v2.yaml` for atualizado para documentar o
      comportamento **deployed**.

## 12. Próxima etapa

Após o backend real satisfazer este contrato:

```text
PAYMENT-02 — PaymentProvider boundary
  PaymentCoordinator
      -> PlugPayProvider
      -> CieloTapProvider (shell inicialmente, SDK depois)
```

A extração deve preservar a máquina de estados durável já existente em
`PaymentHandlerActivity`/Room. Nenhum adapter pode transformar resultado
indeterminado em recusa nem disparar um segundo pagamento automaticamente.
