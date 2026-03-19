(() => {
  const logEl = document.getElementById('log');
  const btnStart = document.getElementById('btnStart');
  const btnSend = document.getElementById('btnSend');
  const btnSaturate = document.getElementById('btnSaturate');
  const btnClose = document.getElementById('btnClose');
  const msgEl = document.getElementById('msg');
  const SATURATE_MESSAGE_SIZE = 16 * 1024;
  const SATURATE_BATCH_SIZE = 128;
  const SATURATE_TARGET_BUFFER = 8 * 1024 * 1024;
  const SATURATE_MAX_MESSAGES = 4096;
  const saturatePayload = "x".repeat(SATURATE_MESSAGE_SIZE);

  const log = (s) => {
    logEl.textContent += `${s}\n`;
    logEl.scrollTop = logEl.scrollHeight;
  };

  // After webpack config change, kpeer should be on globalThis.kpeer (UMD).
  // If you didn't rebuild yet, it may still be under globalThis['com.kerobit:library'].
  const root = globalThis.kpeer ?? globalThis['com.kerobit:library'];
  if (!root?.com?.kerobit?.kpeer?.KPeerJs) {
    log("ERROR: KPeerJs not found. Rebuild JS bundle and reload.");
    return;
  }

  const { KPeerJs } = root.com.kerobit.kpeer;

  let a = null;
  let b = null;
  let chA = null; // channel handle on A (label=data)
  let saturating = false;
  let saturateMonitorId = null;

  const logBufferedAmountA = (reason = "snapshot") => {
    const buffered = chA ? chA.bufferedAmount() : 0;
    console.log(`[A bufferedAmount] reason=${reason} buffered=${buffered}`);
    return buffered;
  };

  const stopSaturateMonitor = () => {
    if (saturateMonitorId !== null) {
      clearInterval(saturateMonitorId);
      saturateMonitorId = null;
    }
  };

  const startSaturateMonitor = () => {
    stopSaturateMonitor();
    saturateMonitorId = setInterval(() => {
      if (!saturating || !chA) {
        stopSaturateMonitor();
        return;
      }
      logBufferedAmountA("interval");
    }, 250);
  };

  const stopSaturation = () => {
    saturating = false;
    stopSaturateMonitor();
    if (chA) {
      logBufferedAmountA("stop");
    }
    updateSaturateAvailability();
  };

  const updateSaturateAvailability = () => {
    btnSaturate.disabled = !chA || saturating;
  };

  const wire = (from, to, fromName, toName) => {
    from.onSignal((sig) => {
      log(`${fromName} → signal → ${toName}: ${sig.type}`);
      to.signal(sig);
    });
    from.onConnectionState((st) => log(`${fromName} state=${st}`));
  };

  btnStart.onclick = () => {
    btnStart.disabled = true;
    btnClose.disabled = false;
    btnSend.disabled = false;
    btnSaturate.disabled = true;

    log("Creating peers A (initiator) and B...");
    a = KPeerJs.createPeer(true);
    b = KPeerJs.createPeer(false);

    wire(a, b, "A", "B");
    wire(b, a, "B", "A");

    a.onChannel((ch) => {
      log(`A got channel label=${ch.label}`);
      if (ch.label === "data") {
        chA = ch;
        updateSaturateAvailability();
        ch.onText((t) => log(`A received: ${t}`));
      }
    });

    b.onChannel((ch) => {
      log(`B got channel label=${ch.label}`);
      ch.onText((t) => log(`B received: ${t}`));
    });

    // Create channel on A. B will receive it via onChannel.
    log("A creating channel 'data' ...");
    a.createChannel("data");
  };

  btnSend.onclick = () => {
    const text = msgEl.value ?? "hello";
    if (!chA) {
      log("No channel handle on A yet.");
      return;
    }
    const ok = chA.sendText(text);
    log(`A send ok=${ok}, buffered=${chA.bufferedAmount()}`);
    logBufferedAmountA("single-send");
  };

  btnSaturate.onclick = () => {
    if (!chA) {
      log("No channel handle on A yet.");
      return;
    }
    if (saturating) {
      return;
    }

    saturating = true;
    updateSaturateAvailability();
    startSaturateMonitor();
    log(
      `Starting saturation: messageSize=${SATURATE_MESSAGE_SIZE}, batch=${SATURATE_BATCH_SIZE}, targetBuffered=${SATURATE_TARGET_BUFFER}`
    );

    let sent = 0;

    const pump = () => {
      if (!saturating || !chA) {
        stopSaturation();
        return;
      }

      let sendOk = true;
      let sentThisTick = 0;
      while (sendOk && sent < SATURATE_MAX_MESSAGES && sentThisTick < SATURATE_BATCH_SIZE) {
        sendOk = chA.sendText(saturatePayload);
        sent += 1;
        sentThisTick += 1;
      }

      const buffered = chA.bufferedAmount();
      console.log(`[A bufferedAmount] reason=pump sent=${sent} buffered=${buffered} sendOk=${sendOk}`);
      if (sent % (SATURATE_BATCH_SIZE * 4) === 0 || buffered >= SATURATE_TARGET_BUFFER || !sendOk) {
        log(`Saturating... sent=${sent}, buffered=${buffered}, sendOk=${sendOk}`);
      }

      if (!sendOk) {
        log(`Saturation stopped: sendText returned false at buffered=${buffered}`);
        stopSaturation();
        return;
      }

      if (buffered >= SATURATE_TARGET_BUFFER) {
        log(`Saturation target reached: buffered=${buffered}, sent=${sent}`);
        stopSaturation();
        return;
      }

      if (sent >= SATURATE_MAX_MESSAGES) {
        log(`Saturation finished without reaching target: buffered=${buffered}, sent=${sent}`);
        stopSaturation();
        return;
      }

      setTimeout(pump, 0);
    };

    pump();
  };

  btnClose.onclick = () => {
    btnSend.disabled = true;
    stopSaturation();
    btnClose.disabled = true;
    chA = null;
    updateSaturateAvailability();
    log("Closing peers...");
    try { a?.close(); } catch {}
    try { b?.close(); } catch {}
  };
})();

