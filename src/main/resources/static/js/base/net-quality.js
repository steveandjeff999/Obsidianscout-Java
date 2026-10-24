/**
 * Base Net-Quality Module - ObsidianScout
 * Estimates link speed from completed requests and decides when the page should behave as if it's on a slow link
 * (5-50 kbps event Wi-Fi, a phone hotspot in a crowded arena). The app uses the same thresholds.
 */

import { safeGetItem, safeSetItem } from './storage.js';

export const LOW_BANDWIDTH_MODE_KEY = "obsidianscout:low_bandwidth_mode";
export const LINK_QUALITY_EVENT = "obsidianscout:link-quality-changed";

/** Enter low-bandwidth behaviour below this estimated speed or above this round trip... */
const ENTER_KBPS = 48;
const ENTER_RTT_MS = 2500;
/** ...and leave it only once the link is clearly better, so it doesn't flap. */
const EXIT_KBPS = 96;
const EXIT_RTT_MS = 1200;
/** Transfers smaller or quicker than this say more about latency than throughput. */
const MIN_SAMPLE_BYTES = 4096;
const MIN_SAMPLE_MS = 250;
/** Weight of the newest sample in the running averages. */
const ALPHA = 0.35;
/** gzip'd JSON is usually 4-8x smaller on the wire; assume the low end when the browser can't say. */
const ASSUMED_GZIP_RATIO = 4;

let kbps = null;
let rttMs = null;
let autoLow = false;
let samples = 0;

export function getLowBandwidthMode() {
    const mode = safeGetItem(LOW_BANDWIDTH_MODE_KEY);
    return mode === "on" || mode === "off" ? mode : "auto";
}

export function setLowBandwidthMode(mode) {
    const next = mode === "on" || mode === "off" ? mode : "auto";
    safeSetItem(LOW_BANDWIDTH_MODE_KEY, next);
    notify();
}

function connectionHintSaysSlow() {
    try {
        const c = typeof navigator !== 'undefined' ? navigator.connection : null;
        if (!c) return false;
        if (c.saveData) return true;
        return c.effectiveType === "slow-2g" || c.effectiveType === "2g";
    } catch (e) {
        return false;
    }
}

/** True when requests should be throttled, bulk data skipped and local data preferred. */
export function isLowBandwidth() {
    const mode = getLowBandwidthMode();
    if (mode === "on") return true;
    if (mode === "off") return false;
    return autoLow || connectionHintSaysSlow();
}

export function getLinkStats() {
    return {
        kbps: kbps === null ? null : Math.round(kbps),
        rttMs: rttMs === null ? null : Math.round(rttMs),
        samples,
        mode: getLowBandwidthMode(),
        low: isLowBandwidth()
    };
}

function notify() {
    if (typeof window !== 'undefined') {
        window.dispatchEvent(new CustomEvent(LINK_QUALITY_EVENT, { detail: getLinkStats() }));
    }
}

function ewma(prev, value) {
    return prev === null ? value : prev + ALPHA * (value - prev);
}

function reevaluate() {
    const before = isLowBandwidth();
    if (autoLow) {
        const speedOk = kbps === null || kbps >= EXIT_KBPS;
        const rttOk = rttMs === null || rttMs <= EXIT_RTT_MS;
        if (speedOk && rttOk) autoLow = false;
    } else {
        const slow = kbps !== null && kbps < ENTER_KBPS;
        const laggy = rttMs !== null && rttMs > ENTER_RTT_MS;
        if (slow || laggy) autoLow = true;
    }
    if (before !== isLowBandwidth()) notify();
}

/**
 * Wire bytes and download time for a finished request. The Resource Timing entry gives the real compressed size when
 * the browser exposes it; otherwise the decoded size is scaled down for gzip.
 */
function measureFromResourceTiming(url) {
    try {
        if (typeof performance === 'undefined' || typeof performance.getEntriesByName !== 'function') return null;
        const absolute = new URL(url, window.location.href).href;
        const list = performance.getEntriesByName(absolute, "resource");
        const entry = list && list[list.length - 1];
        if (!entry || !entry.encodedBodySize || !entry.responseEnd || !entry.responseStart) return null;
        return {
            bytes: entry.encodedBodySize,
            downloadMs: entry.responseEnd - entry.responseStart,
            ttfbMs: entry.responseStart - (entry.requestStart || entry.startTime)
        };
    } catch (e) {
        return null;
    }
}

/**
 * Records one completed request. `decodedBytes` is the body length as read; `gzipped` whether the response was
 * compressed; `ttfbMs` the time until headers arrived; `downloadMs` the time spent reading the body.
 */
export function recordTransfer({ url, decodedBytes, gzipped, ttfbMs, downloadMs }) {
    const timing = url ? measureFromResourceTiming(url) : null;
    const wireBytes = timing ? timing.bytes : (gzipped ? decodedBytes / ASSUMED_GZIP_RATIO : decodedBytes);
    const bodyMs = timing ? timing.downloadMs : downloadMs;
    const firstByte = timing && timing.ttfbMs > 0 ? timing.ttfbMs : ttfbMs;

    if (Number.isFinite(firstByte) && firstByte > 0) {
        rttMs = ewma(rttMs, firstByte);
    }
    if (wireBytes >= MIN_SAMPLE_BYTES && Number.isFinite(bodyMs) && bodyMs >= MIN_SAMPLE_MS) {
        kbps = ewma(kbps, (wireBytes * 8) / bodyMs); // bits per ms == kbit/s
    }
    samples++;
    reevaluate();
}

/** A request that timed out without making progress counts as a very slow sample. */
export function recordStall(waitedMs) {
    rttMs = ewma(rttMs, Math.max(waitedMs, ENTER_RTT_MS + 1));
    samples++;
    reevaluate();
}

if (typeof window !== 'undefined') {
    try {
        const c = navigator.connection;
        if (c && typeof c.addEventListener === 'function') {
            c.addEventListener("change", notify);
        }
    } catch (e) {}
}
