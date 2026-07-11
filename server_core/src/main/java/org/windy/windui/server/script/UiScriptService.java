package org.windy.windui.server.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * 服务端 JS 界面服务：用 Rhino 执行 AI/作者写的 {@code *.js}，产出可下发的 UI JSON，并派发按钮事件。
 * <p>
 * <b>平台无关</b>（在 {@code server_core} 共享模块）：只依赖 java.nio + Rhino + {@code Map<String,Object>}
 * 上下文，返回 {@code String}/{@code List<Effect>}，不碰任何 Velocity/Bukkit 类型。velocity 与未来 bukkit 共用。
 * <p>
 * <b>为什么在服务端跑 JS</b>：AI 最擅长写 JS，逻辑无限表达；跑在服务端 = 服主自己的代码
 * （与装插件同一信任级别），玩家碰不到、篡改不了；客户端仍是纯 ModernUI 傻渲染器，一行 JS 不放。
 * <p>
 * <b>无状态模型</b>（合 SDUI）：每次事件都重跑脚本的 {@code onEvent(ctx,event,data)}，脚本通过
 * {@code ctx.message/rerender/open/...} 产出 {@link Effect} 列表，由平台执行。状态放服务端数据（经济等），
 * 不驻留 Rhino 闭包——无内存泄漏、无每会话作用域生命周期管理。
 * <p>
 * <b>沙箱</b>：{@link org.mozilla.javascript.ClassShutter} 屏蔽一切 Java 类访问（挡反射/IO/命令），
 * 脚本只能用纯 JS + 我们注入的 {@code ctx}。解释器模式（optimizationLevel = -1），无需运行时生成字节码。
 */
public final class UiScriptService {

    /** 脚本单次执行墙钟上限（毫秒）：防 {@code while(true)} 等失控脚本卡住线程。 */
    private static final long MAX_MILLIS = 2000;

    /** 携带执行截止时间的 Context，配合指令观察实现超时中止。 */
    private static final class TimedContext extends Context {
        long deadline;
        TimedContext(ContextFactory factory) {
            super(factory);
        }
    }

    /**
     * 沙箱 + 超时：屏蔽全部 Java 类（挡反射/IO/命令）；解释器模式下每若干指令检查是否超时。
     */
    private static final ContextFactory SANDBOX = new ContextFactory() {
        @Override
        protected Context makeContext() {
            TimedContext cx = new TimedContext(this);
            cx.setOptimizationLevel(-1);            // 解释器模式（指令观察仅此模式有效，也无字节码生成）
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setClassShutter(className -> false); // 一切 Java 类不可见
            cx.setInstructionObserverThreshold(10_000);
            cx.deadline = System.currentTimeMillis() + MAX_MILLIS;
            return cx;
        }

        @Override
        protected void observeInstructionCount(Context cx, int instructionCount) {
            if (cx instanceof TimedContext tc && System.currentTimeMillis() > tc.deadline) {
                throw new RuntimeException("脚本执行超时(>" + MAX_MILLIS + "ms)，已中止");
            }
        }
    };

