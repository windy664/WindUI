package org.windy.windui.client.render;

import icyllis.modernui.core.Context;
import icyllis.modernui.graphics.Image;
import icyllis.modernui.graphics.drawable.GradientDrawable;
import icyllis.modernui.markflow.Markflow;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.MotionEvent;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.Button;
import icyllis.modernui.widget.EditText;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.ImageView;
import icyllis.modernui.widget.LinearLayout;
import icyllis.modernui.widget.ScrollView;
import icyllis.modernui.widget.SeekBar;
import icyllis.modernui.widget.TextView;
import org.windy.windui.core.parser.WidgetNode;
import org.windy.windui.core.script.ActionExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 {@link WidgetNode} 树映射为 ModernUI View。
 * <p>
 * <b>跨 MC 版本共享类</b>：只依赖 {@code core} 与纯 ModernUI 的 view/graphics 类，不引用 mc 层与
 * net.minecraft。按钮行为经 {@link ActionExecutor}、图片加载经 {@link ImageLoader} 注入，故各版本复用。
 */
public final class ViewFactory {

    private final ActionExecutor executor;
    private final ImageLoader imageLoader;
    private final ModelRenderer modelRenderer;

    /** 带 id 的输入控件表（如 TextBox），供按钮 readback 读回当前值。每次 reload 前 {@link #clearRegistries()}。 */
    private final Map<String, View> inputs = new HashMap<>();

    /** 带 id 的所有控件表，供增量更新（{@code windui:patch}）按 id 定位 View。每次 reload 前清。 */
    private final Map<String, View> viewsById = new HashMap<>();

    public ViewFactory(ActionExecutor executor, ImageLoader imageLoader, ModelRenderer modelRenderer) {
        this.executor = executor;
        this.imageLoader = imageLoader;
        this.modelRenderer = modelRenderer;
    }

    /** 清空本次渲染登记的控件表（SDUIScreen 在每次重新渲染前调用）。 */
    public void clearRegistries() {
        inputs.clear();
        viewsById.clear();
    }

    /** 按 id 取已渲染的 View（增量更新用）；不存在返回 null。 */
    public View viewById(String id) {
        return viewsById.get(id);
    }

    public View create(WidgetNode node, Context ctx) {
        if (ctx == null) return null;

        View view = switch (node.type()) {
            case "LinearLayout" -> createLinearLayout(node, ctx);
            case "FrameLayout" -> createFrameLayout(node, ctx);
            case "ScrollView" -> createScrollView(node, ctx);
            case "TextView" -> createTextView(node, ctx);
            case "Markdown" -> createMarkdown(node, ctx);
            case "Button" -> createButton(node, ctx);
            case "ImageView" -> createImageView(node, ctx);
            case "ItemSlot" -> createItemSlot(node, ctx);
            case "TextBox" -> createTextBox(node, ctx);
            case "Slider" -> createSlider(node, ctx);
            case "Bar" -> createBar(node, ctx);
            case "Divider" -> createDivider(node, ctx);
            case "Model3D" -> createModel3D(node, ctx);
            default -> null;
        };

        if (view != null) {
            int pad = node.getInt("padding", 0);
            if (pad > 0) {
                int px = view.dp(pad);
                view.setPadding(px, px, px, px);
            }
            // 通用属性：所有组件共用
            applyBackground(view, node);
            float alpha = node.getFloat("alpha", 1f);
            if (alpha < 1f) view.setAlpha(Math.max(0f, alpha));
            float scale = node.getFloat("scale", 1f);
            if (scale != 1f) {
                view.setScaleX(scale);
                view.setScaleY(scale);
            }
            if (!node.getBool("visible", true)) view.setVisibility(View.GONE);
            if (!node.getBool("enable", true)) view.setEnabled(false);
            // 通用悬停提示：任意组件声明 tip 即挂 tooltip（ItemSlot 若已按 name/lore 建则被显式 tip 覆盖）
            String tip = node.getString("tip", null);
            if (tip != null) view.setTooltipText(tip);
            // 带 id 的组件登记，供增量更新按 id 定位
            String nid = node.id();
            if (nid != null && !nid.isEmpty()) viewsById.put(nid, view);
            // 通用事件：click / rightClick / hoverEnter / hoverLeave / textChange
            bindEvents(view, node);
        }

        return view;
    }

