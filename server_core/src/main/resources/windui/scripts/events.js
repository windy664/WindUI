// WindUI 交互事件示例 —— iOS 设置页风格（/windui open events）。

var IOS = {
  blue: "#007AFF",
  green: "#34C759",
  label: "#FF1C1C1E",
  secondary: "#FF8E8E93",
  card: "#F5FFFFFF",
  field: "#FFF2F2F7",
  separator: "#243C3C43",
  overlay: "#66000000"
};

function render(ctx) {
  return ui.column({ gravity: "center", padding: 22, overlayColor: IOS.overlay }, [
    ui.card({
      width: 360,
      background: IOS.card,
      border: "#1F000000",
      borderWidth: 1,
      radius: 22,
      padding: 18
    }, [
      ui.text("交互", { textSize: 26, textColor: IOS.label }),
      ui.text("在同一个区域体验鼠标扩展事件", {
        textSize: 12,
        textColor: IOS.secondary,
        padding: 3
      }),
      ui.text("事件测试", { textSize: 12, textColor: IOS.secondary, padding: 7 }),

      ui.on(ui.card({
        width: 310,
        background: IOS.field,
        border: "#00000000",
        radius: 14,
        padding: 20
      }, [
        ui.text("触控区域", { textSize: 17, textColor: IOS.label }),
        ui.text("滚轮 · 右键 · 中键 · 松开", {
          textSize: 12,
          textColor: IOS.secondary,
          padding: 4
        }),
        ui.bar(1, { width: 260, height: 4, color: IOS.green, track: "#2034C759" })
      ]), {
        onScroll: { action: "callback", id: "ev_scroll" },
        onRightClick: { action: "callback", id: "ev_right" },
        onMiddleClick: { action: "callback", id: "ev_middle" },
        onRelease: { action: "callback", id: "ev_release" }
      }),

      ui.divider({ color: IOS.separator, margin: 8 }),
      ui.text("动态缩放", { textSize: 12, textColor: IOS.secondary, padding: 4 }),
      ui.text("San Francisco", {
        textSize: 17,
        textColor: IOS.blue,
        scale: 1.25,
        padding: 8
      }),
      {
        type: "Button",
        props: {
          text: "完成",
          textSize: 15,
          textColor: IOS.blue,
          background: "#14007AFF",
          radius: 10,
          width: 100,
          height: 36,
          padding: 7
        },
        onClick: { action: "closeScreen" }
      }
    ])
  ]);
}

function onEvent(ctx, event, data) {
  if (event === "ev_scroll") ctx.toast("滚轮 onScroll ✓");
  else if (event === "ev_right") ctx.toast("右键 onRightClick ✓");
  else if (event === "ev_middle") ctx.toast("中键 onMiddleClick ✓");
  else if (event === "ev_release") ctx.toast("松开 onRelease ✓");
}