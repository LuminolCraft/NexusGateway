package org.luminolcraft.nexusGateway

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicInteger

class Main : JavaPlugin(), Listener, TabExecutor {

    override fun onEnable() {
        instance = this
        configManager = ConfigManager()
        configManager.initConfig()
        configManager.loadConfig()
        initHttpClient()
        Bukkit.getPluginManager().registerEvents(this, this)
        Bukkit.getPluginCommand("nexusgateway")?.setExecutor(this)
        queueManager.startCycleJob()
    }

    override fun onDisable() {
        queueManager.stopCycleJob()
        queueManager.stopScope()
        httpClient.close()
        configManager.savePlayerCache()
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String> {
        if (!sender.hasPermission("nexusgateway.usage")) return listOf()
        return listOf("reload")
    }

    //TODO
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("nexusgateway.usage")) {
            sender.sendMessage("You don't have permission to use this command!")
            return true
        }
        if (args.isEmpty()) {
            return false
        }
        if (args[0] == "reload") {
            configManager.reloadConfig()
        }
        return true
    }

    @EventHandler
    fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        configManager.playerCacheMap[event.uniqueId.toString()]?.let {
            if (it.timestamp > System.currentTimeMillis()) return
        }
        val isBind = runBlocking {
            getIsPlayerBound(event.name)
        }
        if (!isBind) {
            event.loginResult = AsyncPlayerPreLoginEvent.Result.KICK_OTHER
            event.kickMessage(miniMessage.deserialize(configManager.unbindMessage))
            return
        }

    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerName = event.player.name
        queueManager.queuedPlayers.remove(playerName)
    }


    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun getIsPlayerBound(playerName: String): Boolean {
        if (minuteAccessCount.get() >= QPM) {
            if (!queueManager.queuedPlayers.contains(playerName)) {
                queueManager.queuedPlayers.add(playerName)
            }
            return true
        }
        minuteAccessCount.incrementAndGet()
        val response: HttpResponse
        try {
            response = httpClient.get(configManager.apiBaseUrl + "/api/v1/internal/minecraft/name/$playerName")
        } catch (e: Exception) {
            logger.warning("Error getting player bound $playerName,Stopping.")
            throw e
        }
        if (response.status.value == 200) {
            val uuidStr = Bukkit.getOfflinePlayer(playerName).uniqueId.toString()
            val playerCache =
                configManager.playerCacheMap.getOrPutIfNull(uuidStr) {
                    ConfigManager.PlayerCacheData(
                        0,
                        0
                    )
                }
            playerCache.playerJoinCount++
            playerCache.timestamp = System.currentTimeMillis() + configManager.getPlayerCacheExpiresMinutes(
                configManager.playerCacheExpiresEvaluator!!, playerCache.playerJoinCount
            ) * ConfigManager.MILLIS_PER_MINUTE
            return true
        }
        if (response.status.value == 403) {
            logger.warning("Wrong secret! default pass.")
            return true
        }
        if (response.status.value == 429) {
            if (!queueManager.queuedPlayers.contains(playerName)) {
                queueManager.queuedPlayers.add(playerName)
            }
            return true
        }
        return false
    }

    companion object {
        lateinit var instance: Main
        lateinit var configManager: ConfigManager
        val queueManager = QueueManager()
        val miniMessage: MiniMessage = MiniMessage.miniMessage()
        const val QPM = 60

        val minuteAccessCount = AtomicInteger(0)

        private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        @Volatile
        private var timerJob: Job? = null
        lateinit var httpClient: HttpClient

        fun initHttpClient() {
            httpClient = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json()
                }
                followRedirects = true
                install(HttpRedirect) {
                    checkHttpMethod = false
                }
                install(HttpTimeout) {
                    requestTimeoutMillis = configManager.requestTimeoutSec * 1000L
                    connectTimeoutMillis = configManager.connectionTimeoutSec * 1000L
                }
                defaultRequest {
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Webhook-Secret", configManager.secret)
                }
            }
        }
    }
}
