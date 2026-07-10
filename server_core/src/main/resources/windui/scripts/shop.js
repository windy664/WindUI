// WindUI 服务端 JS 商店示例 —— Rhino 执行。蓝渐变玻璃 · 极简。
// ui.* helper：ui.card(玻璃卡) · ui.column/row(布局) · ui.text/image/button(组件)

function item(icon, name, price, id) {
  // 左键购买(按钮) + 右键看详情(整行 onRightClick,经 ui.on 挂事件)
  return ui.on(ui.row({ gravity: "center", padding: 6 }, [
    ui.image(icon, { width: 34, height: 34, padding: 6 }),
    ui.column({ padding: 8 }, [
      ui.text(name, { textSize: 15, textColor: "#EAF2FF" }),
      ui.text(price, { textSize: 12, textColor: "#7E8CA0" })
    ]),
    ui.button("购买", "#66E0A3", { action: "callback", id: id, data: ["1"] })
  ]), { onRightClick: { action: "callback", id: "detail_" + id } });
}

function render(ctx) {
  // 状态行带 id="status"，购买后经 ctx.patch 局部刷新（不重推整树、无闪烁）
  var status = ui.text("左键购买 · 右键看详情", { textSize: 12, textColor: "#7E8CA0", padding: 2 });
  status.id = "status";
  return ui.column({ gravity: "center", padding: 22 }, [
    ui.card({}, [
      ui.text("商店", { textSize: 26, textColor: "#EAF2FF" }),
      ui.text(ctx.player + "  ·  在线 " + ctx.online + " 人", { textSize: 12, textColor: "#7E8CA0", padding: 2 }),
      item("minecraft:textures/item/diamond.png", "钻石", "100 金币", "buy_diamond"),
      item("minecraft:textures/item/gold_ingot.png", "金锭", "20 金币", "buy_gold"),
      status,
      ui.button("关闭", "#8B97A6", { action: "closeScreen" })
    ])
  ]);
}

function onEvent(ctx, event, data) {
  // 购买回调：权威操作在服务端做（此处仅示例反馈）。
  // ctx.toast/sound/command/close 是「服务端主动指挥客户端」的效果，经控制频道下发。
  if (event === "buy_diamond" || event === "buy_gold") {
    var name = event === "buy_diamond" ? "钻石" : "金锭";
    var qty = data[0] || "1";
    ctx.message("§a购买成功 §f· " + qty + " 个" + name);   // 聊天记录
    ctx.toast("已购买 " + qty + " 个" + name);              // 客户端 toast
    ctx.sound("minecraft:ui.button.click");                 // 客户端音效
    ctx.patch("status", { text: "✓ 已购买 " + qty + " 个" + name, textColor: "#66E0A3" }); // 局部刷状态行
  } else if (event === "detail_buy_diamond") {
    ctx.toast("钻石 · 稀有货币 · 100 金币/个");             // 右键看详情
  } else if (event === "detail_buy_gold") {
    ctx.toast("金锭 · 普通货币 · 20 金币/个");
  }
}
