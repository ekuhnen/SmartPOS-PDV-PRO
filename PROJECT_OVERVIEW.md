# SmartPOS PDV Pro — Visão Geral, Arquitetura e Fluxos do Sistema

> **Finalidade deste documento:** Servir como contexto completo e estruturado para Large Language Models (LLMs), desenvolvedores e arquitetos, detalhando a finalidade do projeto, arquitetura nativa Android, módulos, integrações de hardware, APIs e fluxos funcionais.

---

## 1. Visão Geral do Projeto

O **SmartPOS PDV Pro** é um aplicativo de **Ponto de Venda (PDV)** nativo Android (desenvolvido em **Kotlin**), projetado para execução em maquininhas **SmartPOS** (Sunmi, Gertec, Dspread, Kozen, Urovo, etc.) e **Tablets Android**.

### Segmentos Atendidos
1. **Varejo (Retail):** Venda rápida, leitor de código de barras/QR Code, emissão rápida de comprovante e controle de caixa.
2. **Alimentação (Food Service):** Gestão completa de mesas e comandas, lançamento e cancelamento de itens por mesa, transferência de conta, impressão em cozinha e fechamento dividido.

### Diferenciais Técnicos & Negócio
- **Motor Bi-Monetário & Câmbio Multi-Moeda:** Suporte nativo e simultâneo a **BRL, PYG (Guaraníes), ARS, USD e EUR**, aplicando taxas de câmbio em tempo real com regras específicas de arredondamento (como moedas sem casas decimais para PYG).
- **Abstração Hardware-Agnóstica (Multi-Printer HAL):** Detecção automática de marca/modelo do terminal SmartPOS no boot, chaveando dinamicamente entre impressoras térmicas (Sunmi AIDL, ESC/POS, Dspread SDK, Kozen SDK, Gertec SDK).
- **Integração de Pagamento via Deeplink:** Comunicação bidirecional com aplicativos parceiros de pagamento (ex: Plug Pay / TEF) por meio de esquemas customizados de URI (`smartpos://payment_callback`).
- **Segurança & Kill-Switch Remoto:** Sistema triplo de revogação de acesso (Validação Backend 403 + Supabase Realtime WebSocket + FCM Push silencioso) que desloga e limpa credenciais em < 1s em caso de bloqueio.

---

## 2. Arquitetura Técnica e Tecnologias

O projeto adota o padrão recomendado pela Google: **Clean Architecture** alinhada a **MVVM (Model-View-ViewModel)**.

```
┌───────────────────────────────────────────────────────────────────┐
│                          CAMADA DE UI                             │
│       Activities, Fragments, BottomSheets, ViewModels, Adapters   │
├───────────────────────────────────────────────────────────────────┤
│                        CAMADA DE DOMÍNIO                          │
│            UseCases, Business Models, Enums, Interfaces           │
├───────────────────────────────────────────────────────────────────┤
│                         CAMADA DE DADOS                           │
│   Room Local DB, Retrofit API Services, Hardware Controllers HAL   │
└───────────────────────────────────────────────────────────────────┘
```

### Tech Stack
- **Linguagem:** Kotlin 1.9+ (com Coroutines e `StateFlow`/`SharedFlow`)
- **Android Target SDK:** 34+ (Android 14)
- **Injeção de Dependência:** Dagger Hilt
- **Persistência Local (Offline-first):** Room Database (`AppDatabase`)
- **Rede & HTTP:** Retrofit 2, OkHttp 3/4 (com Interceptors para Headers e Bloqueios)
- **BaaS / Backend:** Supabase (Auth, PostgreSQL, Edge Functions em Deno, Realtime WebSockets) e Firebase Cloud Messaging (FCM)
- **Comunicação Inter-Processos (IPC):** Android AIDL Services (Sunmi `IWoyouService`)

---

## 3. Estrutura de Módulos do Aplicativo

A aplicação está organizada no pacote principal `com.plugpdv.pdv`:

### 3.1. Autenticação & Dispositivo (`ui/auth`, `api`, `service`, `utils`)
- **`LoginActivity` & `AuthViewModel`:** Autenticação via e-mail/senha junto ao Supabase Auth, salvando token JWT e informações do operador/caixa.
- **`DeviceIdProvider`:** Gerenciador de UUID único do hardware, persistido de forma segura e enviado no cabeçalho `X-Device-Id`.
- **`BlockResponseInterceptor`:** Interceptor HTTP Retrofit que captura respostas `403 DEVICE_BLOCKED` ou `USER_BLOCKED` e aciona o logout imediato.

### 3.2. Gestão de Caixa & Sessões (`ui/cashier`)
- **`CashierActivity` & `CashierViewModel`:** Controle do ciclo de vida da sessão de caixa (Abertura com valor inicial, Sangria, Suprimento e Fechamento com resumo por forma de pagamento).
- **Validação de Sessão:** Nenhuma venda ou abertura de mesa pode ser concluída sem uma sessão de caixa aberta (`sessionId`).

