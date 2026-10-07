/**
 * Node tests for the Local AI assistant (no browser, no model).
 * Run: node --test src/test/js/ai-assistant.test.mjs   (also wired into `gradlew test` via the jsTest task when Node is installed)
 *
 * Builds a small synthetic event, loads it through ai-data.js with a mocked API, then checks routing,
 * that every routed tool and suggestion chip exists, that every tool runs, and the number guardrail.
 */
import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const here = path.dirname(fileURLToPath(import.meta.url));
const staticJs = path.resolve(here, "../../main/resources/static/js");
const evalSet = JSON.parse(readFileSync(path.join(here, "assistant-eval.json"), "utf8"));

// ------------------------------------------------------------------ synthetic event behind a mocked API

const EVENT = "2026test";
const TEAMS = evalSet.teams;
let seed = 7;
const rand = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);

const config = {
    fields: [
        { id: "sec_auto", type: "section", label: "Autonomous", phase: "auto" },
        { id: "auto_scored", type: "counter", label: "Auto Fuel Scored", pointsPer: 1 },
        { id: "sec_tele", type: "section", label: "Teleop", phase: "teleop" },
        { id: "teleop_scored", type: "counter", label: "Teleop Fuel", pointsPer: 1 },
        { id: "sec_end", type: "section", label: "Endgame", phase: "endgame" },
        { id: "climb", type: "select", label: "Climb", options: [{ value: "n", points: 0 }, { value: "l1", points: 10 }, { value: "l3", points: 30 }] },
        { id: "comments", type: "textarea", label: "Comments" }
    ]
};
const qualConfig = { fields: [{ id: "driver", type: "rating", label: "Driver Rating" }, { id: "notes", type: "textarea", label: "Notes" }] };

const matches = [];
const entries = [];
const qual = [];
for (let m = 1; m <= 20; m++) {
    const six = TEAMS.slice().sort(() => rand() - 0.5).slice(0, 6);
    const played = m <= 14;
    matches.push({
        matchKey: `${EVENT}_qm${m}`, eventKey: EVENT, compLevel: "qm", setNumber: 1, matchNumber: m, label: `QM ${m}`,
        redTeams: six.slice(0, 3).map((t) => `frc${t}`), blueTeams: six.slice(3).map((t) => `frc${t}`),
        redScore: played ? 80 : null, blueScore: played ? 70 : null, scheduledTime: 1790000000 + m * 480
    });
    if (!played) continue;
    six.forEach((team, i) => {
        const skill = 1 - TEAMS.indexOf(team) / TEAMS.length;
        const data = {
            eventKey: EVENT, matchKey: `${EVENT}_qm${m}`, matchNumber: m, targetTeamNumber: team,
            auto_scored: Math.round(skill * 9 + rand() * 2), teleop_scored: Math.round(skill * 30 + rand() * 5),
            climb: skill > 0.6 ? "l3" : skill > 0.3 ? "l1" : "n", comments: i % 2 ? "fast cycles, good driver" : "tipped over once"
        };
        entries.push({ id: `e${m}-${team}`, targetTeamNumber: team, eventKey: EVENT, matchKey: data.matchKey, matchNumber: m, data, createdAt: "2026-10-01T00:00:00Z" });
        qual.push({ id: `q${m}-${team}`, targetTeamNumber: team, eventKey: EVENT, matchNumber: m, data: { driver: Math.round(skill * 5), notes: "solid defense" }, createdAt: "2026-10-01T00:00:00Z" });
    });
}

const responses = {
    "/api/settings": { settings: { year: 2026, eventKey: EVENT } },
    "/api/config": config,
    "/api/qual-config": qualConfig,
    "/api/pit-config": { fields: [] },
    [`/api/teams?eventKey=${EVENT}`]: TEAMS.map((n) => ({ teamNumber: n, teamKey: `frc${n}`, eventKey: EVENT, nickname: `Team ${n}` })),
    [`/api/matches?eventKey=${EVENT}`]: matches,
    "/api/scouting?includePrescout=true": entries,
    "/api/qual-scouting?includePrescout=true": qual,
    "/api/pit-scouting?includePrescout=true": []
};

globalThis.window = {
    Obsidianscout: {
        request: async (p) => (p in responses ? JSON.parse(JSON.stringify(responses[p])) : null),
        getMe: async () => ({ teamNumber: 5454, role: "SCOUT", localAiEnabled: false })
    },
    addEventListener() {},
    dispatchEvent() {}
};

const Data = (await import(pathToFileURL(path.join(staticJs, "ai/ai-data.js")).href)).default;
const Tools = await import(pathToFileURL(path.join(staticJs, "ai/ai-tools.js")).href);
const ctx = await Data.loadContext({ eventKey: EVENT });

// ------------------------------------------------------------------ tests

test("synthetic event loads", () => {
    assert.equal(ctx.stats.size, TEAMS.length);
    assert.equal(ctx.matches.length, 20);
});

