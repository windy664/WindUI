// WindUI 组件全家福 —— 蓝渐变玻璃 · 一屏展示所有组件（/windui open showcase）。

function render(ctx) {
  return ui.scroll(ui.column({ gravity: "center", padding: 20 }, [
    ui.card({}, [
      ui.text("WindUI 组件展示", { textSize: 24, textColor: "#EAF2FF" }),
      ui.text(ctx.player + "  ·  在线 " + ctx.online + " 人", { textSize: 12, textColor: "#7E8CA0", padding: 2 }),

      ui.markdown("**Markdown** · *斜体* · `代码`\n- 物品格 · 输入框 · 滚动 · 3D 模型", { padding: 8, textColor: "#BFD4F2" }),

      ui.divider({}),
      ui.text("3D 模型（表面证明：vanilla 物品后端）", { textSize: 12, textColor: "#7E8CA0", margin: 2 }),
      // modelScale 只控制 Surface 内的 3D 内容大小；通用 scale 仍表示整个 ModernUI View 的变换。
      // 客户端无对应渲染器时该节点自动跳过（优雅降级）。
      ui.model("minecraft:diamond_block", { width: 72, height: 72, modelScale: 4 }),
      ui.divider({}),
      ui.text("进度条 70%", { textSize: 12, textColor: "#7E8CA0", margin: 2 }),
      ui.bar(0.7, { width: 200, height: 8, color: "#66E0A3" }),
      ui.divider({}),

      ui.row({ gravity: "center", padding: 8 }, [
        ui.slot("minecraft:textures/item/diamond.png", { count: 64, name: "钻石", lore: ["珍贵的矿物"], size: 34 }, { action: "callback", id: "pick", data: ["diamond"] }),
        ui.slot("minecraft:textures/item/gold_ingot.png", { count: 32, name: "金锭", size: 34 }, { action: "callback", id: "pick", data: ["gold"] }),
        ui.slot("minecraft:textures/item/emerald.png", { count: 16, name: "绿宝石", size: 34 }, { action: "callback", id: "pick", data: ["emerald"] })
      ]),

      ui.input({ id: "query", hint: "输入搜索词…", textSize: 14, padding: 8, background: "#10FFFFFF", radius: 8 }),

      ui.divider({}),
      ui.text("音量（松手回传）", { textSize: 12, textColor: "#7E8CA0", margin: 2, tip: "拖动调节，松手才发回服务端" }),
      // 滑块：id 供 readback，onChange 松手时把最终值带回（ctx.values.vol）
      ui.on(ui.slider({ id: "vol", value: 60, max: 100, width: 200 }),
            { onChange: { action: "callback", id: "setvol", readback: ["vol"] } }),

      ui.row({ gravity: "center", padding: 4 }, [
        ui.button("搜索", "#4FA3FF", { action: "callback", id: "search", readback: ["query"] }),
        ui.button("音效", "#FFAA55", { action: "playSound", args: ["minecraft:ui.button.click", "1.0", "1.2"] }),
        ui.button("关闭", "#8B97A6", { action: "closeScreen" })
      ])
    ])
  ]));
}

function onEvent(ctx, event, data) {
  if (event === "pick") ctx.message("§b已选 §f" + (data[0] || "?"));
  else if (event === "search") ctx.message("§e搜索 §f" + (ctx.values.query || "(空)"));
  else if (event === "setvol") ctx.toast("音量已设为 " + (ctx.values.vol || "0"));
}