### 3.3. Venda Direta & Carrinho (`ui/sale`)
- **`DirectSaleActivity` & `VendaRapidaFragment`:** Interface principal para varejo. Exibe catálogo em grade/lista, busca por nome/código e leitor de código de barras.
- **`CartBottomSheet` & `CartAdapter`:** Gerenciamento do carrinho de compras em tempo real, permitindo alteração de quantidade, remoção e inclusão de observações.
- **`DirectCheckoutViewModel` / `CheckoutViewModel`:** Lógica de fechamento de carrinho rápido.

### 3.4. Gestão de Mesas e Comandas (`ui/sale`)
- **`MesaFragment` & `ComandaFragment`:** Visão em grade de mesas/comandas indicando status (Livre, Ocupada, Aguardando Pagamento).
- **`TableOrderActivity` & `CommandOrderActivity`:** Tela de detalhamento da mesa/comanda. Permite incluir itens por categoria, solicitar impressão parcial de conta e lançar pedidos diretamente para a cozinha.
- **`TableCheckoutBottomSheet`:** Modal avançado de fechamento de mesa, permitindo divisão de pagamentos (split), aplicação de taxa de serviço (propina) e conversão de moeda na hora.

### 3.5. Pagamento & Checkout (`ui/sale`, `PaymentHandlerActivity`)
- **`PaymentHandlerActivity`:** Activity centralizadora de transações TEF/Cartão. Monta a URI Deeplink para o app de pagamento (Plug Pay), aguarda o callback com os dados da transação (NSU, Autorização, Bandeira, Parcelas) e retorna o resultado.
- **`PaymentMethodSelectorBottomSheet`:** Seletor de forma de pagamento (Dinheiro, Cartão Crédito/Débito, Pix, QR Code).
- **`ServiceFeeOverrideBottomSheet`:** Ajuste fino da taxa de serviço (0%, 10%, valor fixo ou isenção).
- **`FacturaElectronicaDialog`:** Coleta de dados do cliente (RUC/CPF, Razão Social) para emissão fiscal.

### 3.6. Histórico & Painel do Operador (`ui/dashboard`)
- **`OperatorDashboardActivity` & `OperatorDashboardViewModel`:** Consulta de vendas realizadas no dia/turno, reimpressão de comprovantes e visualização dos totais por forma de pagamento.

### 3.7. Camada de Abstração de Hardware / Impressão (`hardware/`, `printer/`)
- **`HardwareFactory`:** Identifica o modelo do dispositivo (`Build.MODEL`, `Build.MANUFACTURER`) e instancia o driver correto de impressão.
- **`Printer` (Interface):** Contrato único para todos os fabricantes.
  - Implementações: `SunmiPrinter`, `DspreadPrinter`, `GertecPrinter`, `KozenPrinter`, `DejavooPrinter`.
- **`Scanner` (Interface) & `SunmiScanner`:** Integração com o leitor de código de barras acoplado ou câmera.
- **`PrinterUtil` & `ESCUtil`:** Utilitários para formatar textos, tabelas, logotipos e enviar comandos ESC/POS para impressoras térmicas.

### 3.8. Utilitários & Core Services (`utils/`, `di/`)
- **`CurrencyManager`:** Singleton responsável pelas taxas de câmbio atualizadas, conversão entre moedas e formatação visual conforme localização.
- **`ServiceFeeManager`:** Gerencia regras de cálculo de taxas adicionais configuradas pela empresa.
- **`KillSwitchManager` & `DeviceGuardService`:** Mantém conexão WebSocket ativa com Supabase Realtime e escuta notificações FCM para acionar o deslogamento forçado instantâneo em caso de revogação.
- **`DatabaseModule` & `NetworkModule`:** Módulos de injeção Dagger Hilt para Room DB e Retrofit/OkHttp.

---

## 4. Fluxos Detalhados do Sistema

### Fluxo 1: Autenticação & Inicialização
```
[Operador abre o App]
       │
       ├─► DeviceIdProvider gera/recupera X-Device-Id único
       │
       ▼
[Tela de Login] ──► Digita credenciais ──► POST /auth-login
       │
       ├─► Recebe JWT, Permissões de Módulo e Dados do Caixa
       ├─► Armazena Token e Perfil no SharedPreferences
       ├─► Inicializa HardwareFactory (Detecta Impressora e Leitor)
       └─► Conecta DeviceGuardService ao Supabase Realtime (Kill-Switch)
```

---

### Fluxo 2: Venda Direta (Varejo)
```
[VendaRapidaFragment]
       │  (Adiciona produtos ao carrinho via clique ou Scanner)
       ▼
[CartBottomSheet] ──► Confirma Itens e Quantidades
       │
       ▼
[PaymentMethodSelectorBottomSheet] ──► Escolhe Forma de Pagamento
       │
       ├─► Se DINHEIRO: Calcula troco (suporta moeda estrangeira)
       │                 └─► Envia venda POST /api-vendas ──► Imprime Recibo
       │
       └─► Se CARTÃO/TEF: Chama PaymentHandlerActivity (Ver Fluxo 4)
```

