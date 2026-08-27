# BACKLOG — Antigravity

Tudo do app Kotlin (SmartPOS PDV Pro). **Este é o único arquivo de trabalho do Antigravity.**

Nada aqui vai para o Lovable. A regra de fronteira: *mudar isso deveria exigir um novo APK? Sim → Antigravity.*

---

## §1 — Estado

```
✅ ONDA 0   implementada, compilando (assembleDebug)
🔶 RELEASE 1  não publicado ← ver §2.1. É o que dá sentido à onda 0
✅ ONDA 1   implementada, compilando (headers, idempotência, sync_batch, server_seq)
✅ ONDA 2   implementada, compilando (capabilities, DTOs v2, formatação sem Locale, teclado numérico)
⬜ ONDA 3   aguarda E5 e E6
⬜ ONDA 4   aguarda F1 e F5
⬜ ONDA 5   aguarda F6
```

---

## §2.1 — Antes da onda 1: o que a onda 0 ainda não provou

`BUILD SUCCESSFUL` diz que compila. Quatro coisas da onda 0 só falham em execução, e três delas não dá para testar em APK de debug.

**In-App Updates não funciona em build de debug.** O fluxo exige que o app tenha sido **instalado pela Play Store**. Sem publicar em faixa interna, o `AppUpdateManager` não devolve informação de atualização e o caminho inteiro fica sem verificação. Use *internal app sharing* ou a faixa de teste interno.

**Isso é o motivo de existir o Release 1.** A capacidade de forçar atualização precisa estar num APK **publicado** antes de você precisar dela. Hoje não dói porque ninguém opera. É a última vez que sai de graça — e publicar agora resolve o teste e a capacidade de uma vez.

**`launchMode="singleTask"` e o roteamento de callback** só se provam matando o processo. Teste real: inicie um pagamento, force o encerramento do app pelo gerenciador de tarefas enquanto o app de pagamento está em primeiro plano, e volte. A tentativa tem que ser recuperada do Room.

**A migração do Room para a versão 5.** Com base vazia é indolor, e é por isso que é o momento de confirmar que o caminho de migração existe e funciona — e não uma `fallbackToDestructiveMigration()` que vai apagar comanda aberta de terminal em produção mais adiante.

**A concorrência da outbox** — `Mutex` por comanda com paralelismo entre comandas. Prove com duas comandas sincronizando ao mesmo tempo e ordem embaralhada dentro de uma delas.

Checklist antes de seguir para a onda 1:

- [ ] Release 1 (v1.x) publicado em faixa interna
- [ ] In-App Updates verificado com app instalado pela Play Store
- [ ] Recuperação por Room verificada com o processo morto de verdade
- [ ] Migração do Room 4→5 confirmada como não destrutiva
- [ ] Outbox: ordem por comanda e paralelismo entre comandas, provados
- [ ] `426`, `403 device_not_registered`, `400`, `503` verificados contra o servidor **depois** que a E2 sair

---

## §2 — Context file

Coloque na raiz do projeto, como `AGENTS.md` ou o equivalente da ferramenta. Entra em todo pedido, para você parar de repetir os invariantes.