    /**
     * 增量更新：只把 {@code props} 里声明的属性覆盖到已渲染的 {@code view} 上（不重建 View）。
     * 支持 text/textColor/textSize（限 TextView 系）、visible/alpha/enable（通用）。
     * 复合属性（如 Bar 的 value、ItemSlot 的图标/数量）暂不支持局部改，忽略。
     * <p><b>须在 ModernUI UI 线程调用</b>（改 View 属性）——由 {@link SDUIScreen#applyPatch} 保证。
     */
    public void applyPatchProps(View view, Map<String, Object> props) {
        for (Map.Entry<String, Object> e : props.entrySet()) {
            Object v = e.getValue();
            switch (e.getKey()) {
                case "text" -> { if (view instanceof TextView tv) tv.setText(String.valueOf(v)); }
                case "textColor" -> { if (view instanceof TextView tv) tv.setTextColor(parseColor(String.valueOf(v))); }
                case "textSize" -> { if (view instanceof TextView tv && v instanceof Number n) tv.setTextSize(n.floatValue()); }
                case "visible" -> view.setVisibility(toBool(v) ? View.VISIBLE : View.GONE);
                case "alpha" -> { if (v instanceof Number n) view.setAlpha(Math.max(0f, Math.min(1f, n.floatValue()))); }
                case "scale" -> { if (v instanceof Number n) { view.setScaleX(n.floatValue()); view.setScaleY(n.floatValue()); } }
                case "enable" -> view.setEnabled(toBool(v));
                case "tip" -> view.setTooltipText(v == null ? null : String.valueOf(v));
                case "value" -> { if (view instanceof SeekBar sb && v instanceof Number n) sb.setProgress(n.intValue()); }
                case "max" -> { if (view instanceof SeekBar sb && v instanceof Number n) sb.setMax(n.intValue()); }
                default -> { /* 暂不支持局部改的属性，忽略 */ }
            }
        }
    }

