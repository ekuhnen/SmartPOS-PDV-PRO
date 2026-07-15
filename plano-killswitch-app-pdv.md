# Plano de Implementação — Kill-Switch Remoto no App PDV (Android)

> **Objetivo:** integrar o app PDV Android ao sistema de bloqueio remoto já implementado no backend Plug PDV, permitindo que o proprietário desconecte um dispositivo (Smart POS extraviada) ou um sub-usuário em **tempo real**, mesmo no meio de uma venda.

---

## 1. Visão geral da arquitetura (3 camadas)

```
┌─────────────────────────────────────────────────────────┐
│ 1. SERVIDOR (autoritativo) — JÁ PRONTO                  │
│    Edge Functions retornam 403 DEVICE_BLOCKED /         │
│    USER_BLOCKED quando o flag está ativo no banco.      │
│    O app só precisa SABER tratar esses códigos.         │
├─────────────────────────────────────────────────────────┤
│ 2. REALTIME (UX imediata) — A IMPLEMENTAR NO APP        │
│    Supabase Realtime escuta mudanças em pdv_devices     │
│    (id = este device) e company_users (auth_user_id).   │
│    Dispara logout em < 1s.                              │
├─────────────────────────────────────────────────────────┤
│ 3. FCM (reforço para app em background) — A IMPLEMENTAR │
│    Push silencioso `data.action = "force_logout"`       │
│    aciona logout mesmo com app fechado/minimizado.      │
└─────────────────────────────────────────────────────────┘
```

> **Defesa real:** a camada 1 (servidor). Realtime e FCM existem só para **UX** (encerrar a sessão antes que o atacante perceba).

---

## 2. Pré-requisitos no app PDV

| Item | Status esperado | Observação |
|------|-----------------|------------|
| Supabase Kotlin SDK (`supabase-kt`) | Instalado | Versão ≥ 2.x com módulos `auth-kt`, `postgrest-kt`, `realtime-kt`, `functions-kt` |
| Firebase Cloud Messaging | Instalado | `google-services.json` válido |
| `auth_user_id` em sessão | Disponível após login | Usado no filtro Realtime |
| Token FCM registrado em `pdv_devices.fcm_token` | Já existe | Garantir que o `id` da linha é igual ao `deviceId` local |

---

## 3. Identificador persistente do dispositivo (`deviceId`)

O backend usa o header `X-Device-Id` como chave para bloqueio por hardware. O app **deve** gerar e persistir um UUID estável, que sobreviva a restarts mas seja **apagado no logout forçado**.

```kotlin
object DeviceIdProvider {
    private const val PREF = "pdv_prefs"
    private const val KEY = "device_id"

    fun get(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
```

> **Importante:** ao bloquear, `clear()` é chamado para que o atacante não consiga reusar o mesmo `deviceId` se reinstalar o app.

---

## 4. Interceptor HTTP — injetar `X-Device-Id` em TODA chamada

Todas as chamadas para Edge Functions e PostgREST precisam carregar o header. Usar OkHttp interceptor:

```kotlin
class DeviceIdInterceptor(private val ctx: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("X-Device-Id", DeviceIdProvider.get(ctx))
            .build()
        return chain.proceed(req)
    }
}
```

Registrar no client Supabase:

```kotlin
val supabase = createSupabaseClient(SUPABASE_URL, SUPABASE_ANON_KEY) {
    install(Auth)
    install(Postgrest)
    install(Realtime)
    install(Functions)
    httpEngine = OkHttp.create {
        addInterceptor(DeviceIdInterceptor(applicationContext))
    }
}
```

---

## 5. Tratamento global de respostas 403

Criar um **response interceptor** que detecta `DEVICE_BLOCKED` ou `USER_BLOCKED` e dispara `forceLogout()` imediatamente (mesmo sem Realtime/FCM).

```kotlin
class BlockResponseInterceptor(
    private val onBlocked: (reason: String) -> Unit
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val res = chain.proceed(chain.request())
        if (res.code == 403) {
            val peek = res.peekBody(2048).string()
            if (peek.contains("DEVICE_BLOCKED") || peek.contains("USER_BLOCKED")) {
                onBlocked(if (peek.contains("DEVICE")) "Dispositivo bloqueado" else "Acesso revogado")
            }
        }
        return res
    }
}
```

---

## 6. Registro/atualização do dispositivo no login

Logo após login bem-sucedido:

