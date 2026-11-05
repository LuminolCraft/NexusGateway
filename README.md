# NexusGateway

Paper/Folia 服务器的进服网关插件：玩家登录时向 Nexus 后端校验 Minecraft 账号绑定状态，未绑定则按配置的 MiniMessage 文案踢出。

## 项目简介

NexusGateway 是 LuminolCraft（Luminol-Nexus 平台）的服务器侧网关。玩家进服时，插件异步请求后端的账号反查接口，确认该角色名是否已绑定网站账号，从而实现“先绑账号、再进服”的准入控制。

适用场景：需要把 Minecraft 服务器与网站账号体系打通、按绑定状态管控进服的 Paper/Folia 服务器。

## 核心特性

- **进服绑定门禁**：`AsyncPlayerPreLoginEvent` 阶段校验，未绑定用 `unbind-message` 踢出
- **绑定结果缓存**：缓存过期时间由 exp4j 表达式按玩家每周加入次数动态计算
- **限流与补查队列**：本地 60 次/分钟配额，超限请求入队由后台协程补查；后端 429 同样入队重试
- **缓存持久化**：`playerCache.json` 启动加载、周期落盘、cron 表达式定期清空
- **配置热重载**：`/nexusgateway reload` 重读配置并重建 HTTP 客户端与定时任务
- **Folia 支持**：`folia-supported: true`，踢人等实体操作调度到实体线程
- **依赖隔离**：exp4j、cron-utils 经 Shadow 重定位，避免与其他插件冲突

## 技术栈

| 组件 | 版本/说明 |
| --- | --- |
| 语言 | Kotlin 2.5.0-Beta1 |
| 服务端 API | Paper API 26.2（`api-version: 26.2`，Folia 支持） |
| HTTP 客户端 | Ktor CIO 3.5.2 + kotlinx-coroutines 1.10.2 |
| 表达式计算 | exp4j 0.4.8（重定位到 `org.luminolcraft.nexusGateway.shaded.exp4j`） |
| Cron 解析 | cron-utils 9.2.1（Quartz 方言，重定位到 `...shaded.cronutils`） |
| 构建 | Gradle + Shadow 9.6.1，JVM toolchain 25 |

## 目录结构

```
NexusGateway/
├── build.gradle.kts              # 依赖与 Shadow 重定位配置
├── settings.gradle.kts
├── gradle.properties             # group=org.luminolcraft, version=0.1
├── gradlew / gradlew.bat         # Gradle wrapper 脚本
├── gradle/wrapper/               # gradle-wrapper.jar + gradle-wrapper.properties（9.4.0）
└── src/main/
    ├── resources/plugin.yml      # 插件声明、命令、权限
    └── kotlin/org/luminolcraft/nexusGateway/
        ├── Main.kt               # 生命周期、进服事件、HTTP 客户端、定时任务
        ├── ConfigManager.kt      # 配置读写校验、playerCache.json、表达式解析
        └── QueueManager.kt       # 超限/429 请求的补查队列
```

## 快速开始

### 环境要求

- JDK 25（构建 toolchain 25）
- Gradle wrapper（仓库已自带 `gradlew` / `gradlew.bat` 与 `gradle-wrapper.jar`，首次运行自动使用 Gradle 9.4.0）
- 服务端：Paper / Folia，`api-version` 26.2
- 一个可用的 Luminol-Nexus 后端地址与 `WEBHOOK_SECRET`

### 构建

```bash
./gradlew build        # Windows: gradlew.bat build
```

产物：`build/libs/NexusGateway-0.1-all.jar`（Shadow fat jar）。

### 安装

1. 将 `NexusGateway-0.1-all.jar` 放入服务端 `plugins/` 目录
2. 启动服务端，插件自动生成 `plugins/NexusGateway/config.yml` 与 `playerCache.json`
3. 编辑 `config.yml`，填入后端地址与 Secret，然后重载或重启

## 配置说明

`config.yml` 首次启动自动生成，缺失或类型错误的键会补回默认值并写入文件。

