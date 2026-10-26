/**
 * Node tests for keeping scouting entries safe when the server can't take them: the shared save path every form uses
 * (services/scouting-save.js), how upload failures are sorted, an expired sign-in never navigating away, entries from
 * another team's account staying put on shared tablets, and drafts saving only what the scout actually changed.
 * Run: node --test src/test/js/offline-reliability.test.mjs   (also part of `gradlew check` via jsTest)
 */
import test from "node:test";
import assert from "node:assert/strict";
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
const location = { href: "http://localhost/scout", origin: "http://localhost", pathname: "/scout", search: "" };
globalThis.window = {
    location,
    addEventListener: (t, f) => { if (!listeners.has(t)) listeners.set(t, []); listeners.get(t).push(f); },
    removeEventListener: (t, f) => { listeners.set(t, (listeners.get(t) || []).filter((g) => g !== f)); },
    dispatchEvent: (e) => { (listeners.get(e.type) || []).slice().forEach((f) => f(e)); return true; },
    setTimeout, clearTimeout,
    Obsidianscout: { isServerOnline: () => true }
};
if (typeof globalThis.CustomEvent === "undefined") {
    globalThis.CustomEvent = class CustomEvent extends Event {
        constructor(type, init = {}) { super(type); this.detail = init.detail; }
    };
}
const docListeners = new Map();
// Just enough of an element for the save receipt and toasts to render into.
function fakeElement() {
    const el = {
        children: [], dataset: {}, style: {}, attributes: {}, innerHTML: "", textContent: "", className: "", id: "",
        classList: { add() {}, remove() {}, toggle() {}, contains: () => false },
        setAttribute(k, v) { this.attributes[k] = v; },
        getAttribute(k) { return this.attributes[k] ?? null; },
        removeAttribute(k) { delete this.attributes[k]; },
        addEventListener() {}, removeEventListener() {},
        appendChild(c) { this.children.push(c); return c; },
        remove() {}, querySelector: () => null, querySelectorAll: () => []
    };
    return el;
}
const byId = new Map();
const body = fakeElement();
body.dataset.page = "scout";
body.getAttribute = (n) => (n === "data-page" ? body.dataset.page : null);
body.appendChild = (c) => { if (c.id) byId.set(c.id, c); return c; };
globalThis.document = {
    querySelector: () => null,
    querySelectorAll: () => [],
    getElementById: (id) => byId.get(id) || null,
    createElement: () => fakeElement(),
    contains: () => true,
    cookie: "",
    // A scouting form page.
    body,
    visibilityState: "visible",
    addEventListener: (t, f) => { if (!docListeners.has(t)) docListeners.set(t, []); docListeners.get(t).push(f); },
    removeEventListener: () => {}
};

const http = await import(js("base/http.js"));
const offline = await import(js("services/offline-sync.js"));
const save = await import(js("services/scouting-save.js"));
const drafts = await import(js("services/form-draft.js"));

const realFetch = globalThis.fetch;
function respondWith(status, body = {}) {
    const calls = [];
    globalThis.fetch = async (url, init) => {
        calls.push({ url, init, body: init && init.body ? JSON.parse(init.body) : null });
        return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
    };
    return calls;
}
function failNetwork() {
    const calls = [];
    globalThis.fetch = async (url, init) => { calls.push({ url, init }); throw new TypeError("Failed to fetch"); };
    return calls;
}
function reset() {
    localStorage.clear();
    globalThis.fetch = realFetch;
    location.href = "http://localhost/scout";
}
const entry = (team = 254) => ({ eventKey: "2026test", targetTeamNumber: team, matchKey: "2026test_qm12", matchNumber: 12, autoScore: 3 });
const queued = () => offline.readPendingQueue("pending_scouting_entries");

// ── Sorting upload failures ──────────────────────────────────────────────────

