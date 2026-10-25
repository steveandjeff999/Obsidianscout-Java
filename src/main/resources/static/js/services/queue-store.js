/**
 * Service Queue-Store Module - ObsidianScout
 * Durable on-device storage for entries waiting to upload, entries the server refused, and form drafts.
 *
 * Everything lives in IndexedDB, which has no 5 MB cap (photos fit) and is shared with the service worker. Pages read
 * from an in-memory copy, so reads stay synchronous; writes resolve only once IndexedDB has committed them, so a page
 * never reports a save that didn't happen. When IndexedDB can't be opened (some private windows), the same API stores
 * into localStorage under the keys older versions used.
 *
 * Older versions kept the queue in localStorage (`osq:<queue key>:<clientId>`, `osf:<id>`, one JSON array per queue
 * key, and `obsidianscout:draft:<form>`). Those are moved here on load and whenever another tab still running an
 * older page writes one.
 */

const DB_NAME = "obsidianscout_queue_db";
const DB_VERSION = 1;
const PENDING = "pending";
const FAILED = "failed";
const DRAFTS = "drafts";
const OPEN_TIMEOUT_MS = 4000;

const ITEM_PREFIX = "osq:";
const FAILED_PREFIX = "osf:";
const DRAFT_PREFIX = "obsidianscout:draft:";
const LEGACY_FAILED_KEY = "failed_offline_entries";
const LEGACY_QUEUE_KEYS = [
    "pending_scouting_entries",
    "pending_pit_scouting_entries",
    "pending_qualitative_entries",
    "pending_prescout_scouting_entries",
    "pending_prescout_pit_scouting_entries",
    "pending_prescout_qualitative_entries"
];

/** Fired on window whenever the stored entries change, in this tab or another one. */
export const STORE_CHANGED_EVENT = "obsidianscout:queue-store-changed";

const pending = new Map();   // `${queueKey}:${clientId}` -> { id, queueKey, item }
const failed = new Map();    // failedId -> entry
const drafts = new Map();    // formId -> { formId, data, savedAt }

let db = null;
let mode = "memory";         // "indexeddb" | "localstorage" | "memory"; set for good by queueReady()
let readyPromise = null;
let channel = null;
let persistRequested = false;

// ── localStorage helpers (fallback mode and legacy migration) ───────────────

function lsGet(key) {
    try { return localStorage.getItem(key); } catch (e) { return null; }
}

function lsSet(key, value) {
    try {
        localStorage.setItem(key, value);
        return true;
    } catch (e) {
        // Out of room: cached API responses are disposable, queued entries are not.
        try {
            for (let i = localStorage.length - 1; i >= 0; i--) {
                const k = localStorage.key(i);
                if (k && (k.startsWith("cache:") || k.startsWith("etag:")) && k !== "cache:/api/auth/me" && k !== "cache:/api/settings") {
                    localStorage.removeItem(k);
                }
            }
            localStorage.setItem(key, value);
            return true;
        } catch (retryErr) {
            return false;
        }
    }
}

function lsRemove(key) {
    try { localStorage.removeItem(key); } catch (e) { /* ignore */ }
}

function lsKeys() {
    const out = [];
    try {
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k) out.push(k);
        }
    } catch (e) { /* ignore */ }
    return out;
}

function parseJson(text, fallback) {
    if (text === null || text === undefined) return fallback;
    try {
        const parsed = JSON.parse(text);
        return parsed === undefined ? fallback : parsed;
    } catch (e) {
        return fallback;
    }
}

function hasLocalStorage() {
    try { return typeof localStorage !== "undefined" && localStorage !== null; } catch (e) { return false; }
}

// Without IndexedDB at all the fallback is known up front, so reads work before queueReady() resolves.
if (typeof indexedDB === "undefined" || !indexedDB) mode = hasLocalStorage() ? "localstorage" : "memory";

// ── IndexedDB plumbing ──────────────────────────────────────────────────────

