package org.luminolcraft.nexusGateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import kotlinx.coroutines.time.delay
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.luminolcraft.nexusGateway.Main.Companion.configManager
import org.luminolcraft.nexusGateway.Main.Companion.httpClient
import org.luminolcraft.nexusGateway.Main.Companion.miniMessage
import org.luminolcraft.nexusGateway.Main.Companion.minuteAccessCount
import java.net.ConnectException
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

class QueueManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduledJob: Job? = null
    private var resetCountJob: Job? = null
    val queuedPlayers = ConcurrentLinkedQueue<Pair<String, UUID>>()

    @OptIn(ExperimentalStdlibApi::class)
    fun startCycleJob() {
        scheduledJob?.cancel()
        resetCountJob?.cancel()
        // 独立协程每分钟清零配额计数，与队列处理循环解耦
        resetCountJob = scope.launch {
            while (isActive) {
                delay(60000)
                minuteAccessCount.set(0)
            }
        }
        scheduledJob = scope.launch {
            while (isActive) {
                // 空队列或配额用尽时休眠，避免循环忙等空转
                if (queuedPlayers.isEmpty() || minuteAccessCount.get() >= Main.QPM) {
                    delay(1000)
                    continue
                }
                while (queuedPlayers.isNotEmpty()) {
                    if (minuteAccessCount.get() >= Main.QPM) break
                    // 队头可能被 quit 事件并发移除，取不到就结束本轮
                    val playerName = runCatching { queuedPlayers.first().first }.getOrElse { break }
                    val playerUuid = runCatching { queuedPlayers.first().second }.getOrElse { break }
                    val response: HttpResponse
                    // 取消是正常停服流程，不能当网络错误重试
                    try {
                        response =
                            httpClient.get(configManager.apiBaseUrl + "/api/v1/internal/minecraft/uuid/${playerUuid}?name=${playerName}")
                    } catch (e: CancellationException) {
                        return@launch
                    } catch (e: ConnectException) {
                        // 连接被拒绝说明后端不可达，冷却 5 分钟再试，避免持续空打连接
                        delay(Duration.ofMinutes(5L))
                        continue
                    } catch (e: Exception) {
                        Main.instance.logger.warning("Error getting player bound $playerName,Stopping." + e.message)
                        // 失败后把队头轮转到队尾，等下个配额窗口再试
                        minuteAccessCount.incrementAndGet()
                        queuedPlayers.poll()
                        queuedPlayers.add(Pair(playerName, playerUuid))
                        continue
                    }
                    minuteAccessCount.incrementAndGet()
                    when (response.status.value) {
                        200 -> {
                            // 补查成功也要写缓存，否则下个窗口还会重复请求
                            val playerCache =
                                configManager.playerCacheMap.getOrPutIfNull(playerUuid.toString()) {
                                    ConfigManager.PlayerCacheData(
                                        0,
                                        0
                                    )
                                }
                            playerCache.playerJoinCount++
                            if (configManager.fixedCacheExpiresMin) {
                                playerCache.timestamp =
                                    System.currentTimeMillis() + 5L * ConfigManager.MILLIS_PER_MINUTE
                            } else {
                                playerCache.timestamp =
                                    System.currentTimeMillis() + configManager.getPlayerCacheExpiresMinutes(
                                        configManager.threadLocalExpression.get(), playerCache.playerJoinCount
                                    ) * ConfigManager.MILLIS_PER_MINUTE
                            }
                            queuedPlayers.poll()
                        }

                        403 -> {
                            Main.instance.logger.warning("Wrong secret! default pass.")
                            queuedPlayers.poll()
                        }

                        429 -> {
                            // 仍被限流则移到队尾，保留重试机会
                            queuedPlayers.poll()
                            queuedPlayers.add(Pair(playerName,playerUuid))
                        }

                        else -> {
                            // 未绑定：踢人必须调度到实体线程，Folia 下不能跨线程操作实体
                            val player = Bukkit.getPlayer(playerName)
                            player?.scheduler?.runDelayed(Main.instance, {
                                player.kick(miniMessage.deserialize(configManager.unbindMessage))
                            }, null, 1L)
                            queuedPlayers.poll()
                        }
                    }
                }
            }
        }
    }

    fun stopCycleJob() {
        scheduledJob?.cancel()
        resetCountJob?.cancel()
        resetCountJob = null
        scheduledJob = null
    }

    fun stopScope() {
        scope.cancel()
    }
}