/**
 * Utility Load-Profile Module - ObsidianScout
 * The sums behind the load profiler page: adds up what each server node reports, and works out rates and shares.
 */

function addTo(map, key, row, sum) {
    const existing = map.get(key);
    map.set(key, existing ? sum(existing, row) : { ...row });
}

const addNumbers = (fields) => (a, b) => {
    const merged = { ...a };
    fields.forEach((field) => { merged[field] = a[field] + b[field]; });
    return merged;
};

const MINUTE_FIELDS = ["requests", "liveRequests", "serverMs", "liveServerMs", "bytes", "liveBytes", "errors", "invalidations", "usersReached"];
const ROUTE_FIELDS = ["requests", "liveRequests", "serverMs", "bytes"];
const TOPIC_FIELDS = ["messages", "usersReached"];

/** One view of the given nodes: minutes and routes added together, and the busiest node's CPU and memory. */
export function combineProfiles(nodes) {
    const minutes = new Map();
    const routes = new Map();
    const topics = new Map();
    nodes.forEach((node) => {
        node.minutes.forEach((row) => addTo(minutes, row.minute, row, addNumbers(MINUTE_FIELDS)));
        node.routes.forEach((row) => addTo(routes, row.route, row, (a, b) => ({
            ...addNumbers(ROUTE_FIELDS)(a, b),
            maxMs: Math.max(a.maxMs, b.maxMs)
        })));
        node.topics.forEach((row) => addTo(topics, row.topic, row, addNumbers(TOPIC_FIELDS)));
    });
    return {
        nodeCount: nodes.length,
        minutes: Array.from(minutes.values()).sort((a, b) => a.minute - b.minute),
        routes: Array.from(routes.values()).sort((a, b) => b.serverMs - a.serverMs),
        topics: Array.from(topics.values()).sort((a, b) => b.messages - a.messages),
        usersConnected: nodes.reduce((total, node) => total + node.usersConnected, 0),
        cpuLoad: Math.max(0, ...nodes.map((node) => node.cpuLoad)),
        heapShare: Math.max(0, ...nodes.map((node) => node.usedHeapMb / Math.max(1, node.maxHeapMb))),
        trackingSinceMs: nodes.length ? Math.min(...nodes.map((node) => node.trackingSinceMs)) : 0
    };
}

/** Headline figures. Rates count only the time actually recorded: not the rest of this minute, or before a restart. */
export function summarize(profile, nowMs = Date.now()) {
    const total = (field) => profile.minutes.reduce((sum, row) => sum + row[field], 0);
    const requests = total("requests");
    const liveRequests = total("liveRequests");
    const serverMs = total("serverMs");
    const firstMinute = profile.minutes.length ? profile.minutes[0].minute : nowMs;
    const seconds = Math.max(1, (nowMs - Math.max(firstMinute, profile.trackingSinceMs)) / 1000);
    const requestsPerSecond = requests / seconds;
    const peakMinute = Math.max(0, ...profile.minutes.map((row) => row.requests));
    return {
        requests,
        liveRequests,
        requestsPerSecond,
        livePerSecond: liveRequests / seconds,
        // A minute that has only just started can run ahead of a full one.
        peakPerSecond: Math.max(peakMinute / 60, requestsPerSecond),
        liveShare: requests ? liveRequests / requests : 0,
        liveServerShare: serverMs ? total("liveServerMs") / serverMs : 0,
        averageMs: requests ? serverMs / requests : 0,
        bytesPerMinute: total("bytes") / (seconds / 60),
        liveBytes: total("liveBytes"),
        errors: total("errors"),
        invalidations: total("invalidations")
    };
}

export function formatBytes(bytes) {
    if (bytes < 1024) return `${Math.round(bytes)} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
    if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    return `${(bytes / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

export function formatMs(ms) {
    if (ms < 10) return `${ms.toFixed(1)} ms`;
    if (ms < 10000) return `${Math.round(ms)} ms`;
    return `${(ms / 1000).toFixed(1)} s`;
}

export function formatRate(perSecond) {
    return perSecond < 10 ? perSecond.toFixed(2) : perSecond.toFixed(1);
}

export function formatPercent(share) {
    return `${Math.round(share * 100)}%`;
}

/** A round top for the chart's axis, so its gridlines fall on readable numbers. */
export function niceMaximum(value) {
    if (value <= 4) return 4;
    const magnitude = 10 ** Math.floor(Math.log10(value));
    const step = [1, 2, 4, 5, 10].find((candidate) => candidate * magnitude >= value);
    return step * magnitude;
}