test("upload failures: refused entries are kept for review, everything else waits", () => {
    const err = (status, extra = {}) => Object.assign(new Error("x"), { status, ...extra });
    assert.equal(offline.classifyUploadFailure(err(400)), "reject");
    assert.equal(offline.classifyUploadFailure(err(422)), "reject");
    assert.equal(offline.classifyUploadFailure(err(401)), "stop");
    assert.equal(offline.classifyUploadFailure(err(429)), "stop");
    assert.equal(offline.classifyUploadFailure(err(403)), "skip");
    assert.equal(offline.classifyUploadFailure(err(500)), "skip");
    assert.equal(offline.classifyUploadFailure(err(503, { network: true })), "stop");
    assert.equal(offline.classifyUploadFailure(new TypeError("Failed to fetch")), "stop");
});

test("a save is kept on the device for network trouble, timeouts, 5xx and an expired sign-in, not for a refusal", () => {
    const err = (status) => Object.assign(new Error("x"), { status });
    assert.equal(save.shouldKeepOnDevice(new TypeError("Failed to fetch")), true);
    assert.equal(save.shouldKeepOnDevice(Object.assign(new Error("Request stalled"), { name: "AbortError" })), true);
    assert.equal(save.shouldKeepOnDevice(err(500)), true);
    assert.equal(save.shouldKeepOnDevice(err(502)), true);
    assert.equal(save.shouldKeepOnDevice(err(401)), true);
    assert.equal(save.shouldKeepOnDevice(err(429)), true);
    assert.equal(save.shouldKeepOnDevice(err(400)), false);
    assert.equal(save.shouldKeepOnDevice(err(403)), false);
});

// ── The shared save path ─────────────────────────────────────────────────────

test("an upload that succeeds sends the entry's clientId and leaves nothing queued", async () => {
    reset();
    const calls = respondWith(200, { entry: { id: "srv-1" } });
    const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry() });
    assert.equal(result.where, "server");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/scouting");
    assert.ok(calls[0].body.clientId, "clientId goes up with the first attempt");
    assert.deepEqual(calls[0].body.data, entry());
    assert.equal(queued().length, 0);
    reset();
});

test("no connection: the entry is kept on the device with the same clientId it was sent with", async () => {
    reset();
    const calls = failNetwork();
    const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry() });
    assert.equal(result.where, "local");
    const [item] = queued();
    assert.equal(item.clientId, result.items[0].clientId);
    assert.ok(calls.length >= 1);
    reset();
});

test("a server error (500) keeps the entry on the device instead of dropping it", async () => {
    reset();
    respondWith(500, { error: "Database is busy" });
    const result = await save.saveScoutingEntry({ kind: "pit-scouting", entry: { eventKey: "2026test", targetTeamNumber: 1678 } });
    assert.equal(result.where, "local");
    assert.equal(offline.readPendingQueue("pending_pit_scouting_entries").length, 1);
    reset();
});

test("an expired sign-in keeps the entry, flags sign-in, and doesn't navigate away from the form", async () => {
    reset();
    respondWith(401, { error: "Not signed in" });
    let expired = 0;
    window.addEventListener(http.SESSION_EXPIRED_EVENT, () => expired++);
    const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry() });
    assert.equal(result.where, "local");
    assert.equal(queued().length, 1);
    assert.equal(location.href, "http://localhost/scout", "still on the form");
    assert.equal(offline.isSignInNeededForUpload(), true);
    assert.ok(expired >= 1);
    offline.setSignInNeededForUpload(false);
    reset();
});

test("a refused entry (400) is reported and nothing is queued, so the form keeps it", async () => {
    reset();
    respondWith(400, { error: "Missing required fields: auto" });
    const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry() });
    assert.equal(result.where, "rejected");
    assert.match(result.error.message, /Missing required fields/);
    assert.equal(queued().length, 0);
    reset();
});

