package org.luminolcraft.nexusGateway

import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.*
import org.bukkit.Bukkit
import org.luminolcraft.nexusGateway.Main.Companion.configManager
import org.luminolcraft.nexusGateway.Main.Companion.httpClient
import org.luminolcraft.nexusGateway.Main.Companion.miniMessage
import org.luminolcraft.nexusGateway.Main.Companion.minuteAccessCount
import java.util.concurrent.ConcurrentLinkedQueue

class QueueManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduledJob: Job? = null
    private var resetJob: Job? = null
    val queuedPlayers = ConcurrentLinkedQueue<String>()
    fun startCycleJob() {
        scheduledJob?.cancel()
        resetJob?.cancel()
        resetJob = scope.launch {
            while (isActive) {
                delay(60000)
                minuteAccessCount.set(0)
            }
        }
        scheduledJob = scope.launch {
            while (isActive) {
                if (queuedPlayers.isEmpty()) {
                    delay(1000)
                    continue
                }
                while (queuedPlayers.isNotEmpty()) {
                    if (minuteAccessCount.get() >= Main.QPM) break
                    val playerName = queuedPlayers.first()
                    val response: HttpResponse
                    try {
                        response =
                            httpClient.get(configManager.apiBaseUrl + "/api/v1/internal/minecraft/name/$playerName")
                    } catch (e: Exception) {
                        Main.instance.logger.warning("Error getting player bound $playerName,Stopping." + e.message)
                        minuteAccessCount.incrementAndGet()
                        continue
                    }
                    minuteAccessCount.incrementAndGet()
                    when (response.status.value) {
                        200 -> {
                            queuedPlayers.remove(playerName)
                        }

                        403 -> {
                            Main.instance.logger.warning("Wrong secret! default pass.")
                            queuedPlayers.remove(playerName)
                        }

                        429 -> {
                            queuedPlayers.remove(playerName)
                            queuedPlayers.add(playerName)
                        }

                        else -> {
                            val player = Bukkit.getPlayer(playerName)
                            player?.scheduler?.runDelayed(Main.instance, {
                                player.kick(miniMessage.deserialize(configManager.unbindMessage))
                            }, null, 1L)
                            queuedPlayers.remove(playerName)
                        }
                    }
                }
            }
        }
    }

    fun stopCycleJob() {
        scheduledJob?.cancel()
        resetJob?.cancel()
        resetJob = null
        scheduledJob = null
    }

    fun stopScope() {
        scope.cancel()
    }
}