```
# SmartPOS PDV Pro — contexto e invariantes

App Android nativo (Kotlin) de PDV para terminais SmartPOS (Sunmi, Gertec,
Dspread, Kozen, Urovo) e tablets. Segmentos: varejo e food service (mesas,
comandas, KDS). Multipaís: BR e PY hoje, América Latina em expansão.
Multi-moeda: BRL, PYG, ARS, USD, EUR, e outras entrando.

Clean Architecture + MVVM. Kotlin 1.9+, Coroutines, StateFlow/SharedFlow.
Hilt, Room, Retrofit 2 + OkHttp. Supabase (Auth, Postgres, Edge Functions,
Realtime) e FCM. AIDL para impressora Sunmi.

O backend é desenvolvido separadamente. O contrato entre os dois está em
/contract — openapi-terminal-v2.yaml e os arquivos de spec. O CONTRATO É A
FONTE DE VERDADE, não a memória de como a API se comportava.

## INVARIANTES

1. DINHEIRO. Sempre inteiro em unidade mínima da moeda, sempre acompanhado do
   código da moeda. Nunca Float, nunca Double, nunca BigDecimal sem moeda.
   O app NUNCA calcula dinheiro como verdade — o servidor calcula e o app
   exibe. Única exceção: estimativa offline, que precisa aparecer MARCADA como
   estimativa na tela.

2. FORMATAÇÃO. Nunca formate por Locale do dispositivo nem do usuário.
   Separadores, símbolo, posição, casas decimais e arredondamento de caixa vêm
   de 'capabilities'. es-MX formata 1,250.50 e es-AR formata 1.250,50 —
   formatar pelo Locale faz o mesmo preço aparecer diferente para dois
   operadores.

3. NADA DE REGRA AUTORADA LOCALMENTE. Alíquota, regra de moeda, lista de meios
   de pagamento, política de offline, limites: tudo vem de 'capabilities' ou da
   API. Room é CACHE, nunca fonte. Se para adicionar um país for preciso
   alterar código Kotlin, o desenho está errado.

4. IDEMPOTÊNCIA. A chave é gerada no momento do TOQUE do operador, não no
   momento do envio. Gerar no envio faz o retry criar chave nova e a
   idempotência não serve para nada. Persista antes de agir.

5. ORDENAÇÃO. Por server_seq vindo do servidor. NUNCA por relógio do aparelho —
   terminal Android tem relógio errado. client_created_at é enviado apenas como
   metadado de diagnóstico.

6. ENUM DESCONHECIDO NÃO QUEBRA. Todo `when` sobre valor vindo do servidor tem
   `else` que degrada com elegância. Sem isso o servidor não pode adicionar
   valor de enum sem release de APK — que é o que o versionamento deveria
   evitar.

7. 'unknown' NUNCA É APRESENTADO COMO RECUSA. Estado de pagamento indeterminado
   mostrado como recusado faz o cliente pagar duas vezes.

8. NUNCA ACEITE UM TOQUE QUE VAI FALHAR. Operação não permitida offline tem
   botão desabilitado com o motivo visível. Aceitar o toque e falhar 20
   segundos depois, com o cliente esperando, é o pior resultado possível.

9. PERSISTA ANTES DE AGIR. Tentativa de pagamento gravada em Room antes de
   disparar o deeplink. Operação na outbox antes da chamada de rede.
   Recuperação sempre a partir do Room, nunca de estado em memória.

10. TEXTO DE ERRO vem de message_key traduzida localmente nas quatro locales
    (pt-BR, es, en, gn). O servidor nunca manda string pronta para exibir.

11. O APP NÃO VÊ TAXA DE PLATAFORMA. É calculada no servidor. Se aparecer em
    algum payload, é bug e precisa ser reportado. Não confundir com a propina
    do restaurante (ServiceFeeManager), que o operador ajusta no balcão — são
    coisas diferentes com nomes parecidos.

## O QUE JÁ ESTÁ CERTO E NÃO DEVE SER REFEITO

- HAL multi-impressora com detecção por Build.MODEL. Resolvido.
- Kill switch com tripla redundância (403 + Realtime + FCM). É referência de
  qualidade; use como modelo para o gate de versão.
- Clean Architecture com Hilt e StateFlow. Mantenha a separação de camadas.
```

---

## §3 — ONDA 0 · comece hoje

Nada aqui depende do Lovable. E há uma razão de cronograma para fazer primeiro, no §7.

