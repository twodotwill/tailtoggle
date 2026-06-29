var KEY_CMD = "cmd";
var KEY_STATE = "state";
var KEY_MESSAGE = "message";
var KEY_DESIRED = "desired";

var CMD_STATUS = 1;
var CMD_TOGGLE = 2;
var CMD_SET = 3;
var CMD_RESULT = 4;
var CMD_ERROR = 5;

var DESIRED_OFF = 0;
var DESIRED_ON = 1;

var DEFAULT_ENDPOINT = "http://127.0.0.1:17999";
var SETTINGS_KEY = "tailtoggle_settings";
var BUILD_KEY = "tailtoggle_build_label";
var BUILD_LABEL = "v0.2.0";

function trim(value) {
  return String(value || "").replace(/^\s+|\s+$/g, "");
}

function normalizeEndpoint(value) {
  var endpoint = trim(value || DEFAULT_ENDPOINT);
  return endpoint.replace(/\/+$/, "");
}

function settings() {
  migrateSettings();
  var raw = localStorage.getItem(SETTINGS_KEY);
  if (!raw) {
    return { endpoint: DEFAULT_ENDPOINT, token: "" };
  }
  try {
    var parsed = JSON.parse(raw);
    return {
      endpoint: normalizeEndpoint(parsed.endpoint),
      token: trim(parsed.token)
    };
  } catch (e) {
    return { endpoint: DEFAULT_ENDPOINT, token: "" };
  }
}

function migrateSettings() {
  if (localStorage.getItem(BUILD_KEY) === BUILD_LABEL) {
    return;
  }
  localStorage.setItem(BUILD_KEY, BUILD_LABEL);
  if (!localStorage.getItem(SETTINGS_KEY)) {
    saveSettings({ endpoint: DEFAULT_ENDPOINT, token: "" });
  }
}

function saveSettings(next) {
  localStorage.setItem(SETTINGS_KEY, JSON.stringify({
    endpoint: normalizeEndpoint(next.endpoint),
    token: trim(next.token)
  }));
}

function compact(value, limit) {
  value = String(value || "").replace(/\s+/g, " ");
  if (value.length <= limit) {
    return value;
  }
  return value.slice(0, limit - 3) + "...";
}

function stateLabel(status) {
  status = String(status || "").toLowerCase();
  if (status === "on" || status === "connected" || status === "vpn_active") {
    return "On";
  }
  if (status === "off" || status === "disconnected" || status === "vpn_inactive") {
    return "Off";
  }
  if (status === "sent_connect") {
    return "On";
  }
  if (status === "sent_disconnect") {
    return "Off";
  }
  return "Unknown";
}

function companionRequest(path, method, callback) {
  var config = settings();
  var xhr = new XMLHttpRequest();
  var done = false;
  var timer = setTimeout(function() {
    finish(new Error("Companion timed out"));
  }, 8000);

  function finish(error, data) {
    if (done) {
      return;
    }
    done = true;
    clearTimeout(timer);
    callback(error, data);
  }

  try {
    xhr.open(method || "GET", config.endpoint + path, true);
    xhr.setRequestHeader("Accept", "application/json");
    if (config.token) {
      xhr.setRequestHeader("X-TailToggle-Token", config.token);
    }
    xhr.onreadystatechange = function() {
      if (xhr.readyState !== 4) {
        return;
      }
      var body = {};
      if (xhr.responseText) {
        try {
          body = JSON.parse(xhr.responseText);
        } catch (e) {
          finish(new Error("Bad companion response"));
          return;
        }
      }
      if (xhr.status < 200 || xhr.status >= 300) {
        finish(new Error(body.error || ("HTTP " + xhr.status)));
        return;
      }
      finish(null, body);
    };
    xhr.onerror = function() {
      finish(new Error("Companion unavailable"));
    };
    xhr.send("");
  } catch (e) {
    finish(e);
  }
}

function commandPath(command, desired) {
  if (command === CMD_STATUS) {
    return { path: "/status", method: "GET" };
  }
  if (command === CMD_TOGGLE) {
    return { path: "/toggle", method: "POST" };
  }
  if (command === CMD_SET && desired === DESIRED_ON) {
    return { path: "/connect", method: "POST" };
  }
  if (command === CMD_SET && desired === DESIRED_OFF) {
    return { path: "/disconnect", method: "POST" };
  }
  return null;
}

function handleCommand(command, desired) {
  var request = commandPath(command, desired);
  if (!request) {
    sendError("Unknown watch command");
    return;
  }

  companionRequest(request.path, request.method, function(error, response) {
    if (error) {
      sendError(error.message || String(error));
      return;
    }
    var status = response.status || (response.vpnActive ? "on" : "off");
    var message = response.message || (response.vpnActive ? "Active" : "Inactive");
    sendResult(stateLabel(status), message);
  });
}

function sendResult(state, message) {
  Pebble.sendAppMessage({
    cmd: CMD_RESULT,
    state: state,
    message: compact(message, 90)
  });
}

function sendError(message) {
  Pebble.sendAppMessage({
    cmd: CMD_ERROR,
    message: compact(message, 90)
  });
}

function configurationHtml() {
  var current = settings();
  return [
    "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">",
    "<style>body{font-family:-apple-system,BlinkMacSystemFont,sans-serif;padding:18px;background:#101318;color:#f5f7fb}",
    "label{display:block;margin:14px 0 6px}input{box-sizing:border-box;width:100%;padding:11px;border:1px solid #59606b;border-radius:6px;background:#171c24;color:#fff}",
    "button{margin-top:18px;width:100%;padding:12px;border:0;border-radius:6px;background:#57d163;color:#071109;font-weight:700}",
    "p{color:#b8c0cc;line-height:1.35}</style></head><body>",
    "<h2>Tailscale Toggle</h2>",
    "<p>Keep the Android companion running. The default endpoint is the phone-local bridge.</p>",
    "<label>Companion endpoint</label>",
    "<input id=\"endpoint\" value=\"" + escapeHtml(current.endpoint) + "\" placeholder=\"http://127.0.0.1:17999\">",
    "<label>Shared token (optional)</label>",
    "<input id=\"token\" value=\"" + escapeHtml(current.token) + "\" placeholder=\"leave blank unless set in companion\">",
    "<button onclick=\"save()\">Save</button>",
    "<script>function save(){var data={endpoint:document.getElementById('endpoint').value,token:document.getElementById('token').value};",
    "location.href='pebblejs://close#'+encodeURIComponent(JSON.stringify(data));}</script>",
    "</body></html>"
  ].join("");
}

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/"/g, "&quot;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;");
}

Pebble.addEventListener("ready", function() {
  handleCommand(CMD_STATUS, 0);
});

Pebble.addEventListener("appmessage", function(event) {
  var payload = event.payload || {};
  handleCommand(Number(payload.cmd || 0), Number(payload.desired || 0));
});

Pebble.addEventListener("showConfiguration", function() {
  Pebble.openURL("data:text/html," + encodeURIComponent(configurationHtml()));
});

Pebble.addEventListener("webviewclosed", function(event) {
  if (!event || !event.response) {
    return;
  }
  try {
    saveSettings(JSON.parse(decodeURIComponent(event.response)));
    handleCommand(CMD_STATUS, 0);
  } catch (e) {
    sendError("Settings not saved");
  }
});