    private static boolean toBool(Object v) {
        return v instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(v));
    }

    private View createLinearLayout(WidgetNode node, Context ctx) {
        var ll = new LinearLayout(ctx);
        ll.setOrientation(node.getInt("orientation", LinearLayout.VERTICAL));

        String gravity = node.getString("gravity", null);
        if ("center".equals(gravity)) ll.setGravity(Gravity.CENTER);
        else if ("start".equals(gravity)) ll.setGravity(Gravity.START);
        else if ("end".equals(gravity)) ll.setGravity(Gravity.END);

        for (WidgetNode child : node.children()) {
            View childView = create(child, ctx);
            if (childView != null) {
                var lp = new LinearLayout.LayoutParams(
                        resolveDim(child, "width", childView),
                        resolveDim(child, "height", childView));
                int m = child.getInt("margin", 0);
                if (m > 0) {
                    int mp = childView.dp(m);
                    lp.leftMargin = mp; lp.topMargin = mp; lp.rightMargin = mp; lp.bottomMargin = mp;
                }
                ll.addView(childView, lp);
            }
        }
        return ll;
    }

    /**
     * FrameLayout：叠层容器，支持子节点绝对定位（{@code x}/{@code y}，单位 dp，映射为左/上边距）。
     * 用于像素级精调布局（对齐 DragonCore 的绝对定位）。
     */
    private View createFrameLayout(WidgetNode node, Context ctx) {
        var fl = new FrameLayout(ctx);
        for (WidgetNode child : node.children()) {
            View cv = create(child, ctx);
            if (cv == null) continue;
            var lp = new FrameLayout.LayoutParams(
                    resolveDim(child, "width", cv), resolveDim(child, "height", cv));
            int x = child.getInt("x", 0);
            int y = child.getInt("y", 0);
            if (x != 0) lp.leftMargin = cv.dp(x);
            if (y != 0) lp.topMargin = cv.dp(y);
            String g = child.getString("gravity", null);
            if ("center".equals(g)) lp.gravity = Gravity.CENTER;
            else if ("end".equals(g)) lp.gravity = Gravity.END;
            else if ("bottom".equals(g)) lp.gravity = Gravity.BOTTOM;
            fl.addView(cv, lp);
        }
        return fl;
    }

    /**
     * ScrollView：竖向滚动容器，承载单个子节点（通常是一个 LinearLayout）。用于长列表（商品/成员）。
     */
    private View createScrollView(WidgetNode node, Context ctx) {
        var sv = new ScrollView(ctx);
        if (!node.children().isEmpty()) {
            WidgetNode childNode = node.children().get(0); // ScrollView 只承载一个子节点
            View child = create(childNode, ctx);
            if (child != null) {
                sv.addView(child, new FrameLayout.LayoutParams(
                        resolveDim(childNode, "width", child), resolveDim(childNode, "height", child)));
            }
        }
        return sv;
    }

    private View createImageView(WidgetNode node, Context ctx) {
        Image image = loadImage(node.getString("src", null));
        if (image == null) return null; // 加载失败，跳过该节点

        var iv = new ImageView(ctx);
        iv.setImage(image);
        iv.setScaleType(parseScaleType(node.getString("scaleType", "fitCenter")));
        iv.setAdjustViewBounds(true); // 单边指定尺寸时保持宽高比
        return iv;
    }

    /**
     * 加载图片：{@code src = "namespace:path"}，如 {@code "minecraft:textures/item/diamond.png"}。
     * 走注入的 {@link ImageLoader}（各版本用各自的 ModernUI ImageStore）。须 UI 线程，reload 即在此线程。
     * <p>将来 {@code "img:xxx"} 走服务端 asset 管线（Phase 3）也在此分流。
     */
    private Image loadImage(String src) {
        if (src == null || imageLoader == null) return null;
        int colon = src.indexOf(':');
        String namespace = colon > 0 ? src.substring(0, colon) : "minecraft";
        String path = colon > 0 ? src.substring(colon + 1) : src;
        // "asset:<hash>" = 服务端下发图，服务端已把 img:path 重写为此；查客户端 asset 缓存
        if ("asset".equals(namespace)) {
            return AssetCache.get(path);
        }
        // "img:<path>" 理论上服务端已重写为 asset:；万一漏了(找不到文件)优雅返 null 不误当 minecraft 纹理
        if ("img".equals(namespace)) {
            return null;
        }
        return imageLoader.load(namespace, path);
    }

    /**
     * ItemSlot 物品格：FrameLayout 叠层 = 图标 + 右下角数量角标 + hover 名称/lore 提示。
     * 复合控件在客户端合成，数据/图源全来自服务端（{@code icon}/{@code count}/{@code name}/{@code lore}）。
     */
    private View createItemSlot(WidgetNode node, Context ctx) {
        var frame = new FrameLayout(ctx);
        int size = node.getInt("size", 32);

        Image icon = loadImage(node.getString("icon", null));
        if (icon != null) {
            var iv = new ImageView(ctx);
            iv.setImage(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            frame.addView(iv, new FrameLayout.LayoutParams(iv.dp(size), iv.dp(size)));
        }

        int count = node.getInt("count", 0);
        if (count > 1) {
            var badge = new TextView(ctx);
            badge.setText(String.valueOf(count));
            badge.setTextSize(10);
            badge.setTextColor(0xFFFFFFFF);
            var lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.END; // 右下角
            frame.addView(badge, lp);
        }

        String tip = buildTooltip(node);
        if (tip != null) frame.setTooltipText(tip);

        return frame;
    }

    /**
     * 进度条 Bar：圆角轨道 + 按比例填充。props: value(0~1)/width/height/radius/color(填充)/track(底色)。
     * 适合 HP/经验/价格/冷却/加载等。
     */
    private View createBar(WidgetNode node, Context ctx) {
        int w = node.getInt("width", 120);
        int h = node.getInt("height", 8);
        int radius = node.getInt("radius", h / 2);
        float value = Math.max(0f, Math.min(1f, node.getFloat("value", 0f)));

        var frame = new FrameLayout(ctx);
        var track = new GradientDrawable();
        track.setColor(parseColor(node.getString("track", "#22FFFFFF")));
        track.setCornerRadius(frame.dp(radius));
        frame.setBackground(track);
        frame.setMinimumWidth(frame.dp(w));
        frame.setMinimumHeight(frame.dp(h));

        var fill = new View(ctx);
        var fd = new GradientDrawable();
        fd.setColor(parseColor(node.getString("color", "#66E0A3")));
        fd.setCornerRadius(frame.dp(radius));
        fill.setBackground(fd);
        int fillW = Math.round(frame.dp(w) * value);
        frame.addView(fill, new FrameLayout.LayoutParams(fillW, frame.dp(h)));
        return frame;
    }

    /**
     * Model3D 3D 模型/实体：委托注入的 {@link ModelRenderer} 用 MinecraftSurfaceView 承载 MC 3D 渲染
     * （各版本 glue 实现，含 vanilla 物品/实体后端 + GeckoLib 软依赖后端）。无渲染器/无法渲染时返 null
     * （节点跳过，优雅降级）。须显式给 width/height（SurfaceView 无内容尺寸）。
     */
    private View createModel3D(WidgetNode node, Context ctx) {
        return modelRenderer != null ? modelRenderer.createModelView(ctx, node) : null;
    }

    /**
     * 分隔线 Divider：一条细线。props: color/height(默认1)。宽度建议配 {@code width:"match"} 填满。
     */
    private View createDivider(WidgetNode node, Context ctx) {
        var v = new View(ctx);
        var d = new GradientDrawable();
        d.setColor(parseColor(node.getString("color", "#26FFFFFF")));
        v.setBackground(d);
        v.setMinimumHeight(v.dp(node.getInt("height", 1)));
        return v;
    }

    /**
     * TextBox 输入框：ModernUI EditText。props: hint(占位)/text(初值)/textSize/textColor/id。
     * <p><b>值回读</b>（读 {@link EditText#getText()} 随回调带回服务端）需事件通道从命令升级为插件消息包，
     * 见 Phase 1c/正规双向协议——当前先落地控件本身（显示+本地编辑）。
     */
    private View createTextBox(WidgetNode node, Context ctx) {
        var et = new EditText(ctx);
        et.setTextSize(node.getFloat("textSize", 14));
        String hint = node.getString("hint", null);
        if (hint != null) et.setHint(hint);
        String text = node.getString("text", null);
        if (text != null) et.setText(text);
        String color = node.getString("textColor", null);
        if (color != null) et.setTextColor(parseColor(color));
        // 带 id 则登记，供按钮 readback 读回当前输入值
        String id = node.id();
        if (id != null && !id.isEmpty()) inputs.put(id, et);
        return et;
    }

    /**
     * Slider 滑块：ModernUI SeekBar。props: value(初值)/max(默认100)/id。
     * <p>值回读同 TextBox（按 id 登记，readback 读 {@link SeekBar#getProgress()}）；变更回调走
     * {@code onChange} 事件，仅在**松手时**触发一次（避免拖动过程刷回程），见 {@link #bindEvents}。
     */
    private View createSlider(WidgetNode node, Context ctx) {
        var sb = new SeekBar(ctx);
        int max = node.getInt("max", 100);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, node.getInt("value", 0))));
        int w = node.getInt("width", 140);
        sb.setMinimumWidth(sb.dp(w));
        String id = node.id();
        if (id != null && !id.isEmpty()) inputs.put(id, sb);
        return sb;
    }

    /** 组装 hover 提示：首行名称，其余为 lore 各行。都无则返回 null。 */
    private static String buildTooltip(WidgetNode node) {
        StringBuilder sb = new StringBuilder();
        String name = node.getString("name", null);
        if (name != null) sb.append(name);
        if (node.props().get("lore") instanceof List<?> lines) {
            for (Object line : lines) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(String.valueOf(line));
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * 解析子节点的 width/height 属性为 LayoutParams 尺寸。
     * 支持：正整数(dp) / "match"(填满) / "wrap"(自适应，默认)。
     */
    private static int resolveDim(WidgetNode node, String key, View view) {
        Object v = node.props().get(key);
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("match") || s.equalsIgnoreCase("match_parent"))
                return ViewGroup.LayoutParams.MATCH_PARENT;
            if (s.equalsIgnoreCase("wrap") || s.equalsIgnoreCase("wrap_content"))
                return ViewGroup.LayoutParams.WRAP_CONTENT;
        }
        int dp = node.getInt(key, -1);
        return dp > 0 ? view.dp(dp) : ViewGroup.LayoutParams.WRAP_CONTENT;
    }

    private static ImageView.ScaleType parseScaleType(String s) {
        return switch (s == null ? "" : s.toLowerCase()) {
            case "fitxy" -> ImageView.ScaleType.FIT_XY;
            case "center" -> ImageView.ScaleType.CENTER;
            case "centercrop" -> ImageView.ScaleType.CENTER_CROP;
            case "centerinside" -> ImageView.ScaleType.CENTER_INSIDE;
            case "fitstart" -> ImageView.ScaleType.FIT_START;
            case "fitend" -> ImageView.ScaleType.FIT_END;
            default -> ImageView.ScaleType.FIT_CENTER;
        };
    }

    private View createTextView(WidgetNode node, Context ctx) {
        var tv = new TextView(ctx);
        tv.setText(node.getString("text", ""));
        tv.setTextSize(node.getFloat("textSize", 14));
        String color = node.getString("textColor", null);
        if (color != null) tv.setTextColor(parseColor(color));
        return tv;
    }

    /**
     * Markdown 组件：用 ModernUI 的 Markflow 引擎(内置 commonmark)把 markdown 文本渲成富文本。
     * 支持标题/粗体/斜体/列表/代码块/链接等。内联图片 {@code ![](...)} 需后续 MarkflowPlugin 接入
     * asset 管线，当前版本不渲染图片节点。
     */
    private View createMarkdown(WidgetNode node, Context ctx) {
        var tv = new TextView(ctx);
        tv.setTextSize(node.getFloat("textSize", 14));
        String color = node.getString("textColor", null);
        if (color != null) tv.setTextColor(parseColor(color));
        // Markflow.create(ctx).setMarkdown(view, md)：一次完成 解析+渲染+setText
        Markflow.create(ctx).setMarkdown(tv, node.getString("text", ""));
        return tv;
    }

    private View createButton(WidgetNode node, Context ctx) {
        var btn = new Button(ctx);
        btn.setText(node.getString("text", ""));
        btn.setTextSize(node.getFloat("textSize", 14));
        int accent = parseColor(node.getString("textColor", "#4FA3FF"));
        btn.setTextColor(accent);
        btn.setGravity(Gravity.CENTER);
        // 默认药丸：同色系半透明填充 + 描边（作者未显式 background/gradient 时）
        if (node.getString("background", null) == null && node.props().get("gradient") == null) {
            var pill = new GradientDrawable();
            pill.setColor(withAlpha(accent, 0x2E));
            pill.setStroke(btn.dp(1), withAlpha(accent, 0x88));
            pill.setCornerRadius(btn.dp(node.getInt("radius", 10)));
            btn.setBackground(pill);
        }
        int hp = btn.dp(16), vp = btn.dp(8);
        btn.setPadding(hp, vp, hp, vp);
        return btn;
    }

    /**
     * 通用背景样式：{@code background}(填充色) / {@code gradient}(渐变色数组) / {@code radius}(圆角 dp)
     * / {@code border}(描边色) + {@code borderWidth} / {@code gradientDir}(tb/lr/tlbr/brtl)。
     * 仅在指定了填充或渐变时生效（否则保留组件自身默认，如按钮药丸）。
     */
    private void applyBackground(View view, WidgetNode node) {
        Object grad = node.props().get("gradient");
        String bg = node.getString("background", null);
        if (grad == null && bg == null) return;

        var d = new GradientDrawable();
        if (grad instanceof List<?> cols && cols.size() >= 2) {
            int[] c = new int[cols.size()];
            for (int i = 0; i < c.length; i++) c[i] = parseColor(String.valueOf(cols.get(i)));
            d.setOrientation(parseOrientation(node.getString("gradientDir", "tb")));
            d.setColors(c);
        } else {
            d.setColor(parseColor(bg));
        }
        int radius = node.getInt("radius", 0);
        if (radius > 0) d.setCornerRadius(view.dp(radius));
        String border = node.getString("border", null);
        if (border != null) d.setStroke(view.dp(node.getInt("borderWidth", 1)), parseColor(border));
        view.setBackground(d);
    }

    private static GradientDrawable.Orientation parseOrientation(String s) {
        return switch (s == null ? "tb" : s.toLowerCase()) {
            case "lr" -> GradientDrawable.Orientation.LEFT_RIGHT;
            case "tlbr", "diag" -> GradientDrawable.Orientation.TL_BR;
            case "brtl" -> GradientDrawable.Orientation.BR_TL;
            default -> GradientDrawable.Orientation.TOP_BOTTOM;
        };
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** 文本变更事件的防抖窗口（毫秒）：逐字输入攒到空闲才发一次，避免回程刷屏。 */
    private static final long TEXT_CHANGE_DEBOUNCE_MS = 300;

    /**
     * 绑定节点声明的交互事件到 ModernUI 监听器。所有组件共用（在 {@link #create} 末尾统一调）。
     * <ul>
     *   <li>{@code click} → setOnClickListener</li>
     *   <li>{@code rightClick} → setOnContextClickListener（右键/上下文点击）</li>
     *   <li>{@code hoverEnter}/{@code hoverLeave} → setOnHoverListener（不消费，保留原生 hover 如 tooltip）</li>
     *   <li>{@code textChange} → EditText.addTextChangedListener（带防抖）</li>
     * </ul>
     * 事件动作经 {@link ActionExecutor} 执行（通常是 {@code callback} 回程到服务端）。
     */
    private void bindEvents(View view, WidgetNode node) {
        if (executor == null || node.events().isEmpty()) return;

        var click = node.on("click");
        if (!click.isEmpty()) {
            view.setOnClickListener(v -> run(click));
        }
        var rightClick = node.on("rightClick");
        if (!rightClick.isEmpty()) {
            view.setOnContextClickListener(v -> { run(rightClick); return true; });
        }
        var enter = node.on("hoverEnter");
        var leave = node.on("hoverLeave");
        if (!enter.isEmpty() || !leave.isEmpty()) {
            view.setOnHoverListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_HOVER_ENTER && !enter.isEmpty()) run(enter);
                else if (action == MotionEvent.ACTION_HOVER_EXIT && !leave.isEmpty()) run(leave);
                return false; // 不消费，保留原生 hover 行为（tooltip 等）
            });
        }
        var textChange = node.on("textChange");
        if (!textChange.isEmpty() && view instanceof EditText et) {
            bindTextChange(et, textChange);
        }
        var change = node.on("change");
        if (!change.isEmpty() && view instanceof SeekBar sb) {
            // 只在松手时发一次（拖动过程 onProgressChanged 会疯狂触发，不接回程）
            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {}
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { run(change); }
            });
        }
        // 滚轮 + 中键(走 generic-motion)。滚轮按刻度离散触发,不消费(让 ScrollView 仍能滚)
        var scroll = node.on("scroll");
        var middleClick = node.on("middleClick");
        if (!scroll.isEmpty() || !middleClick.isEmpty()) {
            view.setOnGenericMotionListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_SCROLL && !scroll.isEmpty()) {
                    run(scroll);
                } else if (action == MotionEvent.ACTION_BUTTON_PRESS && !middleClick.isEmpty()
                        && (event.getButtonState() & MotionEvent.BUTTON_TERTIARY) != 0) {
                    run(middleClick);
                }
                return false;
            });
        }
        // 松开(鼠标抬起),不消费保留原生 click
        var release = node.on("release");
        if (!release.isEmpty()) {
            view.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) run(release);
                return false;
            });
        }
    }

    /** 执行一组动作（每个动作先套 readback，再交执行器）。 */
    private void run(List<Map<String, Object>> actions) {
        for (var action : actions) {
            executor.execute(withReadback(action));
        }
    }

    /**
     * 文本变更绑定：逐字输入会疯狂触发，故防抖——每次变更重置一个 {@value #TEXT_CHANGE_DEBOUNCE_MS}ms
     * 的延时任务，用户停手才真正发出。配合动作里的 {@code readback} 把当前文本带回服务端。
     */
    private void bindTextChange(EditText et, List<Map<String, Object>> actions) {
        final Runnable[] pending = {null};
        et.addTextChangedListener(new icyllis.modernui.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(icyllis.modernui.text.Editable s) {
                if (pending[0] != null) et.removeCallbacks(pending[0]);
                pending[0] = () -> run(actions);
                et.postDelayed(pending[0], TEXT_CHANGE_DEBOUNCE_MS);
            }
        });
    }

    /**
     * 值回读：动作带 {@code "readback":["输入框id",...]} 时，点击瞬间读取这些输入控件的当前值，
     * base64(URL-safe，无空格)编码后追加进回调 {@code data}，随事件带回服务端（脚本读 {@code ctx.values.<id>}）。
     * <p>走现有命令通道故编码避免空格/特殊字符；无 readback 或无输入时原样返回。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> withReadback(Map<String, Object> action) {
        if (!(action.get("readback") instanceof List<?> ids) || ids.isEmpty() || inputs.isEmpty()) {
            return action;
        }
        List<Object> data = new ArrayList<>();
        if (action.get("data") instanceof List<?> orig) data.addAll(orig);
        for (Object idObj : ids) {
            String id = String.valueOf(idObj);
            String val = readInput(id);
            if (val != null) {
                String enc = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(val.getBytes(StandardCharsets.UTF_8));
                data.add("__rb:" + id + ":" + enc);
            }
        }
        Map<String, Object> copy = new HashMap<>(action);
        copy.put("data", data);
        return copy;
    }

    /** 读取已登记输入控件的当前值：TextBox → 文本，Slider → 进度整数字符串；否则 null。 */
    private String readInput(String id) {
        View v = inputs.get(id);
        if (v instanceof EditText et) {
            return et.getText() == null ? "" : et.getText().toString();
        }
        if (v instanceof SeekBar sb) {
            return String.valueOf(sb.getProgress());
        }
        return null;
    }

    private static int parseColor(String hex) {
        try {
            if (hex.startsWith("#")) hex = hex.substring(1);
            if (hex.length() == 6) hex = "FF" + hex;
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException e) {
            return 0xFFFFFFFF;
        }
    }
}