test("'Save on this device only' never touches the network", async () => {
    reset();
    const calls = respondWith(200, {});
    const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry(), mode: "local" });
    assert.equal(result.where, "local");
    assert.equal(calls.length, 0);
    assert.equal(queued().length, 1);
    reset();
});

test("a full device reports 'not saved' instead of pretending", async () => {
    reset();
    failNetwork();
    const realSet = localStorage.setItem;
    localStorage.setItem = (k, v) => {
        if (k.startsWith("osq:")) throw Object.assign(new Error("full"), { name: "QuotaExceededError" });
        return realSet(k, v);
    };
    try {
        const result = await save.saveScoutingEntry({ kind: "match-scouting", entry: entry() });
        assert.equal(result.where, "not-saved");
        assert.equal(queued().length, 0);
    } finally {
        localStorage.setItem = realSet;
        reset();
    }
});

test("an alliance batch save queues one item per team", async () => {
    reset();
    failNetwork();
    const teams = [254, 1678, 118].map((t) => ({ ...entry(t), type: "qual-scout" }));
    const result = await save.saveScoutingEntries({
        kind: "qual-scouting",
        entries: teams,
        endpoint: "/api/qual-scouting/batch",
        body: (items) => ({ entries: items.map((i) => i.data) }),
        itemExtras: { ownerTeamNumber: 9999, pending: true }
    });
    assert.equal(result.where, "local");
    const items = offline.readPendingQueue("pending_qualitative_entries");
    assert.equal(items.length, 3);
    assert.ok(items.every((i) => i.ownerTeamNumber === 9999 && i.pending === true && i.clientId));
    reset();
});

// ── Background upload ────────────────────────────────────────────────────────

test("background upload stops on an expired sign-in without navigating, and keeps everything", async () => {
    reset();
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(1) }, { data: entry(2) }]);
    const calls = respondWith(401, { error: "Not signed in" });
    await offline.syncOfflineEntries();
    assert.equal(calls.length, 1, "stops after the first 401");
    assert.equal(queued().length, 2);
    assert.equal(location.href, "http://localhost/scout");
    assert.equal(offline.isSignInNeededForUpload(), true);

    // Signing in again: the next upload succeeds and clears the note.
    respondWith(200, {});
    await offline.syncOfflineEntries();
    assert.equal(queued().length, 0);
    assert.equal(offline.isSignInNeededForUpload(), false);
    reset();
});

test("entries saved by another team's account stay on a shared tablet", async () => {
    reset();
    localStorage.setItem("cache:/api/auth/me", JSON.stringify({ user: { userId: "u1", username: "a", teamNumber: 1111, program: "FRC" } }));
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(10) }]);
    localStorage.setItem("cache:/api/auth/me", JSON.stringify({ user: { userId: "u2", username: "b", teamNumber: 2222, program: "FRC" } }));
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(20) }]);

    const summary = offline.getPendingSummary();
    assert.equal(summary.total, 2);
    assert.equal(summary.otherTeam, 1);

    const calls = respondWith(200, {});
    await offline.syncOfflineEntries();
    assert.equal(calls.length, 1, "only team 2222's entry goes up");
    assert.equal(calls[0].body.data.targetTeamNumber, 20);
    const left = queued();
    assert.equal(left.length, 1);
    assert.equal(left[0].savedBy.teamNumber, 1111);
    reset();
});

test("one entry the server can't handle (500) doesn't hold up the others", async () => {
    reset();
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(1), createdAt: "2026-01-01T00:00:01Z" }, { data: entry(2), createdAt: "2026-01-01T00:00:02Z" }]);
    const calls = [];
    globalThis.fetch = async (url, init) => {
        const body = JSON.parse(init.body);
        calls.push(body.data.targetTeamNumber);
        const status = body.data.targetTeamNumber === 1 ? 500 : 200;
        return new Response(JSON.stringify(status === 500 ? { error: "boom" } : {}), { status, headers: { "Content-Type": "application/json" } });
    };
    await offline.syncOfflineEntries();
    assert.deepEqual(calls, [1, 2]);
    assert.deepEqual(queued().map((i) => i.data.targetTeamNumber), [1], "the 500 one waits for the next round");
    assert.equal(offline.getFailedEntries().length, 0, "a 500 isn't a refusal");
    reset();
});