---

### Fluxo 3: Gestão de Mesas e Comandas (Food Service)
```
[MesaFragment / ComandaFragment]
       │
       ├─► [Nova Mesa/Comanda] ──► POST /api-comandas (action: abrir)
       │
       ├─► [Lançar Itens] ──► Seleciona Produtos ──► POST /api-comandas (action: adicionar_itens)
       │                        └─► Opcional: Imprime Comanda na Cozinha
       │
       ├─► [Imprimir Parcial] ──► Imprime Pré-Conta no Terminal
       │
       └─► [Fechar Mesa] ──► Abre TableCheckoutBottomSheet
                                 │
                                 ├─► Seleciona Divisão de Conta (Split)
                                 ├─► Aplica/Ajusta Propina (Service Fee)
                                 └─► Inicia Pagamento (Ver Fluxo 4)
```

---

### Fluxo 4: Processamento de Pagamento via Deeplink (Plug Pay / TEF)
```
[CheckoutActivity / TableCheckoutBottomSheet]
       │
       │  1. Inicia PaymentHandlerActivity com EXTRA_AMOUNT e EXTRA_ORDER_ID
       ▼
[PaymentHandlerActivity]
       │
       │  2. Dispara Intent (ACTION_VIEW) com Deeplink Outgoing:
       │     smartpos://payment?amount=150000&currency=PYG&callback_url=...
       ▼
[App Externo de Pagamento (ex: Plug Pay)]
       │
       │  3. Processa cartão na máquina (Chip/Contactless/Senha)
       │  4. Retorna resposta via Callback Deeplink:
       │     smartpos://payment_callback?status=APPROVED&nsu=123456&auth_code=789
       ▼
[PaymentHandlerActivity.onNewIntent()]
       │
       │  5. Valida retorno (APPROVED / REJECTED)
       │  6. Finaliza com setResult(RESULT_OK)
       ▼
[CheckoutActivity / TableCheckoutBottomSheet]
       │
       │  7. Registra a venda concluída na API backend (POST /api-vendas)
       └─► 8. Dispara impressão do recibo de venda na impressora térmica local
```

---

### Fluxo 5: Bloqueio Remoto em Tempo Real (Kill-Switch)
```
[Proprietário revoga dispositivo no Painel Web]
       │
       ├────────────────────────────────────────┬────────────────────────────────────────┐
       ▼                                        ▼                                        ▼
[1. Resposta da API 403]               [2. Supabase Realtime]                   [3. Push FCM Silencioso]
(Ocorre na próxima requisição)         (Evento no WebSocket em <1s)             (Chega com App em segundo plano)
       │                                        │                                        │
       └────────────────────────────────────────┴────────────────────────────────────────┘
                                                │
                                                ▼
                                   [ForceLogoutBus.emit()]
                                                │
                                                ▼
                                    - Cancela Sessão e Limpa Tokens
                                    - Exibe Alerta: "Dispositivo Bloqueado"
                                    - Redireciona para LoginActivity
```

---

## 5. Esquema de Dados e APIs de Comunicação

### 5.1. Banco de Dados Local (Room DB)
- **`LocalSaleEntity`:** Armazena vendas offline pendentes de sincronização.
- **`CatalogInfo` / `CatalogItem`:** Cache local do catálogo de produtos e categorias para funcionamento offline.
- **`TaxEntity`:** Tabela de alíquotas fiscais (IVA 10%, IVA 5%, Isento).

### 5.2. Principais Endpoints (Supabase Edge Functions)
| Endpoint | Método | Descrição |
|---|---|---|
| `/auth-login` | `POST` | Autenticação do operador/caixa e emissão do Bearer JWT. |
| `/api-catalogs` | `GET` | Recuperação do catálogo completo de produtos, preços e estoques. |
| `/api-vendas` | `POST` | Registro oficial da venda concluída com pagamentos e impostos. |
| `/api-comandas` | `GET / POST` | Endpoint action-based (`action: abrir`, `adicionar_itens`, `fechar`, `transferir`) para controle de mesas. |
| `/api-caixas` | `GET / POST` | Abertura, suprimento, sangria e fechamento da sessão de caixa. |

---

## 6. Considerações para LLMs e Desenvolvimento Futuro

1. **Moedas e Casas Decimais:** Atentar sempre ao uso do `CurrencyManager`. Moedas como **PYG (Guaraní)** não possuem casas decimais e são tratadas como números inteiros, enquanto **BRL** e **USD** exigem duas casas decimais.
2. **Impressão:** Sempre verificar se o hardware possui suporte a AIDL (Sunmi) antes de tentar bind no serviço, prevendo fallback gracioso para ESC/POS ou logs em caso de ausência de impressora física.
3. **Gerenciamento de Estado:** Toda alteração de UI em telas complexas (como `TableOrderActivity` e `CheckoutActivity`) deve passar pelos respetivos `ViewModels` expondo `StateFlow` imutáveis.
