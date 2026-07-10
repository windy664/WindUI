# WindUI 开发文档 / 交接说明

> 本文件是项目的**唯一权威交接文档**。接手者请先通读，再动代码。
> 最后更新：2026-07-10。代码注释为简体中文，本文亦然。

---

## 0. 一句话定位

**WindUI = 服务端驱动 UI（SDUI）框架 for Minecraft**：服务端（Velocity 代理，未来可 Bukkit）用 JSON/JS 描述界面，推给客户端（NeoForge + ModernUI）傻渲染。目标是**让 AI 能写 Minecraft UI**，并作为闭源 DragonCore（龙核）GUI 的现代化、自包含替代品。

核心范式类比：**服务端 React（RSC/SDUI）** —— 服务端跑逻辑产出组件树 JSON，客户端只负责把 JSON 渲染成 ModernUI View，零客户端脚本。

---

## 1. 架构总览

### 1.1 六个 Gradle 模块

```
core              纯逻辑，无运行时依赖，编到 Java 21（地板）
                  协议/解析/占位符/GUI注册表/WebSocket原语
client_modernui   跨 MC 版本共享的客户端渲染层（ModernUI），Java 21，mc-free
server_core       跨平台共享的服务端逻辑层（Rhino JS引擎+效果模型+GUI驱动+WS服务端），Java 21
neoforge_26_1_2   MC 26.1.2 客户端 glue，Java 25
neoforge_1_21_1   MC 1.21.1 客户端 glue，Java 21
velocity          Velocity 代理插件（服务端宿主），Java 25
```

**为什么共享层编 Java 21**：高版本运行时（26.1.2 的 Java 25）能加载低版本字节码，反之不行。所以 `core`/`client_modernui`/`server_core` 必须编到最低目标（Java 21），否则 1.21.1 侧无法依赖。**这是铁律。**

### 1.2 两个关键接缝（这是整个架构的命门）

所有跨层解耦都卡在这两个接口上，换传输/换平台只动接缝下面，上层逻辑一行不改：

- **服务端发送** → `server_core/UiHost` 接口（`sendUi/sendAsset/sendControl/sendPatch/sendHud/sendMessage`）。Velocity 实现 = `VelocityUiHost`。
- **客户端入站** → `client_modernui/net/WsClient.Callbacks` 接口（`onUi/onAsset/onControl/onPatch/onHud`）。各 neoforge 的 `WindUIClient` 注入实现。

还有几个注入接口（同样为了共享层 mc-free）：
- `ImageLoader`（图片加载，各版本用各自 ModernUI ImageStore）
- `ModelRenderer`（3D 模型渲染，各版本用 MinecraftSurfaceView）
- `SafeClientAPI`（客户端安全动作：closeScreen/showToast/executeCommand/playSound/sendServerEvent）

### 1.3 数据流（一次开界面）

```
服务端 /windui open shop
  → GuiDriver.open(UiHost, "shop")
  → 脚本优先：scripts/shop.js 的 render(ctx) → Rhino 跑 → 产出 UI JSON
  → UiHost.sendUi(json) → WsServer 按玩家 UUID 找 WS 连接 → 发 [UI][json]
客户端 WsClient 读到 → Callbacks.onUi(json)
  → Minecraft.execute → SDUIScreen.reload(json)（调度到 ModernUI UI 线程）
  → UIJsonParser.parse → ViewFactory.create（WidgetNode 树 → ModernUI View 树）
  → 渲染
点按钮 → ActionExecutor（callback 动作）→ SafeClientImpl.sendServerEvent
  → WsClient.sendEvent → 服务端 WsServer.onEvent → GuiDriver.event → 脚本 onEvent(ctx,event,data)
```

---

## 2. 传输层：独立端口 WebSocket（2026-07-10 大转向，**重点**）

### 2.1 背景：为什么换

