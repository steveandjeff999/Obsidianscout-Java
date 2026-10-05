/**
 * IndexedDB Cache Module - ObsidianScout
 * Asynchronous, quota-resilient HTTP API and offline data cache.
 * Replaces localStorage for large HTTP responses and offline sync payloads,
 * preventing QuotaExceededError and main-thread UI lag.
 */

import { safeGetItem, safeSetItem, safeRemoveItem } from './storage.js';

const DB_NAME = "obsidianscout_cache_db";
const DB_VERSION = 1;
const STORE_NAME = "http_cache";

// In-memory cache for ultra-fast synchronous lookups and hot reads
const memoryCache = new Map();

let dbPromise = null;

/**
 * Initializes and returns the IndexedDB database instance.
 * Gracefully handles unsupported environments or private-browsing restrictions.
 */
export function getDb() {
    if (dbPromise) return dbPromise;

    if (typeof indexedDB === 'undefined') {
        console.warn("[IDB Cache] IndexedDB is not available in this environment. Falling back to memory/localStorage.");
        return Promise.resolve(null);
    }

    dbPromise = new Promise((resolve) => {
        try {
            const request = indexedDB.open(DB_NAME, DB_VERSION);

            request.onupgradeneeded = (event) => {
                const db = event.target.result;
                if (!db.objectStoreNames.contains(STORE_NAME)) {
                    db.createObjectStore(STORE_NAME, { keyPath: "key" });
                }
            };

            request.onsuccess = (event) => {
                resolve(event.target.result);
            };

            request.onerror = (event) => {
                console.warn("[IDB Cache] Failed to open IndexedDB:", event.target.error);
                resolve(null);
            };

            request.onblocked = () => {
                console.warn("[IDB Cache] IndexedDB open request was blocked.");
                resolve(null);
            };
        } catch (e) {
            console.warn("[IDB Cache] Exception opening IndexedDB:", e);
            resolve(null);
        }
    });

    return dbPromise;
}

/**
 * Normalizes a path/key for consistent lookup.
 */
function normalizeKey(path) {
    if (!path) return "";
    return path.startsWith("cache:") ? path.substring(6) : path;
}

/**
 * Retrieves cached HTTP response text and etag from IndexedDB.
 * @param {string} path - The request URL/path
 * @returns {Promise<{ key: string, text: string, etag: string|null, timestamp: number }|null>}
 */
export async function getHttpCache(path) {
    const key = normalizeKey(path);
    if (!key) return null;

    // 1. Check in-memory cache first
    if (memoryCache.has(key)) {
        return memoryCache.get(key);
    }

    // 2. Query IndexedDB
    try {
        const db = await getDb();
        if (db) {
            const result = await new Promise((resolve) => {
                try {
                    const tx = db.transaction(STORE_NAME, "readonly");
                    const store = tx.objectStore(STORE_NAME);
                    const req = store.get(key);
                    req.onsuccess = () => resolve(req.result || null);
                    req.onerror = () => resolve(null);
                } catch (txErr) {
                    resolve(null);
                }
            });

            if (result) {
                memoryCache.set(key, result);
                return result;
            }
        }
    } catch (e) {
        console.warn("[IDB Cache] Error reading cache for " + key + ":", e);
    }

    // 3. Fallback to localStorage (for mirrored auth/settings or unmigrated entries)
    const localText = safeGetItem("cache:" + key);
    if (localText !== null) {
        const localEtag = safeGetItem("etag:" + key);
        const record = { key, text: localText, etag: localEtag || null, timestamp: Date.now() };
        memoryCache.set(key, record);
        return record;
    }

    return null;
}

/**
 * Synchronous cache lookup for fast UI bootstrapping (e.g., banners, theme, role).
 * Checks memory cache and localStorage.
 */
export function getHttpCacheSync(path) {
    const key = normalizeKey(path);
    if (!key) return null;
    if (memoryCache.has(key)) {
        return memoryCache.get(key);
    }
    const localText = safeGetItem("cache:" + key);
    if (localText !== null) {
        return {
            key,
            text: localText,
            etag: safeGetItem("etag:" + key),
            timestamp: Date.now()
        };
    }
    return null;
}

/**
 * Stores HTTP response text and ETag in IndexedDB.
 * Essential metadata (/api/auth/me, /api/settings) is mirrored in localStorage for synchronous UI bootstrapping.
 * Large event datasets are stored purely in IndexedDB and pruned from localStorage.
 */
