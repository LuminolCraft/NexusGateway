package org.luminolcraft.nexusGateway

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerPreLoginEvent
import org.bukkit.plugin.java.JavaPlugin
import org.quartz.Job
import java.util.concurrent.atomic.AtomicInteger

class Main : JavaPlugin(), Listener, TabExecutor {

    override fun onEnable() {
        instance = this
        configManager = ConfigManager()
        configManager.initConfig()
        configManager.loadConfig()
        initHttpClient()
    }

    override fun onDisable() {
        configManager.savePlayerCache()
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<String>
    ): List<String> {
        return listOf()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        return true
    }

    @EventHandler
    fun onPlayerPreLogin(event: AsyncPlayerPreLoginEvent) {
        if (configManager.playerCacheMap.containsKey(event.uniqueId.toString())) {
            val playerCache = configManager.playerCacheMap[event.uniqueId.toString()]!!
            if (playerCache.timestamp > System.currentTimeMillis()) return
        }
        runBlocking {
            if (!getIsPlayerBound(event.name)) {
                event.loginResult = AsyncPlayerPreLoginEvent.Result.KICK_OTHER
                event.kickMessage(miniMessage.deserialize(configManager.unbindMessage))
            }
        }
    }


    private suspend fun getIsPlayerBound(playerName: String): Boolean {
        val response: HttpResponse
        try {
            response = httpClient.get(configManager.apiBaseUrl + "/api/v1/internal/minecraft/name/$playerName")
        } catch (e: Exception) {
            logger.warning("Error getting player bound $playerName,Stopping.")
            throw e
        }
        if (response.status.value == 200) return true
        if (response.status.value == 403) {
            logger.warning("Wrong secret! default pass.")
            return true
        }
        if (response.status.value == 429) {
            //TODO add a fucking queue.
            return false
        }
        return false
    }

    companion object {
        lateinit var instance: Main
        lateinit var configManager: ConfigManager
        val miniMessage: MiniMessage = MiniMessage.miniMessage()

        @Volatile
        var minuteAccessCount = AtomicInteger(0) //TODO use on ratelimit shit.

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