```
Onda 0. Nenhuma mudança de contrato de API — tudo local ao app.

1. In-App Updates (com.google.android.play:app-update-ktx).
   FLEXIBLE quando clientVersionStalenessDays() estiver abaixo do limite,
   IMMEDIATE acima. Verifique isUpdateTypeAllowed() e tenha plano B quando a
   plataforma não permitir o tipo. Trate atualização já em andamento sem
   iniciar duas vezes.

2. FAMÍLIA DE ESTADOS DIRIGIDOS PELO SERVIDOR, tratada num só lugar do
   BlockResponseInterceptor. Ele já trata 403 DEVICE_BLOCKED; estenda:

     403 DEVICE_BLOCKED          já existe
     403 device_not_registered   "Este terminal não está registrado.
                                  Chame o suporte."
     400 device_id_required      não deveria acontecer — o app sempre manda.
                                  Se acontecer é bug: registre e reporte.
     426 upgrade_required        "Este terminal precisa ser atualizado" +
                                  In-App Updates do item 1
     503                         RETENTÁVEL. NÃO bloqueia. A operação vai para
                                  a outbox. É hiccup de banco, e o terminal
                                  precisa continuar operando.

   Todas as mensagens escritas para um GARÇOM entender: o que aconteceu e o
   que fazer. Nunca códigos nem números de versão crus.

3. OUTBOX GENERALIZADA. Hoje LocalSaleEntity cobre venda offline. Crie uma
   abstração de operação pendente que cubra também operações de comanda:
   - ordem preservada POR COMANDA (comandas diferentes sincronizam em
     paralelo; a mesma comanda, não)
   - backoff exponencial com teto
   - alerta VISÍVEL ao operador quando a fila passar de 20 itens ou 5 minutos
     de atraso. Nada de retry silencioso eterno.
   Ainda não implemente envio em lote — depende do contrato novo.

4. TENTATIVA DE PAGAMENTO EM ROOM: reference, idempotency_key, nonce,
   amount (Long, unidade mínima), currency, status, started_at.
   Gravada ANTES de disparar o deeplink.

5. PaymentHandlerActivity: launchMode que sobreviva ao retorno do app de
   pagamento, e recuperação de estado a partir do Room — NUNCA de variável em
   memória. A Activity pode ser destruída enquanto o app de pagamento está em
   primeiro plano; assuma que vai acontecer.

6. TELA DE "PAGAMENTO NÃO DETERMINADO", ainda sem a lógica de consulta:
   - texto: "Não conseguimos confirmar este pagamento". Não use "erro",
     "falhou" nem "recusado"
   - aviso destacado: "O cartão pode ter sido cobrado. Consulte antes de
     tentar novamente."
   - três ações: Consultar de novo · Registrar como pendente e resolver depois ·
     Ver comprovante do app de pagamento
   - a mesa PERMANECE aberta com o pagamento marcado como pendente
   - sem spinner indefinido em nenhum caminho

7. CurrencyManager e TaxEntity recebem suas regras de uma fonte INJETADA em vez
   de constantes. NÃO mude a origem ainda — continue alimentando com os valores
   atuais. O objetivo é que trocar para 'capabilities' na onda 2 seja mudança
   de um provider, não um refactor.

8. MEDIÇÃO: instrumente o intervalo entre a criação da venda e a inserção do
   último item, em milissegundos, especialmente no descarregamento da outbox
   após período offline. O backend hoje recusa item inserido mais de 60s depois
   da venda; preciso saber se a sincronização offline ultrapassa isso. Ver §8.
```

**Ao final: Release 1, v1.x.** Ver §7.

---

## §3.1 — Previsão sobre a janela de 60s

O item 8 da onda 0 já está medindo. Antes do número, uma previsão:

**O backoff da outbox tem teto de 60 segundos.** Uma retentativa no limite consome a janela inteira do servidor; duas ou três, com rede de restaurante, ultrapassam com folga. É provável que o log mostre `EXCEEDED_60S_LIMIT` no descarregamento após período offline — o cenário do piloto.

A correção não é aumentar a janela dos dois lados. É **criação de venda e itens chegarem como operação atômica**: no `sync_batch` da E3, o lote de uma venda processado em uma transação. Aí não existe intervalo. Combine isso com o Lovable quando a E3 for desenhada — está no `BACKLOG_LOVABLE.md` §7.

