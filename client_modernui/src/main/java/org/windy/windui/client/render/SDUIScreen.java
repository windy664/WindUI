package org.windy.windui.client.render;

import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.core.Core;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.graphics.drawable.GradientDrawable;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.Gravity;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.View;
import icyllis.modernui.view.ViewGroup;
import icyllis.modernui.widget.FrameLayout;
import icyllis.modernui.widget.LinearLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.windy.windui.core.parser.UIJsonParser;
import org.windy.windui.core.parser.WidgetNode;
import org.windy.windui.core.script.ActionExecutor;
import org.windy.windui.core.script.SafeClientAPI;

/**
 * SDUI 界面宿主 Fragment。接收服务端下发的 JSON，解析并渲染为 ModernUI View 树。支持 {@link #reload(String)} 热更新。
 * <p>
 * <b>跨 MC 版本共享类</b>：只依赖 {@code core} 与纯 ModernUI（View API 跨版本稳定），不引用任何
 * net.minecraft / NeoForge / mc 层 API。生命周期回调、按钮行为、图片加载分别经 {@code onClosed}、
 * {@link SafeClientAPI}、{@link ImageLoader} 注入，故 neoforge_26_1_2 / neoforge_1_21_1 等可直接复用。
 */
public class SDUIScreen extends Fragment {

    private static final Logger LOGGER = LoggerFactory.getLogger("WindUI");
    // 默认背景：海军蓝→靛蓝对角渐变（兼容已有界面的视觉效果）。
    private static final int[] BG_GRADIENT = {0xF20A1830, 0xF2122A4E};

    private final ViewFactory viewFactory;
    private final Runnable onClosed;
    private LinearLayout rootContainer;
    /** reload 在视图就绪前被调用时暂存的 JSON，onCreateView 完成后补渲染。 */
    private String pendingJson;

    /**
     * @param clientApi     平台注入的安全客户端 API（按钮动作经此执行）
     * @param onClosed      界面销毁时的回调（复位平台状态），解耦版本特定 glue
     * @param imageLoader   平台注入的图片加载器（各版本用各自 ModernUI 的 ImageStore）
     * @param modelRenderer 平台注入的 3D 模型渲染器（各版本用 MinecraftSurfaceView + vanilla/GeckoLib 后端）；可为 null
     */
    public SDUIScreen(SafeClientAPI clientApi, Runnable onClosed, ImageLoader imageLoader,
                      ModelRenderer modelRenderer) {
        this.viewFactory = new ViewFactory(new ActionExecutor(clientApi), imageLoader, modelRenderer);
        this.onClosed = onClosed;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        rootContainer = new LinearLayout(getContext());
        rootContainer.setOrientation(LinearLayout.VERTICAL);
        rootContainer.setGravity(Gravity.CENTER);

        applyDefaultBackground();

        rootContainer.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LOGGER.info("[WindUI] SDUIScreen.onCreateView 完成，根容器已建");

        // 视图就绪前若已收到 JSON（推送路常见：reload 抢在 onCreateView 前跑），此处补渲染
        if (pendingJson != null) {
            String pj = pendingJson;
            pendingJson = null;
            LOGGER.info("[WindUI] 应用暂存的 UI JSON");
            reload(pj);
        }
        return rootContainer;
    }

    /**
     * 热更新入口：可从任意线程调用。
     * <p>
     * ModernUI 的 View 操作（addView/removeAllViews/invalidate）**只能在 ModernUI UI 线程**执行，
     * 从 MC Render 线程直接改会触发 {@code Core.checkUiThread} 崩溃。故统一调度到 UI 线程。
     */
    public void reload(String uiJson) {
        Core.executeOnUiThread(() -> doReload(uiJson));
    }

    /** 实际渲染，保证在 ModernUI UI 线程上执行。 */
    private void doReload(String uiJson) {
        if (rootContainer == null || getContext() == null) {
            LOGGER.warn("[WindUI] reload 推迟：视图未就绪，暂存 JSON 待 onCreateView 应用");
            pendingJson = uiJson;
            return;
        }

        try {
            var root = UIJsonParser.parse(uiJson);
            applyScreenBackground(root);
            viewFactory.clearRegistries(); // 丢弃上一次渲染登记的控件表，避免读到旧引用
            View view = viewFactory.create(root, getContext());
            if (view != null) {
                rootContainer.removeAllViews();
                rootContainer.addView(view, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                LOGGER.info("[WindUI] 渲染完成：根类型={}, 子节点={}", root.type(), root.children().size());
            } else {
                LOGGER.warn("[WindUI] ViewFactory 返回 null，根类型={}（未知组件？）", root.type());
            }
        } catch (Exception e) {
            LOGGER.error("[WindUI] UI 解析/渲染失败", e);
            var errText = new icyllis.modernui.widget.TextView(getContext());
            errText.setText("UI 解析失败: " + e.getMessage());
            errText.setTextColor(0xFFFF4444);
            errText.setTextSize(14);
            rootContainer.removeAllViews();
            rootContainer.addView(errText);
        }
    }

    /**
     * 应用根节点声明的全屏遮罩。默认保留既有靛蓝渐变；{@code dimBackground=false} 关闭遮罩，
     * {@code overlayColor} 使用 {@code #RRGGBB}/{@code #AARRGGBB} 单色遮罩。
     */
    private void applyScreenBackground(WidgetNode root) {
        if (!root.getBool("dimBackground", true)) {
            rootContainer.setBackground(null);
            return;
        }

        String overlayColor = root.getString("overlayColor", null);
        if (overlayColor == null || overlayColor.isBlank()) {
            applyDefaultBackground();
            return;
        }

        Integer color = parseColor(overlayColor);
        if (color == null) {
            LOGGER.warn("[WindUI] overlayColor 非法，回退默认背景: {}", overlayColor);
            applyDefaultBackground();
            return;
        }

        var background = new GradientDrawable();
        background.setColor(color);
        rootContainer.setBackground(background);
    }

    private void applyDefaultBackground() {
        rootContainer.setBackground(
                new GradientDrawable(GradientDrawable.Orientation.TL_BR, BG_GRADIENT));
    }

    private static Integer parseColor(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() == 6) {
            hex = "FF" + hex;
        } else if (hex.length() != 8) {
            return null;
        }
        try {
            return (int) Long.parseLong(hex, 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 增量更新入口：按 id 局部改已渲染组件的属性，不重建 View。可从任意线程调用（内部调度到 UI 线程）。
     * 视图未就绪或对应 id 不存在时静默跳过（增量更新是尽力而为，丢一帧不致命）。
     */
    public void applyPatch(java.util.List<org.windy.windui.core.protocol.PatchCodec.Patch> patches) {
        Core.executeOnUiThread(() -> {
            if (rootContainer == null || getContext() == null) return;
            for (var p : patches) {
                View v = viewFactory.viewById(p.id());
                if (v != null) viewFactory.applyPatchProps(v, p.props());
            }
        });
    }

    /**
     * 界面销毁时复位平台状态（经注入的回调，不直接引用版本特定 glue）。
     * 覆盖 ESC 关闭这条路径——否则平台侧 open 标志/当前界面引用不复位，服务端后续推送会静默失败。
     */
    @Override
    public void onDestroyView() {
        if (onClosed != null) {
            onClosed.run();
        }
        super.onDestroyView();
    }
}