| 键 | 类型 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `api-base-url` | String | `http://localhost:8080` | Nexus 后端地址，仅接受 http/https；生产必须 HTTPS |
| `secret` | String | `some-fucking-secret` | 与后端 `WEBHOOK_SECRET` 一致，经 `X-Webhook-Secret` 请求头发送 |
| `unbind-message` | String | `<red><bold>您当前账户未被绑定</bold></red>` | 未绑定踢出提示（MiniMessage，原样存储不校验） |
| `connection-timeout-sec` | Int | `5` | 连接超时（秒），<=0 回退默认 |
| `request-timeout-sec` | Int | `10` | 请求超时（秒），<=0 回退默认 |
| `player-cache-expires-expression` | String | `5+(120-5)*(x/(120+x))` | exp4j 表达式，缓存时长（分钟），`x` = 玩家每周加入次数；A/B 直接改串内数字 |
| `cache-flush-interval-min` | Int | `5` | `playerCache.json` 落盘间隔（分钟），<=0 回退默认 |
| `reset-cache-cron` | String | `0 0 0 ? * MON` | Quartz 方言 cron，清空缓存的时刻；格式非法回退默认值 |

> 注意：后端本地开发地址默认为 `http://localhost:8787`，与插件默认值不同，按实际部署地址修改 `api-base-url`。

## 命令与权限

| 命令 | 权限 | 说明 |
| --- | --- | --- |
| `/nexusgateway reload`（别名 `/ng`） | `nexusgateway.usage`（default: op） | 重载配置：重读 config.yml、重建 HTTP 客户端与定时任务 |

## 与后端的交互

插件向后端发起：

```
GET {api-base-url}/api/v1/internal/minecraft/name/{playerName}
X-Webhook-Secret: {secret}
```

| HTTP 状态 | 后端语义 | 插件行为 |
| --- | --- | --- |
| 200 | 已绑定 | 放行，写入缓存 |
| 404 | 未绑定 | 踢出并发送 `unbind-message` |
| 403 | Secret 缺失或错误 | 记录 `Wrong secret! default pass.` 并放行 |
| 429 | 后端限流 | 放行并加入补查队列 |
| 其他 | — | 视为未绑定，踢出 |

本地 60 次/分钟配额用尽时，请求入队并放行，由队列按分钟窗口补查；连接被拒绝时队列冷却 5 分钟重试。

## 开发与测试

```bash
./gradlew build     # 编译 + Shadow 打包
./gradlew test      # <!-- TODO: 待补充：当前未配置测试框架 -->
```

<!-- TODO: 待补充：CI 配置、代码风格检查（ktlint/detekt 等） -->

## 贡献指南

<!-- TODO: 待补充：分支策略、提交规范（当前提交使用 Conventional Commits）、PR 流程 -->

## 路线图

<!-- TODO: 待补充：路线图（例如接入后端 verify/avatar/ban 同步端点、解析 error.code 区分 404/500、按 resetAt 退避等） -->

## FAQ / 已知限制

- **后端 Secret 错误会放行所有人**：当前实现把 403 当作服务端配置错误而放行（fail-open），配置错时门禁实际失效，请核对 `secret` 与后端 `WEBHOOK_SECRET`。
- **后端返回 500 时玩家会被踢**：非 200/403/429 状态当前按未绑定处理。
- **名称精确匹配**：后端按角色名精确匹配，游戏内改名后需重新核验绑定，否则可能被判未绑定。
- **限流维度**：后端另有按来源 IP 60 次/分钟的限流，多台服务器共享出口 IP 时可能互相挤占。
- **缓存文件**：`playerCache.json` 损坏时启动回退为空缓存，不中断加载。

<!-- TODO: 待补充：常见问题、性能与兼容性说明 -->

## 许可证

<!-- TODO: 待补充：仓库尚未包含 LICENSE 文件 -->

## 联系方式

<!-- TODO: 待补充：维护者联系方式 / 交流群 / Issue 入口 -->