Mande o número medido assim que tiver, mesmo que seja `WITHIN`. Um `WITHIN` consistente também é resposta.

---

## §4 — ONDA 1 · depois de E2 e E3

- Headers `X-App-Version`, `X-Api-Version` e `X-Idempotency-Key` no interceptor.
- Chave de idempotência **gerada no toque**, propagada até a chamada. Já persistida na onda 0.
- Consumo de `sync_batch`: até 50 operações, resultado individual por operação, uma falhar não aborta as outras.
- Ordenação por `server_seq`. `client_created_at` só como metadado.
- Reporte de divergência de relógio quando o desvio passar de 5 minutos.

---

## §5 — ONDA 2 · depois de E4 · a maior

Adoção do contrato v2.

**DTOs**
- Todo campo monetário vira `Long` em unidade mínima com `currency` ao lado.
- Enums como código string, com `else` em todo `when`.
- Envelope de erro `{code, message_key, details, retriable}`. O `retriable` alimenta a decisão da outbox.
- Ids opacos, com geração local para abrir mesa offline.
- Paginação por cursor.
- Timestamps ISO 8601 com offset explícito.

**`capabilities` — o que ele passa a alimentar**
- `CurrencyManager`: formatação, separadores, símbolo, posição, `display_decimals`, arredondamento de caixa
- **Modo do teclado numérico** — casas implícitas. Em PYG o operador digita `150000` e vê `150.000`; em BRL digita `1500` e vê `15,00`. Um PDV que assume duas casas está errado em PYG e em CLP
- `TaxEntity` como cache do que vem da API. Nada autorado localmente
- `PaymentMethodSelectorBottomSheet` a partir da lista servida, não de lista fixa
- Política de offline por operação (chega na onda 4, mas o consumo do payload nasce aqui)

**Câmbio**
- A taxa vem fixada pelo servidor. O cache local serve só para estimativa, e a estimativa aparece marcada como tal.
- Venda em moeda estrangeira offline: permitida ou não conforme `capabilities`.

---

## §6 — ONDA 3 · depois de E5 e E6

O fluxo de pagamento completo.

- **Duas fases:** registra a intenção no backend, persiste em Room, dispara o deeplink.
- **Intent explícito nomeando o pacote** do app de pagamento. Ou `startActivityForResult`, se o outro lado aceitar — é mais forte, porque o sistema garante o retorno à instância que chamou e permite verificar quem chamou.
- **Validação de eco:** `nonce`, `amount`, `currency`. Divergência não conclui e gera alerta.
- **Rotina de resolução com quatro portas de entrada** — `unknown`, `error after_card`, eco divergente, retorno ao primeiro plano sem resultado, cold start com pendente. **Um fluxo, não quatro.**
- **Realtime antes de poll.** O canal do `DeviceGuardService` já está aberto; o backend notifica por ele. Poll é rede de segurança, com backoff `0/2/5/10/20/30s` e depois 30s até **5 minutos**, quando entrega ao operador.
- **Retentativa gera chave nova** e `attempt_no` incrementado. Nunca reusa — a tentativa anterior pode ter sido aprovada.
- **`receipt_printed`:** se o app de pagamento já imprimiu, não imprima de novo.
- **`approved_unverified`** — o caminho aprovado. Três travas obrigatórias:
  1. só a partir de `approved` **com eco válido**. Nunca de `unknown`, `error` ou eco divergente
  2. o comprovante sai marcado como não confirmado
  3. permissão servida por `capabilities`, por tenant, começando desligada

**Ao final: Release 2, v2.0.**

---

## §7 — Release do APK

Três releases, não um por onda. Cada rollout tem custo e é janela de risco.

**Release 1 — v1.x, só a onda 0. Antes de qualquer mudança de contrato.**

A razão é uma armadilha específica: **para forçar uma atualização, a capacidade de forçar precisa já estar num APK publicado.** Se a versão em campo não traz a In-App Updates, você não consegue forçá-la por dentro do app — sobra o gate do servidor mais contato manual.

