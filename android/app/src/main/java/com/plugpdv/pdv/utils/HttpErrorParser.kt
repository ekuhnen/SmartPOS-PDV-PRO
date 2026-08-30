package com.plugpdv.pdv.utils

import retrofit2.HttpException

/**
 * Utilitário centralizado para mapeamento semântico de erros HTTP 403 e exceções de rede.
 *
 * Regras:
 * - DEVICE_BLOCKED / USER_BLOCKED -> "Dispositivo bloqueado pelo administrador." / "Acesso revogado."
 * - device_not_registered / NOT_REGISTERED -> "Este terminal não está registrado. Chame o suporte."
 * - device_owner_mismatch -> "Este terminal já está registrado para outra empresa. Limpe os dados do app ou recadastre o terminal."
 * - OPERATION_MODE_DISABLED (ou 'Comanda/Mesa/Venda mode is disabled for this user') ->
 *     Mesa: "O modo Mesas está desabilitado para este usuário."
 *     Comanda: "O modo Comandas está desabilitado para este usuário."
 *     Venda Rápida: "O modo Venda Rápida está desabilitado para este usuário."
 * - Genérico 403 / Desconhecido -> "Acesso não autorizado para esta operação."
 *
 * NUNCA declara "Terminal bloqueado" sem o código de servidor correspondente (DEVICE_BLOCKED / USER_BLOCKED).
 */
object HttpErrorParser {

    fun parse403Message(errorBody: String?, defaultMode: String? = null): String {
        if (errorBody.isNullOrBlank()) {
            return "Acesso não autorizado para esta operação."
        }

        val lower = errorBody.lowercase()

        return when {
            lower.contains("device_blocked") -> "Dispositivo bloqueado pelo administrador."
            lower.contains("user_blocked") -> "Acesso revogado."
            lower.contains("device_not_registered") || lower.contains("not_registered") ->
                "Este terminal não está registrado. Chame o suporte."
            lower.contains("device_owner_mismatch") ->
                "Este terminal já está registrado para outra empresa. Limpe os dados do app ou recadastre o terminal."
            lower.contains("device_check_failed") ->
                "Falha na verificação do dispositivo. Tente novamente."
            // Operation mode disabled específico
            (lower.contains("mesa") && (lower.contains("disabled") || lower.contains("desabilitado") || lower.contains("mode"))) ||
                    (lower.contains("operation_mode_disabled") && lower.contains("mesa")) ->
                "O modo Mesas está desabilitado para este usuário."
            (lower.contains("comanda") && (lower.contains("disabled") || lower.contains("desabilitado") || lower.contains("mode"))) ||
                    (lower.contains("operation_mode_disabled") && lower.contains("comanda")) ->
                "O modo Comandas está desabilitado para este usuário."
            ((lower.contains("venda_direta") || lower.contains("venda direta") || lower.contains("venda rápida") || lower.contains("venda")) && (lower.contains("disabled") || lower.contains("desabilitado") || lower.contains("mode"))) ||
                    (lower.contains("operation_mode_disabled") && lower.contains("venda")) ->
                "O modo Venda Rápida está desabilitado para este usuário."
            lower.contains("operation_mode_disabled") || lower.contains("mode is disabled") -> {
                when (defaultMode?.lowercase()) {
                    "mesa" -> "O modo Mesas está desabilitado para este usuário."
                    "comanda" -> "O modo Comandas está desabilitado para este usuário."
                    "venda_direta", "venda" -> "O modo Venda Rápida está desabilitado para este usuário."
                    else -> "Acesso não autorizado para esta operação."
                }
            }
            else -> "Acesso não autorizado para esta operação."
        }
    }

    fun parseHttpErrorMessage(e: HttpException, defaultMode: String? = null): String {
        return when (e.code()) {
            401 -> "Sessão expirada. Faça login novamente."
            403 -> {
                val body = try {
                    e.response()?.errorBody()?.string()
                } catch (_: Exception) {
                    null
                }
                parse403Message(body, defaultMode)
            }
            426 -> "Atualização obrigatória do aplicativo necessária."
            in 500..599 -> "Erro no servidor (Código: ${e.code()})"
            else -> "Erro no servidor (Código: ${e.code()})"
        }
    }
}