test("every rule-router target is a real tool", () => {
    for (const c of evalSet.cases) {
        const route = Tools.ruleRoute(c.q, ctx);
        if (route) assert.ok(Tools.TOOLS[route.tool], `"${c.q}" routes to missing tool ${route.tool}`);
    }
});

test("every suggestion chip and card label points at a real tool", () => {
    const src = readFileSync(path.join(staticJs, "assistant.js"), "utf8");
    const chipTools = [...src.matchAll(/route:\s*\{\s*tool:\s*"([a-z_]+)"/g)].map((m) => m[1]);
    assert.ok(chipTools.length >= 4, "expected suggestion chips with routes");
    for (const name of chipTools) assert.ok(Tools.TOOLS[name], `suggestion chip uses missing tool ${name}`);
});

for (const lang of ["en", "es", "tr", "he"]) {
    test(`rule router: ${lang} evaluation set`, () => {
        const failures = [];
        for (const c of evalSet.cases.filter((x) => x.lang === lang)) {
            const route = Tools.ruleRoute(c.q, ctx);
            const got = route ? route.tool : null;
            if (got !== c.tool) failures.push(`"${c.q}" -> ${got} (expected ${c.tool})`);
        }
        assert.deepEqual(failures, []);
    });
}

test("every tool runs on real-shaped data and has a brief", async () => {
    const sample = {
        team_overview: { team: 254 }, top_teams: { metric: "auto", n: 5, chart: true }, compare_teams: { teams: [254, 1678], chart: true },
        compare_metrics: { teams: [254, 1678], metrics: ["auto", "teleop"] }, team_matches: { team: 254 }, match_by_match: { teams: [254] },
        metric_trend: { teams: [254], metric: "auto" }, scatter: { metric_x: "auto", metric_y: "teleop" }, stacked_breakdown: { teams: [254, 1678] },
        team_radar: { teams: [254, 1678] }, score_distribution: { metric: "total" }, match_preview: { match: "12" },
        pick_candidates: { n: 5 }, filter_teams: { conditions: [{ metric: "auto", op: ">", value: 3 }] }, calculate: { expression: "(12.5+8)/2" },
        create_strategy_brief: { match: "15" }, create_alliance_sheet: {}, create_team_dossier: { team: 254 }, all_matches: {}, all_teams: {},
        capabilities_help: {}, read_docs: { query: "pit scouting" }, current_rankings: {}, match_predictions: {}, projected_rankings: {},
        create_artifact: { title: "Test", markdown: "# Test\n\nTeam 254 averages 999 points." }
    };
    const skip = new Set(["summarize_notes"]); // needs a loaded model
    for (const [name, tool] of Object.entries(Tools.TOOLS)) {
        if (skip.has(name)) continue;
        const args = sample[name] || {};
        let result;
        await assert.doesNotReject(async () => { result = await tool.run(ctx, args, {}); }, `${name} threw`);
        assert.ok(result && result.facts, `${name} returned no facts`);
        if (!result.facts.error && !result.artifact) {
            const brief = Tools.briefFor({ tool: name, args, ...result });
            assert.equal(typeof brief, "string", `${name} brief`);
        }
    }
});

test("restored tools return data, not errors", async () => {
    for (const [name, args] of [["match_preview", { match: "12" }], ["pick_candidates", { n: 4 }], ["filter_teams", { conditions: [{ metric: "auto", op: ">", value: 1 }] }]]) {
        const r = await Tools.TOOLS[name].run(ctx, args, {});
        assert.ok(!r.facts.error, `${name}: ${r.facts.error}`);
        assert.ok(Tools.briefFor({ tool: name, args, ...r }).length > 0, `${name} brief empty`);
    }
    const picks = await Tools.TOOLS.pick_candidates.run(ctx, { n: 20 }, {});
    assert.ok(!picks.facts.candidates.some((c) => c.team === 5454), "our own team is excluded");
});

test("calculator is exact and refuses code", () => {
    assert.equal(Tools.evaluate("(12.5+8)/2"), 10.25);
    assert.equal(Tools.evaluate("-3 + 4*2"), 5);
    assert.throws(() => Tools.evaluate("alert(1)"));
    assert.throws(() => Tools.evaluate("2**10"));
});

test("number guardrail flags invented numbers only", async () => {
    const r = await Tools.TOOLS.top_teams.run(ctx, { metric: "auto", n: 3 }, {});
    const results = [{ tool: "top_teams", args: { metric: "auto", n: 3 }, ...r }];
    const real = r.facts.teams[0].avg;
    assert.deepEqual(Tools.unverifiedNumbers(`Team ${r.facts.teams[0].team} leads with ${real}.`, results, "top 3 auto", ctx), []);
    assert.deepEqual(Tools.unverifiedNumbers("Team 254 leads with 987.6 points.", results, "top 3 auto", ctx), ["987.6"]);
});
