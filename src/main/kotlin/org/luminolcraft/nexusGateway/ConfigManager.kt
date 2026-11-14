package org.luminolcraft.nexusGateway

import com.cronutils.model.Cron
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.objecthunter.exp4j.Expression
import net.objecthunter.exp4j.ExpressionBuilder
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

class ConfigManager {
    val gson = Gson()
    val dataFolder: File = Main.instance.dataFolder
    val configFile = File(dataFolder, "config.yml")
    val playerCacheMap = ConcurrentHashMap<String, PlayerCacheData>()
    val playerCacheFile = File(dataFolder, "playerCache.json")

    var config: FileConfiguration = YamlConfiguration.loadConfiguration(configFile)
        private set

    @Volatile
    var apiBaseUrl: String = DEFAULT_API_BASE_URL

    @Volatile
    var secret: String = DEFAULT_SECRET

    @Volatile
    var unbindMessage: String = DEFAULT_UNBIND_MESSAGE

    @Volatile
    var connectionTimeoutSec: Int = DEFAULT_CONNECTION_TIMEOUT_SEC

    @Volatile
    var requestTimeoutSec: Int = DEFAULT_REQUEST_TIMEOUT_SEC

    @Volatile
    var playerCacheExpiresExpression: String = DEFAULT_EXPIRES_EXPRESSION

    @Volatile
    var fixedCacheExpiresMin = false

    @Volatile
    var cacheFlushIntervalMin: Int = DEFAULT_CACHE_FLUSH_INTERVAL_MIN

    @Volatile
    var resetCacheCron: String = DEFAULT_RESET_CACHE_CRON

    @Volatile
    lateinit var resetCron: Cron

    @Volatile
    lateinit var threadLocalExpression: ThreadLocal<Expression>

    val cacheFlushIntervalMillis: Long
        get() = cacheFlushIntervalMin * MILLIS_PER_MINUTE

    private val logger: Logger
        get() = Main.instance.logger

