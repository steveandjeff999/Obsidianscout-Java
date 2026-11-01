/**
 * Node tests for how an open page keeps up with the server (services/live-refresh.js): refreshes spread out however
 * fast changes come, nothing happens while the reader is busy, hidden or offline, and it catches up afterwards.
 * Run: node --test src/test/js/live-refresh.test.mjs   (also part of `gradlew check` via jsTest)
 */
import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";
import { setTimeout as sleep } from "node:timers/promises";

const here = path.dirname(fileURLToPath(import.meta.url));
const js = (p) => pathToFileURL(path.resolve(here, "../../main/resources/static/js", p)).href;

// Minimal browser globals so the modules load under Node.
const store = new Map();
globalThis.localStorage = {
    get length() { return store.size; },
    key: (i) => Array.from(store.keys())[i] ?? null,
    getItem: (k) => (store.has(k) ? store.get(k) : null),
    setItem: (k, v) => { store.set(k, String(v)); },
    removeItem: (k) => { store.delete(k); },
    clear: () => store.clear()
};
const target = new EventTarget();
globalThis.window = {
    location: { href: "http://localhost/graphs", origin: "http://localhost", pathname: "/graphs", search: "" },
    addEventListener: target.addEventListener.bind(target),
    removeEventListener: target.removeEventListener.bind(target),
    dispatchEvent: target.dispatchEvent.bind(target),
    getSelection: () => selection,
    setTimeout, clearTimeout,
    Obsidianscout: { isServerOnline: () => serverOnline }
};
if (typeof globalThis.CustomEvent === "undefined") {
    globalThis.CustomEvent = class CustomEvent extends Event {
        constructor(type, init = {}) { super(type); this.detail = init.detail; }
    };
}
const documentEvents = new EventTarget();
let serverOnline = true;
let selection = null;
let dialogOpen = false;
let focused = true;
globalThis.document = {
    hidden: false,
    activeElement: null,
    hasFocus: () => focused,
    body: { getAttribute: () => "graphs", dataset: { page: "graphs" } },
    addEventListener: documentEvents.addEventListener.bind(documentEvents),
    removeEventListener: documentEvents.removeEventListener.bind(documentEvents),
    querySelector: () => (dialogOpen ? {} : null),
    querySelectorAll: () => []
};
Object.defineProperty(globalThis, "navigator", { value: { onLine: true, locks: null }, configurable: true });

const { liveRefresh } = await import(js("services/live-refresh.js"));

function invalidate(topics, eventKey) {
    window.dispatchEvent(new CustomEvent("obsidianscout:invalidate", { detail: { topics, eventKey } }));
}

const GATHER_MS = 300;
const stops = [];

function page(topics, options) {
    const page = { runs: 0, refresh: async () => { page.runs++; } };
    stops.push(liveRefresh(topics, page.refresh, { minIntervalMs: 50, catchUpSpreadMs: 0, settleMs: 0, maxGatherMs: 0, spreadMs: 0, ...options }));
    return page;
}

test.afterEach(() => {
    stops.splice(0).forEach((stop) => stop());
    serverOnline = true;
    selection = null;
    dialogOpen = false;
    focused = true;
    document.hidden = false;
    document.activeElement = null;
});

test("refreshes for its topics only", async () => {
    const p = page(["scouting"]);
    invalidate(["chat"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 0);
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 1);
});

test("a steady stream of changes can't hold a refresh back", async () => {
    const p = page(["scouting"], { minIntervalMs: 200 });
    for (let i = 0; i < 12; i++) {
        invalidate(["scouting"]);
        await sleep(100);
    }
    assert.ok(p.runs >= 1, `expected a refresh during the stream, saw ${p.runs}`);
});

test("changes to another event are dropped without a request", async () => {
    const p = page(["schedule"], { eventKey: () => "2026cada" });
    invalidate(["schedule"], "2026other");
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 0);
    invalidate(["schedule"], "2026CADA");
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 1);
});

test("a page showing all events takes changes from any of them", async () => {
    const p = page(["schedule"], { eventKey: () => "" });
    invalidate(["schedule"], "2026other");
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 1);
});

test("waits while the tab is hidden", async () => {
    const p = page(["scouting"]);
    document.hidden = true;
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 0);
    document.hidden = false;
    documentEvents.dispatchEvent(new Event("visibilitychange"));
    await sleep(100);
    assert.equal(p.runs, 1);
});

test("a visible window without focus keeps updating, so a pit display stays current", async () => {
    const p = page(["scouting"]);
    focused = false;
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 1);
});

test("a burst of changes is one refresh, once they stop", async () => {
    const p = page(["scouting"], { settleMs: 500, maxGatherMs: 5000 });
    for (let i = 0; i < 5; i++) {
        invalidate(["scouting"]);
        await sleep(GATHER_MS + 50);
    }
    assert.equal(p.runs, 0);
    await sleep(600);
    assert.equal(p.runs, 1);
});

test("changes that never stop still refresh at the gathering limit", async () => {
    const p = page(["scouting"], { settleMs: 600, maxGatherMs: 1000 });
    for (let i = 0; i < 6; i++) {
        invalidate(["scouting"]);
        await sleep(GATHER_MS + 50);
    }
    assert.ok(p.runs >= 1, `expected a refresh at the limit, saw ${p.runs}`);
});