**旧方案**是裸 Netty 注入 MC 自己的封包管线（客户端 `pipeline.addBefore("decoder", ...)` 读所有入站包 + serverbound 探针反推 packetId）。它导致了一个**卡真机的头号断线 bug**（config 阶段 clientbound `custom_payload` 被读坏成 21 亿长度，`duplicate()` 都没修好），还依赖管线名跨版本漂移、要限流大图。

**新方案**：一个跟 MC 连接完全无关的独立端口 WebSocket。整类断线 bug 从根上消失，天然全双工（上行不用再反推 packetId），无包大小限制，版本无关，以后能挂浏览器调试。

### 2.2 实现

- **`core/ws/`**（纯 java.net，零外部依赖，手写 RFC6455）：
  - `WsProtocol` — 应用层通道字节（UI/ASSET/CTRL/PATCH/HUD 下行，AUTH/HELLO/EVENT 上行），一条 WS 消息 = `[1字节channel][data]`，data 复用现有各 Codec。
  - `WsHandshake` — RFC6455 握手（服务端 accept-key / 客户端 upgrade）。
  - `WsConnection` — 帧编解码（掩码/分片重组/ping-pong/close）。**客户端出站加掩码，服务端不加**（RFC 要求）。写锁用 `ReentrantLock`（虚拟线程 Loom 下不钉载体）。
  - `WsBootstrap` — 引导载荷（`SENTINEL+host\tport\ttoken`，零宽哨兵）。
- **`server_core/ws/`**：
  - `WsServer` — `ServerSocket` accept 循环 + **每连接一条虚拟线程**（`Thread.ofVirtual()`，Java 21 Loom，扛上万连接）+ 会话表 `Map<UUID,WsConnection>` + 首帧 AUTH 鉴权。
  - `TokenStore` — token↔UUID（SecureRandom 192 位 hex，玩家断开清除）。
- **`client_modernui/net/WsClient`** — 一条 Socket 连出 + 握手 + 鉴权 + 读循环派发到 `Callbacks`。客户端单连接用平台线程（一条，无需虚拟）。

### 2.3 引导链路（唯一碰 MC 的一下，走官方高层事件，零 netty）

```
玩家进子服 → Velocity ServerPostConnectEvent
  → tokens.tokenFor(uuid) + player.sendMessage(哨兵系统消息含 {host,port,token})
客户端 → NeoForge 官方 ClientChatReceivedEvent 拦下（已 javap 确认对系统消息也触发+可取消）
  → setCanceled(true) 隐藏 → 解析 → WsClient.connect(host, port, token)
host 为空时回退：mc.getConnection().getConnection().channel().remoteAddress()（客户端连上代理的地址）
```

### 2.4 配置（`plugins/windui/windui.properties`，Velocity 侧 `WsSettings`）

```
ws-enabled=true
ws-bind=0.0.0.0          # 监听地址
ws-port=25599            # 独立端口，0=自动选。⚠️ 服主须放行防火墙
ws-public-host=          # 广播给客户端的 host，留空=客户端用它连上代理的地址
ws-max-message-kb=8192   # 单条消息上限（防 OOM）
```

### 2.5 回程兜底

`SafeClientImpl.sendServerEvent` 优先 `WsClient.sendEvent`；未连接/失败退回命令 `/windui event <id> [data]`（`UiCommand` 拦截，仍保留）。

---

## 3. 组件系统

### 3.1 组件全集（`ViewFactory` 的 type→create 映射）

| 类别 | 组件 |
|---|---|
| 布局 | `LinearLayout`（垂直/水平）、`FrameLayout`（绝对定位 x/y）、`ScrollView`、`ui.card`（玻璃卡） |
| 文本 | `TextView`、`Markdown`（ModernUI Markflow 引擎，标题/粗体/列表/代码/链接） |
| 交互 | `Button`、`TextBox`（EditText）、`Slider`（SeekBar）、`ItemSlot`（图标+数量角标+lore tooltip） |
| 图形 | `ImageView`、`Bar`（进度条）、`Divider`（分隔线）、`Model3D`（3D 模型/实体，见 §6） |

