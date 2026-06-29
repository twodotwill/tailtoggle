const assert = require("assert")
const fs = require("fs")
const path = require("path")
const vm = require("vm")

const source = fs.readFileSync(path.join(__dirname, "..", "src", "pkjs", "index.js"), "utf8")

let stored = {}
let sent = []
let requests = []
let nextResponse = { status: "on", message: "Connect intent sent", vpnActive: true }
let nextStatus = 200
let listeners = {}

class FakeXMLHttpRequest {
  constructor() {
    this.headers = {}
    this.readyState = 0
    this.status = 0
    this.responseText = ""
  }

  open(method, url) {
    this.method = method
    this.url = url
  }

  setRequestHeader(name, value) {
    this.headers[name] = value
  }

  send() {
    requests.push({ method: this.method, url: this.url, headers: this.headers })
    setTimeout(() => {
      this.readyState = 4
      this.status = nextStatus
      this.responseText = JSON.stringify(nextResponse)
      this.onreadystatechange()
    }, 0)
  }
}

const context = {
  console,
  setTimeout,
  clearTimeout,
  XMLHttpRequest: FakeXMLHttpRequest,
  localStorage: {
    getItem(key) {
      return stored[key] || null
    },
    setItem(key, value) {
      stored[key] = value
    },
  },
  Pebble: {
    addEventListener(name, handler) {
      listeners[name] = handler
    },
    openURL(url) {
      context.lastOpenedURL = url
    },
    sendAppMessage(message, success) {
      sent.push(message)
      if (success) success()
    },
  },
}

vm.createContext(context)
vm.runInContext(source, context)

function waitFor(predicate) {
  return new Promise((resolve, reject) => {
    const started = Date.now()
    function tick() {
      const value = predicate()
      if (value) {
        resolve(value)
        return
      }
      if (Date.now() - started > 2000) {
        reject(new Error("timed out"))
        return
      }
      setTimeout(tick, 10)
    }
    tick()
  })
}

async function main() {
  assert.strictEqual(
    JSON.stringify(context.settings()),
    JSON.stringify({ endpoint: "http://127.0.0.1:17999", token: "" }),
  )
  assert.match(context.configurationHtml(), /Companion endpoint/)

  context.saveSettings({ endpoint: "http://127.0.0.1:19000/", token: "secret" })
  listeners.appmessage({ payload: { cmd: 3, desired: 1 } })
  await waitFor(() => sent.find((message) => message.cmd === 4))
  assert.strictEqual(requests[0].method, "POST")
  assert.strictEqual(requests[0].url, "http://127.0.0.1:19000/connect")
  assert.strictEqual(requests[0].headers["X-TailToggle-Token"], "secret")
  assert.strictEqual(sent[0].state, "On")

  sent = []
  requests = []
  nextResponse = { status: "off", message: "Disconnect intent sent", vpnActive: false }
  listeners.appmessage({ payload: { cmd: 2 } })
  await waitFor(() => sent.find((message) => message.cmd === 4))
  assert.strictEqual(requests[0].url, "http://127.0.0.1:19000/toggle")
  assert.strictEqual(sent[0].state, "Off")

  sent = []
  requests = []
  nextStatus = 500
  nextResponse = { error: "intent failed" }
  listeners.appmessage({ payload: { cmd: 1 } })
  await waitFor(() => sent.find((message) => message.cmd === 5))
  assert.strictEqual(sent[0].message, "intent failed")

  console.log("bridge tests passed")
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
