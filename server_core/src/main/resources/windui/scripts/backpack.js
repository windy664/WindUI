// WindUI 真库存演示（仅后端 Bukkit/Paper host 有效；纯代理 host 上 ctx.inventory 为空）。
// /windui open backpack —— 读玩家真实背包，点按钮给/取真实物品。
// iOS 风：浅色卡片 + 系统蓝/绿/红。

function render(ctx) {
  var rows = [];
  rows.push(ui.text("真实背包", { textSize: 22, textColor: "#FF1C1C1E" }));
  rows.push(ui.text(ctx.player + " · " + ctx.inventory.length + " 种物品", { textSize: 12, textColor: "#FF8E8E93" }));
  rows.push(ui.divider({}));

  if (ctx.inventory.length === 0) {
    rows.push(ui.text("背包是空的（或此服务端无真库存能力）", { textColor: "#FF8E8E93", padding: 6 }));
  } else {
    for (var i = 0; i < ctx.inventory.length; i++) {
      var it = ctx.inventory[i];
      var name = it.id.indexOf(":") >= 0 ? it.id.split(":")[1] : it.id;
      rows.push(ui.row({ gravity: "center", padding: 3 }, [
        ui.text(name + "  ×" + it.count, { textColor: "#FF1C1C1E", width: 180 }),
        ui.button("取出 1", "#FF3B30", { action: "callback", id: "take", data: [it.id] })
      ]));
    }
  }

  rows.push(ui.divider({}));
  rows.push(ui.button("给我 1 颗钻石", "#34C759", { action: "callback", id: "give", data: ["minecraft:diamond"] }));
  return ui.scroll(ui.card({}, rows));
}

function onEvent(ctx, event, data) {
  if (event === "give") {
    ctx.give(data[0], 1);
    ctx.rerender(); // 重渲染 → host 重读真库存 → 界面反映新数量
  } else if (event === "take") {
    ctx.take(data[0], 1);
    ctx.rerender();
  }
}
