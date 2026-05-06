// Service worker for ZFIN Helper.
// Credentials live in chrome.storage.session — memory-only, cleared when the
// browser session ends. The content script reads/writes them via messages.

const CRED_KEY = "zfinCredentials";

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "open-helper") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) return;
  let hostname = "";
  try { hostname = new URL(tab.url || "").hostname.toLowerCase(); } catch {}
  if (hostname !== "zfin.org" && !hostname.endsWith(".zfin.org")) return;
  try {
    await chrome.tabs.sendMessage(tab.id, { type: "toggle-helper" });
  } catch {
    // Content script not yet injected (e.g. page still loading). Ignore.
  }
});

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  (async () => {
    if (msg?.type === "get-credentials") {
      const data = await chrome.storage.session.get(CRED_KEY);
      sendResponse(data[CRED_KEY] || null);
    } else if (msg?.type === "set-credentials") {
      await chrome.storage.session.set({
        [CRED_KEY]: { username: msg.username, password: msg.password },
      });
      sendResponse({ ok: true });
    } else if (msg?.type === "clear-credentials") {
      await chrome.storage.session.remove(CRED_KEY);
      sendResponse({ ok: true });
    } else {
      sendResponse(null);
    }
  })();
  return true; // keep the message channel open for the async response
});