function openDb() {
    if (typeof indexedDB === "undefined" || !indexedDB) return Promise.resolve(null);
    return new Promise((resolve) => {
        let settled = false;
        const finish = (value) => {
            if (settled) return;
            settled = true;
            resolve(value);
        };
        // A blocked or hung open must not leave a scout unable to save; fall back to localStorage instead.
        const timer = setTimeout(() => finish(null), OPEN_TIMEOUT_MS);
        try {
            const req = indexedDB.open(DB_NAME, DB_VERSION);
            req.onupgradeneeded = () => {
                const upgradeDb = req.result;
                if (!upgradeDb.objectStoreNames.contains(PENDING)) upgradeDb.createObjectStore(PENDING, { keyPath: "id" });
                if (!upgradeDb.objectStoreNames.contains(FAILED)) upgradeDb.createObjectStore(FAILED, { keyPath: "failedId" });
                if (!upgradeDb.objectStoreNames.contains(DRAFTS)) upgradeDb.createObjectStore(DRAFTS, { keyPath: "formId" });
            };
            req.onsuccess = () => {
                clearTimeout(timer);
                const opened = req.result;
                if (settled) { try { opened.close(); } catch (e) { /* ignore */ } return; }
                // Another tab upgrading the schema: let it, and reopen on next use.
                opened.onversionchange = () => { try { opened.close(); } catch (e) { /* ignore */ } db = null; };
                finish(opened);
            };
            req.onerror = () => { clearTimeout(timer); finish(null); };
            req.onblocked = () => { /* keep waiting until the timeout */ };
        } catch (e) {
            clearTimeout(timer);
            finish(null);
        }
    });
}

/** Runs `fn(stores)` in one readwrite transaction; resolves true once it commits, false if it fails. */
function writeTx(storeNames, fn) {
    return new Promise((resolve) => {
        if (!db) { resolve(false); return; }
        let tx;
        try {
            tx = db.transaction(storeNames, "readwrite");
        } catch (e) {
            resolve(false);
            return;
        }
        tx.oncomplete = () => resolve(true);
        tx.onerror = () => resolve(false);
        tx.onabort = () => resolve(false);
        try {
            const stores = {};
            storeNames.forEach((name) => { stores[name] = tx.objectStore(name); });
            fn(stores);
        } catch (e) {
            try { tx.abort(); } catch (_) { /* ignore */ }
            resolve(false);
        }
    });
}

function readAll(storeName) {
    return new Promise((resolve) => {
        if (!db) { resolve([]); return; }
        try {
            const req = db.transaction(storeName, "readonly").objectStore(storeName).getAll();
            req.onsuccess = () => resolve(req.result || []);
            req.onerror = () => resolve([]);
        } catch (e) {
            resolve([]);
        }
    });
}

function readOne(storeName, key) {
    return new Promise((resolve) => {
        if (!db) { resolve(undefined); return; }
        try {
            const req = db.transaction(storeName, "readonly").objectStore(storeName).get(key);
            req.onsuccess = () => resolve(req.result);
            req.onerror = () => resolve(undefined);
        } catch (e) {
            resolve(undefined);
        }
    });
}

// ── Loading, migration and cross-tab updates ────────────────────────────────

function pendingId(queueKey, clientId) {
    return `${queueKey}:${clientId}`;
}