**通用样式属性**（所有组件）：`background`/`gradient`/`radius`/`border`/`borderWidth`/`gradientDir`/`margin`/`padding`/`alpha`/`visible`/`enable`/`scale`/`tip`（tooltip）/`width`/`height`（正整数dp/"match"/"wrap"）。

### 3.2 富交互事件（对齐 DragonCore 全套）

节点上声明：`onClick`/`onRightClick`/`onMiddleClick`/`onScroll`/`onHoverEnter`/`onHoverLeave`/`onRelease`/`onTextChange`（300ms 防抖）/`onChange`（Slider 松手触发）。值 = 单个动作对象或动作数组。

**动作类型**（`ActionExecutor`）：`executeCommand`/`showToast`/`closeScreen`/`playSound`/`callback`（回程服务端）/`chain`。`callback` 可带 `readback:["输入框id"]` → 点击时读输入框/滑块当前值，base64 编码带回（脚本读 `ctx.values.<id>`）。

### 3.3 增量更新（patch，避免重推整树）

服务端 `ctx.patch(id, props)` → `windui:patch` 通道 → 客户端按组件 `id` 局部改属性（text/textColor/textSize/visible/alpha/enable/scale/tip/value/max）。组件必须带 `id`。

### 3.4 HUD（非模态常驻层）

`ctx.hud(panels)` / `ctx.hudClear()` → `windui:hud` 通道 → 客户端 `HudOverlay`（各版本 glue，钩 `RenderGuiEvent.Post`）用 vanilla `GuiGraphics`(1.21.1)/`GuiGraphicsExtractor`(26.x) 直绘。锚定面板 + SDUI 子树（markdown/物品图标/进度条/分隔线），9 种 anchor。**不碰 ModernUI 离屏（躲开历史黑屏）**。文件驱动：`huds/*.json`，命令 `/windui hud [<id>|clear]`。

---

## 4. 服务端脚本（Rhino JS）

- **`server_core/script/UiScriptService`**：沙箱 Rhino（`setClassShutter(_->false)` 屏蔽所有 Java 类）+ 编译缓存（`ConcurrentHashMap`）+ 执行超时（2s，防 `while(true)`）。脚本写 `render(ctx)` 画界面 + `onEvent(ctx, event, data)` 处理交互。
- **`ctx` 字段**：player/uuid/server/ping/online/values（回读值）。
- **`ctx` 效果方法**：message（聊天§色）/toast/open/rerender/close/command/sound/patch/hud/hudClear。无状态事件模型（不驻留闭包，状态放服务端数据）。
- **`ui.*` 作者 helper**（PRELUDE 内置）：`ui.column/row/card/frame/scroll`（布局）、`ui.text/markdown/image/button/slot/input/slider/bar/divider/model/geckoModel`（组件）、`ui.on(node, events)`（挂事件）、`ui._merge`。
- **契约文件**：`core/schema/windui.d.ts` 是喂 AI 的唯一契约（组件类型 + 动作 + props）。**加组件务必同步更新它。**

**关键约定**：Rhino 是普通 Java 库（`org.mozilla:rhino:1.7.15`），不是模组。JS **只跑在服务端**（服主代码同信任级），客户端仍纯 ModernUI 傻渲染器、零 JS。velocity jar 手动打包 rhino（不引 shadow）。

---

## 5. 文件驱动配置

`plugins/windui/` 下四目录：`guis/*.json`（JSON 界面）、`scripts/*.js`（JS 脚本界面，脚本优先）、`huds/*.json`、`img/`（自定义 PNG，用 `img:xxx` 引用，服务端哈希后经 asset 通道下发）。

**默认文件塞在 jar 资源里**（`server_core/resources/windui/`），`load()` 从 jar 读进内存 → 磁盘目录同名文件覆盖（磁盘优先），**不往磁盘写默认文件**（改默认 = 从 jar 拷出放进磁盘目录改）。内置：guis 的 menu_main/shop_main/confirm/welcome，scripts 的 shop/showcase，huds 的 demo。

