// WindUI 交互事件测试 —— /windui open events
// 验:滚轮 onScroll / 右键 onRightClick / 中键 onMiddleClick / 松开 onRelease / scale 缩放。

function render(ctx) {
  return ui.column({ gravity: "center", padding: 20 }, [
    ui.card({}, [
      ui.text("交互事件测试", { textSize: 22, textColor: "#EAF2FF" }),
      ui.text("鼠标移到下面绿块上试:滚轮 / 右键 / 中键 / 松开", { textSize: 12, textColor: "#7E8CA0", padding: 2 }),

      // 一块可交互区域,挂四种事件(每种回调一个不同 id,onEvent 里 toast 反馈)
      ui.on(ui.card({ background: "#2266E0A3", border: "#5566E0A3", radius: 12, padding: 20 }, [
        ui.text("§a▲ 操作我 ▲", { textSize: 18, textColor: "#EAF2FF" })
      ]), {
        onScroll: { action: "callback", id: "ev_scroll" },
        onRightClick: { action: "callback", id: "ev_right" },
        onMiddleClick: { action: "callback", id: "ev_middle" },
        onRelease: { action: "callback", id: "ev_release" }
      }),

      ui.divider({}),
      ui.text("下面这行 scale=1.5(应比其他字明显大):", { textSize: 12, textColor: "#7E8CA0", padding: 4 }),
      ui.text("§bBIG TEXT", { textSize: 16, textColor: "#4FA3FF", scale: 1.5, padding: 6 }),

      ui.button("关闭", "#8B97A6", { action: "closeScreen" })
    ])
  ]);
}

function onEvent(ctx, event, data) {
  if (event === "ev_scroll") ctx.toast("滚轮 onScroll ✓");
  else if (event === "ev_right") ctx.toast("右键 onRightClick ✓");
  else if (event === "ev_middle") ctx.toast("中键 onMiddleClick ✓");
  else if (event === "ev_release") ctx.toast("松开 onRelease ✓");
}
