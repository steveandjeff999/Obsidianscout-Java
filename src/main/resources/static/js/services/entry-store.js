/**
 * Service Entry-Store Module - ObsidianScout
 * Keeps this device's copy of match, pit and qualitative entries (current event + all prescout) up to date with
 * `GET /api/sync/entries`, which sends only what changed since the last sync. On a slow or missing connection the
 * scouting list endpoints and the analytics endpoints are answered from this copy instead of the network.
 */

import { request, getActiveUserRole, canRoleCacheScouting, getActiveEventKey } from '../base/http.js';
import { isLowBandwidth } from '../base/net-quality.js';
import { PRIORITY } from '../base/request-scheduler.js';
import { safeGetItem } from '../base/storage.js';
import { buildAnalytics, buildComparison, buildDataset } from './local-analytics.js';

const DB_NAME = "obsidianscout_entries";
const DB_VERSION = 1;
const ENTRY_STORE = "entries";
const META_STORE = "meta";

export const KINDS = ["match", "pit", "qual"];
export const ENTRIES_UPDATED_EVENT = "obsidianscout:entries-updated";

/** Skip the delta request if this kind synced this recently and nothing was written since. */
const FRESH_MS = 20000;
/** Wait this long for a delta before answering from what's already stored; the sync finishes in the background. */
const MAX_WAIT_MS = 8000;

const LIST_ROUTES = {
    "/api/scouting": { kind: "match", prescoutOnly: false },
    "/api/pit-scouting": { kind: "pit", prescoutOnly: false },
    "/api/qual-scouting": { kind: "qual", prescoutOnly: false },
    "/api/prescout/scouting": { kind: "match", prescoutOnly: true },
    "/api/prescout/pit-scouting": { kind: "pit", prescoutOnly: true },
    "/api/prescout/qual-scouting": { kind: "qual", prescoutOnly: true }
};

const buckets = new Map();     // bucket -> Map(id -> entry)
const metas = new Map();       // bucket -> { b, cursor, total, syncedAt }
const inflight = new Map();    // bucket -> Promise
const dirtyKinds = new Set();
let dbPromise = null;

// ── IndexedDB ───────────────────────────────────────────────────────────────

function openDb() {
    if (dbPromise) return dbPromise;
    if (typeof indexedDB === 'undefined') return (dbPromise = Promise.resolve(null));
    dbPromise = new Promise((resolve) => {
        try {
            const req = indexedDB.open(DB_NAME, DB_VERSION);
            req.onupgradeneeded = () => {
                const db = req.result;
                if (!db.objectStoreNames.contains(ENTRY_STORE)) {
                    db.createObjectStore(ENTRY_STORE, { keyPath: "k" }).createIndex("b", "b", { unique: false });
                }
                if (!db.objectStoreNames.contains(META_STORE)) {
                    db.createObjectStore(META_STORE, { keyPath: "b" });
                }
            };
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => resolve(null);
            req.onblocked = () => resolve(null);
        } catch (e) {
            resolve(null);
        }
    });
    return dbPromise;
}

function txDone(tx) {
    return new Promise((resolve) => {
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
    });
}

async function loadBucket(bucket) {
    if (buckets.has(bucket)) return buckets.get(bucket);
    const map = new Map();
    const db = await openDb();
    if (db) {
        try {
            const tx = db.transaction([ENTRY_STORE, META_STORE], "readonly");
            const rows = await new Promise((resolve) => {
                const req = tx.objectStore(ENTRY_STORE).index("b").getAll(bucket);
                req.onsuccess = () => resolve(req.result || []);
                req.onerror = () => resolve([]);
            });
            const meta = await new Promise((resolve) => {
                const req = tx.objectStore(META_STORE).get(bucket);
                req.onsuccess = () => resolve(req.result || null);
                req.onerror = () => resolve(null);
            });
            for (const row of rows) map.set(row.id, row.entry);
            if (meta) metas.set(bucket, meta);
        } catch (e) {
            console.warn("[Entry Store] Failed to load", bucket, e);
        }
    }
    // Another caller may have loaded it while this one waited.
    if (buckets.has(bucket)) return buckets.get(bucket);
    buckets.set(bucket, map);
    return map;
}