```kotlin
suspend fun registerDevice() {
    val userId = supabase.auth.currentUserOrNull()?.id ?: return
    val deviceId = DeviceIdProvider.get(ctx)
    val fcmToken = FirebaseMessaging.getInstance().token.await()

    // Resolver owner (pode ser sub-usuário)
    val cu = supabase.from("company_users").select { 
        filter { eq("auth_user_id", userId) }
        limit(1)
    }.decodeSingleOrNull<CompanyUser>()
    val ownerId = cu?.ownerUserId ?: userId

    supabase.from("pdv_devices").upsert(mapOf(
        "id" to deviceId,
        "owner_user_id" to ownerId,
        "auth_user_id" to userId,
        "fcm_token" to fcmToken,
        "platform" to "android",
        "device_label" to Build.MODEL,
        "app_version" to BuildConfig.VERSION_NAME,
        "last_seen_at" to Clock.System.now().toString()
    ))
}
```

> Se `pdv_devices.blocked == true` no momento do registro → chamar `forceLogout()` antes de qualquer tela carregar.

---

## 7. Realtime kill-switch (núcleo da feature)

Subscrever **dois canais** ao iniciar a sessão autenticada (em um `DeviceGuardService` rodando como Foreground Service ou `LifecycleObserver` do Application):

```kotlin
class DeviceGuardService(private val ctx: Context) {

    private var channel: RealtimeChannel? = null

    suspend fun start(userId: String, deviceId: String) {
        channel = supabase.channel("device-guard-$deviceId")

        // 1) Bloqueio do DISPOSITIVO
        channel!!.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "pdv_devices"
            filter("id", FilterOperator.EQ, deviceId)
        }.onEach { change ->
            val blocked = change.record["blocked"]?.jsonPrimitive?.boolean ?: false
            if (blocked) {
                val reason = change.record["blocked_reason"]?.jsonPrimitive?.content
                    ?: "Dispositivo bloqueado pelo administrador"
                forceLogout(reason)
            }
        }.launchIn(scope)

        // 2) Bloqueio do USUÁRIO (active = false)
        channel!!.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
            table = "company_users"
            filter("auth_user_id", FilterOperator.EQ, userId)
        }.onEach { change ->
            val active = change.record["active"]?.jsonPrimitive?.boolean ?: true
            if (!active) {
                val reason = change.record["blocked_reason"]?.jsonPrimitive?.content
                    ?: "Acesso revogado pelo administrador"
                forceLogout(reason)
            }
        }.launchIn(scope)

        channel!!.subscribe()
    }

    suspend fun stop() {
        channel?.unsubscribe()
        channel = null
    }
}
```

### Onde rodar
- **Application class** (`onCreate`) → instancia o `DeviceGuardService`.
- Iniciar `start()` ao detectar sessão válida (`supabase.auth.sessionStatus`).
- Parar em `signOut()`.
- Para sobrevivência em background prolongado, considere um **Foreground Service leve** (notificação “PDV ativo”).

---

## 8. FCM — push silencioso `force_logout`

O backend já dispara push com payload:
```json
{ "data": { "action": "force_logout", "reason": "..." } }
```

No `FirebaseMessagingService` do app:

```kotlin
class PdvMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        when (msg.data["action"]) {
            "force_logout" -> {
                val reason = msg.data["reason"] ?: "Sessão encerrada"
                ForceLogoutBus.emit(reason) // SharedFlow global
            }
            else -> { /* outros pushes */ }
        }
    }

    override fun onNewToken(token: String) {
        // Atualiza pdv_devices.fcm_token
        scope.launch {
            supabase.from("pdv_devices").update({ set("fcm_token", token) }) {
                filter { eq("id", DeviceIdProvider.get(applicationContext)) }
            }
        }
    }
}
```

A `MainActivity` (ou um `LifecycleObserver` global) coleta o `ForceLogoutBus` e executa `forceLogout(reason)`.

---

## 9. Função `forceLogout(reason)` — comportamento crítico

```kotlin
suspend fun forceLogout(reason: String) {
    if (logoutInProgress.getAndSet(true)) return  // idempotente

    // 1. Mostrar overlay bloqueante (não dá pra fechar)
    BlockedOverlay.show(reason)

    // 2. Limpar filas offline (vendas pendentes são DESCARTADAS — comportamento desejado)
    OfflineQueue.clearAll()
    LocalCache.clearAll()

    // 3. Limpar deviceId (impede reuso por atacante)
    DeviceIdProvider.clear(ctx)

    // 4. Encerrar sessão Supabase
    runCatching { supabase.auth.signOut() }

    // 5. Parar Realtime
    deviceGuardService.stop()

    // 6. Navegar para tela de login com motivo
    navigator.navigate(LoginScreen(reason = "blocked", message = reason)) {
        popUpTo(0) { inclusive = true }
    }
}
```