test("'too many requests' (429) stops the round and keeps everything", async () => {
    reset();
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(1) }, { data: entry(2) }, { data: entry(3) }]);
    const calls = respondWith(429, { error: "slow down" });
    await offline.syncOfflineEntries();
    assert.equal(calls.length, 1);
    assert.equal(queued().length, 3);
    reset();
});

test("an entry saved while an upload round is running goes up straight after it", async () => {
    reset();
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(1) }]);
    const sent = [];
    let release;
    const gate = new Promise((r) => { release = r; });
    globalThis.fetch = async (url, init) => {
        const team = JSON.parse(init.body).data.targetTeamNumber;
        sent.push(team);
        if (team === 1) await gate;
        return new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } });
    };
    const round = offline.syncOfflineEntries();
    await new Promise((r) => setTimeout(r, 20));
    await offline.enqueueOfflineEntries("match-scouting", [{ data: entry(2) }]);
    await offline.syncOfflineEntries(); // asked while the first round is busy
    release();
    await round;
    assert.deepEqual(sent, [1, 2]);
    assert.equal(queued().length, 0);
    reset();
});

test("a large entry gets time to upload on a slow link before it counts as stalled", async () => {
    reset();
    // The reply only starts once the body has gone up (~150 ms here).
    globalThis.fetch = (url, init) => new Promise((resolve, reject) => {
        const t = setTimeout(() => resolve(new Response("{}", { status: 200, headers: { "Content-Type": "application/json" } })), 150);
        init.signal.addEventListener("abort", () => { clearTimeout(t); reject(Object.assign(new Error("aborted"), { name: "AbortError" })); });
    });
    try {
        // 20 KB: about 0.8 s of allowance at the assumed slow-link speed, so a 50 ms idle limit doesn't cut it off.
        const big = { data: { photo: "x".repeat(20 * 1024) } };
        assert.deepEqual(await http.request("/api/pit-scouting", { method: "POST", json: big, timeoutMs: 50 }), {});
        // A tiny body gets no such allowance and is reported as stalled.
        await assert.rejects(http.request("/api/pit-scouting", { method: "POST", json: { data: {} }, timeoutMs: 50 }),
            (err) => http.isNetworkError(err));
    } finally {
        reset();
    }
});

// ── Drafts ───────────────────────────────────────────────────────────────────

function fakeForm() {
    const handlers = {};
    return {
        addEventListener: (t, f) => { (handlers[t] = handlers[t] || []).push(f); },
        removeEventListener: () => {},
        fire: (t) => (handlers[t] || []).forEach((f) => f({ target: {} }))
    };
}
const pagehide = () => (listeners.get("pagehide") || []).forEach((f) => f({ type: "pagehide" }));
const draftKey = (id) => `obsidianscout:draft:${id}`;

test("a form's starting values (a rating's default) are not saved as a draft", async () => {
    reset();
    const form = fakeForm();
    let data = { _targetTeamNumber: "254", _matchKey: "2026test_qm1", driverRating: "1", notes: "" };
    const stop = drafts.startDraftAutosave("t-defaults", form, () => data, 60000);
    try {
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(localStorage.getItem(draftKey("t-defaults")), null);

        data = { ...data, notes: "fast cycles" };
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        const saved = JSON.parse(localStorage.getItem(draftKey("t-defaults")));
        assert.equal(saved.data.notes, "fast cycles");

        // Undoing the change removes the draft again.
        data = { ...data, notes: "" };
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(localStorage.getItem(draftKey("t-defaults")), null);
    } finally {
        stop();
        reset();
    }
});