    /**
     * 注入脚本前的前奏（纯 JS，不暴露任何 Java 对象）：
     * ① 效果队列 __fx 与 ctx 的效果方法；② {@code ui.*} 组件构造 helper（让 AI 写界面更短更稳）。
     */
    private static final String PRELUDE = """
            var __fx = [];
            ctx.values = {};
            ctx.message  = function(m){ __fx.push('message\\t' + m); };
            ctx.toast    = function(m){ __fx.push('toast\\t'   + m); };
            ctx.open     = function(g){ __fx.push('open\\t'    + g); };
            ctx.rerender = function(){  __fx.push('rerender');       };
            ctx.close    = function(){  __fx.push('close');          };
            ctx.command  = function(c){ __fx.push('command\\t' + c); };
            ctx.sound    = function(s){ __fx.push('sound\\t'   + s); };
            // 局部更新：只改 id 组件的 props(text/textColor/textSize/visible/alpha/enable 等)，不重推整树
            ctx.patch    = function(id, props){ __fx.push('patch\\t' + JSON.stringify({ id: id, props: props || {} })); };
            // 非模态 HUD:推一组锚定面板(每个 {anchor,x,y,root:<ui节点>});hudClear 清空
            ctx.hud      = function(panels){ __fx.push('hud\\t' + JSON.stringify(panels || [])); };
            ctx.hudClear = function(){ __fx.push('hudClear'); };
            // 真库存（仅后端 Bukkit/Paper host 有效；纯代理 host 静默跳过）。
            ctx.give     = function(id, count){ __fx.push('give\\t' + id + '\\t' + (count || 1)); };
            ctx.take     = function(id, count){ __fx.push('take\\t' + id + '\\t' + (count || 1)); };
            // ctx.inventory 由 host 以 JSON 字符串注入（沙箱不能直传 Java List）→ 这里还原成原生数组;
            // 无库存的 host（代理）注入不到 → 空数组。
            ctx.inventory = (typeof ctx.inventory === 'string') ? JSON.parse(ctx.inventory) : (ctx.inventory || []);

            var ui = {
              _merge: function(base, extra){
                if (extra) { for (var k in extra) { if (extra.hasOwnProperty(k)) base[k] = extra[k]; } }
                return base;
              },
              text:     function(text, props){ return { type: 'TextView', props: ui._merge({ text: text }, props) }; },
              markdown: function(md, props){   return { type: 'Markdown', props: ui._merge({ text: md }, props) }; },
              image:    function(src, props){  return { type: 'ImageView', props: ui._merge({ src: src }, props) }; },
              button:   function(text, color, onClick){
                return { type: 'Button', props: { text: text, textColor: color, textSize: 15, padding: 6 }, onClick: onClick };
              },
              slot: function(icon, props, onClick){
                var n = { type: 'ItemSlot', props: ui._merge({ icon: icon }, props) };
                if (onClick) n.onClick = onClick;
                return n;
              },
              input:  function(props){ var p = props || {}; var n = { type: 'TextBox', props: p }; if (p.id) n.id = p.id; return n; },
              slider: function(props){ var p = props || {}; var n = { type: 'Slider', props: p }; if (p.id) n.id = p.id; return n; },
              column: function(props, children){ return { type: 'LinearLayout', props: ui._merge({ orientation: 1 }, props), children: children || [] }; },
              row:    function(props, children){ return { type: 'LinearLayout', props: ui._merge({ orientation: 0 }, props), children: children || [] }; },
              card:   function(props, children){ return { type: 'LinearLayout', props: ui._merge({ orientation: 1, background: '#14FFFFFF', border: '#26FFFFFF', radius: 16, padding: 16 }, props), children: children || [] }; },
              frame:  function(props, children){ return { type: 'FrameLayout', props: props || {}, children: children || [] }; },
              scroll:  function(child){ return { type: 'ScrollView', children: child ? [child] : [] }; },
              bar:     function(value, props){ return { type: 'Bar', props: ui._merge({ value: value }, props) }; },
              divider: function(props){ return { type: 'Divider', props: ui._merge({ width: 'match', height: 1, margin: 6 }, props) }; },
              // 3D 模型/实体：type=item(默认)/entity/geckolib；model=物品或实体或模型 id。须给 width/height。
              model:   function(model, props){ return { type: 'Model3D', props: ui._merge({ type: 'item', model: model, width: 64, height: 64 }, props) }; },
              // 原版生物实体；modelScale 是传给物品栏实体渲染器的像素尺寸。
              entityModel: function(entity, props){ return { type: 'Model3D', props: ui._merge({ type: 'entity', model: entity, width: 96, height: 96, modelScale: 30 }, props) }; },
              // GeckoLib 模型（软依赖，客户端无 GeckoLib 则跳过）。geo/texture 为资源路径，可选 animation 循环动画名。
              geckoModel: function(geo, texture, props){ return { type: 'Model3D', props: ui._merge({ type: 'geckolib', geo: geo, texture: texture, width: 96, height: 96 }, props) }; },
              // 给任意节点挂交互事件；events 键：onClick/onRightClick/onHoverEnter/onHoverLeave/onTextChange，
              // 值为单个动作对象或动作数组。返回原节点便于链式：ui.on(ui.slot(...), { onRightClick: {...} })
              on: function(node, events){
                if (node && events) { for (var k in events) { if (events.hasOwnProperty(k)) node[k] = events[k]; } }
                return node;
              }
            };
            """;

