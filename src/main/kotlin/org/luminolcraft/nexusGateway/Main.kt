package org.luminolcraft.nexusGateway

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.plugin.java.JavaPlugin

class Main : JavaPlugin() {

    override fun onEnable() {
        instance = this
        configManager = ConfigManager()
        configManager.initConfig()
        configManager.loadConfig()
    }

    override fun onDisable() {
        configManager.savePlayerCache()
    }

    companion object {
        lateinit var instance: Main
        lateinit var configManager: ConfigManager
        val miniMessage: MiniMessage = MiniMessage.miniMessage()
    }
}