命令：`/windui open <id> [玩家]` / `reload` / `list` / `hud [id|clear]` / `event <id> [data]`（回程兜底）。

---

## 6. 3D 模型（Model3D，2026-07-10 开工，**未真机验**）

### 6.1 架构

`Model3D` 组件 → `ViewFactory` 委托注入的 `ModelRenderer`（client_modernui，mc-free 接口）→ 各 neoforge 的 `ModelRendererImpl` 用 ModernUI-MC 的 **`MinecraftSurfaceView`**（`onDraw(GuiGraphics/GuiGraphicsExtractor,...)` 每帧把 MC 渲染上下文交到手上）承载 3D 渲染。

**表面层是深水区**：把 3D 塞进 ModernUI View 区域，历史上 arc3d 离屏渲染在这台机器（MX450+Sodium+大整合包）黑屏过，只能真机验。所以策略是**先用零依赖的 vanilla 物品后端证明表面能画，再叠 GeckoLib**。

### 6.2 后端

- **item 后端**（两版都有，**表面证明用**）：`gg.renderItem`(1.21.1)/`gg.item`(26.x) 把物品画进面板中心。API 直接抄两版 `HudOverlay` 里已验证的。
- **geckolib 后端**（**仅 1.21.1**，`GeckoModelBackend`）：软依赖隔离——所有 GeckoLib 符号只在这个类，`ModelRendererImpl` 仅 `ModList.get().isLoaded("geckolib")` 真时才调它（缺席不 class-load 不 NoClassDefFound）。`compileOnly files('libs/geckolib-...jar')`，**不打包不反射**。用 `GeoObjectRenderer` + 最小 `GeoAnimatable`（系统时间推 tick）+ `GeoModel`（3 资源路径）+ `Lighting.setupForEntityInInventory` + 仿 InventoryScreen 变换（scale(s,-s,s) + Axis.YP 自转）。
- **entity 后端**：未做（`renderEntityInInventory`）。
- **26.x GeckoLib**：未做（5.x API + render-state churn，风险高，殿后）。

### 6.3 作者用法

```js
ui.model("minecraft:diamond_block", { width:72, height:72, scale:4 })          // item 后端
ui.geckoModel("ns:geo/x.geo.json", "ns:textures/x.png", { animation:"idle" })  // geckolib（需自备资源）
```

### 6.4 ⚠️ 状态：**只编译验证，渲染行为待真机**。定位/缩放/打光多半需微调（scale/spin 做成了 props）。

---

## 7. 构建与运行

```bash
./gradlew build          # 六模块全编 + 跑测试，出三 jar
./gradlew :core:test :server_core:test   # 只跑离线单测（WS/协议/脚本引擎）
```

**产物**：
- `neoforge_26_1_2/build/libs/windui-26.1.2-1.0-SNAPSHOT.jar`（客户端 mod，含 core+client_modernui）
- `neoforge_1_21_1/build/libs/windui-1.21.1-1.0-SNAPSHOT.jar`（客户端 mod）
- `velocity/build/libs/windui-velocity-1.0-SNAPSHOT.jar`（服务端插件，含 core+server_core+rhino）

**版本矩阵**：
| 模块 | MC | Java | NeoForge | ModernUI |
|---|---|---|---|---|
| neoforge_26_1_2 | 26.1.2 | 25 | 26.1.2.78 | 3.13.0.5（自包含 universal） |
| neoforge_1_21_1 | 1.21.1 | 21 | 21.1.209 | 3.13.0.1（Modrinth eMf1VSQd） |

**测试策略三层**：①服务端逻辑/协议 → JUnit（`gradlew test`，离线全绿）；②客户端渲染 → `gradlew :neoforge_*:runClient` 按 U 键开本地测试界面；③完整链路 → 真 Velocity + 后端 + 客户端。JUnit 覆盖不到客户端渲染/GL/真机网络。

---

