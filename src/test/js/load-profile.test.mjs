/**
 * Node tests for the sums behind the load profiler page (utilities/load-profile.js).
 * Run: node --test src/test/js/load-profile.test.mjs   (also part of `gradlew check` via jsTest)
 */
import test from "node:test";
import assert from "node:assert/strict";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const { combineProfiles, summarize, formatBytes, formatMs, niceMaximum } = await import(
    pathToFileURL(path.resolve(here, "../../main/resources/static/js/utilities/load-profile.js")).href
);

const MINUTE = 60000;

function minute(at, requests, liveRequests = 0) {
    return { minute: at, requests, liveRequests, serverMs: requests * 10, liveServerMs: liveRequests * 10, bytes: requests * 100, liveBytes: liveRequests * 100, errors: 0, invalidations: 0, usersReached: 0 };
}

function node(overrides) {
    return { nodeIp: "a", trackingSinceMs: 0, usersConnected: 0, cpuLoad: 0, usedHeapMb: 0, maxHeapMb: 100, minutes: [], routes: [], topics: [], ...overrides };
}

test("nodes add up minute by minute, and the slowest time is kept rather than summed", () => {
    const route = (serverMs, maxMs) => ({ route: "GET /api/teams", requests: 2, liveRequests: 1, serverMs, maxMs, bytes: 10 });
    const combined = combineProfiles([
        node({ usersConnected: 3, minutes: [minute(MINUTE, 5, 1)], routes: [route(20, 15)], topics: [{ topic: "scouting", messages: 1, usersReached: 3 }] }),
        node({ usersConnected: 4, minutes: [minute(MINUTE, 7, 2)], routes: [route(30, 25)], topics: [{ topic: "scouting", messages: 2, usersReached: 4 }] })
    ]);
    assert.equal(combined.minutes.length, 1);
    assert.equal(combined.minutes[0].requests, 12);
    assert.equal(combined.minutes[0].liveRequests, 3);
    assert.equal(combined.routes[0].requests, 4);
    assert.equal(combined.routes[0].serverMs, 50);
    assert.equal(combined.routes[0].maxMs, 25);
    assert.equal(combined.topics[0].messages, 3);
    assert.equal(combined.usersConnected, 7);
});

test("CPU and memory show the busiest node, not a sum", () => {
    const combined = combineProfiles([
        node({ cpuLoad: 0.2, usedHeapMb: 90 }),
        node({ cpuLoad: 0.6, usedHeapMb: 10 })
    ]);
    assert.equal(combined.cpuLoad, 0.6);
    assert.equal(combined.heapShare, 0.9);
});

test("no nodes is an empty view rather than an error", () => {
    const combined = combineProfiles([]);
    assert.deepEqual(combined.minutes, []);
    assert.equal(summarize(combined, MINUTE).requests, 0);
});

test("live refresh shares are worked out from requests and server time", () => {
    const summary = summarize(combineProfiles([node({ minutes: [minute(0, 10, 4)] })]), MINUTE);
    assert.equal(summary.liveShare, 0.4);
    assert.equal(summary.liveServerShare, 0.4);
    assert.equal(summary.averageMs, 10);
});

test("rates count the time recorded, not the rest of this minute", () => {
    const profile = combineProfiles([node({ minutes: [minute(0, 60), minute(MINUTE, 60)] })]);
    assert.equal(summarize(profile, MINUTE + 60000).requestsPerSecond, 1);
    assert.equal(summarize(profile, MINUTE + 30000).requestsPerSecond, 120 / 90);
});

test("rates don't count the time before a restart", () => {
    const profile = combineProfiles([node({ trackingSinceMs: MINUTE, minutes: [minute(0, 0), minute(MINUTE, 60)] })]);
    assert.equal(summarize(profile, 2 * MINUTE).requestsPerSecond, 1);
});

test("the peak is the busiest minute", () => {
    const profile = combineProfiles([node({ minutes: [minute(0, 60), minute(MINUTE, 600)] })]);
    assert.equal(summarize(profile, 2 * MINUTE).peakPerSecond, 10);
});

test("the peak is never below the average, even a few seconds after a restart", () => {
    const profile = combineProfiles([node({ trackingSinceMs: 50000, minutes: [minute(0, 60)] })]);
    const summary = summarize(profile, MINUTE);
    assert.equal(summary.requestsPerSecond, 6);
    assert.equal(summary.peakPerSecond, 6);
});

test("figures are readable", () => {
    assert.equal(formatBytes(512), "512 B");
    assert.equal(formatBytes(2048), "2.0 KB");
    assert.equal(formatBytes(3 * 1024 * 1024), "3.0 MB");
    assert.equal(formatMs(4.25), "4.3 ms");
    assert.equal(formatMs(250), "250 ms");
    assert.equal(formatMs(12000), "12.0 s");
});

test("the axis tops out on a round number above the data", () => {
    assert.equal(niceMaximum(0), 4);
    assert.equal(niceMaximum(37), 40);
    assert.equal(niceMaximum(120), 200);
    assert.equal(niceMaximum(100), 100);
});