### Tela de login deve mostrar
- Banner vermelho: “Sessão encerrada pelo administrador. Motivo: {reason}”.
- Campo de email/senha normal — usuário pode logar de novo se ainda tiver acesso.

---

## 10. Filas offline — política em caso de bloqueio

| Situação | Comportamento |
|----------|---------------|
| Vendas na fila aguardando sync | **Descartar** (perda intencional) |
| Caixa aberto localmente | **Descartar** estado local; admin reabre pelo painel |
| Comandas em edição | **Descartar** rascunho local |
| Logs de auditoria locais | **Tentar enviar 1x antes** de limpar (best effort, com timeout 2s) |

> Justificativa: extravio de hardware = potencial fraude. Melhor perder 5 min de vendas não sincronizadas do que dar ao atacante uma janela para “sincronizar coisas suas” depois.

---

## 11. Endpoint admin (apenas referência — não chamado pelo app PDV)

O painel web já chama:
```
POST /functions/v1/device-control
{ "action": "block_device", "device_id": "...", "reason": "..." }
```
O app PDV **não consome** essa rota — apenas reage aos efeitos (Realtime + push + 403).

---

## 12. Checklist de implementação

### Setup base
- [ ] Adicionar dependências: `supabase-kt:realtime-kt`, Firebase Messaging.
- [ ] Criar `DeviceIdProvider` (Section 3).
- [ ] Configurar OkHttp interceptors (`DeviceIdInterceptor` + `BlockResponseInterceptor`).

### Fluxo de login
- [ ] Após login, chamar `registerDevice()` (Section 6).
- [ ] Verificar `blocked` antes de navegar para Home.

### Realtime
- [ ] Implementar `DeviceGuardService` com 2 canais (Section 7).
- [ ] Iniciar no `Application.onCreate` quando há sessão.
- [ ] Reconectar automaticamente em mudança de rede (`Realtime` faz isso, mas validar).

### FCM
- [ ] Implementar `PdvMessagingService` (Section 8).
- [ ] Tratar `data.action == "force_logout"`.
- [ ] Atualizar `fcm_token` em `onNewToken`.

### Logout
- [ ] Implementar `forceLogout(reason)` idempotente (Section 9).
- [ ] Criar `BlockedOverlay` que cobre qualquer tela ativa (inclusive checkout).
- [ ] Limpar filas offline + deviceId + cache.
- [ ] Tela de login com banner do motivo.

### Testes manuais
- [ ] Bloquear dispositivo no painel → app desloga em < 2s com tela aberta.
- [ ] Bloquear no meio de uma venda (carrinho cheio) → overlay bloqueia, item não é cobrado.
- [ ] Bloquear com app em background → push acorda + desloga ao abrir.
- [ ] Tentar reusar JWT após bloqueio (via cURL com token salvo) → recebe 403.
- [ ] Desbloquear → próximo login funciona normalmente.
- [ ] Bloquear sub-usuário → todos os dispositivos dele deslogam, owner não é afetado.

---

## 13. Pontos de atenção

1. **JWT continua válido até expirar** (1h padrão). A defesa real é o 403 do servidor; Realtime/FCM são UX. **Não diminua o TTL do JWT** sem entender o impacto em refresh.
2. **Realtime exige conexão WebSocket aberta.** Em redes corporativas que bloqueiam WS, o app fica dependente do FCM + 403. Considere fallback de polling a cada 30s em `/pdv_devices?id=eq.X&select=blocked` se a conexão Realtime cair.
3. **Foreground Service** melhora latência do logout em background, mas exige notificação persistente. Avalie UX vs. responsividade.
4. **Permissão FCM no Android 13+** (`POST_NOTIFICATIONS`): solicitar no onboarding.
5. **Auditoria local:** todo `forceLogout` deve gravar (best-effort) um evento em `eventos_auditoria` com tipo `FORCE_LOGOUT_RECEBIDO` antes de limpar a sessão.

---

## 14. Resumo dos contratos de API usados pelo app

| Recurso | Como o app usa |
|---------|----------------|
| Header `X-Device-Id` | Enviado em **todas** as requests |
| Tabela `pdv_devices` | Upsert no login, escutar via Realtime |
| Tabela `company_users` | Escutar `active` e `blocked_reason` via Realtime |
| Códigos 403 `DEVICE_BLOCKED`/`USER_BLOCKED` | Disparam `forceLogout` |
| FCM `data.action = "force_logout"` | Dispara `forceLogout` |

---

**Fim do plano.** Implementando os 6 blocos (deviceId → interceptors → registro → Realtime → FCM → forceLogout) o app fica 100% alinhado com o backend e o painel web já existentes.
