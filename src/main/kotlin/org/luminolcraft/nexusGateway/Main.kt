package org.luminolcraft.nexusGateway

import com.cronutils.model.time.ExecutionTime
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
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
import java.time.ZonedDateTime
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.toJavaDuration

class Main : JavaPlugin(), Listener, TabExecutor {

    override fun onEnable() {
        instance = this
        configManager = ConfigManager()
        configManager.initConfig()
        configManager.loadConfig()
        configManager.loadPlayerCache()
        initHttpClient()
        Bukkit.getPluginManager().registerEvents(this, this)
        Bukkit.getPluginCommand("nexusgateway")?.setExecutor(this)
        queueManager.startCycleJob()
        initSaveCacheTimerJob()
        initCacheResetJob()
    }

    override fun onDisable() {
        queueManager.stopCycleJob()
        queueManager.stopScope()
        stopJobs()
        scope.cancel()
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
            else configManager.playerCacheMap.remove(event.uniqueId.toString())
        }
        // 事件必须同步返回登录结果，只能阻塞等待；等待上限由 HttpTimeout 控制
        val isBind = runBlocking {
            getIsPlayerBound(event.name, event.uniqueId)
        }
        if (!isBind) {
            event.loginResult = AsyncPlayerPreLoginEvent.Result.KICK_OTHER
            event.kickMessage(miniMessage.deserialize(configManager.unbindMessage))
            return
        }

    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        queueManager.queuedPlayers.remove(Pair(player.name, player.uniqueId))
    }


    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun getIsPlayerBound(playerName: String, playerUuid: UUID): Boolean {
        // 配额每分钟重置，超限请求先入队放行，由队列在下一个窗口补查
        if (minuteAccessCount.get() >= QPM) {
            if (!queueManager.queuedPlayers.contains(Pair(playerName, playerUuid))) {
                queueManager.queuedPlayers.add(Pair(playerName, playerUuid))
            }
            return true
        }
        minuteAccessCount.incrementAndGet()
        val response: HttpResponse
        try {
            response =
                httpClient.get(
                    configManager.apiBaseUrl +
                        "/api/v1/internal/minecraft/uuid/$playerUuid?name=${playerName.encodeURLParameter()}"
                )
        } catch (e: Exception) {
            logger.warning("Error getting player bound $playerName,Stopping.")
            if (!queueManager.queuedPlayers.contains(Pair(playerName, playerUuid))) {
                queueManager.queuedPlayers.add(Pair(playerName, playerUuid))
            }
            throw e
        }
        if (response.status.value == 200) {
            // 缓存键统一用事件 UUID：与读取侧（onPlayerPreLogin）以及请求参数三处必须是同一个值，
            // 否则代理/混合模式下读写键不一致，缓存永不命中，门禁会因撞限流而静默放行。
            val playerCache =
                configManager.playerCacheMap.computeIfAbsent(playerUuid.toString()) {
                    ConfigManager.PlayerCacheData(
                        0,
                        0
                    )
                }
            playerCache.playerJoinCount++
            // 表达式完全不可用时的固定兜底 TTL
            if (configManager.fixedCacheExpiresMin) {
                playerCache.timestamp = System.currentTimeMillis() + 5L * ConfigManager.MILLIS_PER_MINUTE
            } else {
                playerCache.timestamp = System.currentTimeMillis() + configManager.getPlayerCacheExpiresMinutes(
                    configManager.threadLocalExpression.get(), playerCache.playerJoinCount
                ) * ConfigManager.MILLIS_PER_MINUTE
            }
            return true
        }
        // 403 说明服务端 secret 配错，不是玩家问题，放行避免全员被锁在门外
        if (response.status.value == 403) {
            logger.warning("Wrong secret! default pass.")
            return true
        }
        // 后端限流时同样入队，交给队列重试，不直接拒绝登录
        if (response.status.value == 429) {
            if (!queueManager.queuedPlayers.contains(Pair(playerName, playerUuid))) {
                queueManager.queuedPlayers.add(Pair(playerName, playerUuid))
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

        //Save Cache
        @Volatile
        private var timerJob: Job? = null

        //Reset Cache
        private var resetJob: Job? = null
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
                // 超时取自配置，重载配置时重建 client 才会生效
                install(HttpTimeout) {
                    requestTimeoutMillis = configManager.requestTimeoutSec * 1000L
                    connectTimeoutMillis = configManager.connectionTimeoutSec * 1000L
                }
                defaultRequest {
                    // 内部接口统一携带鉴权头，secret 重载后由重建 client 生效
                    header(HttpHeaders.ContentType, ContentType.Application.Json)
                    header("X-Webhook-Secret", configManager.secret)
                }
            }
        }

        fun initSaveCacheTimerJob() {
            timerJob?.cancel()
            timerJob = scope.launch {
                while (isActive) {
                    delay(configManager.cacheFlushIntervalMillis)
                    runCatching { configManager.savePlayerCache() }.onFailure {
                        instance.logger.warning("Error saving playerCache because: \n ${it.message}")
                    }
                }
            }
        }

        fun initCacheResetJob() {
            val executionTime = ExecutionTime.forCron(configManager.resetCron)
            resetJob?.cancel()
            resetJob = scope.launch {
                while (isActive) {
                    // 睡到下一次 cron 执行；若 cron 永远不会有下次执行，则用 INFINITE 让协程永久挂起
                    delay(
                        executionTime.timeToNextExecution(ZonedDateTime.now())
                            .orElse(kotlin.time.Duration.INFINITE.toJavaDuration())!!
                    )
                    // 重置前先停保存任务，避免清空过程中另一个协程把旧快照写回
                    timerJob?.cancel()
                    timerJob = null
                    runCatching { configManager.resetPlayerCache() }.onFailure {
                        instance.logger.warning("Error resetting playerCache because: \n ${it.message}")
                    }
                    // 重置完成后重新按间隔排保存
                    initSaveCacheTimerJob()
                    // 防止 cron 刚好命中导致紧循环
                    delay(1000)
                }
            }
        }

        fun stopJobs() {
            timerJob?.cancel()
            timerJob = null
            resetJob?.cancel()
            resetJob = null
        }
    }
}
