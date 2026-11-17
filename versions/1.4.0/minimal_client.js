// Offline MC 1.21.x client with compression framing
const net = require("net");
const zlib = require("zlib");
const HOST = "127.0.0.1", PORT = 25565, PROTO = 774;

function vi(n) {
  const b = []; let v = n >>> 0;
  for (;;) { if ((v & ~0x7f) === 0) { b.push(v); break; } b.push((v & 0x7f) | 0x80); v >>>= 7; }
  return Buffer.from(b);
}
function vs(s) { const b = Buffer.from(s, "utf8"); return Buffer.concat([vi(b.length), b]); }
function rv(buf, o) {
  let n = 0, r = 0, b;
  do { if (o + n >= buf.length) throw new Error("need more"); b = buf[o + n]; r |= (b & 0x7f) << (7 * n); n++; if (n > 5) throw new Error("varint"); } while ((b & 0x80) !== 0);
  return [r, o + n];
}

function connect(name) {
  return new Promise((resolve, reject) => {
    const sock = net.connect(PORT, HOST);
    let state = "login";
    let compression = -1;
    let buf = Buffer.alloc(0);
    let settled = false;
    const timer = setTimeout(() => {
      if (!settled) { settled = true; sock.destroy(); reject(new Error("timeout " + name)); }
    }, 25000);

    function sendRaw(body) {
      // Before compression: [len][body]. After: [len][dataLen=0][body] if body small
      if (compression < 0) {
        sock.write(Buffer.concat([vi(body.length), body]));
      } else {
        // dataLength 0 = uncompressed
        const inner = Buffer.concat([vi(0), body]);
        sock.write(Buffer.concat([vi(inner.length), inner]));
      }
    }
    function sendPkt(id, payload) {
      sendRaw(Buffer.concat([vi(id), payload]));
    }

    sock.on("connect", () => {
      // Handshake + login always uncompressed
      sendRaw(Buffer.concat([vi(0x00), vi(PROTO), vs(HOST), Buffer.from([(PORT >> 8) & 0xff, PORT & 0xff]), vi(2)]));
      const uuid = Buffer.alloc(16, 9);
      for (let i = 0; i < name.length && i < 16; i++) uuid[i] = name.charCodeAt(i);
      sendRaw(Buffer.concat([vi(0x00), vs(name), uuid]));
      console.log(`[${name}] login sent`);
    });

    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk]);
      try {
        for (;;) {
          let plen, off = 0;
          [plen, off] = rv(buf, 0);
          if (buf.length < off + plen) break;
          let frame = buf.subarray(off, off + plen);
          buf = buf.subarray(off + plen);

          let payload = frame;
          if (compression >= 0) {
            let dataLen, doff = 0;
            [dataLen, doff] = rv(frame, 0);
            const rest = frame.subarray(doff);
            payload = dataLen === 0 ? rest : zlib.inflateSync(rest);
          }

          let pid, poff = 0;
          [pid, poff] = rv(payload, 0);
          const data = payload.subarray(poff);
          handle(pid, data);
        }
      } catch (e) {
        if (e.message !== "need more") console.error(`[${name}] parse`, e.message);
      }
    });

    function handle(pid, data) {
      if (state === "login") {
        if (pid === 0x03) { // login_compression
          let th = 0;
          try { th = rv(data, 0)[0]; } catch {}
          compression = th;
          console.log(`[${name}] compression=${th}`);
          return;
        }
        if (pid === 0x02 || pid === 0x01) { // login finished/success
          console.log(`[${name}] login ok pid=0x${pid.toString(16)}`);
          sendPkt(0x03, Buffer.alloc(0)); // login acknowledged
          state = "config";
          console.log(`[${name}] -> config`);
          return;
        }
        if (pid === 0x00) {
          console.log(`[${name}] login disconnect hex=${data.toString("hex")}`);
          if (!settled) { settled = true; clearTimeout(timer); reject(new Error("login disconnect")); }
          sock.destroy();
        }
        return;
      }
      if (state === "config") {
        if (pid === 0x0e) { // select_known_packs clientbound -> reply empty serverbound 0x07
          sendPkt(0x07, Buffer.from([0])); // varint count=0
          console.log(`[${name}] known_packs empty reply`);
          return;
        }
        if (pid === 0x02) {
          console.log(`[${name}] config disconnect hex=${data.toString("hex")}`);
          if (!settled) { settled = true; clearTimeout(timer); reject(new Error("config disconnect")); }
          sock.destroy();
          return;
        }
        if (pid === 0x03) { // finish configuration (clientbound 0x03)
          sendPkt(0x03, Buffer.alloc(0)); // serverbound finish = 0x03
          state = "play";
          console.log(`[${name}] -> play`);
          if (!settled) { settled = true; clearTimeout(timer); resolve(name); }
          return;
        }
        if (pid === 0x04) { sendPkt(0x04, data); return; } // keepalive serverbound = 0x04
        if (pid === 0x05) { sendPkt(0x05, data); return; } // pong serverbound = 0x05
        return;
      }
      if (state === "play") {
        // Clientbound KEEP_ALIVE = 43 (0x2B), serverbound KEEP_ALIVE = 27 (0x1B)
        if (pid === 43 && data.length === 8) {
          sendPkt(0x1B, data);
        }
        // Clientbound PING = 44? respond pong if needed - skip for now
      }
    }

    sock.on("error", (e) => { if (!settled) { settled = true; clearTimeout(timer); reject(e); } });
    sock.on("close", () => { if (!settled) { settled = true; clearTimeout(timer); reject(new Error("closed")); } });
  });
}

async function main() {
  const names = ["RealOne", "RealTwo", "RealThree", "RealFour"];
  let ok = 0;
  for (const n of names) {
    try { await connect(n); ok++; console.log(`[${n}] OK`); }
    catch (e) { console.error(`[${n}] FAIL:`, e.message); }
  }
  console.log(`CONNECTED ${ok}/${names.length}`);
  setInterval(() => {}, 15000);
}
main().catch((e) => { console.error(e); process.exit(1); });