## 8. 当前状态：已验证 vs 待真机

### ✅ 已完成 + 离线/编译验证
- SDUI 全栈（解析/渲染/事件/patch/HUD/asset 管线/协议版本协商）。
- 服务端 Rhino JS 引擎（编译缓存/超时/沙箱），JUnit 回归套件（core+server_core，含 WS）。
- **WebSocket 传输**（core.ws + WsServer + WsClient + 引导 + token 鉴权 + 虚拟线程），离线端到端过（回环 socket）。
- **3D Model3D 通链 + vanilla 物品后端 + GeckoLib 后端（1.21.1）**，六模块编译全绿，GeckoLib 未打包确认。
- DragonCore 第一二梯队 + HUD 全部补齐或反超。

### ⏳ 待真机验证（**接手后第一优先级**）
1. **WebSocket 转向**：装新三 jar → 进服看是否收到引导、WS 连上、`/windui open shop` 弹界面 + 按钮回调 + HUD、**确认那个 config 阶段断线 bug 随裸 netty 删除而消失**。
2. **Model3D item 表面证明**：`/windui open showcase` 看 Model3D 的钻石块能否在面板显示（`MinecraftSurfaceView` 表面路真机能不能画）。
3. **GeckoLib 渲染**：item 表面通了之后，装 GeckoLib + 自备模型资源 + `ui.geckoModel(...)` 验 3D（定位/缩放/打光多半要调）。

**历史教训（血泪，务必读）**：这台机器（MX450+Sodium+大整合包）曾让 26.2 arc3d 几何静默黑屏。但**别拿旧 blocker 套新症状**——曾误判 WS 黑屏是 arc3d 被用户打脸（"U 键本地测试一直好，26.1.2 是稳定版"）。诊断先信用户的对照实验。

---

## 9. 开发方向 / 路线图

### 近期（真机验证驱动）
1. 跑通 §8 的三个真机验证。WS 断线消失 = 解锁一切。
2. Model3D：item 表面通 → 调 GeckoLib 变换 → 加 entity 后端（`renderEntityInInventory`）。
3. WS 验通后可默认开 serverbound、上高频事件（scroll 免防抖 textChange）——现在 WS 全双工已天然支持。

### 中期（对标 DragonCore 剩余小众项，见 §10）
- 屏内可交互真实 ItemStack 格子（putClientSlotItem，能拿起/放下）。
- HUD 任意 PNG 图片、可点击 HUD。
- 2D 自由拖拽（DragonCore maxDistance）。
- 组件级异步占位符推送（patch 已覆盖大部分）。

### 远期
- 26.x 的 GeckoLib（5.x API + render-state 系统）。
- Bukkit/Paper 服务端宿主（写 `BukkitUiHost` 实现 `UiHost` + 薄 glue，server_core 引擎不动）。接 PAPI/经济做富占位符（现占位符在 Velocity 解析，代理拿不到余额等富数据）。
- 浏览器端调试面板（WebSocket 已天然支持）。

---

## 10. 对标 DragonCoreGui（龙核）差距

**本质**：DragonCoreGui 是闭源龙核客户端 mod（MC 1.12.2）的薄 API 壳。我们自建 ModernUI 渲染器。**我们领先**：不依赖闭源 mod、现代版本、代理原生跨子服、服务端 JS 面向 AI、WebSocket 传输。

**第一二梯队（富交互事件/组件级增量更新/tip/滑块/HUD/图片）已全部补齐或反超。** 还差的都是深水区小众项：
| 缺的 | 难点 |
|---|---|
| 屏内可交互真实 ItemStack 格子 | Screen 里桥接可交互 MC 物品栏（HUD 侧真物品已做） |
| HUD 任意 PNG / 可点击 HUD | HUD 纹理管线 / 光标锁定态命中判定 |
| 2D 自由拖拽 | 我们只有 Slider（一维） |
| 屏内 3D 实体/GeckoLib | 见 §6，进行中 |

