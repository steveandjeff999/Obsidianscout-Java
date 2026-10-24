/**
 * Base Request-Scheduler Module - ObsidianScout
 * Runs network requests by priority. On a slow link, bulk downloads go one at a time and never hold up uploads,
 * assignments or the match schedule; on a normal link up to six requests run at once, as browsers allow anyway.
 */

import { isLowBandwidth } from './net-quality.js';

export const PRIORITY = Object.freeze({ CRITICAL: 0, HIGH: 1, NORMAL: 2, LOW: 3 });

const NORMAL_CONCURRENCY = 6;
/** On a slow link: two requests in flight, and at most one of them below HIGH priority. */
const LOW_BW_CONCURRENCY = 2;
const LOW_BW_BULK_SLOTS = 1;

const HIGH_PATHS = [
    "/api/assignments",
    "/api/settings",
    "/api/matches",
    "/api/teams",
    "/api/sync/entries",
    "/api/match-checklist",
    "/api/battery-tracker"
];
const LOW_PATHS = [
    "/api/analytics",
    "/api/custom-analytics",
    "/api/matches/predict",
    "/api/field-images",
    "/api/events",
    "/api/summary",
    "/api/stats",
    "/api/validation",
    "/api/admin/",
    "/api/chat",
    "/api/banners",
    "/api/alliances/import-sources"
];

/** Default priority for a request when the caller doesn't give one. Writes and auth always go first. */
export function priorityFor(method, path) {
    const m = (method || "GET").toUpperCase();
    const clean = String(path || "").split("?")[0];
    if (m !== "GET" || clean.startsWith("/api/auth")) return PRIORITY.CRITICAL;
    // Predictions live under /api/matches but are bulk; check LOW before HIGH.
    if (LOW_PATHS.some(p => clean.startsWith(p))) return PRIORITY.LOW;
    if (HIGH_PATHS.some(p => clean.startsWith(p))) return PRIORITY.HIGH;
    return PRIORITY.NORMAL;
}

const queue = [];
let active = 0;
let activeBulk = 0;
let seq = 0;

function pump() {
    while (queue.length) {
        const low = isLowBandwidth();
        const limit = low ? LOW_BW_CONCURRENCY : NORMAL_CONCURRENCY;
        if (active >= limit) return;

        let best = 0;
        for (let i = 1; i < queue.length; i++) {
            const a = queue[i], b = queue[best];
            if (a.priority < b.priority || (a.priority === b.priority && a.seq < b.seq)) best = i;
        }
        const job = queue[best];
        const bulk = job.priority >= PRIORITY.NORMAL;
        // The best waiting job is bulk and the bulk slot is taken: nothing more important is waiting either.
        if (low && bulk && activeBulk >= LOW_BW_BULK_SLOTS) return;

        queue.splice(best, 1);
        active++;
        if (bulk) activeBulk++;
        Promise.resolve()
            .then(job.task)
            .then(job.resolve, job.reject)
            .finally(() => {
                active--;
                if (bulk) activeBulk--;
                pump();
            });
    }
}

/** Runs `task` (a function returning a promise) when a slot is free. */
export function schedule(priority, task) {
    return new Promise((resolve, reject) => {
        queue.push({ priority, task, resolve, reject, seq: seq++ });
        pump();
    });
}

export function getQueueStats() {
    return { active, activeBulk, waiting: queue.length };
}