async function persist(bucket, { full, upserts, deletes, meta }) {
    const db = await openDb();
    if (!db) return;
    try {
        const tx = db.transaction([ENTRY_STORE, META_STORE], "readwrite");
        const store = tx.objectStore(ENTRY_STORE);
        if (full) {
            await new Promise((resolve) => {
                const req = store.index("b").openKeyCursor(IDBKeyRange.only(bucket));
                req.onsuccess = () => {
                    const cursor = req.result;
                    if (!cursor) return resolve();
                    store.delete(cursor.primaryKey);
                    cursor.continue();
                };
                req.onerror = () => resolve();
            });
        }
        for (const id of deletes) store.delete(`${bucket}|${id}`);
        for (const entry of upserts) store.put({ k: `${bucket}|${entry.id}`, b: bucket, id: entry.id, entry });
        tx.objectStore(META_STORE).put(meta);
        await txDone(tx);
    } catch (e) {
        console.warn("[Entry Store] Failed to save", bucket, e);
    }
}

// ── Scope ───────────────────────────────────────────────────────────────────

function currentUser() {
    try {
        const parsed = JSON.parse(safeGetItem("cache:/api/auth/me") || "null");
        return parsed ? (parsed.user || parsed) : null;
    } catch (e) {
        return null;
    }
}

function ownTeamNumber() {
    const user = currentUser();
    return user && user.teamNumber !== undefined ? Number(user.teamNumber) : null;
}

/** One stored copy per signed-in team, program, kind and event. */
function bucketFor(kind) {
    const user = currentUser() || {};
    return `${user.teamNumber || 0}|${user.program || "FRC"}|${kind}|${getActiveEventKey() || "*"}`;
}

function storeAllowed() {
    return canRoleCacheScouting(getActiveUserRole());
}

function serverReachable() {
    if (typeof navigator !== 'undefined' && navigator.onLine === false) return false;
    if (typeof window !== 'undefined' && window.Obsidianscout && typeof window.Obsidianscout.isServerOnline === 'function') {
        return window.Obsidianscout.isServerOnline() !== false;
    }
    return true;
}

// ── Sync ────────────────────────────────────────────────────────────────────

/** Expands the compact form (`cols`/`keys`/`rows`, see EntrySync.pack on the server) back into entry objects. */
export function unpackCompact(cols, keys, rows) {
    return (rows || []).map((row) => {
        const out = {};
        cols.forEach((c, i) => { out[c] = row[i] === undefined ? null : row[i]; });
        const values = Array.isArray(row[cols.length]) ? row[cols.length] : [];
        const nulls = new Set(Array.isArray(row[cols.length + 1]) ? row[cols.length + 1] : []);
        const data = {};
        keys.forEach((k, i) => {
            const v = values[i];
            if (v !== undefined && v !== null) data[k] = v;
            else if (nulls.has(i)) data[k] = null;
        });
        out.data = data;
        return out;
    });
}

async function runSync(kind, bucket, force) {
    const map = await loadBucket(bucket);
    const meta = metas.get(bucket);
    const eventKey = getActiveEventKey();
    let url = `/api/sync/entries?kind=${kind}&format=compact`;
    if (eventKey) url += `&eventKey=${encodeURIComponent(eventKey)}`;
    if (!force && meta && meta.cursor && map.size > 0) url += `&cursor=${encodeURIComponent(meta.cursor)}`;

    const res = await request(url, { skipEntryStore: true, noCache: true, priority: PRIORITY.HIGH, timeoutMs: 30000 });
    if (!res || typeof res !== 'object' || !res.cursor) throw new Error("Unexpected sync response");

    const changed = Array.isArray(res.entries) ? res.entries : unpackCompact(res.cols || [], res.keys || [], res.rows || []);
    const deleted = Array.isArray(res.deleted) ? res.deleted : [];
    if (res.full) map.clear();
    for (const id of deleted) map.delete(id);
    for (const entry of changed) if (entry && entry.id) map.set(entry.id, entry);

    // The merged copy should hold exactly what the server has; if not, something was missed. Start over once.
    if (!res.full && typeof res.total === 'number' && map.size !== res.total) {
        console.warn(`[Entry Store] ${kind}: have ${map.size}, server has ${res.total}. Re-downloading.`);
        return runSync(kind, bucket, true);
    }

    const nextMeta = { b: bucket, cursor: res.cursor, total: res.total, syncedAt: Date.now() };
    metas.set(bucket, nextMeta);
    dirtyKinds.delete(kind);
    await persist(bucket, { full: !!res.full, upserts: changed, deletes: deleted, meta: nextMeta });

    if ((changed.length || deleted.length || res.full) && typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(ENTRIES_UPDATED_EVENT, { detail: { kind, changed: changed.length, deleted: deleted.length, full: !!res.full } }));
    }
    return { changed: changed.length, deleted: deleted.length, full: !!res.full, total: map.size };
}