    /** 内置默认脚本的 classpath 目录（打进 jar，可直接查看）。 */
    private static final String BUNDLED_DIR = "/windui/scripts/";

    /** 随 jar 内置的默认脚本 id（首次运行释放到磁盘）。 */
    private static final List<String> DEFAULT_IDS = List.of("shop", "showcase", "events", "backpack");

    private final Path scriptsDir;
    private final Map<String, String> scripts = new HashMap<>(); // id -> js 源码
    private final Map<String, Script> compiled = new ConcurrentHashMap<>(); // id -> 已编译脚本（免每次重解析）
    private volatile Script preludeCompiled; // PRELUDE 常量，编译一次

    public UiScriptService(Path scriptsDir) {
        this.scriptsDir = scriptsDir;
    }

    /**
     * 载入脚本：把 jar 内置默认（{@link #DEFAULT_IDS}）中磁盘上缺失的释放到磁盘(已存在的不覆盖),
     * 然后从磁盘 {@code scripts/*.js} 全量加载。故默认既在磁盘可见/可编辑,用户改动也保留。
     */
    public synchronized int load() {
        scripts.clear();
        compiled.clear(); // 源码可能已改，弃编译缓存
        try {
            Files.createDirectories(scriptsDir);
            // 内置默认:磁盘上缺哪个就释放哪个(供服主查看/编辑;已存在的不覆盖,保留用户改动)
            for (String id : DEFAULT_IDS) {
                Path f = scriptsDir.resolve(id + ".js");
                if (!Files.exists(f)) {
                    String src = readBundled(id);
                    if (src != null && !src.isBlank()) {
                        Files.writeString(f, src, StandardCharsets.UTF_8);
                    }
                }
            }
            try (Stream<Path> stream = Files.list(scriptsDir)) {
                List<Path> files = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".js"))
                        .toList();
                for (Path p : files) {
                    String id = stripExtension(p.getFileName().toString());
                    String src = Files.readString(p, StandardCharsets.UTF_8);
                    if (!src.isBlank()) {
                        scripts.put(id, src);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("加载脚本目录失败: " + scriptsDir, e);
        }
        return scripts.size();
    }

    public synchronized boolean has(String id) {
        return scripts.containsKey(id);
    }

    public synchronized Set<String> ids() {
        return new TreeSet<>(scripts.keySet());
    }

    public Path directory() {
        return scriptsDir;
    }

    /**
     * 执行脚本的 {@code render(ctx)}，返回 {@code JSON.stringify} 后的 UI JSON。
     *
     * @return UI JSON；脚本不存在或执行出错返回 {@code null}
     */
    public String render(String id, Map<String, Object> ctx) {
        String src = source(id);
        if (src == null) {
            return null;
        }
        Context cx = SANDBOX.enterContext();
        try {
            Scriptable scope = setup(cx, id, src, ctx);
            Object r = cx.evaluateString(scope, "JSON.stringify(render(ctx))", "<render>", 1, null);
            return r == null || r == Context.getUndefinedValue() ? null : Context.toString(r);
        } catch (RuntimeException ex) {
            System.err.println("[WindUI] 脚本 render 失败 " + id + ".js: " + ex.getMessage());
            return null;
        } finally {
            Context.exit();
        }
    }

    /**
     * 派发按钮事件到脚本的 {@code onEvent(ctx,event,data)}，返回脚本产出的 {@link Effect} 列表。
     *
     * @return 效果列表（脚本无 onEvent 时为空列表）；脚本不存在或出错返回 {@code null}
     */
    public List<Effect> dispatchEvent(String id, Map<String, Object> ctx, String event,
                                      List<String> data, Map<String, String> values) {
        String src = source(id);
        if (src == null) {
            return null;
        }
        Context cx = SANDBOX.enterContext();
        try {
            Scriptable scope = setup(cx, id, src, ctx);
            // 注入 readback 值为 ctx.values（JS 对象，脚本读 ctx.values.<输入框id>）
            if (values != null && !values.isEmpty()
                    && scope.get("ctx", scope) instanceof Scriptable ctxObj) {
                Scriptable valuesObj = cx.newObject(scope);
                for (Map.Entry<String, String> e : values.entrySet()) {
                    ScriptableObject.putProperty(valuesObj, e.getKey(), e.getValue());
                }
                ScriptableObject.putProperty(ctxObj, "values", valuesObj);
            }
            ScriptableObject.putProperty(scope, "__event", event);
            ScriptableObject.putProperty(scope, "__data", cx.newArray(scope, data.toArray()));
            Object hasHandler = cx.evaluateString(scope, "(typeof onEvent === 'function')", "<chk>", 1, null);
            if (Boolean.TRUE.equals(hasHandler)) {
                cx.evaluateString(scope, "onEvent(ctx, __event, __data)", "<onEvent>", 1, null);
            }
            return readEffects(scope);
        } catch (RuntimeException ex) {
            System.err.println("[WindUI] 脚本 onEvent 失败 " + id + ".js: " + ex.getMessage());
            return null;
        } finally {
            Context.exit();
        }
    }

    // ===== 内部 =====

    private synchronized String source(String id) {
        return scripts.get(id);
    }

    /**
     * 建作用域：initStandardObjects + 注入 ctx 数据 + 执行前奏（效果方法/ui helper）+ 执行脚本体。
     * 前奏与脚本体都<b>编译一次缓存</b>，此后每次调用只在新作用域里 exec（免重复解析）。
     */
    private Scriptable setup(Context cx, String id, String src, Map<String, Object> ctxData) {
        Scriptable scope = cx.initStandardObjects();
        Scriptable ctx = cx.newObject(scope);
        for (Map.Entry<String, Object> e : ctxData.entrySet()) {
            ScriptableObject.putProperty(ctx, e.getKey(), Context.javaToJS(e.getValue(), scope));
        }
        ScriptableObject.putProperty(scope, "ctx", ctx);
        preludeScript(cx).exec(cx, scope);
        scriptFor(cx, id, src).exec(cx, scope);
        return scope;
    }

    /** 编译一次并缓存 PRELUDE（常量，不随 reload 变）。 */
    private Script preludeScript(Context cx) {
        Script p = preludeCompiled;
        if (p == null) {
            p = cx.compileString(PRELUDE, "<prelude>", 1, null);
            preludeCompiled = p;
        }
        return p;
    }

    /** 编译一次并缓存脚本体（reload 时 {@link #compiled} 已清）。 */
    private Script scriptFor(Context cx, String id, String src) {
        return compiled.computeIfAbsent(id, k -> cx.compileString(src, k + ".js", 1, null));
    }

    /** 读效果队列 __fx（NativeArray of "type\targ" 字符串）为 {@link Effect} 列表。 */
    private static List<Effect> readEffects(Scriptable scope) {
        List<Effect> out = new ArrayList<>();
        Object fx = scope.get("__fx", scope);
        if (fx instanceof Scriptable arr) {
            int len = (int) Context.toNumber(ScriptableObject.getProperty(arr, "length"));
            for (int i = 0; i < len; i++) {
                Object el = arr.get(i, arr);
                if (el == null || el == Scriptable.NOT_FOUND) {
                    continue;
                }
                String s = Context.toString(el);
                int tab = s.indexOf('\t');
                out.add(tab < 0 ? new Effect(s, null) : new Effect(s.substring(0, tab), s.substring(tab + 1)));
            }
        }
        return out;
    }

    /** 读 jar 内置脚本资源 {@code /windui/scripts/<id>.js}；缺失返回 null。 */
    private static String readBundled(String id) {
        try (InputStream in = UiScriptService.class.getResourceAsStream(BUNDLED_DIR + id + ".js")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
