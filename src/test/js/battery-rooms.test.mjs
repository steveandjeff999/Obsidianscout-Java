/**
 * Node tests for live battery editing: two devices share the battery inventory and an event plan through
 * js/services/collab-rooms.js (the room plumbing the Battery Tracker and Match Checklist pages use), against a
 * minimal in-process server that follows the ConfigCollabHub protocol.
 * Run: node --test src/test/js/battery-rooms.test.mjs   (also part of `gradlew check` via jsTest)
 */
import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ------------------------------------------------------------------ fake network

const servers = new Map(); // room path ("/api/battery-tracker/collab?inventory=1") -> FakeServer
let networkUp = true;

class FakeSocket {
    constructor(url) {
        this.url = url;
        this.readyState = 0;
        const u = new URL(url, "http://x");
        this.sid = u.searchParams.get("sid");
        u.searchParams.delete("sid");
        this.room = `${u.pathname}${u.search}`;
        setTimeout(() => {
            const server = servers.get(this.room);
            if (networkUp && server && this.readyState === 0) server.accept(this);
            else this.close();
        }, 0);
    }
    send(text) { if (this.server) this.server.receive(this, JSON.parse(text)); }
    close() {
        if (this.readyState === 3) return;
        this.readyState = 3;
        if (this.server) this.server.drop(this);
        this.onclose && this.onclose();
    }
    deliver(msg) { this.onmessage && this.onmessage({ data: JSON.stringify(msg) }); }
}
globalThis.WebSocket = FakeSocket;
Object.defineProperty(globalThis, "navigator", { value: { onLine: true }, configurable: true });

const { engine } = require(path.resolve(here, "../../main/resources/static/js/services/config-collab.js"));
require(path.resolve(here, "../../main/resources/static/js/services/collab-rooms.js"));
require(path.resolve(here, "../../main/resources/static/js/services/battery-rotation.js"));
const { ObsidianscoutCollabRooms, ObsidianBattery: battery } = globalThis;

class FakeServer {
    constructor(doc = {}) {
        this.n = 0;
        this.state = engine.keyed(doc, () => `~${this.n++}`);
        this.version = 0;
        this.ack = new Map();
        this.clients = new Set();
    }
    accept(socket) {
        socket.server = this;
        socket.readyState = 1;
        this.clients.add(socket);
        socket.deliver({ type: "init", proto: 1, epoch: "e1", version: this.version, doc: this.state.doc, keys: this.state.keys, ackSeq: this.ack.get(socket.sid) || 0, canEdit: true, sid: socket.sid, editors: [] });
    }
    receive(socket, msg) {
        if (msg.type !== "ops") return;
        this.state = engine.applyOps(this.state, msg.ops);
        this.version += 1;
        this.ack.set(socket.sid, msg.seq);
        for (const c of this.clients) c.deliver({ type: "ops", version: this.version, sid: socket.sid, seq: msg.seq, user: socket.sid, ops: msg.ops });
    }
    drop(socket) { this.clients.delete(socket); }
    get doc() { return this.state.doc; }
}

// ------------------------------------------------------------------ a device (one page's room manager)

const INVENTORY_URL = "/api/battery-tracker/collab?inventory=1";
const PLAN_URL = "/api/battery-tracker/collab?eventKey=2026test";

function device(name, storage = new Map()) {
    globalThis.Obsidianscout = {
        safeGetItem: (k) => storage.get(k) ?? null,
        safeSetItem: (k, v) => storage.set(k, v),
        safeRemoveItem: (k) => storage.delete(k),
        showToast: () => {},
        // REST: reads return the server copy, which is how a page loads a doc before connecting.
        request: async (url, opts) => {
            if (!networkUp) throw new Error("offline");
            const room = url.includes("inventory") ? INVENTORY_URL : PLAN_URL;
            const server = servers.get(room);
            if (opts?.method === "PUT") {
                server.state = engine.keyed(JSON.parse(opts.json.dataJson), () => `~${server.n++}`);
                return {};
            }
            return { dataJson: JSON.stringify(server.doc) };
        }
    };
    const changes = [];
    const rooms = ObsidianscoutCollabRooms.create(Object.assign(battery.roomConfig({ teamNumber: 254, program: "FRC", username: name }), {
        onChange: (room) => changes.push(room.id)
    }));
    return { name, rooms, storage, changes };
}

const open = [];
test.afterEach(() => {
    while (open.length) open.pop().close();
    networkUp = true;
});
function track(room) { open.push(room.session); return room; }

const schedule = (inv, plan, keys) => {
    const s = battery.computeSchedule(inv.session.lastView.doc, plan.session.lastView.doc, keys);
    return keys.map((k) => s[k].batteryId);
};

