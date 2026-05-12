// Service worker for ZFIN Helper.
// Routes the keyboard command to the active tab's content script.

function isHelperHost(hostname) {
  if (!hostname) return false;
  return (
    hostname === "zfin.org" ||
    hostname.endsWith(".zfin.org") ||
    hostname === "localhost" ||
    hostname === "zfin.atlassian.net"
  );
}

chrome.commands.onCommand.addListener(async (command) => {
  if (command !== "open-helper") return;
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.id) return;
  let hostname = "";
  try { hostname = new URL(tab.url || "").hostname.toLowerCase(); } catch {}
  if (!isHelperHost(hostname)) return;
  try {
    await chrome.tabs.sendMessage(tab.id, { type: "toggle-helper" });
  } catch {
    // Content script not yet injected (e.g. page still loading). Ignore.
  }
});
