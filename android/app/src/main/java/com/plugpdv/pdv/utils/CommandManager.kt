package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.Command

object CommandManager {
    private val commands = mutableListOf<Command>()

    @JvmStatic
    fun getCommands(): List<Command> = commands

    @JvmStatic
    fun setCommands(newCommands: List<Command>) {
        commands.clear()
        commands.addAll(newCommands)
    }

    @JvmStatic
    fun getCommandByCode(code: String): Command? = commands.find { it.code == code }
}
