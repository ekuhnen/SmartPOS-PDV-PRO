# Plug PDV — Guia de Teste do Piloto Interno (0.1.0-pilot)

**Versão do Aplicativo:** `0.1.0-pilot`  
**Version Code:** `12`  
**Ambiente:** Supabase Production Edge Functions (`https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/`)  
**Hardware Suportado:** Sunmi V2 (Android 7.1.1 / API 25), P8 Neo (Android 11 / API 30), Kozen, Gertec e Tablets.

---

## 🎯 Objetivo do Piloto

Testar a estabilidade, resiliência offline, conciliação durável de operações e usabilidade do SmartPOS PDV Pro em condições reais de operação antes da distribuição comercial.

---

## 📋 Roteiro de Testes Recomendado

Siga os blocos de teste abaixo e tente deliberadamente provocar falhas ou estados inconsistentes.

---

### 1. Fluxo Normal de Operação

- [ ] **Login & Registro do Dispositivo**: Realize login com credenciais de operador válidas. Confirme que a licença e o caixa da sessão são carregados.
- [ ] **Abertura/Status do Caixa**: Verifique se o saldo inicial do caixa é exibido corretamente.
- [ ] **Venda Direta / Balcão**: Realize uma venda simples em dinheiro e PIX/cartão. Confirme a geração da nota e saldo.
- [ ] **Operação de Mesas**:
  - Abra uma mesa (ex: Mesa 5).
  - Adicione itens à mesa.
  - Envie os pedidos para a cozinha (KDS/impressão).
  - Realize o fechamento/pagamento parcial e total da mesa.
- [ ] **Operação de Comandas**:
  - Abra uma comanda por digitação de código ou leitura de QR Code.
  - Adicione itens e confirme a vinculação correta.

---

### 2. Resiliência de Rede (Offline & Transição)

- [ ] **Venda Totalmente Offline**:
  - Desligue a rede Wi-Fi / Dados Móveis do terminal SmartPOS.
  - Abra uma mesa ou realize uma venda no balcão.
  - Verifique se a operação é aceita localmente com feedback visual claro de estimativa/offline.
- [ ] **Reconexão e Reenvio Automático**:
  - Religue o Wi-Fi / Dados Móveis.
  - Verifique se o `WorkManager` em segundo plano sincroniza automaticamente as mutações pendentes.
- [ ] **Corte de Rede Durante Transação**:
  - Dispare o envio de uma operação e desligue a rede imediatamente no meio do processo.
  - Verifique se a operação permanece armazenada na Outbox durável em Room sem perda de dados.

---

### 3. Ciclo de Vida do Aplicativo (Lifecycle & Hardware)

- [ ] **Force-Stop / Encerramento Forçado**:
  - Encerre forçadamente o app nas configurações do Android com operações pendentes na fila.
  - Reabra o aplicativo e confirme que nenhuma mutação foi perdida.
- [ ] **Reboot do Dispositivo SmartPOS**:
  - Reinicie o equipamento (desligar e ligar a maquininha).
  - Abra o aplicativo e verifique se a sessão e a fila durável permanecem intactas.
- [ ] **Rotação de Tela e Troca de Apps**:
  - Alterne entre o PDV e outros aplicativos Android e retorne.
  - Confirme que o estado da UI é restaurado corretamente a partir do Room.

---

### 4. Gestão de Operadores e Autoridade Durável

- [ ] **Troca de Operador**:
  - Realize logout de um operador e login com outro usuário no mesmo terminal.
  - Confirme que mutações duráveis associadas ao operador anterior não são indevidamente sobrescritas nem alteradas por outro operador sem autoridade.
- [ ] **Tentativa com Credenciais Inválidas**:
  - Tente logar com senha errada e confirme a negação de acesso sem afetar os dados armazenados localmente.

---

### 5. Estresse de Interface & Repetição (Double Interaction)

- [ ] **Clique Duplo / Toque Rápido**:
  - Toque repetidamente e rapidamente nos botões de "Confirmar", "Abrir Mesa" ou "Finalizar Pagamento".
  - Confirme que nenhuma operação é enviada duplicada ou processada duas vezes.
- [ ] **Navegação Rápida (Voltar durante carregamento)**:
  - Pressione o botão de voltar do sistema enquanto uma requisição de rede estiver em andamento.
  - Confirme que o app não sofre crash e o resultado é persitido com segurança no repositório.

---

### 6. Reconciliação e Resolução pelo Operador

- [ ] **Detecção de Conflitos**:
  - Caso ocorra um conflito no servidor (ex: mesa aberta em outro terminal), verifique se o banner **“Operações que precisam de atenção (N)”** é exibido no topo da tela principal.
- [ ] **Tela de Reconciliação**:
  - Abra a lista de reconciliações ao tocar no banner.
  - Inspecione a justificativa humana (ex: *"Esta mesa já está ocupada por outra operação."*).
- [ ] **Execução das Ações de Resolução**:
  - Teste a ação **“Tentar novamente”** (preserva a chave de idempotência original).
  - Teste a ação **“Cancelar operação”** (exige confirmação explícita e guarda histórico de auditoria).
  - Teste a ação **“Já foi concluída”** (para cenários de confirmação remota ambígua).
  - Confirme que após a resolução a pendência sai da lista ativa.

---

## 🚨 Como Reportar Inconsistências ou Bugs

Caso encontre qualquer comportamento inesperado, trava, erro ou divergência, registe as seguintes informações:

1. **Passos para Reproduzir**: O que foi feito exatamente antes do problema ocorrer.
2. **Resultado Esperado**: O que deveria ter acontecido.
3. **Resultado Encontrado**: O que realmente aconteceu (descreva a mensagem ou comportamento).
4. **Equipamento & Versão Android**: Ex: *Sunmi V2 (Android 7.1.1)* ou *P8 Neo (Android 11)*.
5. **Versão do Plug PDV**: `0.1.0-pilot` (build code 12).
6. **Horário Aproximado**: Para localização nos logs.
7. **Foto ou Vídeo**: Quando possível, grave ou tire foto da tela do SmartPOS.

---

## 🏷️ Classificação de Gravidade para Correções

- **P0 — Crítico (Bloqueador)**: Perda de vendas, duplicação de pagamentos, destruição de banco de dados ou travamento definitivo do app.
- **P1 — Alto**: Funcionalidade principal com falha sem contorno simples.
- **P2 — Médio**: Falha em fluxo secundário com contorno operacional disponível.
- **P3 — Baixo / Cosmético**: Ajuste de layout, alinhamento ou texto de mensagem.