test("switching to another team or match starts over instead of saving the loaded entry", async () => {
    reset();
    const form = fakeForm();
    let data = { _targetTeamNumber: "254", notes: "" };
    const stop = drafts.startDraftAutosave("t-switch", form, () => data, 60000);
    try {
        // The form loads team 118's saved entry and says so, as every scouting form's handleSelectionChange does.
        data = { _targetTeamNumber: "118", notes: "already uploaded" };
        drafts.resetDraftBaseline("t-switch");
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(localStorage.getItem(draftKey("t-switch")), null);

        data = { _targetTeamNumber: "118", notes: "already uploaded, plus an edit" };
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        assert.ok(localStorage.getItem(draftKey("t-switch")));
    } finally {
        stop();
        reset();
    }
});

test("a team typed outside the form and an answer typed right after are both kept", async () => {
    reset();
    const form = fakeForm();
    let data = { _targetTeamNumber: "", notes: "" };
    const stop = drafts.startDraftAutosave("t-together", form, () => data, 60000);
    try {
        // No event for the team box (it's outside the form); the first save sees both changes at once.
        data = { _targetTeamNumber: "2056", notes: "watched on video" };
        pagehide();
        await new Promise((r) => setTimeout(r, 0));
        const saved = JSON.parse(localStorage.getItem(draftKey("t-together")));
        assert.equal(saved && saved.data.notes, "watched on video");
    } finally {
        stop();
        reset();
    }
});

test("drafts are saved shortly after a change, without waiting for the interval", async () => {
    reset();
    const form = fakeForm();
    let data = { _targetTeamNumber: "254", counter: "" };
    const stop = drafts.startDraftAutosave("t-soon", form, () => data, 60000);
    try {
        data = { ...data, counter: "3" };
        form.fire("click");
        await new Promise((r) => setTimeout(r, 900));
        assert.ok(localStorage.getItem(draftKey("t-soon")), "saved about 0.7 s after the tap");
    } finally {
        stop();
        reset();
    }
});

test("saving within the autosave delay doesn't leave a draft of what was just saved", async () => {
    reset();
    const form = fakeForm();
    let data = { _targetTeamNumber: "971", notes: "" };
    const stop = drafts.startDraftAutosave("t-race", form, () => data, 60000);
    try {
        data = { ...data, notes: "Tank drive" };
        form.fire("input");                 // autosave due in 0.7 s
        drafts.clearDraft("t-race");         // the scout pressed Save first; the form still shows the answers
        await new Promise((r) => setTimeout(r, 1000));
        assert.equal(localStorage.getItem(draftKey("t-race")), null);

        data = { ...data, notes: "Tank drive, then an edit" };
        form.fire("input");
        await new Promise((r) => setTimeout(r, 900));
        assert.ok(localStorage.getItem(draftKey("t-race")), "a later edit is still saved");
    } finally {
        stop();
        reset();
    }
});

test("hasMeaningfulDraftData ignores the team and match selection on its own", () => {
    assert.equal(drafts.hasMeaningfulDraftData({ _targetTeamNumber: "254", _matchKey: "qm1" }), false);
    assert.equal(drafts.hasMeaningfulDraftData({ _targetTeamNumber: "254", notes: "x" }), true);
    assert.equal(drafts.hasMeaningfulDraftData({ checkbox: false, count: 0 }), false);
});

// ── After-login redirect ─────────────────────────────────────────────────────

test("http: 401 on a non-form page still redirects for ordinary requests", async () => {
    reset();
    body.dataset.page = "dashboard";
    window.Obsidianscout.checkLoginStatus = async () => false;
    respondWith(401, { error: "no" });
    try {
        await assert.rejects(http.request("/api/summary"), (e) => e.status === 401);
        await new Promise((r) => setTimeout(r, 0));
        assert.equal(location.href, "/");
    } finally {
        delete window.Obsidianscout.checkLoginStatus;
        body.dataset.page = "scout";
        reset();
    }
});