/** Brings one kind up to date. Concurrent callers share one request. */
export function syncKind(kind, { force = false } = {}) {
    if (!storeAllowed()) return Promise.resolve(null);
    const bucket = bucketFor(kind);
    if (inflight.has(bucket)) return inflight.get(bucket);
    const p = runSync(kind, bucket, force).finally(() => inflight.delete(bucket));
    inflight.set(bucket, p);
    return p;
}

/** Brings every kind up to date, one after another (one small request each when little changed). */
export async function syncAllKinds({ force = false } = {}) {
    const results = {};
    for (const kind of KINDS) {
        try {
            results[kind] = await syncKind(kind, { force });
        } catch (e) {
            results[kind] = { error: e && e.message ? e.message : String(e) };
        }
    }
    return results;
}

async function ensureFresh(kind) {
    const bucket = bucketFor(kind);
    const map = await loadBucket(bucket);
    const meta = metas.get(bucket);
    const fresh = meta && Date.now() - meta.syncedAt < FRESH_MS && !dirtyKinds.has(kind);
    if (fresh || !serverReachable()) return map.size > 0 || !!meta;

    const sync = syncKind(kind).catch((e) => {
        console.warn(`[Entry Store] ${kind} sync failed:`, e && e.message ? e.message : e);
        return null;
    });
    if (map.size === 0 && !meta) {
        // Nothing stored yet: only a completed download can answer.
        await sync;
    } else {
        await Promise.race([sync, new Promise((r) => setTimeout(r, MAX_WAIT_MS))]);
    }
    return (buckets.get(bucket) || map).size > 0 || metas.has(bucket);
}

export async function getEntries(kind) {
    const map = await loadBucket(bucketFor(kind));
    return newestFirst(Array.from(map.values()));
}

export function getSyncInfo(kind) {
    const meta = metas.get(bucketFor(kind));
    return meta ? { syncedAt: meta.syncedAt, total: meta.total } : null;
}

/** Called after any write so the next read fetches the change instead of trusting the 20 s freshness window. */
export function noteEntryWrite(basePath) {
    const clean = String(basePath || "");
    if (clean.includes("/pit-scouting")) dirtyKinds.add("pit");
    else if (clean.includes("/qual-scouting")) dirtyKinds.add("qual");
    else if (clean.includes("/scouting")) dirtyKinds.add("match");
}

export async function clearEntryStore() {
    buckets.clear();
    metas.clear();
    const db = await openDb();
    if (!db) return;
    try {
        const tx = db.transaction([ENTRY_STORE, META_STORE], "readwrite");
        tx.objectStore(ENTRY_STORE).clear();
        tx.objectStore(META_STORE).clear();
        await txDone(tx);
    } catch (e) {}
}

// ── Answering requests ──────────────────────────────────────────────────────

function ts(value) {
    const t = Date.parse(value || "");
    return Number.isNaN(t) ? 0 : t;
}

function newestFirst(list) {
    return list.slice().sort((a, b) => ts(b.createdAt) - ts(a.createdAt));
}

function parseBool(value) {
    return String(value || "").toLowerCase() === "true";
}

/** Same rule as the server's resolveEntriesList: one entry per match+team (event+team for pit), own team's first. */
export function resolveEntries(list, kind, ownTeam) {
    const groups = new Map();
    for (const e of list) {
        const key = kind === "pit"
            ? `${e.eventKey || ""}|${e.targetTeamNumber || 0}`
            : `${e.matchKey || ""}|${e.targetTeamNumber || 0}`;
        if (!groups.has(key)) groups.set(key, []);
        groups.get(key).push(e);
    }
    return Array.from(groups.values()).map((g) =>
        g.length <= 1 ? g[0] : (g.find((e) => Number(e.ownerTeamNumber) === ownTeam) || g[0])
    );
}

/** The list a scouting GET would return, built from stored entries. */
export function deriveList(entries, route, params, ownTeam) {
    let list = entries;
    if (route.prescoutOnly) {
        list = list.filter((e) => e.isPrescout);
    } else if (!parseBool(params.get("includePrescout"))) {
        list = list.filter((e) => !e.isPrescout);
    }
    const eventKey = (params.get("eventKey") || "").trim();
    if (eventKey) list = list.filter((e) => e.eventKey === eventKey);
    return parseBool(params.get("all")) ? list : resolveEntries(list, route.kind, ownTeam);
}