export async function setHttpCache(path, text, etag = null) {
    const key = normalizeKey(path);
    if (!key || text === null || text === undefined) return false;

    const record = {
        key: key,
        text: typeof text === "string" ? text : JSON.stringify(text),
        etag: etag || null,
        timestamp: Date.now()
    };

    // Update in-memory cache
    memoryCache.set(key, record);

    // Keep memory cache size bounded (max 50 recent items)
    if (memoryCache.size > 50) {
        const oldestKey = memoryCache.keys().next().value;
        memoryCache.delete(oldestKey);
    }

    // Mirror essential UI shell endpoints in localStorage for synchronous boots
    if (key === "/api/auth/me" || key.startsWith("/api/auth/me?") ||
        key === "/api/settings" || key.startsWith("/api/settings?")) {
        safeSetItem("cache:" + key, record.text);
        if (record.etag) {
            safeSetItem("etag:" + key, record.etag);
        }
    } else {
        // Clean out of localStorage to preserve quota
        safeRemoveItem("cache:" + key);
        safeRemoveItem("etag:" + key);
    }

    // Persist to IndexedDB
    try {
        const db = await getDb();
        if (db) {
            return await new Promise((resolve) => {
                try {
                    const tx = db.transaction(STORE_NAME, "readwrite");
                    const store = tx.objectStore(STORE_NAME);
                    const req = store.put(record);
                    req.onsuccess = () => resolve(true);
                    req.onerror = (err) => {
                        console.warn("[IDB Cache] Failed to write " + key + " to IndexedDB:", err);
                        resolve(false);
                    };
                } catch (txErr) {
                    console.warn("[IDB Cache] Transaction error for " + key + ":", txErr);
                    resolve(false);
                }
            });
        }
    } catch (e) {
        console.warn("[IDB Cache] Exception writing cache for " + key + ":", e);
    }

    return true;
}

/**
 * Removes a specific cache entry from IndexedDB, memory, and localStorage.
 */
export async function removeHttpCache(path) {
    const key = normalizeKey(path);
    if (!key) return;

    memoryCache.delete(key);
    safeRemoveItem("cache:" + key);
    safeRemoveItem("etag:" + key);

    try {
        const db = await getDb();
        if (db) {
            await new Promise((resolve) => {
                try {
                    const tx = db.transaction(STORE_NAME, "readwrite");
                    const store = tx.objectStore(STORE_NAME);
                    const req = store.delete(key);
                    req.onsuccess = () => resolve(true);
                    req.onerror = () => resolve(false);
                } catch (_) {
                    resolve(false);
                }
            });
        }
    } catch (e) {
        console.warn("[IDB Cache] Error removing cache for " + key + ":", e);
    }
}

/**
 * Clears all HTTP caches from IndexedDB, memory, and localStorage.
 */
export async function clearAllHttpCaches(preserveAuth = true) {
    memoryCache.clear();

    try {
        const db = await getDb();
        if (db) {
            if (preserveAuth) {
                // Keep /api/auth/me if preserving session
                const authRecord = await getHttpCache("/api/auth/me");
                await new Promise((resolve) => {
                    try {
                        const tx = db.transaction(STORE_NAME, "readwrite");
                        const store = tx.objectStore(STORE_NAME);
                        const req = store.clear();
                        req.onsuccess = () => resolve(true);
                        req.onerror = () => resolve(false);
                    } catch (_) {
                        resolve(false);
                    }
                });
                if (authRecord) {
                    await setHttpCache("/api/auth/me", authRecord.text, authRecord.etag);
                }
            } else {
                await new Promise((resolve) => {
                    try {
                        const tx = db.transaction(STORE_NAME, "readwrite");
                        const store = tx.objectStore(STORE_NAME);
                        const req = store.clear();
                        req.onsuccess = () => resolve(true);
                        req.onerror = () => resolve(false);
                    } catch (_) {
                        resolve(false);
                    }
                });
            }
        }
    } catch (e) {
        console.warn("[IDB Cache] Error clearing IndexedDB cache:", e);
    }

    // Also clear localStorage cache entries
    try {
        const keysToRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && (k.startsWith("cache:") || k.startsWith("etag:"))) {
                if (!preserveAuth || (k !== "cache:/api/auth/me" && k !== "etag:/api/auth/me")) {
                    keysToRemove.push(k);
                }
            }
        }
        keysToRemove.forEach(k => safeRemoveItem(k));
    } catch (_) {}
}

/**
 * Checks whether a given path is scouting/analytics dataset data.
 */
