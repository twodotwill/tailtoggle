#!/usr/bin/env node

const fs = require("fs")

const phone = process.env.PEBBLE_PHONE || process.argv[2] || "100.76.64.6"
const pbwPath = process.env.PBW_PATH || process.argv[3] || "dist/tailtoggle.pbw"
const pbw = fs.readFileSync(pbwPath)
const payload = Buffer.concat([Buffer.from([0x04]), pbw])
const ws = new WebSocket(`ws://${phone}:9000/`)
let sent = false

const timeout = setTimeout(() => {
  console.error("Timed out waiting for install result")
  try {
    ws.close()
  } catch (error) {
  }
  process.exit(2)
}, 60000)

ws.addEventListener("open", () => {
  console.log("connected to Core Devices dev server")
})

ws.addEventListener("message", async (event) => {
  const data = event.data instanceof Blob
    ? Buffer.from(await event.data.arrayBuffer())
    : Buffer.from(event.data)
  const type = data[0]

  if (type === 0x07 && !sent) {
    sent = true
    console.log("sending PBW bytes", pbw.length)
    ws.send(payload)
    return
  }

  if (type === 0x05) {
    clearTimeout(timeout)
    const status = data.length >= 5 ? data.readUInt32LE(1) : data[1]
    console.log("install status", status === 0 ? "success" : "failure", `(${status})`)
    ws.close()
    process.exit(status === 0 ? 0 : 1)
  }
})

ws.addEventListener("error", (event) => {
  clearTimeout(timeout)
  console.error("websocket error", event.message || event.type || event)
  process.exit(1)
})
