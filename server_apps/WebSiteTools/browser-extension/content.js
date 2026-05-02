(() => {
  if (window.__zfinHelperLoaded) return;
  window.__zfinHelperLoaded = true;

  const DEFAULT_ENV_HOSTS = [
    "cell.zfin.org",
    "trunk.zfin.org",
    "test.zfin.org",
    "schlapp.zfin.org",
    "zfin.org",
  ];

  const PAGE_HISTORY_LIMIT = 100;
  const PAGE_DISPLAY_LIMIT = 20;

  let host = null;
  let root = null;
  let currentCreds = null;
  let envCache = DEFAULT_ENV_HOSTS.slice();
  let envEditMode = false;
  let pagesCache = [];

  function shortName(host) {
    if (!host) return "";
    if (host === "zfin.org") return "zfin.org";
    return host.replace(/\.zfin\.org$/i, "") || host;
  }

  async function getStored(key, fallback) {
    try {
      const data = await chrome.storage.local.get(key);
      return key in data ? data[key] : fallback;
    } catch {
      return fallback;
    }
  }

  async function setStored(key, value) {
    try {
      await chrome.storage.local.set({ [key]: value });
      return true;
    } catch {
      return false;
    }
  }

  async function loadEnvs() {
    const data = await (async () => {
      try { return await chrome.storage.local.get("envHosts"); }
      catch { return {}; }
    })();
    if ("envHosts" in data && Array.isArray(data.envHosts)) {
      envCache = data.envHosts;
    } else {
      envCache = DEFAULT_ENV_HOSTS.slice();
      await setStored("envHosts", envCache);
    }
  }

  async function loadPages() {
    pagesCache = await getStored("recentPages", []);
  }

  function ensureUI() {
    if (host) return;
    host = document.createElement("div");
    host.id = "zfin-helper-host";
    host.style.all = "initial";
    host.style.position = "fixed";
    host.style.inset = "0";
    host.style.zIndex = "2147483647";
    host.style.display = "none";
    document.documentElement.appendChild(host);

    root = host.attachShadow({ mode: "closed" });
    root.innerHTML = `
      <style>
        :host { all: initial; }
        .backdrop {
          position: fixed; inset: 0;
          background: rgba(15, 23, 42, 0.45);
          display: flex; align-items: flex-start; justify-content: center;
          padding-top: 8vh;
          font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
        }
        .panel {
          width: min(620px, 92vw);
          max-height: 84vh;
          display: flex; flex-direction: column;
          background: #fff;
          border-radius: 10px;
          box-shadow: 0 25px 60px rgba(0,0,0,0.35);
          overflow: hidden;
          color: #0f172a;
          outline: none;
        }
        header {
          padding: 12px 16px;
          background: linear-gradient(180deg, #0ea5e9, #0284c7);
          color: white;
          font-weight: 600;
          display: flex; justify-content: space-between; align-items: center; gap: 8px;
          flex: none;
        }
        header .hint { font-weight: 400; font-size: 12px; opacity: 0.9; }
        .body { overflow-y: auto; }
        section { padding: 12px 16px; border-top: 1px solid #e2e8f0; }
        section:first-child { border-top: 0; }
        .section-header {
          display: flex; align-items: center; justify-content: space-between;
          gap: 8px; margin-bottom: 8px;
        }
        h2 {
          margin: 0; font-size: 12px; text-transform: uppercase;
          letter-spacing: 0.04em; color: #475569; font-weight: 600;
        }
        label.field {
          display: block; font-size: 12px; color: #475569; margin-top: 6px;
        }
        input[type=text], input[type=password] {
          width: 100%; box-sizing: border-box;
          padding: 7px 9px; font-size: 14px;
          border: 1px solid #cbd5e1; border-radius: 6px;
          background: #f8fafc;
        }
        input:focus { outline: 2px solid #0ea5e9; border-color: #0ea5e9; background: white; }
        .row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
        .row.mt { margin-top: 10px; }
        button {
          font: inherit; font-size: 13px; cursor: pointer;
          padding: 6px 10px; border-radius: 6px;
          border: 1px solid #cbd5e1; background: #f1f5f9; color: #0f172a;
          display: inline-flex; align-items: center; gap: 8px;
        }
        button:hover:not([disabled]) { background: #e2e8f0; }
        button[disabled] { opacity: 0.5; cursor: default; }
        button.primary { background: #0ea5e9; color: white; border-color: #0284c7; }
        button.primary:hover:not([disabled]) { background: #0284c7; }
        button.danger { background: white; color: #b91c1c; border-color: #fecaca; }
        button.danger:hover:not([disabled]) { background: #fef2f2; }
        button.ghost { background: transparent; border-color: transparent; color: #475569; padding: 4px 8px; font-size: 12px; }
        button.ghost:hover:not([disabled]) { background: #f1f5f9; }
        kbd {
          font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          font-size: 11px; line-height: 1.4;
          padding: 1px 6px;
          background: rgba(15, 23, 42, 0.07);
          border: 1px solid rgba(15, 23, 42, 0.18);
          border-bottom-width: 2px;
          border-radius: 4px;
          color: inherit;
          min-width: 8px; text-align: center;
        }
        button.primary kbd {
          background: rgba(255,255,255,0.18);
          border-color: rgba(255,255,255,0.35);
          color: white;
        }
        .login-summary {
          display: flex; align-items: center; gap: 10px;
          font-size: 13px; color: #0f172a;
        }
        .login-summary .badge {
          display: inline-flex; align-items: center; gap: 6px;
          padding: 3px 10px; border-radius: 999px;
          background: #ecfdf5; color: #047857;
          font-size: 12px; font-weight: 500;
        }
        .input-with-kbd {
          display: flex; align-items: stretch; gap: 6px;
        }
        .input-with-kbd input { flex: 1; }
        .input-with-kbd .kbd-label {
          display: inline-flex; align-items: center; padding: 0 4px;
        }
        .pages-list {
          margin-top: 8px;
          max-height: 240px; overflow-y: auto;
          border: 1px solid #e2e8f0; border-radius: 6px;
          background: #f8fafc;
        }
        .pages-list:empty { display: none; }
        .page-row {
          display: flex; flex-direction: column; gap: 1px;
          padding: 6px 10px; cursor: pointer;
          border-bottom: 1px solid #e2e8f0;
        }
        .page-row:last-child { border-bottom: 0; }
        .page-row:hover, .page-row.active { background: #e0f2fe; }
        .page-row .title {
          font-size: 13px; color: #0f172a;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
        .page-row .url {
          font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
          font-size: 11px; color: #64748b;
          overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
        }
        .page-empty {
          padding: 10px; text-align: center; color: #94a3b8; font-size: 12px;
        }
        .env-list {
          display: flex; flex-wrap: wrap; gap: 6px;
        }
        .env-pill {
          display: inline-flex; align-items: center; gap: 6px;
          padding: 4px 10px;
          background: #f8fafc; border: 1px solid #e2e8f0;
          border-radius: 6px; cursor: pointer;
          font-size: 13px; color: #0f172a;
          font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
        }
        .env-pill:hover:not(.current) {
          background: #e0f2fe; border-color: #7dd3fc;
        }
        .env-pill.current {
          opacity: 0.7; cursor: default;
          background: #e2e8f0; border-color: #cbd5e1;
        }
        .env-pill kbd { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; }
        .env-pill .remove {
          display: inline-flex; align-items: center; justify-content: center;
          width: 16px; height: 16px;
          margin-left: 2px;
          border-radius: 50%;
          color: #b91c1c; background: transparent;
          font-size: 14px; line-height: 1; cursor: pointer;
          user-select: none;
        }
        .env-pill .remove:hover { background: #fef2f2; }
        .env-add { display: flex; gap: 6px; margin-top: 8px; }
        .env-add input { flex: 1; }
        .env-help, .help {
          margin-top: 8px; font-size: 11px; color: #64748b;
          display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
        }
        .actions-row { display: flex; gap: 8px; flex-wrap: wrap; }
        .status {
          margin-top: 8px; font-size: 12px; color: #475569; min-height: 1em;
        }
        .status.ok { color: #047857; }
        .status.err { color: #b91c1c; }
        .close {
          background: transparent; border: 0; color: white;
          font-size: 18px; cursor: pointer; padding: 0 4px; line-height: 1;
        }
        [hidden] { display: none !important; }
      </style>
      <div class="backdrop" part="backdrop">
        <div class="panel" role="dialog" aria-label="ZFIN Helper" tabindex="-1">
          <header>
            <span>ZFIN Helper</span>
            <span class="hint"><kbd>esc</kbd> to close</span>
            <button class="close" aria-label="Close">×</button>
          </header>

          <div class="body">
            <section>
              <h2>Login auto-fill</h2>

              <div id="zh-collapsed" hidden>
                <div class="login-summary">
                  <span class="badge">Saved as <strong id="zh-user-display"></strong></span>
                </div>
                <div class="row mt">
                  <button class="primary" id="zh-fill"><span>Fill + sign in</span><kbd>F</kbd></button>
                  <button id="zh-edit"><span>Edit</span><kbd>E</kbd></button>
                  <button class="danger" id="zh-clear"><span>Forget</span><kbd>X</kbd></button>
                </div>
              </div>

              <div id="zh-expanded">
                <label class="field" for="zh-user">Username</label>
                <input id="zh-user" type="text" autocomplete="off" spellcheck="false">
                <label class="field" for="zh-pass">Password</label>
                <input id="zh-pass" type="password" autocomplete="off">
                <div class="row mt">
                  <button class="primary" id="zh-save"><span>Save for session</span><kbd>↵</kbd></button>
                  <button id="zh-cancel" hidden><span>Cancel</span><kbd>esc</kbd></button>
                </div>
              </div>

              <div class="status" id="zh-cred-status"></div>
            </section>

            <section>
              <h2>Quick actions</h2>
              <div class="actions-row">
                <button id="zh-go-case"><span>Go to case</span><kbd>C</kbd></button>
                <button id="zh-go-gene"><span>Go to gene</span><kbd>G</kbd></button>
              </div>
            </section>

            <section>
              <div class="section-header">
                <h2>Recent pages</h2>
                <button class="ghost" id="zh-pages-clear">Clear</button>
              </div>
              <div class="input-with-kbd">
                <input id="zh-pages-filter" type="text" autocomplete="off" spellcheck="false" placeholder="Filter recent pages…">
                <span class="kbd-label"><kbd>R</kbd></span>
              </div>
              <div class="pages-list" id="zh-pages-list"></div>
              <div class="help">
                <span>↑/↓ to move ·</span><kbd>↵</kbd><span>to open ·</span><kbd>⇧</kbd>+<kbd>↵</kbd><span>for new tab</span>
              </div>
            </section>

            <section>
              <div class="section-header">
                <h2>Jump to environment</h2>
                <button class="ghost" id="zh-env-edit-toggle">Edit</button>
              </div>
              <div class="env-list" id="zh-env-list"></div>
              <div class="env-add" id="zh-env-add" hidden>
                <input id="zh-env-add-input" type="text" autocomplete="off" spellcheck="false" placeholder="newhost.zfin.org">
                <button id="zh-env-add-btn" class="primary">Add</button>
              </div>
              <div class="env-help" id="zh-env-help">
                <span>Press</span><kbd>1</kbd><span>–</span><kbd id="zh-env-max"></kbd>
                <span>to open here · add</span><kbd>⇧</kbd><span>for a new tab</span>
              </div>
            </section>
          </div>
        </div>
      </div>
    `;

    const backdrop = root.querySelector(".backdrop");
    backdrop.addEventListener("click", (e) => {
      if (e.target === backdrop) hideUI();
    });
    root.querySelector(".close").addEventListener("click", hideUI);
    root.querySelector("#zh-save").addEventListener("click", () => saveCreds());
    root.querySelector("#zh-fill").addEventListener("click", () => fillLogin({ showFeedback: true, submit: true }));
    root.querySelector("#zh-clear").addEventListener("click", () => clearCreds());
    root.querySelector("#zh-edit").addEventListener("click", () => expandLogin());
    root.querySelector("#zh-cancel").addEventListener("click", () => {
      if (currentCreds) collapseLogin();
    });
    root.querySelector("#zh-go-case").addEventListener("click", () => goToCase());
    root.querySelector("#zh-go-gene").addEventListener("click", () => goToGene());

    root.querySelector("#zh-pages-filter").addEventListener("input", () => renderPages());
    root.querySelector("#zh-pages-filter").addEventListener("keydown", onPagesFilterKey);
    root.querySelector("#zh-pages-clear").addEventListener("click", clearPages);

    root.querySelector("#zh-env-edit-toggle").addEventListener("click", toggleEnvEdit);
    root.querySelector("#zh-env-add-btn").addEventListener("click", () => addEnv(root.querySelector("#zh-env-add-input").value));
  }

  function renderEnvList() {
    const list = root.querySelector("#zh-env-list");
    list.innerHTML = "";
    const currentHost = location.host.toLowerCase();
    const path = location.pathname + location.search + location.hash;
    const envs = Array.isArray(envCache) ? envCache : [];
    envs.forEach((h, i) => {
      const url = `https://${h}${path}`;
      const isCurrent = h === currentHost;

      const pill = document.createElement("button");
      pill.className = "env-pill" + (isCurrent ? " current" : "");
      pill.title = h + (isCurrent ? " (current)" : "");
      pill.disabled = isCurrent;
      pill.addEventListener("click", (e) => {
        if (isCurrent) return;
        hideUI();
        if (e.shiftKey) window.open(url, "_blank", "noopener");
        else location.href = url;
      });

      const num = document.createElement("kbd");
      num.textContent = String(i + 1);
      pill.appendChild(num);

      const label = document.createElement("span");
      label.textContent = shortName(h);
      pill.appendChild(label);

      if (envEditMode) {
        const remove = document.createElement("span");
        remove.className = "remove";
        remove.textContent = "×";
        remove.title = `Remove ${h}`;
        remove.role = "button";
        remove.addEventListener("click", (e) => {
          e.stopPropagation();
          e.preventDefault();
          removeEnv(h);
        });
        pill.appendChild(remove);
      }

      list.appendChild(pill);
    });
    root.querySelector("#zh-env-max").textContent = String(Math.max(envs.length, 1));
    root.querySelector("#zh-env-add").hidden = !envEditMode;
    root.querySelector("#zh-env-help").hidden = envEditMode;
    root.querySelector("#zh-env-edit-toggle").textContent = envEditMode ? "Done" : "Edit";
  }

  function toggleEnvEdit() {
    envEditMode = !envEditMode;
    renderEnvList();
    if (envEditMode) {
      setTimeout(() => root.querySelector("#zh-env-add-input").focus(), 0);
    }
  }

  async function addEnv(raw) {
    const cleaned = (raw || "")
      .trim()
      .toLowerCase()
      .replace(/^https?:\/\//, "")
      .replace(/\/.*$/, "");
    if (!cleaned) return;
    if (envCache.includes(cleaned)) return;
    envCache = [...envCache, cleaned];
    await setStored("envHosts", envCache);
    renderEnvList();
    const input = root.querySelector("#zh-env-add-input");
    input.value = "";
    input.focus();
  }

  async function removeEnv(host) {
    envCache = envCache.filter((h) => h !== host);
    await setStored("envHosts", envCache);
    renderEnvList();
  }

  function renderPages() {
    const filterEl = root.querySelector("#zh-pages-filter");
    const filter = (filterEl?.value || "").trim().toLowerCase();
    const list = root.querySelector("#zh-pages-list");
    list.innerHTML = "";
    let items = pagesCache || [];
    if (filter) {
      items = items.filter(
        (p) =>
          (p.url || "").toLowerCase().includes(filter) ||
          (p.title || "").toLowerCase().includes(filter),
      );
    }
    if (!items.length) {
      const empty = document.createElement("div");
      empty.className = "page-empty";
      empty.textContent = filter ? "No matches." : "No history yet.";
      list.appendChild(empty);
      return;
    }
    items.slice(0, PAGE_DISPLAY_LIMIT).forEach((p, i) => {
      const row = document.createElement("div");
      row.className = "page-row";
      row.dataset.url = p.url;
      if (i === 0) row.classList.add("active");
      const title = document.createElement("div");
      title.className = "title";
      title.textContent = p.title || p.url;
      const url = document.createElement("div");
      url.className = "url";
      try {
        const u = new URL(p.url);
        url.textContent = u.host + u.pathname + u.search;
      } catch {
        url.textContent = p.url;
      }
      row.appendChild(title);
      row.appendChild(url);
      row.addEventListener("click", (e) => {
        hideUI();
        if (e.shiftKey) window.open(p.url, "_blank", "noopener");
        else location.href = p.url;
      });
      list.appendChild(row);
    });
  }

  function onPagesFilterKey(e) {
    const list = root.querySelector("#zh-pages-list");
    const rows = Array.from(list.querySelectorAll(".page-row"));
    if (!rows.length) return;
    const activeIdx = rows.findIndex((r) => r.classList.contains("active"));
    if (e.key === "ArrowDown") {
      e.preventDefault();
      const next = Math.min(rows.length - 1, activeIdx + 1);
      rows.forEach((r, i) => r.classList.toggle("active", i === next));
      rows[next].scrollIntoView({ block: "nearest" });
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      const next = Math.max(0, activeIdx - 1);
      rows.forEach((r, i) => r.classList.toggle("active", i === next));
      rows[next].scrollIntoView({ block: "nearest" });
    } else if (e.key === "Enter") {
      e.preventDefault();
      const target = rows[Math.max(activeIdx, 0)];
      const url = target?.dataset.url;
      if (!url) return;
      hideUI();
      if (e.shiftKey) window.open(url, "_blank", "noopener");
      else location.href = url;
    }
  }

  async function clearPages() {
    pagesCache = [];
    await setStored("recentPages", []);
    renderPages();
  }

  async function pushPageHistory(entry) {
    if (!entry?.url) return;
    const list = await getStored("recentPages", []);
    const filtered = list.filter((x) => x.url !== entry.url);
    filtered.unshift({
      url: entry.url,
      title: entry.title || entry.url,
      ts: Date.now(),
    });
    if (filtered.length > PAGE_HISTORY_LIMIT) filtered.length = PAGE_HISTORY_LIMIT;
    await setStored("recentPages", filtered);
    pagesCache = filtered;
    if (root) renderPages();
  }

  function expandLogin() {
    root.querySelector("#zh-collapsed").hidden = true;
    root.querySelector("#zh-expanded").hidden = false;
    root.querySelector("#zh-cancel").hidden = !currentCreds;
    if (currentCreds) {
      root.querySelector("#zh-user").value = currentCreds.username || "";
      root.querySelector("#zh-pass").value = currentCreds.password || "";
    }
    setTimeout(() => {
      const u = root.querySelector("#zh-user");
      (u.value ? root.querySelector("#zh-pass") : u).focus();
    }, 0);
  }

  function collapseLogin() {
    if (!currentCreds) {
      expandLogin();
      return;
    }
    root.querySelector("#zh-user-display").textContent = currentCreds.username;
    root.querySelector("#zh-collapsed").hidden = false;
    root.querySelector("#zh-expanded").hidden = true;
    setTimeout(() => root.querySelector(".panel").focus(), 0);
  }

  async function showUI() {
    ensureUI();
    host.style.display = "block";
    await Promise.all([loadEnvs(), loadPages()]);
    currentCreds = await sendMessage({ type: "get-credentials" });
    renderEnvList();
    renderPages();
    if (currentCreds) {
      collapseLogin();
      setStatus("", "");
    } else {
      expandLogin();
      setStatus("No credentials stored for this browser session.", "");
    }
  }

  function hideUI() {
    if (host) host.style.display = "none";
  }

  function isVisible() {
    return host && host.style.display !== "none";
  }

  function focusedInPanelInput() {
    const ae = root && root.activeElement;
    if (!ae) return false;
    const t = ae.tagName;
    return t === "INPUT" || t === "TEXTAREA";
  }

  function setStatus(text, kind) {
    const el = root.querySelector("#zh-cred-status");
    el.textContent = text;
    el.className = "status" + (kind ? " " + kind : "");
  }

  async function saveCreds() {
    const username = root.querySelector("#zh-user").value.trim();
    const password = root.querySelector("#zh-pass").value;
    if (!username || !password) {
      setStatus("Enter both a username and password.", "err");
      return;
    }
    const result = await sendMessage({ type: "set-credentials", username, password });
    if (!result || !result.ok) {
      setStatus(
        "Could not save credentials. The extension may need to be reloaded — try chrome://extensions, click reload, then refresh this page.",
        "err",
      );
      return;
    }
    currentCreds = { username, password };
    collapseLogin();
    setStatus("Saved for this browser session.", "ok");
  }

  async function clearCreds() {
    await sendMessage({ type: "clear-credentials" });
    currentCreds = null;
    root.querySelector("#zh-user").value = "";
    root.querySelector("#zh-pass").value = "";
    expandLogin();
    setStatus("Credentials forgotten.", "ok");
  }

  async function fillLogin({ showFeedback = false, submit = false } = {}) {
    const form = findLoginForm();
    if (!form) {
      if (showFeedback) setStatus("No login form on this page.", "err");
      return false;
    }
    let creds = currentCreds;
    if (!creds || !creds.username || !creds.password) {
      creds = await sendMessage({ type: "get-credentials" });
    }
    if (!creds || !creds.username || !creds.password) {
      if (showFeedback) setStatus("No credentials saved yet.", "err");
      return false;
    }
    setNativeValue(form.username, creds.username);
    setNativeValue(form.password, creds.password);
    if (submit) {
      hideUI();
      const submitBtn = form.form?.querySelector('button[type="submit"], input[type="submit"]');
      if (submitBtn) submitBtn.click();
      else if (form.form?.requestSubmit) form.form.requestSubmit();
      else form.form?.submit();
    } else if (showFeedback) {
      setStatus("Login form filled.", "ok");
    }
    return true;
  }

  function findLoginForm() {
    const password = document.querySelector('input[type="password"]');
    if (!password) return null;
    const form = password.form;
    let username =
      form?.querySelector('input[name="username"], input[name="j_username"], input[type="email"]') ||
      document.querySelector('input[name="username"]');
    if (!username) {
      const candidates = form
        ? form.querySelectorAll('input[type="text"], input:not([type])')
        : document.querySelectorAll('input[type="text"], input:not([type])');
      username = candidates[0] || null;
    }
    if (!username) return null;
    return { username, password, form };
  }

  function setNativeValue(input, value) {
    const proto = Object.getPrototypeOf(input);
    const setter = Object.getOwnPropertyDescriptor(proto, "value")?.set;
    if (setter) setter.call(input, value);
    else input.value = value;
    input.dispatchEvent(new Event("input", { bubbles: true }));
    input.dispatchEvent(new Event("change", { bubbles: true }));
  }

  async function goToCase() {
    hideUI();
    const raw = window.prompt("ZFIN case number:");
    if (raw == null) return;
    const num = String(raw).trim().replace(/^ZFIN-/i, "");
    if (!/^\d+$/.test(num)) {
      window.alert(`Not a valid case number: "${raw}"`);
      return;
    }
    const url = `https://zfin.atlassian.net/browse/ZFIN-${num}`;
    await pushPageHistory({ url, title: `ZFIN-${num}` });
    location.href = url;
  }

  async function goToGene() {
    hideUI();
    const raw = window.prompt("Gene name or symbol:");
    if (raw == null) return;
    const name = String(raw).trim();
    if (!name) return;
    const url = `${location.protocol}//${location.host}/action/quicksearch/prototype?q=!!${encodeURIComponent(name)}`;
    await pushPageHistory({ url, title: `Gene: ${name}` });
    location.href = url;
  }

  function sendMessage(msg) {
    return new Promise((resolve) => {
      try {
        chrome.runtime.sendMessage(msg, (response) => {
          if (chrome.runtime.lastError) resolve(null);
          else resolve(response);
        });
      } catch {
        resolve(null);
      }
    });
  }

  document.addEventListener(
    "keydown",
    (e) => {
      const isToggle =
        (e.metaKey || e.ctrlKey) && !e.altKey && !e.shiftKey && e.key.toLowerCase() === "k";
      if (isToggle) {
        e.preventDefault();
        e.stopPropagation();
        if (isVisible()) hideUI();
        else showUI();
        return;
      }

      if (!isVisible()) return;

      if (e.key === "Escape") {
        e.stopPropagation();
        const ae = root && root.activeElement;
        if (envEditMode && ae && ae.id === "zh-env-add-input") {
          toggleEnvEdit();
          return;
        }
        hideUI();
        return;
      }

      if (e.metaKey || e.ctrlKey || e.altKey) return;

      if (focusedInPanelInput()) {
        if (e.key === "Enter") {
          const ae = root.activeElement;
          if (ae?.id === "zh-user" || ae?.id === "zh-pass") {
            e.preventDefault();
            saveCreds();
          } else if (ae?.id === "zh-env-add-input") {
            e.preventDefault();
            addEnv(ae.value);
          }
        }
        return;
      }

      const k = e.key.toLowerCase();
      const collapsed = !root.querySelector("#zh-collapsed").hidden;

      if (collapsed && k === "f") { e.preventDefault(); fillLogin({ showFeedback: true, submit: true }); return; }
      if (collapsed && k === "e") { e.preventDefault(); expandLogin(); return; }
      if (collapsed && k === "x") { e.preventDefault(); clearCreds(); return; }

      if (k === "c") { e.preventDefault(); goToCase(); return; }
      if (k === "g") { e.preventDefault(); goToGene(); return; }
      if (k === "r") { e.preventDefault(); root.querySelector("#zh-pages-filter").focus(); return; }

      if (/^[1-9]$/.test(e.key)) {
        const idx = parseInt(e.key, 10) - 1;
        const target = envCache && envCache[idx];
        if (!target || target === location.host.toLowerCase()) return;
        e.preventDefault();
        const url = `https://${target}${location.pathname}${location.search}${location.hash}`;
        hideUI();
        if (e.shiftKey) window.open(url, "_blank", "noopener");
        else location.href = url;
      }
    },
    true,
  );

  chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
    if (msg?.type === "toggle-helper") {
      if (isVisible()) hideUI();
      else showUI();
      sendResponse({ ok: true });
    }
    return false;
  });

  function isLoginPage() {
    if (/\/(login|sign[- ]?in)/i.test(location.pathname)) return true;
    const form = document.querySelector('form#login, form[action*="j_security-check"]');
    return !!form;
  }

  function shouldRecordCurrentPage() {
    if (/\/(login|j_security[_-]check)/i.test(location.pathname)) return false;
    return true;
  }

  async function recordCurrentPage() {
    if (!shouldRecordCurrentPage()) return;
    const url = location.href;
    const title = (document.title || "").trim() || location.pathname;
    await pushPageHistory({ url, title });
  }

  // Eagerly seed env defaults on first load so the list renders even if
  // the user never opens the helper before navigating.
  loadEnvs();

  if (isLoginPage()) {
    setTimeout(() => fillLogin({ showFeedback: false, submit: false }), 150);
  }
  // Record immediately at content-script-idle so fast navigations still capture.
  recordCurrentPage();
  // And once more after a short delay in case the page sets its title late.
  setTimeout(recordCurrentPage, 600);
})();