Hoje isso não dói, porque nenhum cliente opera. **É a última vez que sai de graça.**

**Release 2 — v2.0, ondas 1 a 3.** A quebra de contrato, num release só. Prioridade alta definida no rollout — ela é fixada no lançamento e **não pode ser alterada depois** para aquele release. Rollout escalonado: 20%, observação em `pdv_devices`, depois 100%.

**Release 3 — v2.1, ondas 4 e 5.** Incremental, compatível, sem pressa.

---

## §8 — Item conjunto com o Lovable

**A janela de 60s.** O backend recusa inserção de item mais de 60 segundos depois da criação da venda. É um proxy de tempo para uma condição que não é de tempo — a condição real é "estes itens fazem parte da criação desta venda".

O cenário de risco é o seu: **descarregamento da outbox depois de período offline.** Se a fila estiver grande, a rede ruim, ou os itens forem enviados em requisições separadas com backoff, o intervalo pode passar de 60s. O resultado é **venda gravada sem itens, permanentemente, parecendo válida.**

O item 8 da onda 0 instrumenta a medição. Com o número real em mãos, o Lovable ajusta a janela — ou confirma que não é problema.

**A janela dissolve na E5**, quando a venda passa a nascer como `pending_payment` e só é promovida depois dos itens. Aí a condição vira estrutural.

---

## §9 — ONDAS 4 e 5

**Onda 4 · depois de F1 e F5**
- Política de offline vinda de `capabilities`: botão desabilitado com o motivo visível, sem hardcode.
- Notificação operacional pelo **Realtime que já existe** no `DeviceGuardService`.
- UX em 480x800: banner grande, som audível em cozinha barulhenta, um toque para agir.
- Validade da notificação: expirada é descartada, não exibida atrasada. "Pedido pronto" chegando 40 minutos depois é pior que não chegar.

**Onda 5 · depois de F6**
- Heartbeat com estado resumido: versão, fila de sincronização, impressora, bateria.
- Upload de log **sob demanda**, acionado por FCM, para o bucket. Nunca telemetria contínua — terminal em rede de restaurante não pode virar cliente de telemetria.
- Apelido do dispositivo exibido, para operador e suporte falarem a mesma língua.

---

## §10 — Handoffs

O Lovable produz, você consome. Três pontos, numa pasta `/contract` versionada nos dois lados.

| # | Depois de | Arquivos |
| --- | --- | --- |
| 1 | E4 · E5 · E6 | `openapi-terminal-v2.yaml`, `TERMINAL_API_RULES.md`, `TERMINAL_SYNC.md`, `DEEPLINK_CONTRACT.md`, `PAYMENT_STATE_MACHINE.md`, catálogo de erros com `message_key` nas 4 locales, `capabilities.example.json` |
| 2 | F1 | `capabilities` atualizado com a política de offline por operação |
| 3 | F5 | Contrato do canal `pdv`: entrega, validade da notificação, comportamento offline |

**A regra que evita divergência:** depois da onda 2, mudança de contrato **começa no arquivo e desce para os dois lados**. Nunca o contrário. Enquanto o contrato existir só no chat do Lovable e na sua memória, a divergência é questão de tempo — e ela aparece como valor errado em produção, não como erro de compilação.

---

## §11 — Referência

| Arquivo | Contém |
| --- | --- |
| `ref/ANALISE_APP.md` | Leitura do app real contra os planos: o que muda por classe, achados de risco |
| `ref/CONTRATO_PAGAMENTO.md` | Fronteira com o app de pagamento, spec da chamada e do retorno |
| `ref/ESTADO_PAGAMENTO.md` | Matriz de 12 possibilidades de retorno, política de consulta e backoff |
| `ref/API_V2.md` | Lista dos 11 itens do contrato, In-App Updates, rollout |
| `ref/PILOTO_SMARTPOS.md` | Contrato do terminal, offline e concorrência, checklist de porta |