**不算差距**：extends 组件继承（我们用 JS 函数组合，更强）；客户端零脚本（安全取舍）。

---

## 11. 约定与坑（**务必遵守，多为用户明确要求**）

- **不要反射**：能编译期引用就改 build 让它可见直接 new/调（GeckoLib 软依赖用 `ModList.isLoaded` 门控 + 类隔离，非反射）。
- **共享层编 Java 21**（§1.1 铁律）。
- **改传输/封包/渲染的盲改留最后**：历史黑屏/断线都在这类路；additive/refactor 先做，保持树绿。
- **加组件三处同步**：`ViewFactory`（渲染）+ `UIJsonParser`/`WidgetNode`（解析，多数自动）+ `schema/windui.d.ts`（AI 契约）。
- **API 别猜，javap 真 jar**：MC/ModernUI/GeckoLib 跨版本漂移严重。本项目大量用 `javap` 从真 jar 确认签名（编译过 = API 存在）。参考 jar：MC 21.1 在 `E:/Project/Antiloop/build/moddev/artifacts/`，ModernUI 26.2 universal 在 `E:/Project/ModernUI-MC/ci-out2/`，GeckoLib 在 `E:/Project/Craftlin-main/.minecraft/mods/`。
- **ModernUI View 操作只能在 ModernUI UI 线程**：`Core.executeOnUiThread`；从 MC Render 线程直接改必崩（`Core.checkUiThread`）。
- **26.x API 漂移**：`ResourceLocation→Identifier`、`.get()→.getValue()`、`GuiGraphics→GuiGraphicsExtractor`、`@EventBusSubscriber` 去 `bus=` 参数（1.26 总线合并）。1.21.1 侧 `bus=Bus.MOD` 仍需（总线拆分）。
- **菜单审美偏好**：蓝渐变玻璃、极简文字、交错布局；忌材质杂/配色花/文字啰嗦/呆板方阵。已有视觉系统 v1（`applyBackground`/`ui.card`/`SDUIScreen` 靛蓝渐变背景）。
- **可调数值做成 config/props**，别让用户当场挑魔数。
- **提交不署 Claude 名**，推送用户自己来。用户常自己构建，但本项目历史上也接受"你自己编译测试"（改传输/深水区时我会自己 `gradlew build` 验证）。

---

## 12. 关键文件地图

| 要改什么 | 看这里 |
|---|---|
| 传输/协议 | `core/ws/*`、`core/protocol/*`、`server_core/ws/*`、`client_modernui/net/WsClient` |
| 加/改组件 | `client_modernui/render/ViewFactory`、`core/parser/WidgetNode`、`core/schema/windui.d.ts` |
| 渲染宿主/线程 | `client_modernui/render/SDUIScreen` |
| 服务端逻辑/JS/helper | `server_core/GuiDriver`、`server_core/script/UiScriptService`（PRELUDE 在这） |
| Velocity 接线/引导/命令 | `velocity/WindUIPlugin`、`velocity/UiCommand`、`velocity/VelocityUiHost`、`velocity/WsSettings` |
| 客户端 glue（每版一份） | `neoforge_*/client/WindUIClient`、`SafeClientImpl`、`ModelRendererImpl`、`HudOverlay` |
| 3D/GeckoLib | `client_modernui/render/ModelRenderer`、`neoforge_1_21_1/client/GeckoModelBackend` |
| HUD 渲染 | `client_modernui/hud/*`、`neoforge_*/client/HudOverlay` |
| 默认界面/脚本 | `server_core/resources/windui/{guis,scripts,huds}/` |

---

## 13. 接手第一步建议

1. `./gradlew build` 确认本机能出三 jar（六模块全绿）。
2. 读本文 §2（WebSocket）+ §8（待真机）+ §11（约定）。
3. 装三 jar，跑 §8 的真机验证 1→2→3，把结果记下来——**这决定后续一切方向**（尤其 WS 断线是否消失、Model3D 表面能否画）。
4. 有真机结论后，按 §9 路线图推进。
