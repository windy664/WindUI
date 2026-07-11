/**
 * WindUI SDUI 协议定义 — 喂给 AI 的唯一契约。
 *
 * AI 只需生成一段 JSON：布局树 + 内嵌动作定义。
 * 无需客户端 JS 引擎，所有逻辑通过结构化 JSON Action 表达。
 */

// ===== 布局协议 =====

type ComponentType =
  | "LinearLayout"   // 线性布局（垂直/水平）
  | "FrameLayout"    // 叠层/绝对定位容器
  | "ScrollView"     // 竖向滚动容器
  | "TextView"       // 文本
  | "Markdown"       // Markdown 富文本
  | "Button"         // 按钮
  | "ImageView"      // 图片（MC 资源或服务端 asset）
  | "ItemSlot"       // 图标、数量角标和 tooltip 组成的物品格
  | "TextBox"        // 输入框
  | "Slider"         // 滑块
  | "Bar"            // 进度条
  | "Divider"        // 分隔线
  | "Model3D";       // 3D 模型/实体（MinecraftSurfaceView）

// Model3D props：
//   type: 后端。"item"(默认) / "entity" / "geckolib"(1.21.1 软依赖)
//   width/height: SurfaceView 面板尺寸，建议显式填写
//   modelScale: Surface 内 3D 内容缩放。新界面必须用该属性控制模型大小
//   scale: 通用 ModernUI View 缩放，会缩放整个 View；不要用它代替 modelScale
//
// item 后端：
//   model: 物品 id，如 "minecraft:diamond"
//
// entity 后端：
//   model: 生物实体 id，如 "minecraft:zombie"；仅支持 LivingEntity
//   modelScale: 传给原版物品栏实体渲染器的像素尺寸，默认 30
//
// geckolib 后端：
//   geo: 模型资源，如 "ns:geo/xxx.geo.json"
//   texture: 贴图资源，如 "ns:textures/xxx.png"
//   animFile: 动画文件，可选
//   animation: 循环动画名，可选
//   spin: 是否自转，默认 true
//
// 客户端无对应后端、GeckoLib 未安装或资源非法时，该节点会被跳过。
// JS helper：ui.model(itemId, props) / ui.entityModel(entityId, props) /
//            ui.geckoModel(geo, texture, props)。

// ===== 动作定义 =====

type Action =
  | { action: "executeCommand"; args: [string] }
  | { action: "showToast"; args: [string] }
  | { action: "closeScreen" }
  | { action: "playSound"; args: [string, string?, string?] }
  | {
      action: "callback";
      id: string;
      data?: string[];
      readback?: string[];
    }
  | { action: "chain"; actions: Action[] };

interface UIElement {
  /** 组件类型 */
  type: ComponentType;

  /** 可选控件标识，用于 patch 和输入回读 */
  id?: string;

  /** 组件属性 */
  props?: Record<string, any>;

  /** 子组件，仅容器类组件支持 */
  children?: UIElement[];

  /** 左键点击 */
  onClick?: Action | Action[];

  /** 右键/上下文点击 */
  onRightClick?: Action | Action[];

  /** 中键点击 */
  onMiddleClick?: Action | Action[];

  /** 鼠标滚轮 */
  onScroll?: Action | Action[];

  /** 鼠标松开 */
  onRelease?: Action | Action[];

  /** 鼠标移入 */
  onHoverEnter?: Action | Action[];

  /** 鼠标移出 */
  onHoverLeave?: Action | Action[];

  /** TextBox 文本变更，约 300ms 防抖 */
  onTextChange?: Action | Action[];

  /** Slider 松手时触发 */
  onChange?: Action | Action[];
}

// ===== 根节点专用 props =====
//
// dimBackground: boolean
//   是否绘制全屏遮罩，默认 true。设为 false 时 overlayColor 不生效。
// overlayColor: string
//   自定义全屏单色遮罩，支持 "#RRGGBB" / "#AARRGGBB"。
//   未设置时使用 WindUI 默认的靛蓝渐变。

// ===== 通用 props =====
//
// padding: number
// margin: number
// width / height: number | "match" | "wrap"
// background: string
// gradient: string[]
// gradientDir: "tb" | "lr" | "tlbr" | "brtl"
// radius: number
// border: string
// borderWidth: number
// alpha: number
// scale: number
// visible: boolean
// enable: boolean
// tip: string

// ===== 组件属性参考 =====
//
// LinearLayout:
//   orientation: 0 | 1
//   gravity: "center" | "start" | "end"
//
// FrameLayout 子节点:
//   x: number
//   y: number
//   gravity: "center" | "end" | "bottom"
//
// TextView / Markdown:
//   text: string
//   textSize: number
//   textColor: string
//
// Button:
//   text: string
//   textSize: number
//   textColor: string
//
// ImageView:
//   src: string
//   scaleType:
//     "fitCenter" | "fitXY" | "center" | "centerCrop" |
//     "centerInside" | "fitStart" | "fitEnd"
//
// ItemSlot:
//   icon: string
//   count: number
//   name: string
//   lore: string[]
//   size: number
//
// TextBox:
//   text: string
//   hint: string
//   textSize: number
//   textColor: string
//
// Slider:
//   value: number
//   max: number
//   width: number
//
// Bar:
//   value: number
//   width: number
//   height: number
//   radius: number
//   color: string
//   track: string
//
// Divider:
//   width: number | "match"
//   height: number
//   color: string
//
// Model3D:
//   type: "item" | "entity" | "geckolib"
//   model: string
//   modelScale: number
//   geo: string
//   texture: string
//   animFile: string
//   animation: string
//   spin: boolean

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
//
// {
//   "type": "Model3D",
//   "props": {
//     "type": "item",
//     "model": "minecraft:diamond_block",
//     "width": 72,
//     "height": 72,
//     "modelScale": 4
//   }
// }
