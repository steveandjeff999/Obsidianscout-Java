/**
 * Node tests for the battery rotation (static/js/services/battery-rotation.js).
 * Run: node --test src/test/js/battery-rotation.test.mjs   (also part of `gradlew check` via jsTest)
 *
 * Replays the shared cases the app's battery_rotation.dart is checked against, then the plan edits.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const battery = require(path.resolve(here, "../../main/resources/static/js/services/battery-rotation.js"));
const { cases } = JSON.parse(readFileSync(path.resolve(here, "../resources/battery-rotation-cases.json"), "utf8"));

for (const c of cases) {
    test(`schedule: ${c.name}`, () => {
        const schedule = battery.computeSchedule(c.inventory, c.plan, c.matches);
        const actual = Object.fromEntries(Object.entries(schedule).map(([k, v]) => [k, [v.batteryId, v.source]]));
        assert.deepEqual(actual, c.expected);
    });
}

const inventory = { batteries: { b1: { name: "B1", order: 0 }, b2: { name: "B2", order: 1 }, b3: { name: "B3", order: 2 } } };
const ids = (plan, keys) => keys.map((k) => battery.computeSchedule(inventory, plan, keys)[k].batteryId);

test("skip, then clear skips restores the rotation", () => {
    const plan = {};
    battery.skip(plan, "q1", "b1", "alex");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b2", "b3"]);
    assert.equal(plan.matches.q1.skipped.b1.by, "alex");
    battery.skip(plan, "q1", "b2", "alex");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b3", "b1"]);
    battery.clearSkips(plan, "q1");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b1", "b2"]);
    assert.deepEqual(plan.matches, {});
});

test("pin and un-pin", () => {
    const plan = {};
    battery.skip(plan, "q1", "b3", "a");
    battery.pin(plan, "q1", "b3");
    assert.equal(plan.matches.q1.skipped, undefined, "pinning a skipped battery un-skips it");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b3", "b1"]);
    battery.pin(plan, "q1", "");
    assert.deepEqual(plan.matches, {});
});

test("marking used freezes the match and skipping it un-freezes", () => {
    const plan = {};
    battery.markUsed(plan, "q1", "b2");
    assert.equal(battery.usedOf(plan, "q1"), "b2");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b2", "b3"]);
    assert.deepEqual(battery.usageByBattery(plan, ["q1", "q2"]), { b2: ["q1"] });
    battery.skip(plan, "q1", "b2", "a");
    assert.equal(battery.usedOf(plan, "q1"), "");
    assert.deepEqual(ids(plan, ["q1", "q2"]), ["b1", "b2"]);
});

test("autoMarkUsed defaults on", () => {
    assert.equal(battery.autoMarkUsed({}), true);
    assert.equal(battery.autoMarkUsed({ autoMarkUsed: false }), false);
});

test("ObsidianMatchUtils.compareMatches orders practice > qualification > playoff (qf, sf, f) by set and match number", () => {
    const matchUtils = require(path.resolve(here, "../../main/resources/static/js/services/match-utils.js"));
    const matches = [
        { matchKey: "e_f1m2", compLevel: "f", setNumber: 1, matchNumber: 2 },
        { matchKey: "e_sf2m1", compLevel: "sf", setNumber: 2, matchNumber: 1 },
        { matchKey: "e_qm10", compLevel: "qm", matchNumber: 10 },
        { matchKey: "e_f1m1", compLevel: "f", setNumber: 1, matchNumber: 1 },
        { matchKey: "e_qf1m2", compLevel: "qf", setNumber: 1, matchNumber: 2 },
        { matchKey: "e_sf1m1", compLevel: "sf", setNumber: 1, matchNumber: 1 },
        { matchKey: "custom_Practice_2", label: "Practice 2" },
        { matchKey: "e_qm2", compLevel: "qm", matchNumber: 2 },
        { matchKey: "e_qf1m1", compLevel: "qf", setNumber: 1, matchNumber: 1 },
        { matchKey: "e_pr1", compLevel: "pr", matchNumber: 1 }
    ];
    matches.sort(matchUtils.compareMatches);
    assert.deepEqual(
        matches.map((m) => m.matchKey),
        ["e_pr1", "custom_Practice_2", "e_qm2", "e_qm10", "e_qf1m1", "e_qf1m2", "e_sf1m1", "e_sf2m1", "e_f1m1", "e_f1m2"]
    );
});