function cachedJson(path) {
    try {
        const text = safeGetItem("cache:" + path);
        if (text) return JSON.parse(text);
    } catch (e) {}
    return null;
}

async function cachedResponse(path) {
    const { getHttpCache } = await import('../base/idb-cache.js');
    const record = await getHttpCache(path);
    if (record && record.text) {
        try { return JSON.parse(record.text); } catch (e) {}
    }
    return cachedJson(path);
}

function currentSettings() {
    const parsed = cachedJson("/api/settings");
    return parsed ? (parsed.settings || parsed) : {};
}

/** Whether reads should come from the stored copy right now. */
export function shouldServeLocally() {
    if (!storeAllowed()) return false;
    return isLowBandwidth() || !serverReachable();
}

/**
 * Returns the response for `path` from stored entries, or `undefined` when the network should be used instead
 * (good connection, path not handled, nothing stored yet, or a different event than the stored one).
 */
export async function serveFromEntryStore(path) {
    const [clean, query = ""] = String(path || "").split("?");
    const params = new URLSearchParams(query);
    const route = LIST_ROUTES[clean];
    const isAnalytics = clean === "/api/analytics" || clean === "/api/analytics/compare" || clean === "/api/custom-analytics/dataset";
    if (!route && !isAnalytics) return undefined;
    if (!shouldServeLocally()) return undefined;

    const storedEvent = getActiveEventKey();
    const askedEvent = (params.get("eventKey") || "").trim().toLowerCase();
    if (askedEvent && storedEvent && askedEvent !== storedEvent) return undefined;

    if (route) {
        if (!(await ensureFresh(route.kind))) return undefined;
        return deriveList(await getEntries(route.kind), route, params, ownTeamNumber());
    }

    if (!(await ensureFresh("match"))) return undefined;
    const matchEntries = await getEntries("match");
    const config = await cachedResponse("/api/config");
    if (!config || !Array.isArray(config.fields)) return undefined;
    const settings = currentSettings();
    const ownTeam = ownTeamNumber();

    const matchRoute = { kind: "match", prescoutOnly: false };
    if (clean === "/api/analytics") {
        return buildAnalytics({
            config,
            regular: deriveList(matchEntries, matchRoute, new URLSearchParams(), ownTeam),
            prescout: deriveList(matchEntries, matchRoute, new URLSearchParams("includePrescout=true"), ownTeam).filter((e) => e.isPrescout),
            currentEventKey: storedEvent,
            forcePrescout: parseBool(params.get("usePrescout"))
        });
    }

    const teams = storedEvent ? ((await cachedResponse(`/api/teams?eventKey=${storedEvent}`)) || []) : [];
    if (clean === "/api/analytics/compare") {
        const teamsParam = params.get("teams") || [params.get("team1"), params.get("team2"), params.get("team3")].filter(Boolean).join(",");
        const teamNumbers = Array.from(new Set(teamsParam.split(",").map((t) => parseInt(t.trim(), 10)).filter((n) => !Number.isNaN(n))));
        if (!teamNumbers.length) return undefined;
        return buildComparison({
            config,
            entries: matchEntries,
            teams: Array.isArray(teams) ? teams : [],
            teamNumbers,
            eventKey: params.get("eventKey"),
            recencyBias: settings.recencyBias
        });
    }

    await Promise.all([ensureFresh("pit"), ensureFresh("qual")]);
    const includePrescout = params.has("includePrescout") ? parseBool(params.get("includePrescout")) : true;
    const listParams = new URLSearchParams(includePrescout ? "includePrescout=true" : "");
    const pitConfig = (await cachedResponse("/api/pit-config")) || { fields: [] };
    const qualConfig = (await cachedResponse("/api/qual-config")) || { fields: [] };
    return buildDataset({
        matchConfig: config,
        pitConfig,
        qualConfig,
        matchEntries: deriveList(matchEntries, matchRoute, listParams, ownTeam),
        pitEntries: deriveList(await getEntries("pit"), { kind: "pit", prescoutOnly: false }, listParams, ownTeam),
        qualEntries: deriveList(await getEntries("qual"), { kind: "qual", prescoutOnly: false }, listParams, ownTeam),
        teams: Array.isArray(teams) ? teams : [],
        eventKeyFilter: params.get("eventKey"),
        program: (currentUser() || {}).program || settings.program || "FRC"
    });
}