export function isScoutingDataPath(path) {
    if (!path) return false;
    const clean = normalizeKey(path).split("?")[0];
    return clean === "/api/scouting" ||
           clean === "/api/pit-scouting" ||
           clean === "/api/qual-scouting" ||
           clean.startsWith("/api/prescout/") ||
           clean === "/api/analytics" ||
           clean === "/api/custom-analytics/dataset";
}

/**
 * Purges all scouting/analytics datasets from IndexedDB and localStorage.
 */
export async function purgeScoutingHttpCache() {
    for (const key of Array.from(memoryCache.keys())) {
        if (isScoutingDataPath(key)) {
            memoryCache.delete(key);
        }
    }

    try {
        const db = await getDb();
        if (db) {
            await new Promise((resolve) => {
                try {
                    const tx = db.transaction(STORE_NAME, "readwrite");
                    const store = tx.objectStore(STORE_NAME);
                    const req = store.openCursor();
                    req.onsuccess = (e) => {
                        const cursor = e.target.result;
                        if (cursor) {
                            if (isScoutingDataPath(cursor.key)) {
                                cursor.delete();
                            }
                            cursor.continue();
                        } else {
                            resolve(true);
                        }
                    };
                    req.onerror = () => resolve(false);
                } catch (_) {
                    resolve(false);
                }
            });
        }
    } catch (e) {
        console.warn("[IDB Cache] Failed to purge scouting cache from IDB:", e);
    }

    // Also remove from localStorage if any residue
    try {
        const keysToRemove = [];
        for (let i = 0; i < localStorage.length; i++) {
            const k = localStorage.key(i);
            if (k && (k.startsWith("cache:") || k.startsWith("etag:"))) {
                const sub = k.startsWith("cache:") ? k.substring(6) : k.substring(5);
                if (isScoutingDataPath(sub)) {
                    keysToRemove.push(k);
                }
            }
        }
        keysToRemove.forEach(k => safeRemoveItem(k));
    } catch (_) {}
}

/**
 * Prunes cached endpoints not in the keepSet (used after background offline sync).
 * @param {Set<string>} keepEndpoints - Set of endpoints/keys to preserve
 */
export async function pruneHttpCaches(keepEndpoints) {
    if (!keepEndpoints || !(keepEndpoints instanceof Set)) return;

    try {
        const db = await getDb();
        if (db) {
            await new Promise((resolve) => {
                try {
                    const tx = db.transaction(STORE_NAME, "readwrite");
                    const store = tx.objectStore(STORE_NAME);
                    const req = store.openCursor();
                    req.onsuccess = (e) => {
                        const cursor = e.target.result;
                        if (cursor) {
                            const key = cursor.key;
                            if (key !== "/api/auth/me" && key !== "/api/settings" && !keepEndpoints.has(key) && !keepEndpoints.has("cache:" + key)) {
                                cursor.delete();
                                memoryCache.delete(key);
                            }
                            cursor.continue();
                        } else {
                            resolve(true);
                        }
                    };
                    req.onerror = () => resolve(false);
                } catch (_) {
                    resolve(false);
                }
            });
        }
    } catch (e) {
        console.warn("[IDB Cache] Failed to prune HTTP caches:", e);
    }
}

/**
 * One-time migration: moves existing cache:* and etag:* from localStorage into IndexedDB,
 * reclaiming the 5MB localStorage quota immediately.
 */
export async function migrateLocalStorageToIdb() {
    try {
        if (typeof localStorage === 'undefined') return;

        const cacheKeys = [];
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i);
            if (key && key.startsWith("cache:")) {
                cacheKeys.push(key);
            }
        }

        if (cacheKeys.length === 0) return;

        console.log(`[IDB Cache] Found ${cacheKeys.length} legacy cache entries in localStorage. Migrating to IndexedDB...`);

        for (const cacheKey of cacheKeys) {
            const path = cacheKey.substring(6);
            const text = localStorage.getItem(cacheKey);
            const etag = localStorage.getItem("etag:" + path);

            if (text !== null) {
                await setHttpCache(path, text, etag);
            }

            // Keep auth and settings mirrored in localStorage for instant synchronous boot,
            // remove all large datasets to liberate localStorage quota!
            if (path !== "/api/auth/me" && !path.startsWith("/api/auth/me?") &&
                path !== "/api/settings" && !path.startsWith("/api/settings?")) {
                localStorage.removeItem(cacheKey);
                if (etag) localStorage.removeItem("etag:" + path);
            }
        }

        console.log(`[IDB Cache] Successfully migrated and liberated localStorage space.`);
    } catch (e) {
        console.warn("[IDB Cache] Migration from localStorage encountered an issue:", e);
    }
}
