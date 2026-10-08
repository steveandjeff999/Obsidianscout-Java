/**
 * Node tests for live config editing in the browser (static/js/services/config-collab.js).
 * Run: node --test src/test/js/config-collab.test.mjs   (also part of `gradlew check` via jsTest)
 *
 * Replays the shared engine vectors, then drives ConfigCollabSession against a fake WebSocket and a
 * minimal in-process server that follows the same protocol as ConfigCollabHub.kt.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const vectors = JSON.parse(readFileSync(path.resolve(here, "../resources/config-collab-vectors.json"), "utf8"));

// ------------------------------------------------------------------ fake socket + server

const sockets = [];
class FakeSocket {
    constructor(url) {
        this.url = url;
        this.readyState = 0;
        this.sent = [];
        sockets.push(this);
    }
    send(text) { this.sent.push(JSON.parse(text)); if (this.server) this.server.receive(this, JSON.parse(text)); }
    close() { if (this.readyState === 3) return; this.readyState = 3; if (this.server) this.server.drop(this); this.onclose && this.onclose(); }
    deliver(msg) { this.onmessage && this.onmessage({ data: JSON.stringify(msg) }); }
}
globalThis.WebSocket = FakeSocket;
const { engine, ConfigCollabSession } = require(path.resolve(here, "../../main/resources/static/js/services/config-collab.js"));

class FakeServer {
    constructor(doc) {
        this.n = 0;
        this.epoch = "e1";
        this.state = engine.keyed(doc, () => `~${this.n++}`);
        this.version = 0;
        this.ack = new Map();
        this.clients = new Set();
    }
    accept(socket, { canEdit = true } = {}) {
        socket.server = this;
        socket.canEdit = canEdit;
        socket.readyState = 1;
        this.clients.add(socket);
        const sid = new URL(socket.url, "http://x").searchParams.get("sid");
        socket.sid = sid;
        socket.deliver({ type: "init", proto: 1, epoch: this.epoch, version: this.version, doc: this.state.doc, keys: this.state.keys, ackSeq: this.ack.get(sid) || 0, canEdit, sid, editors: [] });
    }
    receive(socket, msg) {
        if (msg.type !== "ops") return;
        if (!socket.canEdit) { socket.deliver({ type: "reject", seq: msg.seq, reason: "read only" }); return; }
        if (this.holdAcks) { (this.held ||= []).push([socket, msg]); return; }
        this.apply(socket.sid, msg.seq, msg.ops);
    }
    apply(sid, seq, ops) {
        this.state = engine.applyOps(this.state, ops);
        this.version += 1;
        this.ack.set(sid, seq);
        for (const c of this.clients) c.deliver({ type: "ops", version: this.version, sid, seq, user: sid, ops });
    }
    drop(socket) { this.clients.delete(socket); }
    restart(doc) {
        // A new room epoch: fresh keys, ack state lost (e.g. failover to another node).
        for (const c of [...this.clients]) c.close();
        this.epoch = `e${Math.random()}`;
        this.n = 1000;
        this.state = engine.keyed(doc ?? this.state.doc, () => `~${this.n++}`);
        this.ack = new Map();
    }
}

const openSessions = [];
const track = (session) => { openSessions.push(session); return session; };
test.afterEach(() => { while (openSessions.length) openSessions.pop().close(); });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const labels = (doc) => doc.fields.map((f) => f.label);
const field = (id, label = id.toUpperCase()) => ({ id, label, type: "counter" });

function editor(server, opts = {}) {
    const views = [];
    const statuses = [];
    const notices = [];
    const session = track(new ConfigCollabSession({
        url: "ws://test/api/config-collab/team/game",
        onView: (v, info) => views.push([v, info]),
        onStatus: (s) => statuses.push(s),
        onNotice: (level, m) => notices.push([level, m]),
        ...opts,
    }));
    const before = sockets.length;
    session.connect();
    const socket = sockets[before];
    if (server) server.accept(socket, opts);
    return { session, socket, views, statuses, notices };
}

// ------------------------------------------------------------------ vectors

test("apply vectors", () => {
    for (const c of vectors.apply) {
        const out = engine.applyOps(engine.clone(c.state), c.ops);
        assert.deepEqual(out, c.expect, c.name);
    }
});

test("diff vectors", () => {
    for (const c of vectors.diff) {
        let n = 0;
        const out = engine.diff(engine.clone(c.base), engine.clone(c.target), () => `n${n++}`);
        assert.deepEqual(out.ops, c.ops, c.name);
        assert.deepEqual(out.keys, c.keys, c.name);
    }
});

test("diff round-trips random edits", () => {
    let seed = 3;
    const rand = (n) => ((seed = (seed * 16807) % 2147483647) % n);
    for (let it = 0; it < 400; it++) {
        const fields = Array.from({ length: rand(7) }, (_, i) => ({ id: `f${i}`, label: `L${rand(3)}`, type: "counter", ...(rand(2) ? { min: rand(3) } : {}) }));
        let k = 0;
        const base = engine.keyed({ title: "T", fields }, () => `k${k++}`);
        const next = engine.clone(base.doc);
        for (let m = rand(5); m > 0; m--) {
            const r = rand(4);
            if (r === 0) next.fields.splice(rand(next.fields.length + 1), 0, field(`n${rand(50)}`));
            else if (r === 1 && next.fields.length) next.fields.splice(rand(next.fields.length), 1);
            else if (r === 2 && next.fields.length > 1) { const [v] = next.fields.splice(rand(next.fields.length), 1); next.fields.splice(rand(next.fields.length + 1), 0, v); }
            else if (next.fields.length) next.fields[rand(next.fields.length)].label = `E${rand(9)}`;
        }
        const out = engine.diff(base, next, () => `x${k++}`);
        const applied = engine.applyOps(base, out.ops);
        assert.deepEqual(applied.doc, next, `iteration ${it}`);
        assert.deepEqual(applied.keys, out.keys);
    }
});

// ------------------------------------------------------------------ session

test("two editors see each other's edits and both survive", async () => {
    const server = new FakeServer({ title: "T", fields: [field("a"), field("b")] });
    const alice = editor(server);
    const bob = editor(server);
    assert.equal(alice.session.status, "live");

    const aView = alice.session.lastView.doc;
    alice.session.commit({ ...aView, fields: [{ ...aView.fields[0], label: "Alpha" }, aView.fields[1]] });
    const bView = bob.session.lastView.doc;
    bob.session.commit({ ...bView, fields: [aView.fields[0], { ...bView.fields[1], label: "Bravo" }, field("c")] });
    await sleep(100);

    assert.deepEqual(labels(server.state.doc), ["Alpha", "Bravo", "C"]);
    assert.deepEqual(labels(alice.session.lastView.doc), ["Alpha", "Bravo", "C"]);
    assert.deepEqual(labels(bob.session.lastView.doc), ["Alpha", "Bravo", "C"]);
    assert.equal(alice.session.hasUnsavedChanges, false);
    assert.equal(bob.session.hasUnsavedChanges, false);
    assert.ok(alice.views.some(([, info]) => info.source === "remote"), "alice was shown bob's edit");
});

test("edits made while disconnected are resent after reconnecting to the same room", async () => {
    const server = new FakeServer({ title: "T", fields: [field("a")] });
    const ed = editor(server);
    ed.socket.close();
    assert.notEqual(ed.session.status, "live");

    const v = ed.session.lastView.doc;
    ed.session.commit({ ...v, title: "Offline title" });
    assert.equal(ed.session.lastView.doc.title, "Offline title");

    clearTimeout(ed.session._reconnectTimer);
    ed.session.connect();
    server.accept(sockets[sockets.length - 1]);
    await sleep(100);
    assert.equal(server.state.doc.title, "Offline title");
    assert.equal(ed.session.status, "live");
});

test("a batch the server already applied is not applied twice after a reconnect", async () => {
    const server = new FakeServer({ fields: [field("a")] });
    const ed = editor(server);
    server.holdAcks = true;
    const v = ed.session.lastView.doc;
    ed.session.commit({ ...v, fields: [...v.fields, field("b")] });
    await sleep(100);
    // The server applies it, but the connection drops before the echo arrives.
    const [[, msg]] = server.held;
    server.holdAcks = false;
    ed.socket.server = null;
    server.drop(ed.socket);
    server.apply(ed.socket.sid, msg.seq, msg.ops);
    ed.socket.close();

    clearTimeout(ed.session._reconnectTimer);
    ed.session.connect();
    server.accept(sockets[sockets.length - 1]);
    await sleep(100);
    assert.deepEqual(labels(server.state.doc), ["A", "B"]);
    assert.deepEqual(labels(ed.session.lastView.doc), ["A", "B"]);
});

test("unsaved edits are merged into a restarted room without duplicating fields", async () => {
    const server = new FakeServer({ fields: [field("a"), field("b")] });
    const ed = editor(server);
    const v = ed.session.lastView.doc;
    ed.socket.close();
    ed.session.commit({ ...v, fields: [v.fields[0], { ...v.fields[1], label: "Bee" }, field("c")] });

    // Meanwhile someone else saved over REST and the room restarted with new keys.
    server.restart({ fields: [field("a"), field("b"), field("z")] });
    clearTimeout(ed.session._reconnectTimer);
    ed.session.connect();
    server.accept(sockets[sockets.length - 1]);
    await sleep(100);
    assert.deepEqual(labels(server.state.doc), ["A", "Bee", "C", "Z"]);
    assert.deepEqual(labels(ed.session.lastView.doc), ["A", "Bee", "C", "Z"]);
});

test("first connect merges edits made while the page was still connecting", async () => {
    const server = new FakeServer({ fields: [{ id: "a", label: { en: "A" }, type: "counter" }] });
    const views = [];
    const session = track(new ConfigCollabSession({ url: "ws://test/x", onView: (v) => views.push(v) }));
    // Loaded over REST first (labels as stored), user types before the socket is up.
    const loaded = session.load({ fields: [{ id: "a", label: "A", type: "counter" }] });
    session.commit({ ...loaded.doc, title: "Typed early" });
    session.connect();
    server.accept(sockets[sockets.length - 1]);
    await sleep(100);
    assert.equal(server.state.doc.title, "Typed early");
    assert.equal(server.state.doc.fields.length, 1);
});

test("view-only editors cannot change the config", async () => {
    const server = new FakeServer({ title: "T", fields: [] });
    const ed = editor(server, { canEdit: false });
    assert.equal(ed.session.status, "readonly");
    ed.session.commit({ ...ed.session.lastView.doc, title: "Nope" });
    await sleep(100);
    assert.equal(server.state.doc.title, "T");
    assert.equal(ed.session.lastView.doc.title, "T");
    assert.ok(ed.notices.length > 0);
});

test("REST fallback merges with changes saved by someone else", async () => {
    const session = new ConfigCollabSession({ url: null });
    const v = session.load({ title: "T", fields: [field("a"), field("b")] }).doc;
    session.commit({ ...v, fields: [{ ...v.fields[0], label: "Alpha" }, v.fields[1]] });
    let saved = null;
    await session.saveViaRest(
        async () => ({ title: "Theirs", fields: [field("a"), field("b"), field("c")] }),
        async (doc) => { saved = doc; return { ok: true }; }
    );
    assert.equal(saved.title, "Theirs");
    assert.deepEqual(labels(saved), ["Alpha", "B", "C"]);
    assert.equal(session.hasUnsavedChanges, false);
});

test("projection hides fields and keys stay aligned with the view", () => {
    const session = new ConfigCollabSession({
        url: null,
        projectField: (f) => (f.id === "hidden" ? null : { id: f.id, label: f.label }),
        projectRest: (doc) => ({ title: doc.title || "Default" }),
    });
    const view = session.load({ secret: 1, fields: [field("a"), { id: "hidden", label: "H" }, field("b")] });
    assert.deepEqual(view.doc, { title: "Default", fields: [{ id: "a", label: "A" }, { id: "b", label: "B" }] });
    session.commit({ title: "Default", fields: [{ id: "b", label: "B" }, { id: "a", label: "A2" }] });
    // Unknown properties and hidden fields are left alone.
    assert.equal(session.local.doc.secret, 1);
    // The swap moves b to the front; the hidden field keeps its place relative to a.
    assert.deepEqual(session.local.doc.fields.map((f) => f.id), ["b", "a", "hidden"]);
    assert.equal(session.local.doc.fields.find((f) => f.id === "a").label, "A2");
    assert.equal(session.local.doc.fields.find((f) => f.id === "a").type, "counter");
});
