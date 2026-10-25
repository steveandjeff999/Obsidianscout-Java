/**
 * Node tests for the slow-connection support on the site:
 * on-device analytics (services/local-analytics.js) against the server's own output in
 * src/test/resources/local-analytics-cases.json (written by LocalAnalyticsVectorsTest), the delta-sync entry store's
 * compact decoding and list rules, the request scheduler, and the per-entry offline queue.
 * Run: node --test src/test/js/low-bandwidth.test.mjs   (also part of `gradlew check` via jsTest)
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

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
const listeners = new Map();
globalThis.window = globalThis.window || {
    location: { href: "http://localhost/", origin: "http://localhost", pathname: "/" },
    addEventListener: (t, f) => { if (!listeners.has(t)) listeners.set(t, []); listeners.get(t).push(f); },
    removeEventListener: () => {},
    dispatchEvent: (e) => { (listeners.get(e.type) || []).forEach((f) => f(e)); return true; },
    setTimeout, clearTimeout
};
if (typeof globalThis.CustomEvent === "undefined") {
    globalThis.CustomEvent = class CustomEvent extends Event {
        constructor(type, init = {}) { super(type); this.detail = init.detail; }
    };
}
globalThis.document = globalThis.document || { querySelector: () => null, getElementById: () => null, cookie: "", body: null };

const analytics = await import(js("services/local-analytics.js"));
const entryStore = await import(js("services/entry-store.js"));
const scheduler = await import(js("base/request-scheduler.js"));
const netQuality = await import(js("base/net-quality.js"));
const offline = await import(js("services/offline-sync.js"));

const cases = JSON.parse(readFileSync(path.resolve(here, "../resources/local-analytics-cases.json"), "utf8"));
const close = (a, b) => assert.ok(Math.abs(a - b) < 1e-9, `${a} != ${b}`);

// ── On-device analytics == server ────────────────────────────────────────────

test("entry scores match the server", () => {
    for (const entry of cases.entries) {
        close(analytics.scoreEntry(cases.config, entry), cases.expected.scores[entry.id]);
    }
});

test("default widgets match the server", () => {
    assert.deepEqual(analytics.generate(cases.config, cases.entries).widgets, cases.expected.defaultWidgets);
});

test("configured widgets match the server", () => {
    const team = cases.entries.filter((e) => e.targetTeamNumber === 1678);
    assert.deepEqual(analytics.generate(cases.customConfig, team).widgets, cases.expected.customWidgets);
    assert.deepEqual(analytics.generate(cases.customConfig, []).widgets, cases.expected.emptyWidgets);
});

test("prescout merge matches the server", () => {
    const merged = analytics.mergePrescoutEntries(
        cases.entries.filter((e) => !e.isPrescout), cases.entries.filter((e) => e.isPrescout), "2026test", false);
    assert.deepEqual(merged.map((e) => e.id), cases.expected.mergedIds);
});

test("recency weights match the server", () => {
    const team = cases.entries.filter((e) => e.targetTeamNumber === 1678);
    for (const c of cases.expected.recency) {
        const weights = analytics.recencyWeights(team, c.settings);
        assert.equal(weights.length, c.weights.length, c.name);
        weights.forEach((w, i) => close(w, c.weights[i]));
    }
});

test("team comparison builds metrics from stored entries", () => {
    const result = analytics.buildComparison({
        config: cases.config,
        entries: cases.entries,
        teams: [{ eventKey: "2026test", teamNumber: 1678, nickname: "Citrus", opr: 50, epa: null, exp: 30 }],
        teamNumbers: [1678, 118],
        eventKey: "2026test",
        recencyBias: { enabled: false }
    });
    const t = result.teams["1678"];
    assert.equal(t.nickname, "Citrus");
    assert.equal(t.matchesScouted, 5, "five event entries, so prescout is left out");
    const expectedAvg = ["a1", "a2", "a3", "a4", "a5"].reduce((s, id) => s + cases.expected.scores[id], 0) / 5;
    close(t.avgTotalPoints, expectedAvg);
    assert.equal(t.metrics.opr, 50);
    assert.equal(t.metrics.epa, undefined);
    assert.equal(result.teams["118"].matchesScouted, 3, "fewer than three event entries, so prescout would be added (118 has none)");
    assert.equal(result.metricDefinitions.find((m) => m.id === "auto_coral").unit, "3.0 pts");
});

test("dataset flattens entries like the server", () => {
    const ds = analytics.buildDataset({
        matchConfig: cases.config, pitConfig: { fields: [] }, qualConfig: { fields: [] },
        matchEntries: cases.entries.slice(0, 2), pitEntries: [], qualEntries: [],
        teams: [{ eventKey: "2026test", teamNumber: 1678, opr: 50 }], eventKeyFilter: null, program: "FRC"
    });
    const first = ds.matchEntries[0];
    assert.equal(first.match_auto_coral, 3);
    assert.equal(first.calc_pts_auto_coral, 9);
    assert.equal(first.calc_pts_leave, 2);
    assert.equal(first.tba_opr, 50);
    assert.equal(ds.totalTeams, 1);
});

// ── Entry store ─────────────────────────────────────────────────────────────

test("compact rows decode to the server's records", () => {
    const { cols, keys, rows } = cases.expected.packed;
    assert.deepEqual(entryStore.unpackCompact(cols, keys, rows), cases.entries.map((e) => {
        const out = { ...e };
        for (const c of cols) if (!(c in out)) out[c] = null;
        return out;
    }));
});

test("lists are de-duplicated per match and team, own team first", () => {
    const list = [
        { id: "x", matchKey: "m1", targetTeamNumber: 1, ownerTeamNumber: 9999, createdAt: "2026-01-02" },
        { id: "y", matchKey: "m1", targetTeamNumber: 1, ownerTeamNumber: 254, createdAt: "2026-01-01" },
        { id: "z", matchKey: "m2", targetTeamNumber: 1, ownerTeamNumber: 9999, createdAt: "2026-01-01", isPrescout: true }
    ];
    const route = { kind: "match", prescoutOnly: false };
    assert.deepEqual(entryStore.deriveList(list, route, new URLSearchParams(""), 254).map((e) => e.id), ["y"]);
    assert.deepEqual(entryStore.deriveList(list, route, new URLSearchParams("includePrescout=true&all=true"), 254).map((e) => e.id), ["x", "y", "z"]);
    assert.deepEqual(entryStore.deriveList(list, { kind: "match", prescoutOnly: true }, new URLSearchParams(""), 254).map((e) => e.id), ["z"]);
    assert.deepEqual(entryStore.resolveEntries([{ id: "p1", eventKey: "e", targetTeamNumber: 5 }, { id: "p2", eventKey: "e", targetTeamNumber: 5 }], "pit", 1).map((e) => e.id), ["p1"]);
});

test("scouting reads use the network on a good connection", async () => {
    netQuality.setLowBandwidthMode("off");
    assert.equal(await entryStore.serveFromEntryStore("/api/scouting?all=true"), undefined);
    assert.equal(await entryStore.serveFromEntryStore("/api/teams?eventKey=x"), undefined);
});

// ── Scheduler ───────────────────────────────────────────────────────────────

test("priorities: writes first, bulk last", () => {
    const { PRIORITY, priorityFor } = scheduler;
    assert.equal(priorityFor("POST", "/api/scouting"), PRIORITY.CRITICAL);
    assert.equal(priorityFor("GET", "/api/assignments/my"), PRIORITY.HIGH);
    assert.equal(priorityFor("GET", "/api/matches/predict-all?eventKey=x"), PRIORITY.LOW);
    assert.equal(priorityFor("GET", "/api/matches?eventKey=x"), PRIORITY.HIGH);
    assert.equal(priorityFor("GET", "/api/whatever"), PRIORITY.NORMAL);
});

test("on a slow link bulk requests run one at a time and never block uploads", async () => {
    netQuality.setLowBandwidthMode("on");
    const { PRIORITY, schedule } = scheduler;
    const order = [];
    let releaseBulk;
    const bulkGate = new Promise((r) => { releaseBulk = r; });
    const bulk1 = schedule(PRIORITY.LOW, async () => { order.push("bulk1:start"); await bulkGate; order.push("bulk1:end"); });
    const bulk2 = schedule(PRIORITY.LOW, async () => { order.push("bulk2"); });
    const upload = schedule(PRIORITY.CRITICAL, async () => { order.push("upload"); });
    await upload;
    assert.deepEqual(order, ["bulk1:start", "upload"], "the upload ran while bulk1 held the only bulk slot; bulk2 waited");
    releaseBulk();
    await Promise.all([bulk1, bulk2]);
    assert.deepEqual(order, ["bulk1:start", "upload", "bulk1:end", "bulk2"]);
    netQuality.setLowBandwidthMode("auto");
});

test("link estimate turns low-bandwidth mode on and off with hysteresis", () => {
    netQuality.setLowBandwidthMode("auto");
    // kbit/s * 4 s = bits; / 8 = bytes, always above the 4 KB minimum sample size.
    const sample = (kbps) => netQuality.recordTransfer({ decodedBytes: (kbps * 4000) / 8, gzipped: false, ttfbMs: 100, downloadMs: 4000 });
    for (let i = 0; i < 8; i++) sample(10);
    assert.equal(netQuality.isLowBandwidth(), true);
    for (let i = 0; i < 3; i++) sample(70);
    assert.equal(netQuality.isLowBandwidth(), true, "70 kbps is between the thresholds, so it stays on");
    for (let i = 0; i < 10; i++) sample(500);
    assert.equal(netQuality.isLowBandwidth(), false);
});

// ── Offline queue ───────────────────────────────────────────────────────────

test("queued entries each get their own key and survive concurrent removal", async () => {
    localStorage.clear();
    const key = "pending_scouting_entries";
    assert.equal(await offline.enqueueOfflineEntries(key, [{ data: { a: 1 } }, { data: { a: 2 } }]), true);
    const [first, second] = offline.readPendingQueue(key);
    assert.ok(first.clientId && second.clientId && first.clientId !== second.clientId);
    // A sync in another tab removes the first while this tab saves a third: nothing is lost or resurrected.
    await offline.removePendingItem(key, first.clientId);
    await offline.enqueueOfflineEntry(key, { data: { a: 3 } });
    assert.deepEqual(offline.readPendingQueue(key).map((i) => i.data.a).sort(), [2, 3]);
    assert.equal(localStorage.getItem(key), null, "the shared array is never written");
});

test("entries left in the old shared array are moved and keep everything", () => {
    localStorage.clear();
    const key = "pending_pit_scouting_entries";
    localStorage.setItem(key, JSON.stringify([{ data: { t: 1 }, createdAt: "2026-01-01" }, { data: { t: 2 }, createdAt: "2026-01-02", clientId: "keep-me" }]));
    const items = offline.readPendingQueue(key);
    assert.deepEqual(items.map((i) => i.data.t), [1, 2]);
    assert.equal(items[1].clientId, "keep-me");
    assert.equal(localStorage.getItem(key), null);
    assert.equal(offline.readPendingQueue(key).length, 2, "reading again finds the same two");
});

test("rejected entries are retried and discarded by id", async () => {
    localStorage.clear();
    localStorage.setItem("failed_offline_entries", JSON.stringify([
        { type: "match-scouting", item: { clientId: "f1", data: {} }, status: 400, failedAt: "2026-01-01" },
        { type: "match-scouting", item: { clientId: "f2", data: {} }, status: 400, failedAt: "2026-01-02" }
    ]));
    const failed = offline.getFailedEntries();
    assert.deepEqual(failed.map((f) => f.failedId), ["f1", "f2"]);
    assert.equal(await offline.retryFailedEntry("f2"), true);
    await offline.discardFailedEntry("f1");
    assert.equal(offline.getFailedEntries().length, 0);
    assert.deepEqual(offline.readPendingQueue("pending_scouting_entries").map((i) => i.clientId), ["f2"]);
});

// ── Saves that time out or can't reach the server go to the offline queue ────

const http = await import(js("base/http.js"));

test("network failures are told apart from server refusals", () => {
    assert.equal(http.isNetworkError(new TypeError("Failed to fetch")), true);
    assert.equal(http.isNetworkError(Object.assign(new Error("Request timed out. Try refreshing this page."), { network: true })), true);
    assert.equal(http.isNetworkError(Object.assign(new Error("Bad gateway"), { status: 502, network: true })), true);
    assert.equal(http.isNetworkError(Object.assign(new Error("Missing required fields: auto"), { status: 400 })), false);
    assert.equal(http.isNetworkError(Object.assign(new Error("Forbidden"), { status: 403 })), false);
    assert.equal(http.isNetworkError(null), false);
});

test("a queueable save on a slow link is handed to the queue without touching the network", async () => {
    localStorage.clear();
    const realFetch = globalThis.fetch;
    let fetched = 0;
    globalThis.fetch = async () => { fetched++; throw new Error("should not be called"); };
    netQuality.setLowBandwidthMode("on");
    try {
        await assert.rejects(
            http.request("/api/scouting", { method: "POST", queueable: true, json: { data: { a: 1 } } }),
            (err) => http.isNetworkError(err)
        );
        assert.equal(fetched, 0);
    } finally {
        netQuality.setLowBandwidthMode("auto");
        globalThis.fetch = realFetch;
    }
});

test("a stalled save is reported as a network error, not a plain failure", async () => {
    localStorage.clear();
    const realFetch = globalThis.fetch;
    globalThis.fetch = (url, init) => new Promise((_, reject) => {
        init.signal.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
    });
    try {
        await assert.rejects(
            http.request("/api/scouting", { method: "POST", json: { data: { a: 1 } }, timeoutMs: 30 }),
            (err) => http.isNetworkError(err) && !err.status
        );
    } finally {
        globalThis.fetch = realFetch;
    }
});