function newId() {
    if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") return crypto.randomUUID();
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

async function loadMirror() {
    const [p, f, d] = await Promise.all([readAll(PENDING), readAll(FAILED), readAll(DRAFTS)]);
    pending.clear();
    p.forEach((rec) => { if (rec && rec.id && rec.item) pending.set(rec.id, rec); });
    failed.clear();
    f.forEach((rec) => { if (rec && rec.failedId) failed.set(rec.failedId, rec); });
    drafts.clear();
    d.forEach((rec) => { if (rec && rec.formId) drafts.set(rec.formId, rec); });
}

/** Everything older pages left in localStorage, without removing it yet. */
function collectLegacy() {
    const out = { pending: [], failed: [], drafts: [], arrays: {}, keys: [] };
    for (const k of lsKeys()) {
        if (k.startsWith(ITEM_PREFIX)) {
            const rest = k.slice(ITEM_PREFIX.length);
            const sep = rest.lastIndexOf(":");
            const item = parseJson(lsGet(k), null);
            if (sep > 0 && item && typeof item === "object") {
                const queueKey = rest.slice(0, sep);
                const clientId = item.clientId || rest.slice(sep + 1);
                out.pending.push({ id: pendingId(queueKey, clientId), queueKey, item: { ...item, clientId } });
            }
            out.keys.push(k);
        } else if (k.startsWith(FAILED_PREFIX)) {
            const entry = parseJson(lsGet(k), null);
            if (entry && typeof entry === "object") {
                const failedId = entry.failedId || (entry.item && entry.item.clientId) || k.slice(FAILED_PREFIX.length);
                out.failed.push({ ...entry, failedId });
            }
            out.keys.push(k);
        } else if (k.startsWith(DRAFT_PREFIX)) {
            const rec = parseJson(lsGet(k), null);
            if (rec && typeof rec === "object" && rec.data) {
                out.drafts.push({ formId: k.slice(DRAFT_PREFIX.length), data: rec.data, savedAt: rec.savedAt || Date.now() });
            }
            out.keys.push(k);
        }
    }
    for (const queueKey of LEGACY_QUEUE_KEYS) {
        const arr = parseJson(lsGet(queueKey), null);
        if (!Array.isArray(arr) || !arr.length) continue;
        out.arrays[queueKey] = arr.length;
        arr.forEach((item) => {
            if (!item || typeof item !== "object") return;
            const clientId = item.clientId || newId();
            out.pending.push({ id: pendingId(queueKey, clientId), queueKey, item: { ...item, clientId } });
        });
    }
    const legacyFailed = parseJson(lsGet(LEGACY_FAILED_KEY), null);
    if (Array.isArray(legacyFailed) && legacyFailed.length) {
        out.arrays[LEGACY_FAILED_KEY] = legacyFailed.length;
        legacyFailed.forEach((entry) => {
            if (!entry || typeof entry !== "object") return;
            const failedId = entry.failedId || (entry.item && entry.item.clientId) || newId();
            out.failed.push({ ...entry, failedId });
        });
    }
    return out;
}

/** Moves localStorage entries into IndexedDB; they are removed from localStorage only after the copy commits. */
async function migrateLegacy() {
    if (mode !== "indexeddb" || !hasLocalStorage()) return false;
    const legacy = collectLegacy();
    if (!legacy.pending.length && !legacy.failed.length && !legacy.drafts.length && !Object.keys(legacy.arrays).length) return false;

    const ok = await writeTx([PENDING, FAILED, DRAFTS], (s) => {
        legacy.pending.forEach((rec) => s[PENDING].put(rec));
        legacy.failed.forEach((rec) => s[FAILED].put(rec));
        legacy.drafts.forEach((rec) => {
            // A newer draft already here wins.
            const existing = drafts.get(rec.formId);
            if (!existing || (existing.savedAt || 0) < rec.savedAt) s[DRAFTS].put(rec);
        });
    });
    if (!ok) return false;

    legacy.keys.forEach(lsRemove);
    for (const [arrayKey, count] of Object.entries(legacy.arrays)) {
        // Entries an older tab appended while this ran stay behind and move next time.
        const latest = parseJson(lsGet(arrayKey), []);
        const appended = Array.isArray(latest) ? latest.slice(count) : [];
        if (appended.length) lsSet(arrayKey, JSON.stringify(appended));
        else lsRemove(arrayKey);
    }
    return true;
}

function isLegacyKey(key) {
    return !!key && (key.startsWith(ITEM_PREFIX) || key.startsWith(FAILED_PREFIX) || key.startsWith(DRAFT_PREFIX) ||
        key === LEGACY_FAILED_KEY || LEGACY_QUEUE_KEYS.includes(key));
}

function emitChanged(broadcast) {
    if (typeof window !== "undefined" && typeof window.dispatchEvent === "function" && typeof CustomEvent !== "undefined") {
        window.dispatchEvent(new CustomEvent(STORE_CHANGED_EVENT));
    }
    if (broadcast && channel) {
        try { channel.postMessage({ type: "changed" }); } catch (e) { /* ignore */ }
    }
}

async function refreshFromDisk() {
    if (mode !== "indexeddb") return;
    await migrateLegacy();
    await loadMirror();
    emitChanged(false);
}

function wireCrossTab() {
    // Only IndexedDB needs telling: localStorage writes already reach other tabs as `storage` events.
    if (mode === "indexeddb" && typeof BroadcastChannel !== "undefined") {
        try {
            channel = new BroadcastChannel("obsidianscout-queue");
            channel.onmessage = () => { refreshFromDisk(); };
            if (typeof channel.unref === "function") channel.unref();
        } catch (e) {
            channel = null;
        }
    }
    if (typeof window !== "undefined" && typeof window.addEventListener === "function") {
        window.addEventListener("storage", (e) => {
            // Fallback mode reads localStorage directly; IndexedDB mode moves what an older tab wrote.
            if (e.key && !isLegacyKey(e.key)) return;
            if (mode === "indexeddb") refreshFromDisk();
            else emitChanged(false);
        });
    }
}

/** Resolves once stored entries are loaded. Every read below is complete only after this. */
export function queueReady() {
    if (readyPromise) return readyPromise;
    readyPromise = (async () => {
        db = await openDb();
        if (db) {
            mode = "indexeddb";
            await loadMirror();
            try {
                if (await migrateLegacy()) await loadMirror();
            } catch (e) {
                console.warn("[Queue Store] Moving saved entries out of localStorage failed:", e);
            }
        } else {
            mode = hasLocalStorage() ? "localstorage" : "memory";
            if (mode === "localstorage") console.warn("[Queue Store] IndexedDB unavailable; saving entries to localStorage.");
        }
        wireCrossTab();
        // Anything that rendered from the empty copy before this finished draws again.
        if (mode === "indexeddb") setTimeout(() => emitChanged(false), 0);
        return mode;
    })();
    return readyPromise;
}

/** "indexeddb", "localstorage" or "memory" (nothing durable: entries are lost when the page closes). */
export function storageMode() {
    return mode;
}

/** Asks the browser not to clear this site's storage under pressure. Called on the first save. */
function requestPersistence() {
    if (persistRequested) return;
    persistRequested = true;
    try {
        if (typeof navigator !== "undefined" && navigator.storage && typeof navigator.storage.persist === "function") {
            navigator.storage.persisted().then((already) => already || navigator.storage.persist()).catch(() => {});
        }
    } catch (e) { /* ignore */ }
}

async function ensureDb() {
    await queueReady();
    if (mode === "indexeddb" && !db) {
        // Closed by a version change in another tab.
        db = await openDb();
        if (!db) mode = hasLocalStorage() ? "localstorage" : "memory";
    }
}

// ── Pending queue ───────────────────────────────────────────────────────────

/**
 * Fallback mode: moves entries an older page left in the shared array into per-entry keys. Entries appended to the
 * array while this runs stay there and move on the next read.
 */
function splitLegacyArray(queueKey) {
    const legacy = parseJson(lsGet(queueKey), null);
    if (!Array.isArray(legacy) || !legacy.length) return;
    for (const item of legacy) {
        if (!item || typeof item !== "object") continue;
        const clientId = item.clientId || newId();
        // Out of storage: leave the array as it is; it is still read below.
        if (!lsSet(`${ITEM_PREFIX}${queueKey}:${clientId}`, JSON.stringify({ ...item, clientId }))) return;
    }
    const latest = parseJson(lsGet(queueKey), []);
    const appended = Array.isArray(latest) ? latest.slice(legacy.length) : [];
    if (appended.length) lsSet(queueKey, JSON.stringify(appended));
    else lsRemove(queueKey);
}

/** Every entry waiting in one queue, in no particular order. */
export function listPending(queueKey) {
    if (mode === "localstorage") {
        splitLegacyArray(queueKey);
        const prefix = `${ITEM_PREFIX}${queueKey}:`;
        const out = [];
        for (const k of lsKeys()) {
            if (!k.startsWith(prefix)) continue;
            const item = parseJson(lsGet(k), null);
            if (item && typeof item === "object") out.push(item);
        }
        const arr = parseJson(lsGet(queueKey), []);
        if (Array.isArray(arr)) arr.forEach((item) => { if (item && typeof item === "object") out.push(item); });
        return out;
    }
    const out = [];
    for (const rec of pending.values()) if (rec.queueKey === queueKey) out.push(rec.item);
    return out;
}

/** Stores items (each with a clientId). All or nothing: resolves false and stores none when storage is full. */
export async function putPending(queueKey, items) {
    await ensureDb();
    requestPersistence();
    if (mode === "indexeddb") {
        const recs = items.map((item) => ({ id: pendingId(queueKey, item.clientId), queueKey, item }));
        const ok = await writeTx([PENDING], (s) => recs.forEach((rec) => s[PENDING].put(rec)));
        if (!ok) return false;
        recs.forEach((rec) => pending.set(rec.id, rec));
        emitChanged(true);
        return true;
    }
    if (mode === "localstorage") {
        const written = [];
        for (const item of items) {
            const k = `${ITEM_PREFIX}${queueKey}:${item.clientId}`;
            if (!lsSet(k, JSON.stringify(item))) {
                written.forEach(lsRemove);
                return false;
            }
            written.push(k);
        }
        emitChanged(false);
        return true;
    }
    items.forEach((item) => pending.set(pendingId(queueKey, item.clientId), { id: pendingId(queueKey, item.clientId), queueKey, item }));
    emitChanged(false);
    return true;
}

/** Removes one entry. The in-memory copy changes at once; the returned promise settles when storage has. */
export async function deletePending(queueKey, clientId) {
    if (!clientId) return;
    pending.delete(pendingId(queueKey, clientId));
    if (mode === "localstorage") {
        lsRemove(`${ITEM_PREFIX}${queueKey}:${clientId}`);
        const arr = parseJson(lsGet(queueKey), null);
        if (Array.isArray(arr)) {
            const next = arr.filter((item) => !item || item.clientId !== clientId);
            if (next.length !== arr.length) {
                if (next.length) lsSet(queueKey, JSON.stringify(next));
                else lsRemove(queueKey);
            }
        }
        emitChanged(false);
        return;
    }
    if (mode === "indexeddb") {
        await ensureDb();
        await writeTx([PENDING], (s) => s[PENDING].delete(pendingId(queueKey, clientId)));
        emitChanged(true);
    }
}

export async function clearPending(queueKey) {
    const ids = [];
    for (const rec of pending.values()) if (rec.queueKey === queueKey) ids.push(rec.id);
    ids.forEach((id) => pending.delete(id));
    if (mode === "localstorage") {
        const prefix = `${ITEM_PREFIX}${queueKey}:`;
        lsKeys().filter((k) => k.startsWith(prefix)).forEach(lsRemove);
        lsRemove(queueKey);
        emitChanged(false);
        return;
    }
    if (mode === "indexeddb") {
        await ensureDb();
        await writeTx([PENDING], (s) => ids.forEach((id) => s[PENDING].delete(id)));
        emitChanged(true);
    }
}

/**
 * True when the entry is still stored, read from storage itself rather than this tab's copy, so a sync never
 * re-sends what another tab already uploaded or deleted.
 */
export async function pendingStillStored(queueKey, clientId) {
    if (mode === "localstorage") {
        if (lsGet(`${ITEM_PREFIX}${queueKey}:${clientId}`) !== null) return true;
        const arr = parseJson(lsGet(queueKey), []);
        return Array.isArray(arr) && arr.some((item) => item && item.clientId === clientId);
    }
    if (mode === "indexeddb") {
        await ensureDb();
        const rec = await readOne(PENDING, pendingId(queueKey, clientId));
        if (!rec) pending.delete(pendingId(queueKey, clientId));
        return !!rec;
    }
    return pending.has(pendingId(queueKey, clientId));
}

// ── Rejected entries ────────────────────────────────────────────────────────

export function listFailed() {
    if (mode === "localstorage") {
        const out = [];
        const arr = parseJson(lsGet(LEGACY_FAILED_KEY), []);
        if (Array.isArray(arr)) {
            arr.forEach((entry) => {
                if (!entry || typeof entry !== "object") return;
                out.push({ ...entry, failedId: entry.failedId || (entry.item && entry.item.clientId) });
            });
        }
        for (const k of lsKeys()) {
            if (!k.startsWith(FAILED_PREFIX)) continue;
            const entry = parseJson(lsGet(k), null);
            if (entry && typeof entry === "object") out.push({ ...entry, failedId: entry.failedId || k.slice(FAILED_PREFIX.length) });
        }
        return out;
    }
    return Array.from(failed.values());
}

export async function putFailed(entry) {
    await ensureDb();
    const rec = { ...entry, failedId: entry.failedId || (entry.item && entry.item.clientId) || newId() };
    if (mode === "indexeddb") {
        const ok = await writeTx([FAILED], (s) => s[FAILED].put(rec));
        if (ok) failed.set(rec.failedId, rec);
        emitChanged(true);
        return ok;
    }
    if (mode === "localstorage") {
        const ok = lsSet(FAILED_PREFIX + rec.failedId, JSON.stringify(rec));
        emitChanged(false);
        return ok;
    }
    failed.set(rec.failedId, rec);
    emitChanged(false);
    return true;
}

export async function deleteFailed(failedId) {
    if (!failedId) return;
    failed.delete(failedId);
    if (mode === "localstorage") {
        lsRemove(FAILED_PREFIX + failedId);
        const arr = parseJson(lsGet(LEGACY_FAILED_KEY), null);
        if (Array.isArray(arr)) {
            const next = arr.filter((entry) => !entry || (entry.failedId || (entry.item && entry.item.clientId)) !== failedId);
            if (next.length) lsSet(LEGACY_FAILED_KEY, JSON.stringify(next));
            else lsRemove(LEGACY_FAILED_KEY);
        }
        emitChanged(false);
        return;
    }
    if (mode === "indexeddb") {
        await ensureDb();
        await writeTx([FAILED], (s) => s[FAILED].delete(failedId));
        emitChanged(true);
    }
}

// ── Form drafts ─────────────────────────────────────────────────────────────

/** `{ data, savedAt }` or null. Complete once queueReady() has resolved. */
export function getDraftRecord(formId) {
    if (mode === "localstorage") {
        const rec = parseJson(lsGet(DRAFT_PREFIX + formId), null);
        return rec && rec.data ? rec : null;
    }
    const rec = drafts.get(formId);
    return rec ? { data: rec.data, savedAt: rec.savedAt } : null;
}

export async function putDraftRecord(formId, data, savedAt = Date.now()) {
    const rec = { formId, data, savedAt };
    // Readable at once, even before storage commits.
    drafts.set(formId, rec);
    await ensureDb();
    if (mode === "indexeddb") return writeTx([DRAFTS], (s) => s[DRAFTS].put(rec));
    if (mode === "localstorage") return lsSet(DRAFT_PREFIX + formId, JSON.stringify({ data, savedAt }));
    return true;
}

export async function deleteDraftRecord(formId) {
    drafts.delete(formId);
    lsRemove(DRAFT_PREFIX + formId);
    await ensureDb();
    if (mode === "indexeddb") await writeTx([DRAFTS], (s) => s[DRAFTS].delete(formId));
}

/** For tests: forget everything loaded so the next queueReady() starts over. */
export function _resetForTests() {
    pending.clear();
    failed.clear();
    drafts.clear();
    if (db) { try { db.close(); } catch (e) { /* ignore */ } }
    db = null;
    mode = "memory";
    readyPromise = null;
    if (channel) { try { channel.close(); } catch (e) { /* ignore */ } }
    channel = null;
}