test("each page waits its own random time, so a score sync doesn't arrive all at once", async () => {
    const random = Math.random;
    Math.random = () => 0.5;
    const p = page(["schedule"], { spreadMs: 1000 });
    invalidate(["schedule"]);
    await sleep(GATHER_MS + 20);
    Math.random = random;
    assert.equal(p.runs, 0);
    await sleep(600);
    assert.equal(p.runs, 1);
});

test("offline does nothing, and back online catches up", async () => {
    const p = page(["scouting"]);
    serverOnline = false;
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 0);
    serverOnline = true;
    window.dispatchEvent(new CustomEvent("obsidianscout:connection-changed", { detail: { online: true } }));
    await sleep(100);
    assert.equal(p.runs, 1);
});

test("a reconnected socket catches up on what it missed", async () => {
    const p = page(["scouting"]);
    window.dispatchEvent(new CustomEvent("obsidianscout:socket-reconnected"));
    await sleep(100);
    assert.equal(p.runs, 1);
});

test("catching up after a reconnect waits a random time, so a restart doesn't bring everyone at once", async () => {
    const random = Math.random;
    Math.random = () => 0.5;
    const p = page(["scouting"], { catchUpSpreadMs: 600 });
    window.dispatchEvent(new CustomEvent("obsidianscout:socket-reconnected"));
    window.dispatchEvent(new CustomEvent("obsidianscout:connection-changed", { detail: { online: true } }));
    Math.random = random;
    await sleep(150);
    assert.equal(p.runs, 0);
    await sleep(350);
    assert.equal(p.runs, 1);
});

test("a change that arrives during the wait doesn't have to wait with it", async () => {
    const p = page(["scouting"], { catchUpSpreadMs: 60000 });
    window.dispatchEvent(new CustomEvent("obsidianscout:socket-reconnected"));
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    assert.equal(p.runs, 1);
});

test("waits while someone is typing, but not for a ticked checkbox", async () => {
    const p = page(["scouting"]);
    document.activeElement = { tagName: "INPUT", type: "text" };
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 100);
    assert.equal(p.runs, 0);

    document.activeElement = { tagName: "INPUT", type: "checkbox" };
    await sleep(1600);
    assert.equal(p.runs, 1);
});

test("waits for an open dialog and a text selection", async () => {
    const p = page(["scouting"]);
    dialogOpen = true;
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 100);
    assert.equal(p.runs, 0);

    dialogOpen = false;
    selection = { isCollapsed: false };
    await sleep(1600);
    assert.equal(p.runs, 0);

    selection = null;
    await sleep(1600);
    assert.equal(p.runs, 1);
});

test("a failed refresh is quiet, and the next change tries again", async () => {
    let attempts = 0;
    const warn = console.warn;
    console.warn = () => {};
    stops.push(liveRefresh(["scouting"], async () => {
        attempts++;
        if (attempts === 1) throw new Error("server busy");
    }, { minIntervalMs: 50, settleMs: 0, maxGatherMs: 0, spreadMs: 0 }));
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 50);
    invalidate(["scouting"]);
    await sleep(GATHER_MS + 100);
    console.warn = warn;
    assert.equal(attempts, 2);
});

test("spinners and retry screens stay away during a background refresh", async () => {
    const { runQuietly, isQuietRefresh } = await import(js("utilities/quiet-refresh.js"));
    assert.equal(isQuietRefresh(), false);
    let during = false;
    await runQuietly(async () => { during = isQuietRefresh(); });
    assert.equal(during, true);
    assert.equal(isQuietRefresh(), false);
    await assert.rejects(runQuietly(async () => { throw new Error("no"); }));
    assert.equal(isQuietRefresh(), false);
});

test("the last-updated line follows the server, and says when it's showing a saved copy", async () => {
    const attributes = new Map();
    const main = { setAttribute: (k, v) => attributes.set(k, v) };
    const querySelector = document.querySelector;
    document.querySelector = (s) => (s === ".main-content" ? main : querySelector(s));
    document.readyState = "complete";
    const fetched = (path) => window.dispatchEvent(new CustomEvent("obsidianscout:data-fetched", { detail: { path } }));
    const line = () => attributes.get("data-last-updated");

    const { initLastUpdated } = await import(js("components/last-updated.js"));
    initLastUpdated();
    const onLoad = line();
    assert.match(onLoad, /^Last updated /);

    // Background requests every page makes say nothing about the page's data.
    fetched("/api/version");
    fetched("/api/chat/unread");
    fetched("/api/banners");
    assert.equal(line(), onLoad);

    await sleep(1100);
    fetched("/api/scouting?includePrescout=true");
    assert.match(line(), /^Last updated /);
    assert.notEqual(line(), onLoad);

    window.dispatchEvent(new CustomEvent("obsidianscout:served-from-cache", {
        detail: { path: "/api/teams", timestamp: Date.now() + 5000 }
    }));
    assert.match(line(), /^Showing saved data from /);

    fetched("/api/teams");
    assert.match(line(), /^Last updated /);
    document.querySelector = querySelector;
});