    fun initConfig() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.warning("无法创建数据目录: ${dataFolder.absolutePath}")
        }
        ensureFileExists(configFile)
        initPlayerCacheFile()

        var shouldSave = false
        shouldSave = ensureString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL, COMMENT_API_BASE_URL) || shouldSave
        shouldSave = ensureString(KEY_SECRET, DEFAULT_SECRET, COMMENT_SECRET) || shouldSave
        shouldSave = ensureString(KEY_UNBIND_MESSAGE, DEFAULT_UNBIND_MESSAGE, COMMENT_UNBIND_MESSAGE) || shouldSave
        shouldSave = ensureInt(
            KEY_CONNECTION_TIMEOUT_SEC,
            DEFAULT_CONNECTION_TIMEOUT_SEC,
            COMMENT_CONNECTION_TIMEOUT_SEC
        ) || shouldSave
        shouldSave =
            ensureInt(KEY_REQUEST_TIMEOUT_SEC, DEFAULT_REQUEST_TIMEOUT_SEC, COMMENT_REQUEST_TIMEOUT_SEC) || shouldSave
        shouldSave =
            ensureString(KEY_EXPIRES_EXPRESSION, DEFAULT_EXPIRES_EXPRESSION, COMMENT_EXPIRES_EXPRESSION) || shouldSave
        shouldSave = ensureInt(
            KEY_CACHE_FLUSH_INTERVAL_MIN,
            DEFAULT_CACHE_FLUSH_INTERVAL_MIN,
            COMMENT_CACHE_FLUSH_INTERVAL_MIN
        ) || shouldSave
        shouldSave =
            ensureString(KEY_RESET_CACHE_CRON, DEFAULT_RESET_CACHE_CRON, COMMENT_RESET_CACHE_CRON) || shouldSave
        if (shouldSave) {
            runCatching { config.save(configFile) }.onFailure {
                logger.warning("保存 config.yml 失败: ${it.message}")
            }
        }
    }

    fun loadConfig() {
        // 每次重新加载都从"表达式可用"开始判定：否则配置里把表达式改好后 reload，
        // 上一次置 true 的固定兜底会一直生效，缓存永远只有 5 分钟。
        fixedCacheExpiresMin = false

        // 只接受 http/https 且必须有 host，非法配置回退默认地址
        apiBaseUrl = run {
            val trimmed = config.getString(KEY_API_BASE_URL)?.trim().orEmpty()
            if (trimmed.isEmpty()) {
                DEFAULT_API_BASE_URL
            } else {
                runCatching {
                    val uri = URI.create(trimmed)
                    require(uri.scheme?.lowercase() == "http" || uri.scheme?.lowercase() == "https") {
                        "scheme must be http or https"
                    }
                    require(!uri.host.isNullOrBlank()) { "host is missing" }
                    trimmed.trimEnd('/')
                }.getOrElse {
                    logger.warning("api-base-url 无效(${it.message})，回退默认值: $DEFAULT_API_BASE_URL")
                    DEFAULT_API_BASE_URL
                }
            }
        }
        // 空 secret 视为配置缺失，回退默认值并告警
        secret = config.getString(KEY_SECRET)?.takeIf { it.isNotBlank() } ?: run {
            logger.warning("secret 为空，回退默认值")
            DEFAULT_SECRET
        }
        unbindMessage = config.getString(KEY_UNBIND_MESSAGE)?.takeIf { it.isNotBlank() } ?: DEFAULT_UNBIND_MESSAGE
        connectionTimeoutSec = positiveOrDefault(
            config.getInt(KEY_CONNECTION_TIMEOUT_SEC), DEFAULT_CONNECTION_TIMEOUT_SEC, KEY_CONNECTION_TIMEOUT_SEC
        )
        requestTimeoutSec = positiveOrDefault(
            config.getInt(KEY_REQUEST_TIMEOUT_SEC), DEFAULT_REQUEST_TIMEOUT_SEC, KEY_REQUEST_TIMEOUT_SEC
        )
        cacheFlushIntervalMin = positiveOrDefault(
            config.getInt(KEY_CACHE_FLUSH_INTERVAL_MIN), DEFAULT_CACHE_FLUSH_INTERVAL_MIN, KEY_CACHE_FLUSH_INTERVAL_MIN
        )
        // 用 Quartz 方言校验 cron；非法配置回退默认值，保证 resetCron 一定可用
        val rawResetCacheCron = config.getString(KEY_RESET_CACHE_CRON)?.trim().orEmpty()
        resetCacheCron = runCatching {
            resetCron = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
                .parse(rawResetCacheCron)
                .validate()
            rawResetCacheCron
        }.getOrElse {
            logger.warning("reset-cache-cron 无效($rawResetCacheCron)，回退默认值: $DEFAULT_RESET_CACHE_CRON")
            resetCron = CronParser(CronDefinitionBuilder.instanceDefinitionFor(CronType.QUARTZ))
                .parse(DEFAULT_RESET_CACHE_CRON)
            DEFAULT_RESET_CACHE_CRON
        }

        // 表达式解析失败时退回内置默认表达式，避免启动即失效
        val rawExpression = config.getString(KEY_EXPIRES_EXPRESSION)?.trim().orEmpty()
        playerCacheExpiresExpression = if (rawExpression.isEmpty()) DEFAULT_EXPIRES_EXPRESSION else rawExpression

        var evaluator = buildExpression(playerCacheExpiresExpression)
        if (evaluator == null) {
            logger.warning("player-cache-expires-expression 无法解析，改用默认表达式: $DEFAULT_EXPIRES_EXPRESSION")
            playerCacheExpiresExpression = DEFAULT_EXPIRES_EXPRESSION
            evaluator = buildExpression(DEFAULT_EXPIRES_EXPRESSION)
        }

        var minutes =
            evaluator?.let {
                getPlayerCacheExpiresMinutes(
                    it
                )
            } ?: Long.MIN_VALUE
        if (minutes <= 0L) {
            logger.warning("player-cache-expires-expression 求值无效($minutes)，改用默认表达式: $DEFAULT_EXPIRES_EXPRESSION")
            evaluator = buildExpression(DEFAULT_EXPIRES_EXPRESSION)
            minutes =
                evaluator?.let {
                    getPlayerCacheExpiresMinutes(
                        it
                    )
                } ?: Long.MIN_VALUE
        }
        // 两次求值都失败说明表达式不可用，改用固定 5 分钟兜底
        if (minutes <= 0L) {
            logger.warning("默认表达式仍无法求值，玩家缓存过期时间回退为 5 min.")
            fixedCacheExpiresMin = true
        }
        // exp4j 的 Expression 非线程安全，每线程各建一个实例，避免并发 setVariable 互相污染
        threadLocalExpression = ThreadLocal.withInitial { buildExpression(playerCacheExpiresExpression)!! }
    }

    fun reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile)
        Main.httpClient.close()
        Main.stopJobs()
        initConfig()
        loadConfig()
        Main.initHttpClient()
        Main.initSaveCacheTimerJob()
        Main.initCacheResetJob()
    }

    fun initPlayerCacheFile() {
        ensureFileExists(playerCacheFile)
    }

    fun loadPlayerCache() {
        if (playerCacheFile.length() == 0L) return
        val typeToken = object : TypeToken<ConcurrentHashMap<String, PlayerCacheData>>() {}.type
        // 缓存文件损坏（或内容为字面量 null）时回退空表，避免启动阶段直接失败。
        // gson 的返回值是平台类型，文件内容为 "null" 时会返回 null，必须显式兜底，
        // 否则 putAll(null) 直接 NPE。
        val addMap = runCatching {
            gson.fromJson<ConcurrentHashMap<String, PlayerCacheData>>(
                playerCacheFile.bufferedReader(Charsets.UTF_8),
                typeToken
            )
        }.getOrNull() ?: ConcurrentHashMap()
        playerCacheMap.putAll(addMap)
    }

    fun savePlayerCache() {
        // 先写临时文件再一次性替换：直接覆盖时进程在写一半被杀会留下残缺 JSON，
        // 下次启动只能整体丢弃缓存。同卷 rename 在 NTFS / ext4 上是原子的。
        val tmp = File(dataFolder, playerCacheFile.name + ".tmp")
        tmp.bufferedWriter(Charsets.UTF_8).use {
            it.write(gson.toJson(playerCacheMap))
        }
        runCatching {
            Files.move(
                tmp.toPath(), playerCacheFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        }.onFailure {
            // 个别文件系统不支持原子替换，退回普通覆盖（仍比直接写目标文件安全）
            Files.move(tmp.toPath(), playerCacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // 清空内存缓存并截断文件，保证内存与磁盘一致
    fun resetPlayerCache() {
        playerCacheMap.clear()
        playerCacheFile.bufferedWriter(Charsets.UTF_8).use {
            it.write("")
        }
    }

    fun buildExpression(source: String): Expression? = runCatching {
        ExpressionBuilder(source).variable(VARIABLE_X).build()
    }.getOrElse {
        logger.warning("player-cache-expires-expression 解析失败: ${it.message}")
        null
    }

    fun getPlayerCacheExpiresMinutes(expression: Expression, joinCount: Long = 1L): Long = runCatching {
        expression.setVariable(VARIABLE_X, joinCount.toDouble()).evaluate().toLong()
    }.getOrElse {
        logger.warning("player-cache-expires-expression 求值失败: ${it.message}")
        Long.MIN_VALUE
    }

    private fun positiveOrDefault(value: Int, default: Int, key: String): Int {
        if (value > 0) return value
        logger.warning("$key 必须大于 0，回退默认值: $default")
        return default
    }

    private fun ensureString(path: String, default: String, comment: List<String>): Boolean {
        if (config.get(path) != null && config.isString(path)) return false
        config.set(path, default)
        config.setComments(path, comment)
        return true
    }

    private fun ensureInt(path: String, default: Int, comment: List<String>): Boolean {
        if (config.get(path) != null && config.isInt(path)) return false
        config.set(path, default)
        config.setComments(path, comment)
        return true
    }

    private fun ensureFileExists(file: File) {
        if (file.exists()) return
        runCatching { file.createNewFile() }.onFailure {
            logger.warning("无法创建文件 ${file.name}: ${it.message}")
        }
    }

    data class PlayerCacheData(@Volatile var timestamp: Long, @Volatile var playerJoinCount: Long)

    companion object {
        const val MILLIS_PER_MINUTE = 60_000L

        private const val DEFAULT_API_BASE_URL = "http://localhost:8080"
        private const val DEFAULT_SECRET = "some-fucking-secret"
        private const val DEFAULT_UNBIND_MESSAGE = "<red><bold>您当前账户未被绑定</bold></red>"
        private const val DEFAULT_CONNECTION_TIMEOUT_SEC = 5
        private const val DEFAULT_REQUEST_TIMEOUT_SEC = 10
        private const val DEFAULT_EXPIRES_EXPRESSION = "5+(120-5)*(x/(120+x))"
        private const val DEFAULT_CACHE_FLUSH_INTERVAL_MIN = 5
        private const val DEFAULT_RESET_CACHE_CRON = "0 0 0 ? * MON"

        private const val KEY_API_BASE_URL = "api-base-url"
        private const val KEY_SECRET = "secret"
        private const val KEY_UNBIND_MESSAGE = "unbind-message"
        private const val KEY_CONNECTION_TIMEOUT_SEC = "connection-timeout-sec"
        private const val KEY_REQUEST_TIMEOUT_SEC = "request-timeout-sec"
        private const val KEY_EXPIRES_EXPRESSION = "player-cache-expires-expression"
        private const val KEY_CACHE_FLUSH_INTERVAL_MIN = "cache-flush-interval-min"
        private const val KEY_RESET_CACHE_CRON = "reset-cache-cron"

        private const val VARIABLE_X = "x"

        private val COMMENT_API_BASE_URL = listOf(
            "Nexus 后端地址，生产环境建议使用 https。",
            "必须是 http/https，否则回退默认值。"
        )
        private val COMMENT_SECRET = listOf(
            "内部接口鉴权密钥，随请求头发送，请勿泄露。"
        )
        private val COMMENT_UNBIND_MESSAGE = listOf(
            "玩家账户未绑定时显示的 MiniMessage 文本（原样存储，不做格式校验）。"
        )
        private val COMMENT_CONNECTION_TIMEOUT_SEC = listOf(
            "建立连接超时，单位：秒。",
            "小于等于 0 时回退默认值。"
        )
        private val COMMENT_REQUEST_TIMEOUT_SEC = listOf(
            "请求超时，单位：秒。",
            "小于等于 0 时回退默认值。"
        )
        private val COMMENT_EXPIRES_EXPRESSION = listOf(
            "玩家缓存过期时间表达式（exp4j 语法），结果单位：分钟。",
            "变量 x 表示某玩家每周加入服务器的次数，运行时由插件提供。",
            "A、B 直接写成数字，改这两个数字即可调整参数；默认值中 A=5、B=120。"
        )
        private val COMMENT_CACHE_FLUSH_INTERVAL_MIN = listOf(
            "玩家缓存写盘间隔，单位：分钟。",
            "小于等于 0 时回退默认值。"
        )
        private val COMMENT_RESET_CACHE_CRON = listOf(
            "玩家缓存自动清空时间（Quartz 风格 cron，6/7 段）。",
            "默认 0 0 0 ? * MON 表示每周一 00:00:00。",
            "日/周字段必须有一个写 ?；格式非法时回退默认值。"
        )
    }
}
