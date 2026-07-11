// WindUI 组件全家福 —— iOS 控制中心风格（/windui open showcase）。

var IOS = {
  blue: "#007AFF",
  orange: "#FF9500",
  green: "#34C759",
  label: "#FF1C1C1E",
  secondary: "#FF8E8E93",
  card: "#F5FFFFFF",
  field: "#FFF2F2F7",
  separator: "#243C3C43",
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
      width: 88,
      height: 36,
      padding: 7,
      margin: 3
    },
    onClick: onClick
  };
}

function render(ctx) {
  var content = ui.column({ gravity: "center", padding: 20 }, [
    ui.card({
      width: 390,
      background: IOS.card,
      border: "#1F000000",
      borderWidth: 1,
      radius: 22,
      padding: 18
    }, [
      ui.text("WindUI", { textSize: 27, textColor: IOS.label }),
      ui.text("组件陈列室  ·  " + ctx.player + "  ·  " + ctx.online + " 人在线", {
        textSize: 12,
        textColor: IOS.secondary,
        padding: 3
      }),

      ui.markdown("**原生组件**\n简洁、清晰，并保持服务端驱动。", {
        padding: 8,
        textColor: IOS.label
      }),
      ui.divider({ color: IOS.separator, margin: 5 }),

      ui.text("模型", { textSize: 12, textColor: IOS.secondary, padding: 4 }),
      ui.card({ background: IOS.field, border: "#00000000", radius: 14, padding: 5 }, [
        ui.row({ gravity: "center" }, [
          ui.model("minecraft:diamond_block", { width: 72, height: 72, modelScale: 4 }),
          ui.entityModel("minecraft:zombie", { width: 96, height: 96, modelScale: 30 })
        ])
      ]),

      ui.text("资源", { textSize: 12, textColor: IOS.secondary, padding: 5 }),
      ui.row({ gravity: "center", padding: 6 }, [
        ui.slot("minecraft:textures/item/diamond.png", { count: 64, name: "钻石", lore: ["珍贵的矿物"], size: 34, margin: 5 }, { action: "callback", id: "pick", data: ["diamond"] }),
        ui.slot("minecraft:textures/item/gold_ingot.png", { count: 32, name: "金锭", size: 34, margin: 5 }, { action: "callback", id: "pick", data: ["gold"] }),
        ui.slot("minecraft:textures/item/emerald.png", { count: 16, name: "绿宝石", size: 34, margin: 5 }, { action: "callback", id: "pick", data: ["emerald"] })
      ]),

      ui.text("搜索", { textSize: 12, textColor: IOS.secondary, padding: 4 }),
      ui.input({
        id: "query",
        hint: "搜索组件",
        width: 330,
        textSize: 14,
        textColor: IOS.label,
        padding: 9,
        background: IOS.field,
        radius: 11
      }),

      ui.text("音量  60%", { textSize: 12, textColor: IOS.secondary, padding: 6 }),
      ui.on(ui.slider({ id: "vol", value: 60, max: 100, width: 300 }),
            { onChange: { action: "callback", id: "setvol", readback: ["vol"] } }),
      ui.bar(0.7, { width: 300, height: 5, color: IOS.blue, track: "#20007AFF" }),

      ui.row({ gravity: "center", padding: 7 }, [
        iosButton("搜索", IOS.blue, "#FFFFFFFF", { action: "callback", id: "search", readback: ["query"] }),
        iosButton("音效", "#20FF9500", IOS.orange, { action: "playSound", args: ["minecraft:ui.button.click", "1.0", "1.2"] }),
        iosButton("完成", "#14007AFF", IOS.blue, { action: "closeScreen" })
      ])
    ])
  ]);

  var screen = ui.scroll(content);
  screen.props = { overlayColor: IOS.overlay };
  return screen;
}

function onEvent(ctx, event, data) {
  if (event === "pick") ctx.message("§b已选 §f" + (data[0] || "?"));
  else if (event === "search") ctx.message("§e搜索 §f" + (ctx.values.query || "(空)"));
  else if (event === "setvol") ctx.toast("音量已设为 " + (ctx.values.vol || "0"));
}