test("a skip on one device changes the schedule on the other, live", async () => {
    servers.set(INVENTORY_URL, new FakeServer({}));
    servers.set(PLAN_URL, new FakeServer({}));
    const a = device("alex");
    const aInv = track(await a.rooms.acquire("", ""));
    const aPlan = track(await a.rooms.acquire("2026test", ""));
    const b = device("sam");
    const bInv = track(await b.rooms.acquire("", ""));
    const bPlan = track(await b.rooms.acquire("2026test", ""));
    await sleep(20);
    assert.equal(aInv.session.status, "live");

    a.rooms.edit(aInv, (doc) => {
        doc.batteries = {
            b1: { name: "B1", type: "match", order: 0 },
            b2: { name: "B2", type: "match", order: 1 },
            p1: { name: "Pit", type: "pit", order: 2 },
            b3: { name: "B3", type: "match", order: 3 }
        };
    });
    await sleep(150);
    const keys = ["q1", "q2", "q3"];
    assert.deepEqual(schedule(bInv, bPlan, keys), ["b1", "b2", "b3"], "sam sees alex's batteries; the pit battery isn't scheduled");

    b.rooms.edit(bPlan, (doc) => battery.skip(doc, "q1", "b1", "sam"));
    await sleep(150);
    assert.deepEqual(schedule(aInv, aPlan, keys), ["b2", "b3", "b1"], "alex sees sam's skip and the next battery");
    assert.ok(a.changes.includes("event|2026test"), "alex's page was told to re-render");
    assert.equal(servers.get(PLAN_URL).doc.matches.q1.skipped.b1.by, "sam");
});

test("simultaneous edits to different batteries and matches both survive", async () => {
    servers.set(INVENTORY_URL, new FakeServer({ batteries: { b1: { name: "B1", order: 0 }, b2: { name: "B2", order: 1 } } }));
    servers.set(PLAN_URL, new FakeServer({}));
    const a = device("alex");
    const aInv = track(await a.rooms.acquire("", ""));
    const aPlan = track(await a.rooms.acquire("2026test", ""));
    const b = device("sam");
    const bInv = track(await b.rooms.acquire("", ""));
    const bPlan = track(await b.rooms.acquire("2026test", ""));
    await sleep(20);

    a.rooms.edit(aInv, (doc) => { doc.batteries.b1.notes = "low CCA"; });
    b.rooms.edit(bInv, (doc) => { doc.batteries.b2.type = "pit"; doc.batteries.b9 = { name: "B9", order: 2 }; });
    a.rooms.edit(aPlan, (doc) => battery.markUsed(doc, "q1", "b1"));
    b.rooms.edit(bPlan, (doc) => battery.skip(doc, "q2", "b9", "sam"));
    await sleep(150);

    for (const inv of [aInv, bInv]) {
        const bat = inv.session.lastView.doc.batteries;
        assert.equal(bat.b1.notes, "low CCA");
        assert.equal(bat.b2.type, "pit");
        assert.equal(bat.b9.name, "B9");
    }
    assert.deepEqual(schedule(aInv, aPlan, ["q1", "q2"]), ["b1", "b1"]);
    assert.deepEqual(schedule(bInv, bPlan, ["q1", "q2"]), ["b1", "b1"]);
});

test("edits made offline are kept on the device and saved after a reload once back online", async () => {
    servers.set(INVENTORY_URL, new FakeServer({ batteries: { b1: { name: "B1", order: 0 }, b2: { name: "B2", order: 1 } } }));
    servers.set(PLAN_URL, new FakeServer({}));
    const storage = new Map();
    const a = device("alex", storage);
    const aPlan = track(await a.rooms.acquire("2026test", ""));
    await sleep(20);

    networkUp = false;
    for (const c of [...servers.get(PLAN_URL).clients]) c.close();
    await sleep(20);
    a.rooms.edit(aPlan, (doc) => battery.skip(doc, "q1", "b1", "alex"));
    assert.equal(a.rooms.pendingOffline().length, 1);
    assert.ok([...storage.keys()].some((k) => k.startsWith("obsidianscout:battery-drafts:254:FRC:")), "draft kept on this device");
    assert.equal(servers.get(PLAN_URL).doc.matches, undefined);

    // The page is closed while offline; a fresh page opens later with the network back.
    aPlan.session.close();
    open.length = 0;
    networkUp = true;
    const again = device("alex", storage);
    await again.rooms.resumeDrafts();
    await sleep(150);
    assert.ok(servers.get(PLAN_URL).doc.matches.q1.skipped.b1, "the offline skip reached the server");
    again.rooms.tick();
    assert.equal(again.rooms.hasUnsaved(), false);
    assert.equal([...storage.keys()].length, 0, "draft cleared once saved");
});
