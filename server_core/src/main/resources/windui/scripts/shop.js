// WindUI 服务端 JS 商店示例 —— iOS 分组列表风格（/windui open shop）。

var IOS = {
  blue: "#007AFF",
  green: "#34C759",
  label: "#FF1C1C1E",
  secondary: "#FF8E8E93",
  separator: "#243C3C43",
  card: "#F5FFFFFF",
  field: "#FFF2F2F7",
  overlay: "#66000000"
};

function iosButton(text, background, textColor, onClick) {
  return {
    type: "Button",
    props: {
      text: text,
      textSize: 14,
      textColor: textColor,
      background: background,
      radius: 10,
      width: 76,
      height: 36,
      padding: 7,
      margin: 4
    },
    onClick: onClick
  };
}

function item(icon, name, subtitle, price, id) {
  return ui.on(ui.row({ gravity: "center", width: 330, padding: 8 }, [
    ui.image(icon, { width: 36, height: 36, padding: 4 }),
    ui.column({ width: 150, padding: 5 }, [
      ui.text(name, { textSize: 15, textColor: IOS.label }),
      ui.text(subtitle, { textSize: 11, textColor: IOS.secondary })
    ]),
    ui.text(price, { width: 42, textSize: 12, textColor: IOS.secondary }),
    iosButton("购买", IOS.blue, "#FFFFFFFF", { action: "callback", id: id, data: ["1"] })
  ]), { onRightClick: { action: "callback", id: "detail_" + id } });
}

function render(ctx) {
  var status = ui.text("轻点购买 · 右键查看详情", {
    textSize: 12,
    textColor: IOS.secondary,
    padding: 6
  });
  status.id = "status";

  return ui.column({
    gravity: "center",
    padding: 22,
    overlayColor: IOS.overlay
  }, [
    ui.card({
      width: 360,
      background: IOS.card,
      border: "#1F000000",
      borderWidth: 1,
      radius: 22,
      padding: 18
    }, [
      ui.text("商店", { textSize: 26, textColor: IOS.label }),
      ui.text("欢迎，" + ctx.player + "  ·  " + ctx.online + " 人在线", {
        textSize: 12,
        textColor: IOS.secondary,
        padding: 3
      }),
      ui.text("推荐", { textSize: 12, textColor: IOS.secondary, padding: 6 }),
      ui.card({ background: IOS.field, border: "#00000000", radius: 14, padding: 4 }, [
        item("minecraft:textures/item/diamond.png", "钻石", "稀有货币", "100", "buy_diamond"),
        ui.divider({ color: IOS.separator, margin: 2 }),
        item("minecraft:textures/item/gold_ingot.png", "金锭", "通用货币", "20", "buy_gold")
      ]),
      status,
      iosButton("完成", "#14007AFF", IOS.blue, { action: "closeScreen" })
    ])
  ]);
}

function onEvent(ctx, event, data) {
  if (event === "buy_diamond" || event === "buy_gold") {
    var name = event === "buy_diamond" ? "钻石" : "金锭";
    var qty = data[0] || "1";
    ctx.message("§a购买成功 §f· " + qty + " 个" + name);
    ctx.toast("已购买 " + qty + " 个" + name);
    ctx.sound("minecraft:ui.button.click");
    ctx.patch("status", { text: "✓ 已购买 " + qty + " 个" + name, textColor: IOS.green });
  } else if (event === "detail_buy_diamond") {
    ctx.toast("钻石 · 稀有货币 · 100 金币/个");
  } else if (event === "detail_buy_gold") {
    ctx.toast("金锭 · 通用货币 · 20 金币/个");
  }
}