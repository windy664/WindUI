/**
 * WindUI SDUI 协议定义 — 喂给 AI 的唯一契约。
 *
 * AI 只需生成一段 JSON：布局树 + 内嵌动作定义。
 * 无需 JS 引擎，所有逻辑通过结构化 JSON Action 表达。
 */

// ===== 布局协议 =====

type ComponentType =
  | "LinearLayout"   // 线性布局（垂直/水平）
  | "TextView"       // 文本
  | "Button"         // 按钮
  | "ImageView"      // 图片（从 MC 资源加载纹理）
  | "Model3D";       // 3D 模型/实体（MinecraftSurfaceView 承载；后端 item/entity/geckolib）

// Model3D props（3D 模型/实体）：
//   type:      后端。"item"(默认，vanilla 物品) / "entity"(vanilla 实体，待做) / "geckolib"(GeckoLib 模型，软依赖)
//   width/height: 面板尺寸（必填，SurfaceView 无内容尺寸）
//   scale:     渲染缩放（可选）
//   —— item 后端：model = 物品 id（如 "minecraft:diamond"）
//   —— geckolib 后端：geo = 模型资源（"ns:geo/xxx.geo.json"）、texture = 贴图（"ns:textures/xxx.png"）、
//      animFile = 动画文件（可选）、animation = 循环动画名（可选）、spin = 是否自转（默认 true）
// 客户端无对应后端/GeckoLib 未装/资源缺失时该节点跳过（优雅降级）。
// JS：ui.model(itemId, props) 或 ui.geckoModel(geo, texture, props)。

// ===== 动作定义 =====

type Action =
  | { action: "executeCommand"; args: [string] }        // 执行客户端命令
  | { action: "showToast"; args: [string] }              // 显示提示
  | { action: "closeScreen" }                            // 关闭界面
  | { action: "callback"; id: string; data?: string[] }  // 回调服务端事件（买卖/翻页等由服务端处理）
  | { action: "chain"; actions: Action[] }               // 串联多个动作

interface UIElement {
  /** 组件类型 */
  type: ComponentType;
  /** 可选控件标识（用于后续定向刷新/引用；当前渲染不依赖它） */
  id?: string;
  /** 组件属性 */
  props?: Record<string, any>;
  /** 子组件（仅容器类组件支持） */
  children?: UIElement[];
  /** 左键点击时执行的动作（单个或数组） */
  onClick?: Action | Action[];
  /** 右键（上下文）点击 —— 常用于「左键买 / 右键看详情」 */
  onRightClick?: Action | Action[];
  /** 中键点击 */
  onMiddleClick?: Action | Action[];
  /** 鼠标滚轮（按刻度触发）—— 翻页 / 调数量 */
  onScroll?: Action | Action[];
  /** 鼠标松开（抬起） */
  onRelease?: Action | Action[];
  /** 鼠标移入组件 —— 做高亮 / 动态提示 / 预览 */
  onHoverEnter?: Action | Action[];
  /** 鼠标移出组件 */
  onHoverLeave?: Action | Action[];
  /** 输入框(TextBox)文本变更（带 ~300ms 防抖）—— 做实时搜索/校验；
   *  配合动作里的 readback 把当前文本带回服务端：{ action:"callback", id:"search", readback:["输入框id"] } */
  onTextChange?: Action | Action[];
  /** 滑块(Slider)值变更 —— 仅在松手时触发一次；配合 readback 取最终值：
   *  { action:"callback", id:"setvol", readback:["滑块id"] } */
  onChange?: Action | Action[];
}

// 通用 props（补充）:
//   tip: string           鼠标悬停提示（任意组件）

// Slider props（滑块，SeekBar）:
//   value: number  初值（0~max）
//   max: number    上限（默认 100）
//   width: number  宽度 dp（默认 140）
//   id: string     供 readback 读回当前进度（onChange 松手时带回）

// ===== 组件属性参考 =====

// 通用 props（所有组件）:
//   padding: number (dp)
//   width / height: number(dp) | "match"(填满父容器) | "wrap"(自适应，默认)
//   alpha: number(0~1 透明度)  scale: number(缩放,1=原始)  tip: string(悬停提示)
//   visible: boolean  enable: boolean
// 组件复用/继承：用 JS 函数(定义一个返回节点的函数,传参调用)即可,比 DragonCore 的 extends 更灵活。

// LinearLayout props:
//   orientation: 0=水平, 1=垂直 (默认 1)
//   gravity: "center" | "start" | "end"

// TextView props:
//   text: string
//   textSize: number (sp)
//   textColor: string (如 "#FFD700")

// Button props:
//   text: string
//   textSize: number (sp)
//   textColor: string

// ImageView props:
//   src: string  资源标识 "namespace:path"，如 "minecraft:textures/item/diamond.png"
//   scaleType: "fitCenter"(默认) | "fitXY" | "center" | "centerCrop" | "centerInside" | "fitStart" | "fitEnd"
//   （通常配合 width/height 使用，因物品贴图仅 16×16）

// ===== 示例 =====
//
// {
//   "type": "Button",
//   "props": { "text": "点击传送" },
//   "onClick": [
//     { "action": "executeCommand", "args": ["/spawn"] },
//     { "action": "showToast", "args": ["正在传送..."] }
//   ]
// }
