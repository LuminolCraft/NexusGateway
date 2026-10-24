package org.luminolcraft.nexusGateway

import com.google.gson.Gson
import net.objecthunter.exp4j.Expression
import net.objecthunter.exp4j.ExpressionBuilder
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.util.logging.Logger

class ConfigManager {
    val gson = Gson()
    val dataFolder: File = Main.instance.dataFolder
    val configFile = File(dataFolder, "config.yml")
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
    var playerCacheExpiresMinutes: Double = 0.0

    @Volatile
    var playerCacheExpiresEvaluator: Expression? = null

    @Volatile
    var cacheFlushIntervalMin: Int = DEFAULT_CACHE_FLUSH_INTERVAL_MIN

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
        shouldSave = ensureInt(KEY_CONNECTION_TIMEOUT_SEC, DEFAULT_CONNECTION_TIMEOUT_SEC, COMMENT_CONNECTION_TIMEOUT_SEC) || shouldSave
        shouldSave = ensureInt(KEY_REQUEST_TIMEOUT_SEC, DEFAULT_REQUEST_TIMEOUT_SEC, COMMENT_REQUEST_TIMEOUT_SEC) || shouldSave
        shouldSave = ensureString(KEY_EXPIRES_EXPRESSION, DEFAULT_EXPIRES_EXPRESSION, COMMENT_EXPIRES_EXPRESSION) || shouldSave
        shouldSave = ensureInt(KEY_CACHE_FLUSH_INTERVAL_MIN, DEFAULT_CACHE_FLUSH_INTERVAL_MIN, COMMENT_CACHE_FLUSH_INTERVAL_MIN) || shouldSave
        if (shouldSave) {
            runCatching { config.save(configFile) }.onFailure {
                logger.warning("保存 config.yml 失败: ${it.message}")
            }
        }
    }

    fun loadConfig() {
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
        val rawExpression = config.getString(KEY_EXPIRES_EXPRESSION)?.trim().orEmpty()
        playerCacheExpiresExpression = if (rawExpression.isEmpty()) DEFAULT_EXPIRES_EXPRESSION else rawExpression

        var evaluator = buildExpression(playerCacheExpiresExpression)
        if (evaluator == null) {
            logger.warning("player-cache-expires-expression 无法解析，改用默认表达式: $DEFAULT_EXPIRES_EXPRESSION")
            playerCacheExpiresExpression = DEFAULT_EXPIRES_EXPRESSION
            evaluator = buildExpression(DEFAULT_EXPIRES_EXPRESSION)
        }

        var minutes = evaluator?.let { evaluateExpiresMinutes(it) } ?: Double.NaN
        if (!minutes.isFinite() || minutes <= 0.0) {
            logger.warning("player-cache-expires-expression 求值无效($minutes)，改用默认表达式: $DEFAULT_EXPIRES_EXPRESSION")
            evaluator = buildExpression(DEFAULT_EXPIRES_EXPRESSION)
            minutes = evaluator?.let { evaluateExpiresMinutes(it) } ?: Double.NaN
        }
        if (!minutes.isFinite() || minutes <= 0.0) {
            logger.warning("默认表达式仍无法求值，玩家缓存过期时间回退为 0")
            minutes = 0.0
        }
        playerCacheExpiresMinutes = minutes
        playerCacheExpiresEvaluator = evaluator
    }

    fun reloadConfig() {
        config = YamlConfiguration.loadConfiguration(configFile)
        initConfig()
        loadConfig()
    }

    fun initPlayerCacheFile() {
        ensureFileExists(playerCacheFile)
    }

    fun loadPlayerCache() {
        // TODO: 由用户实现 playerCache.json 的读取。
    }

    fun savePlayerCache() {
        // TODO: 由用户实现 playerCache.json 的写入。
    }

    private fun buildExpression(source: String): Expression? = runCatching {
        ExpressionBuilder(source).variable(VARIABLE_X).build()
    }.getOrElse {
        logger.warning("player-cache-expires-expression 解析失败: ${it.message}")
        null
    }

    private fun evaluateExpiresMinutes(expression: Expression): Double = runCatching {
        expression.setVariable(VARIABLE_X, PLACEHOLDER_PLAYER_WEEKLY_JOIN_COUNT.toDouble())
        expression.evaluate()
    }.getOrElse {
        logger.warning("player-cache-expires-expression 求值失败: ${it.message}")
        Double.NaN
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

    companion object {
        private const val MILLIS_PER_MINUTE = 60_000L

        private const val DEFAULT_API_BASE_URL = "http://localhost:8080"
        private const val DEFAULT_SECRET = "some-fucking-secret"
        private const val DEFAULT_UNBIND_MESSAGE = "<red><bold>您当前账户未被绑定</bold></red>"
        private const val DEFAULT_CONNECTION_TIMEOUT_SEC = 5
        private const val DEFAULT_REQUEST_TIMEOUT_SEC = 10
        private const val DEFAULT_EXPIRES_EXPRESSION = "5+(120-5)*(x/(120+x))"
        private const val DEFAULT_CACHE_FLUSH_INTERVAL_MIN = 5

        private const val KEY_API_BASE_URL = "api-base-url"
        private const val KEY_SECRET = "secret"
        private const val KEY_UNBIND_MESSAGE = "unbind-message"
        private const val KEY_CONNECTION_TIMEOUT_SEC = "connection-timeout-sec"
        private const val KEY_REQUEST_TIMEOUT_SEC = "request-timeout-sec"
        private const val KEY_EXPIRES_EXPRESSION = "player-cache-expires-expression"
        private const val KEY_CACHE_FLUSH_INTERVAL_MIN = "cache-flush-interval-min"

        private const val VARIABLE_X = "x"

        // TODO: 接入真实“玩家每周加入次数”后替换该占位常量。
        private const val PLACEHOLDER_PLAYER_WEEKLY_JOIN_COUNT: Int = 1

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
    }
}
