# SmartPOS PDV Pro — Contexto, Invariantes e Diretrizes

App Android nativo (Kotlin) de PDV para terminais SmartPOS (Sunmi, Gertec, Dspread, Kozen, Urovo) e tablets. Segmentos: varejo e food service (mesas, comandas, KDS). Multipaís: BR e PY hoje, América Latina em expansão. Multi-moeda: BRL, PYG, ARS, USD, EUR, e outras entrando.

Clean Architecture + MVVM. Kotlin 1.9+, Coroutines, StateFlow/SharedFlow. Hilt, Room, Retrofit 2 + OkHttp. Supabase (Auth, Postgres, Edge Functions, Realtime) e FCM. AIDL para impressora Sunmi.

O backend é desenvolvido separadamente. O contrato entre os dois está em `/contract` — `openapi-terminal-v2.yaml` e os arquivos de spec. **O CONTRATO É A FONTE DE VERDADE**, não a memória de como a API se comportava.

O backlog de desenvolvimento e roteiro de ondas do Kotlin está documentado em `BACKLOG_ANTIGRAVITY.md`.

---

## INVARIANTES

1. **DINHEIRO.** Sempre inteiro (`Long`) em unidade mínima da moeda, sempre acompanhado do código da moeda. Nunca `Float`, nunca `Double`, nunca `BigDecimal` sem moeda. O app NUNCA calcula dinheiro como verdade — o servidor calcula e o app exibe. Única exceção: estimativa offline, que precisa aparecer MARCADA como estimativa na tela.
2. **FORMATAÇÃO.** Nunca formate por `Locale` do dispositivo nem do usuário. Separadores, símbolo, posição, casas decimais e arredondamento de caixa vêm de `capabilities`. `es-MX` formata `1,250.50` e `es-AR` formata `1.250,50` — formatar pelo Locale faz o mesmo preço aparecer diferente para dois operadores.
3. **NADA DE REGRA AUTORADA LOCALMENTE.** Alíquota, regra de moeda, lista de meios de pagamento, política de offline, limites: tudo vem de `capabilities` ou da API. Room é CACHE, nunca fonte. Se para adicionar um país for preciso alterar código Kotlin, o desenho está errado.
4. **IDEMPOTÊNCIA.** A chave é gerada no momento do TOQUE do operador, não no momento do envio. Gerar no envio faz o retry criar chave nova e a idempotência não serve para nada. Persista antes de agir.
5. **ORDENAÇÃO.** Por `server_seq` vindo do servidor. NUNCA por relógio do aparelho — terminal Android tem relógio errado. `client_created_at` é enviado apenas como metadado de diagnóstico.
6. **ENUM DESCONHECIDO NÃO QUEBRA.** Todo `when` sobre valor vindo do servidor tem `else` que degrada com elegância. Sem isso o servidor não pode adicionar valor de enum sem release de APK — que é o que o versionamento deveria evitar.
7. **`unknown` NUNCA É APRESENTADO COMO RECUSA.** Estado de pagamento indeterminado mostrado como recusado faz o cliente pagar duas vezes.
8. **NUNCA ACEITE UM TOQUE QUE VAI FALHAR.** Operação não permitida offline tem botão desabilitado com o motivo visível. Aceitar o toque e falhar 20 segundos depois, com o cliente esperando, é o pior resultado possível.
9. **PERSISTA ANTES DE AGIR.** Tentativa de pagamento gravada em Room antes de disparar o deeplink. Operação na outbox antes da chamada de rede. Recuperação sempre a partir do Room, nunca de estado em memória.
10. **TEXTO DE ERRO** vem de `message_key` traduzida localmente nas quatro locales (`pt-BR`, `es`, `en`, `gn`). O servidor nunca manda string pronta para exibir.
11. **O APP NÃO VÊ TAXA DE PLATAFORMA.** É calculada no servidor. Se aparecer em algum payload, é bug e precisa ser reportado. Não confundir com a propina do restaurante (`ServiceFeeManager`), que o operador ajusta no balcão — são coisas diferentes com nomes parecidos.

---

## O QUE JÁ ESTÁ CERTO E NÃO DEVE SER REFEITO

- HAL multi-impressora com detecção por `Build.MODEL`. Resolvido.
- Kill switch com tripla redundância (403 + Realtime + FCM). É referência de qualidade; use como modelo para o gate de versão.
- Clean Architecture com Hilt e StateFlow. Mantenha a separação de camadas.
