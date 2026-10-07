/**
 * More read-only assistant tools: schedules, head-to-head, consistency, recent form, alliance builder, event-wide
 * statistics, percentiles, notes / pit search, metric list, event summary and scouting coverage.
 * Same contract as tools.js: run(ctx, args) returns { facts, table?, chart?, links? } built by code.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { fmtTeamPage, metricOrDefault, r1, t, toTeamNumbers } from "./shared.js";

// ------------------------------------------------------------------ helpers

const isPlayed = (m) => m.redScore !== null && m.redScore !== undefined && m.redScore >= 0;
const sides = (m) => ({
    red: (m.redTeams || []).map(Data.teamNumberFromKey).filter(Boolean),
    blue: (m.blueTeams || []).map(Data.teamNumberFromKey).filter(Boolean)
});
const mean = (vals) => (vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : null);
const totalMetric = (ctx) => ctx.metrics.find((m) => m.id === "score_total") || ctx.metrics[0];
const phaseMetrics = (ctx) => ctx.metrics.filter((m) => m.kind === "score" && m.id !== "score_total");
const avgOf = (ctx, team, metric) => {
    const s = ctx.stats.get(team);
    const m = s && metric ? s.metrics[metric.id] : null;
    return m && m.avg !== null && m.avg !== undefined ? m.avg : null;
};
/** Expected contribution: scouted total, else EPA, else xP. */
const strengthOf = (ctx, team) => avgOf(ctx, team, totalMetric(ctx))
    ?? avgOf(ctx, team, ctx.metrics.find((m) => m.id === "ext:epa"))
    ?? avgOf(ctx, team, ctx.metrics.find((m) => m.id === "ext:exp"))
    ?? 0;
const winProbability = (forScore, againstScore) => Math.round(100 / (1 + Math.exp(-(forScore - againstScore) / 15)));
const outcome = (m, color) => {
    if (!isPlayed(m)) return null;
    const ours = color === "red" ? m.redScore : m.blueScore;
    const theirs = color === "red" ? m.blueScore : m.redScore;
    return { result: ours > theirs ? "W" : ours < theirs ? "L" : "T", score: `${ours}-${theirs}` };
};
const keywords = (query) => String(query || "").toLowerCase()
    .split(/[\s,;/|]+/)
    .map((w) => w.replace(/^["'(]+|["').?!:]+$/g, ""))
    .filter((w) => w.length >= 3 && !STOP_WORDS.has(w));
const STOP_WORDS = new Set(["the", "and", "for", "with", "that", "which", "teams", "team", "have", "has", "who", "what", "notes", "note",
    "mention", "mentions", "mentioned", "about", "said", "say", "scouts", "pit", "data", "any", "are", "was", "were", "their", "they"]);
const snippet = (text, terms, max = 140) => {
    const lower = text.toLowerCase();
    const at = Math.max(0, Math.min(...terms.map((w) => lower.indexOf(w)).filter((i) => i >= 0), text.length));
    const start = Math.max(0, at - 40);
    const out = text.slice(start, start + max);
    return `${start > 0 ? "..." : ""}${out}${start + max < text.length ? "..." : ""}`;
};
const teamOrOurs = (ctx, value) => {
    if (value === undefined || value === null || value === "" || /^(our|ours|us|we)$/i.test(String(value))) {
        return ctx.ourTeam && ctx.stats.has(ctx.ourTeam) ? ctx.ourTeam : null;
    }
    return toTeamNumbers(ctx, value)[0] || null;
};
const teamLink = (ctx, team) => ({ label: fmtTeamPage(team), href: `/team?teamNumber=${team}${ctx.eventKey ? `&eventKey=${encodeURIComponent(ctx.eventKey)}` : ""}` });

// ------------------------------------------------------------------ tools

export const EXTRA_TOOLS = {
    team_schedule: {
        description: "One team's matches (default: our team): alliance colour, partners, opponents, results and record, plus expected scores for upcoming matches.",
        params: { team: "team number (omit for our team)", unplayed_only: "true for upcoming matches only" },
        async run(ctx, args) {
            const team = teamOrOurs(ctx, args.team);
            if (!team) return { facts: { error: args.team ? `Team ${args.team} has no data at this event.` : "Our team is not at this event; ask about a specific team number." } };
            let list = ctx.matches.filter((m) => { const s = sides(m); return s.red.includes(team) || s.blue.includes(team); });
            if (!list.length) return { facts: { team, error: `No scheduled matches found for team ${team}.` } };
            const record = { wins: 0, losses: 0, ties: 0 };
            const rows = list.map((m) => {
                const s = sides(m);
                const color = s.red.includes(team) ? "red" : "blue";
                const partners = (color === "red" ? s.red : s.blue).filter((x) => x !== team);
                const opponents = color === "red" ? s.blue : s.red;
                const res = outcome(m, color);
                if (res) record[{ W: "wins", L: "losses", T: "ties" }[res.result]]++;
                const expFor = r1([team, ...partners].reduce((sum, x) => sum + strengthOf(ctx, x), 0));
                const expAgainst = r1(opponents.reduce((sum, x) => sum + strengthOf(ctx, x), 0));
                return {
                    match: Data.matchLabel(m), alliance: color, partners, opponents,
                    result: res ? `${res.result} ${res.score}` : undefined,
                    expected: res ? undefined : `${expFor}-${expAgainst}`,
                    win_chance: res ? undefined : `${winProbability(expFor, expAgainst)}%`
                };
            });
            const upcoming = rows.filter((r) => !r.result);
            const shown = args.unplayed_only ? upcoming : rows;
            return {
                facts: {
                    team, name: Data.teamName(ctx, team) || undefined, is_our_team: team === ctx.ourTeam || undefined,
                    record: `${record.wins}-${record.losses}-${record.ties}`, matches_played: rows.length - upcoming.length,
                    matches_upcoming: upcoming.length, next_match: upcoming[0] || undefined, matches: shown
                },
                table: {
                    columns: [t("ai.col.match", "Match"), t("ai.col.alliance", "Alliance"), t("ai.col.partners", "Partners"), t("ai.col.opponents", "Opponents"), t("ai.col.result", "Result / expected")],
                    rows: shown.map((r) => [r.match, r.alliance === "red" ? "Red" : "Blue", r.partners.join(", ") || "-", r.opponents.join(", ") || "-",
                        r.result || `${r.expected} (${r.win_chance})`])
                },
                links: [teamLink(ctx, team)]
            };
        }
    },

    head_to_head: {
        description: "Two teams' history at this event: matches as partners and as opponents, with results.",
        params: { teams: "two team numbers" },
        async run(ctx, args) {
            const teams = toTeamNumbers(ctx, args.teams || [args.team, args.other].filter(Boolean));
            if (teams.length < 2) return { facts: { error: "Give two team numbers that are at this event." } };
            const [a, b] = teams;
            const together = [];
            const against = [];
            ctx.matches.forEach((m) => {
                const s = sides(m);
                const aColor = s.red.includes(a) ? "red" : s.blue.includes(a) ? "blue" : null;
                const bColor = s.red.includes(b) ? "red" : s.blue.includes(b) ? "blue" : null;
                if (!aColor || !bColor) return;
                const res = outcome(m, aColor);
                if (aColor === bColor) together.push({ match: Data.matchLabel(m), alliance: aColor, result: res ? `${res.result} ${res.score}` : "upcoming" });
                else against.push({ match: Data.matchLabel(m), winner: res ? (res.result === "W" ? a : res.result === "L" ? b : "tie") : "upcoming", score: res ? `${a} ${res.score} ${b}` : undefined });
            });
            const aWins = against.filter((x) => x.winner === a).length;
            const bWins = against.filter((x) => x.winner === b).length;
            const total = totalMetric(ctx);
            return {
                facts: {
                    team_a: a, team_b: b,
                    [`${a}_avg_${total.label}`]: r1(avgOf(ctx, a, total)) ?? undefined,
                    [`${b}_avg_${total.label}`]: r1(avgOf(ctx, b, total)) ?? undefined,
                    matches_as_partners: together, matches_as_opponents: against,
                    head_to_head_record: `${a} ${aWins} - ${bWins} ${b}`,
                    never_met: !together.length && !against.length ? true : undefined
                },
                table: {
                    columns: [t("ai.col.match", "Match"), t("ai.col.relation", "Relation"), t("ai.col.result", "Result")],
                    rows: [
                        ...together.map((x) => [x.match, t("ai.h2h.partners", "Partners"), x.result]),
                        ...against.map((x) => [x.match, t("ai.h2h.opponents", "Opponents"), x.winner === "upcoming" ? "upcoming" : x.winner === "tie" ? `tie (${x.score})` : `${x.winner} won (${x.score})`])
                    ]
                }
            };
        }
    },

    consistency: {
        description: "Rank teams by how consistent they are on a metric (standard deviation and coefficient of variation across scouted matches).",
        params: { metric: "metric name (default total points)", n: "how many teams (default 10)", order: "'most' consistent first (default) or 'least'" },
        async run(ctx, args) {
            const metric = metricOrDefault(ctx, args.metric);
            const least = /^(least|asc|worst|desc_cv)$/i.test(String(args.order || ""));
            const rows = Data.rankTeams(ctx, metric, { minMatches: 2 })
                .filter((r) => r.avg !== null && r.n >= 2 && r.avg > 0)
                .map((r) => ({ ...r, cv: (r.stdev / r.avg) * 100 }))
                .sort((x, y) => (least ? y.cv - x.cv : x.cv - y.cv));
            if (!rows.length) return { facts: { metric: metric.label, error: `Not enough scouted matches (2+ per team) to measure consistency on ${metric.label}.` } };
            const n = Math.max(1, Math.min(rows.length, parseInt(args.n, 10) || 10));
            const top = rows.slice(0, n);
            return {
                facts: {
                    metric: metric.label, order: least ? "least consistent first" : "most consistent first", teams_measured: rows.length,
                    note: "Lower spread = more predictable. cv_pct is standard deviation as a percentage of the average.",
                    teams: top.map((r, i) => ({ rank: i + 1, team: r.teamNumber, name: r.name || undefined, avg: r1(r.avg), stdev: r1(r.stdev), cv_pct: r1(r.cv), min: r1(r.min), max: r1(r.max), matches: r.n }))
                },
                table: {
                    columns: ["#", t("ai.col.team", "Team"), t("ai.col.avg", "Avg"), t("ai.col.stdev", "Std dev"), "CV %", t("ai.col.min", "Min"), t("ai.col.max", "Max"), t("ai.col.n", "N")],
                    rows: top.map((r, i) => [i + 1, Data.teamLabel(ctx, r.teamNumber), r1(r.avg), r1(r.stdev), r1(r.cv), r1(r.min), r1(r.max), r.n])
                },
                chart: {
                    type: "bar", title: `${metric.label} - ${t("ai.card.consistency", "Consistency")} (${t("ai.col.stdev", "Std dev")})`,
                    x: top.map((r) => String(r.teamNumber)), xTitle: t("ai.col.team", "Team"), yTitle: t("ai.col.stdev", "Std dev"),
                    series: [{ name: t("ai.col.stdev", "Std dev"), y: top.map((r) => r1(r.stdev)) }]
                }
            };
        }
    },

    recent_form: {
        description: "Who is improving or slumping: each team's average over its last few matches compared with its overall average.",
        params: { metric: "metric name (default total points)", last: "how many recent matches (default 3)", n: "how many teams (default 10)", teams: "optional team numbers", order: "'hot' improving first (default) or 'cold'" },
        async run(ctx, args) {
            const metric = metricOrDefault(ctx, args.metric);
            const last = Math.max(1, Math.min(10, parseInt(args.last, 10) || 3));
            const only = args.teams || args.team ? toTeamNumbers(ctx, args.teams || args.team) : null;
            const cold = /^(cold|asc|worst|declining|slump)$/i.test(String(args.order || ""));
            const rows = [];
            ctx.stats.forEach((s) => {
                if (only && !only.includes(s.teamNumber)) return;
                const vals = (s.perMatch || []).map((p) => p.values && p.values[metric.id]).filter((v) => v !== null && v !== undefined && Number.isFinite(v));
                if (vals.length < last + 1) return;
                const overall = mean(vals);
                const recent = mean(vals.slice(-last));
                rows.push({ team: s.teamNumber, name: s.name, overall, recent, change: recent - overall, matches: vals.length });
            });
            if (!rows.length) return { facts: { metric: metric.label, error: `Not enough matches (more than ${last} per team) to compare recent form on ${metric.label}.` } };
            rows.sort((a, b) => (cold ? a.change - b.change : b.change - a.change));
            const n = only ? rows.length : Math.max(1, Math.min(rows.length, parseInt(args.n, 10) || 10));
            const top = rows.slice(0, n);
            return {
                facts: {
                    metric: metric.label, recent_matches: last, order: cold ? "biggest drop first" : "biggest improvement first", teams_measured: rows.length,
                    teams: top.map((r) => ({ team: r.team, name: r.name || undefined, overall_avg: r1(r.overall), [`last_${last}_avg`]: r1(r.recent), change: r1(r.change), matches: r.matches }))
                },
                table: {
                    columns: [t("ai.col.team", "Team"), `${metric.label} (${t("ai.col.avg", "Avg")})`, `${t("ai.col.last", "Last")} ${last}`, t("ai.col.change", "Change"), t("ai.col.n", "N")],
                    rows: top.map((r) => [Data.teamLabel(ctx, r.team), r1(r.overall), r1(r.recent), `${r.change >= 0 ? "+" : ""}${r1(r.change)}`, r.matches])
                },
                chart: {
                    type: "groupedBar", title: `${metric.label} - ${t("ai.card.recent_form", "Recent form")}`,
                    x: top.map((r) => String(r.team)), xTitle: t("ai.col.team", "Team"), yTitle: metric.label,
                    series: [
                        { name: t("ai.col.overall", "Overall"), y: top.map((r) => r1(r.overall)) },
                        { name: `${t("ai.col.last", "Last")} ${last}`, y: top.map((r) => r1(r.recent)) }
                    ]
                }
            };
        }
    },

    alliance_builder: {
        description: "How strong a hypothetical alliance of 2-3 teams would be: expected points by phase, optionally against an opposing alliance with a win chance.",
        params: { teams: "2-3 team numbers for the alliance (our team is added if 'we/us' is meant)", opponents: "optional 1-3 opposing team numbers" },
        async run(ctx, args) {
            const teams = toTeamNumbers(ctx, args.teams).slice(0, 3);
            const opponents = toTeamNumbers(ctx, args.opponents || []).filter((x) => !teams.includes(x)).slice(0, 3);
            if (!teams.length) return { facts: { error: "Give the team numbers for the alliance." } };
            const phases = phaseMetrics(ctx);
            const total = totalMetric(ctx);
            const describe = (list) => list.map((team) => ({
                team, name: Data.teamName(ctx, team) || undefined,
                expected_points: r1(strengthOf(ctx, team)),
                ...Object.fromEntries(phases.map((m) => [m.label, r1(avgOf(ctx, team, m))]))
            }));
            const alliance = describe(teams);
            const sum = (list, key) => r1(list.reduce((acc, x) => acc + (x[key] || 0), 0));
            const facts = {
                alliance, alliance_expected_total: sum(alliance, "expected_points"),
                alliance_by_phase: Object.fromEntries(phases.map((m) => [m.label, sum(alliance, m.label)])),
                basis: `${total.label} averages (EPA or xP when a team has no scouting)`
            };
            if (phases.length) {
                const weakest = phases.slice().sort((a, b) => facts.alliance_by_phase[a.label] - facts.alliance_by_phase[b.label])[0];
                facts.weakest_phase = weakest.label;
            }
            const rows = alliance.map((r) => [t("ai.col.alliance", "Alliance"), Data.teamLabel(ctx, r.team), ...phases.map((m) => r[m.label] ?? "-"), r.expected_points]);
            if (opponents.length) {
                const opp = describe(opponents);
                facts.opponents = opp;
                facts.opponents_expected_total = sum(opp, "expected_points");
                facts.alliance_win_chance = `${winProbability(facts.alliance_expected_total, facts.opponents_expected_total)}%`;
                opp.forEach((r) => rows.push([t("ai.col.opponents", "Opponents"), Data.teamLabel(ctx, r.team), ...phases.map((m) => r[m.label] ?? "-"), r.expected_points]));
            }
            const chartTeams = [...alliance, ...(facts.opponents || [])];
            return {
                facts,
                table: { columns: [t("ai.col.side", "Side"), t("ai.col.team", "Team"), ...phases.map((m) => m.label), total.label], rows },
                chart: phases.length ? {
                    type: "stackedBar", title: `${t("ai.card.alliance_builder", "Alliance builder")} - ${teams.join(", ")}${opponents.length ? ` vs ${opponents.join(", ")}` : ""}`,
                    x: chartTeams.map((r) => String(r.team)), xTitle: t("ai.col.team", "Team"), yTitle: total.label,
                    series: phases.map((m) => ({ name: m.label, y: chartTeams.map((r) => r[m.label] || 0) }))
                } : null
            };
        }
    },

    metric_summary: {
        description: "Event-wide statistics for one metric: mean, median, spread, quartiles, best and worst team, plus a histogram. Optionally where one team sits.",
        params: { metric: "metric name", team: "optional team number to place in the distribution" },
        async run(ctx, args) {
            const metric = metricOrDefault(ctx, args.metric);
            const rows = Data.rankTeams(ctx, metric, { minMatches: metric.kind === "external" || metric.kind === "qual" ? 0 : 1 }).filter((r) => r.avg !== null);
            if (!rows.length) return { facts: { metric: metric.label, error: `No team has ${metric.label} data yet.` } };
            const vals = rows.map((r) => r.avg).sort((a, b) => a - b);
            const q = (p) => { const i = (vals.length - 1) * p; const lo = Math.floor(i); return vals[lo] + (vals[Math.ceil(i)] - vals[lo]) * (i - lo); };
            const avg = mean(vals);
            const sd = Math.sqrt(mean(vals.map((v) => (v - avg) ** 2)));
            const facts = {
                metric: metric.label, teams_with_data: vals.length, mean: r1(avg), median: r1(q(0.5)), stdev: r1(sd),
                q1: r1(q(0.25)), q3: r1(q(0.75)), min: r1(vals[0]), max: r1(vals[vals.length - 1]),
                best_team: rows[0].teamNumber, worst_team: rows[rows.length - 1].teamNumber
            };
            const [team] = args.team ? toTeamNumbers(ctx, args.team) : [];
            if (team) {
                const idx = rows.findIndex((r) => r.teamNumber === team);
                if (idx >= 0) facts.team = { team, value: r1(rows[idx].avg), rank: `${idx + 1} of ${rows.length}`, above_mean_by: r1(rows[idx].avg - avg) };
            }
            const bins = Math.min(8, Math.max(3, Math.round(Math.sqrt(vals.length))));
            const lo = vals[0];
            const width = (vals[vals.length - 1] - lo) / bins || 1;
            const counts = new Array(bins).fill(0);
            vals.forEach((v) => { counts[Math.min(bins - 1, Math.floor((v - lo) / width))]++; });
            const labels = counts.map((_, i) => `${r1(lo + i * width)}-${r1(lo + (i + 1) * width)}`);
            return {
                facts,
                table: {
                    columns: [t("ai.col.statistic", "Statistic"), metric.label],
                    rows: [["Teams", vals.length], ["Mean", facts.mean], ["Median", facts.median], [t("ai.col.stdev", "Std dev"), facts.stdev],
                        ["Q1 (25%)", facts.q1], ["Q3 (75%)", facts.q3], [t("ai.col.min", "Min"), `${facts.min} (${facts.worst_team})`], [t("ai.col.max", "Max"), `${facts.max} (${facts.best_team})`],
                        ...(facts.team ? [[`${t("ai.col.team", "Team")} ${team}`, `${facts.team.value} (#${facts.team.rank})`]] : [])]
                },
                chart: {
                    type: "bar", title: `${metric.label} - ${t("ai.chart.histogram", "how many teams average in each range")}`,
                    x: labels, xTitle: metric.label, yTitle: t("ai.chart.teams", "Teams"),
                    series: [{ name: t("ai.chart.teams", "Teams"), y: counts }]
                }
            };
        }
    },

    team_percentiles: {
        description: "Where one team ranks on every metric (rank and percentile versus the event), with its strongest and weakest areas.",
        params: { team: "team number (omit for our team)" },
        async run(ctx, args) {
            const team = teamOrOurs(ctx, args.team);
            if (!team) return { facts: { error: args.team ? `Team ${args.team} has no data at this event.` : "Give a team number." } };
            const out = [];
            ctx.metrics.forEach((metric) => {
                const ranked = Data.rankTeams(ctx, metric, { minMatches: metric.kind === "external" || metric.kind === "qual" ? 0 : 1 }).filter((r) => r.avg !== null);
                const idx = ranked.findIndex((r) => r.teamNumber === team);
                if (idx < 0 || ranked.length < 2) return;
                out.push({ metric: metric.label, value: r1(ranked[idx].avg), rank: `${idx + 1} of ${ranked.length}`, percentile: Math.round(((ranked.length - 1 - idx) / (ranked.length - 1)) * 100) });
            });
            if (!out.length) return { facts: { team, error: `Team ${team} has no ranked metrics yet.` } };
            const sorted = out.slice().sort((a, b) => b.percentile - a.percentile);
            const shown = out.slice(0, 14);
            return {
                facts: {
                    team, name: Data.teamName(ctx, team) || undefined, metrics: out,
                    strongest: sorted.slice(0, 3).map((m) => m.metric), weakest: sorted.slice(-3).reverse().map((m) => m.metric),
                    note: "Percentile 100 = best at the event, 0 = lowest."
                },
                table: {
                    columns: [t("ai.col.metric", "Metric"), t("ai.col.avg", "Avg"), t("ai.col.rank", "Rank"), t("ai.col.percentile", "Percentile")],
                    rows: out.map((m) => [m.metric, m.value, m.rank, m.percentile])
                },
                chart: {
                    type: "bar", title: `${Data.teamLabel(ctx, team)} - ${t("ai.col.percentile", "Percentile")}`,
                    x: shown.map((m) => m.metric), yTitle: t("ai.col.percentile", "Percentile"),
                    series: [{ name: t("ai.col.percentile", "Percentile"), y: shown.map((m) => m.percentile) }]
                },
                links: [teamLink(ctx, team)]
            };
        }
    },

    search_notes: {
        description: "Search every scout note and comment for words (e.g. 'defense', 'tipped', 'climb') and list which teams they were written about.",
        params: { query: "words to look for", team: "optional team number to limit the search" },
        async run(ctx, args) {
            const terms = keywords(args.query || args.topic);
            if (!terms.length) return { facts: { error: "Say which words to look for in the notes." } };
            const only = args.team ? toTeamNumbers(ctx, args.team) : null;
            const hits = [];
            ctx.stats.forEach((s) => {
                if (only && !only.includes(s.teamNumber)) return;
                Data.teamNotes(ctx, s.teamNumber).forEach((note) => {
                    const lower = note.text.toLowerCase();
                    const matched = terms.filter((w) => lower.includes(w));
                    if (matched.length) hits.push({ team: s.teamNumber, match: note.match || "-", text: note.text, matched: matched.length });
                });
            });
            hits.sort((a, b) => b.matched - a.matched || a.team - b.team);
            const byTeam = new Map();
            hits.forEach((h) => byTeam.set(h.team, (byTeam.get(h.team) || 0) + 1));
            return {
                facts: {
                    query: terms.join(" "), matching_notes: hits.length, teams_mentioned: byTeam.size,
                    teams: Array.from(byTeam.entries()).sort((a, b) => b[1] - a[1]).map(([team, notes]) => ({ team, notes })),
                    examples: hits.slice(0, 12).map((h) => ({ team: h.team, match: h.match, note: snippet(h.text, terms, 160) }))
                },
                table: {
                    columns: [t("ai.col.team", "Team"), t("ai.col.match", "Match"), t("ai.col.note", "Note")],
                    rows: hits.slice(0, 80).map((h) => [Data.teamLabel(ctx, h.team), h.match, snippet(h.text, terms)])
                }
            };
        }
    },

    pit_search: {
        description: "Search pit scouting answers (drivetrain, mechanisms, weight, etc.) across teams, or list one team's pit data.",
        params: { query: "words to look for (e.g. 'swerve')", team: "optional team number" },
        async run(ctx, args) {
            const terms = keywords(args.query || args.topic);
            const only = args.team ? toTeamNumbers(ctx, args.team) : null;
            if (!terms.length && !only) return { facts: { error: "Say what to look for in pit data, or give a team number." } };
            const rows = [];
            ctx.stats.forEach((s) => {
                if (only && !only.includes(s.teamNumber)) return;
                Data.pitHighlights(ctx, s.teamNumber, 40).forEach((line) => {
                    const lower = line.toLowerCase();
                    if (terms.length && !terms.some((w) => lower.includes(w))) return;
                    const cut = line.indexOf(": ");
                    rows.push({ team: s.teamNumber, field: cut > 0 ? line.slice(0, cut) : "", value: cut > 0 ? line.slice(cut + 2) : line });
                });
            });
            const teams = Array.from(new Set(rows.map((r) => r.team)));
            const pitTeams = new Set(ctx.pitEntries.map((e) => Number(e.targetTeamNumber)));
            return {
                facts: {
                    query: terms.join(" ") || undefined, team: only ? only[0] : undefined, matching_answers: rows.length, teams_matching: teams,
                    teams_with_pit_data: pitTeams.size,
                    answers: rows.slice(0, 20)
                },
                table: {
                    columns: [t("ai.col.team", "Team"), t("ai.col.field", "Question"), t("ai.col.value", "Answer")],
                    rows: rows.slice(0, 120).map((r) => [Data.teamLabel(ctx, r.team), r.field || "-", r.value])
                }
            };
        }
    },

    list_metrics: {
        description: "List every metric the assistant can rank, chart or compare (scouted points, counters, rates, EPA / OPR / xP, qualitative ratings) and how many teams have data.",
        params: {},
        async run(ctx) {
            const kindName = { score: "scouted points", numeric: "scouted count", rate: "scouted yes/no rate (%)", selectPoints: "scouted choice points", external: "external rating", qual: "qualitative rating" };
            const rows = ctx.metrics.map((m) => {
                let teams = 0;
                ctx.stats.forEach((s) => { if (s.metrics[m.id] && s.metrics[m.id].avg !== null && s.metrics[m.id].avg !== undefined) teams++; });
                return { metric: m.label, kind: kindName[m.kind] || m.kind, teams_with_data: teams };
            });
            return {
                facts: { metric_count: rows.length, metrics: rows },
                table: { columns: [t("ai.col.metric", "Metric"), t("ai.col.kind", "Kind"), t("ai.chart.teams", "Teams")], rows: rows.map((r) => [r.metric, r.kind, r.teams_with_data]) }
            };
        }
    },

    event_summary: {
        description: "Event at a glance: team and match counts, scouting coverage, top teams, and our team's rank and next match.",
        params: {},
        async run(ctx) {
            const played = ctx.matches.filter(isPlayed);
            const total = totalMetric(ctx);
            const top = Data.rankTeams(ctx, total).filter((r) => r.avg !== null).slice(0, 3);
            const reports = ctx.entries.filter((e) => !e._prescout).length;
            const teamMatches = played.reduce((n, m) => { const s = sides(m); return n + s.red.length + s.blue.length; }, 0);
            const facts = {
                event: ctx.eventKey || undefined, teams: ctx.stats.size, matches_scheduled: ctx.matches.length, matches_played: played.length,
                matches_upcoming: ctx.matches.length - played.length, scouting_reports: reports,
                scouting_coverage_pct: teamMatches ? Math.min(100, Math.round((reports / teamMatches) * 100)) : undefined,
                [`top_by_${total.label}`]: top.map((r) => ({ team: r.teamNumber, avg: r1(r.avg) }))
            };
            if (ctx.ourTeam && ctx.stats.has(ctx.ourTeam)) {
                const ranked = Data.rankTeams(ctx, total).filter((r) => r.avg !== null);
                const idx = ranked.findIndex((r) => r.teamNumber === ctx.ourTeam);
                const next = Data.findMatch(ctx, "next");
                facts.our_team = { team: ctx.ourTeam, rank_by_scouted_total: idx >= 0 ? `${idx + 1} of ${ranked.length}` : undefined, next_match: next ? Data.matchLabel(next) : undefined };
            }
            return {
                facts,
                table: {
                    columns: [t("ai.col.statistic", "Statistic"), t("ai.col.value", "Value")],
                    rows: [
                        [t("ai.chart.teams", "Teams"), facts.teams],
                        [t("ai.summary.matches", "Matches played / scheduled"), `${facts.matches_played} / ${facts.matches_scheduled}`],
                        [t("ai.summary.reports", "Scouting reports"), `${reports}${facts.scouting_coverage_pct !== undefined ? ` (${facts.scouting_coverage_pct}%)` : ""}`],
                        ...top.map((r, i) => [`#${i + 1} ${total.label}`, `${Data.teamLabel(ctx, r.teamNumber)} - ${r1(r.avg)}`]),
                        ...(facts.our_team ? [[t("ai.summary.our_team", "Our team"), `${facts.our_team.rank_by_scouted_total ? `#${facts.our_team.rank_by_scouted_total}` : "-"}${facts.our_team.next_match ? ` · ${facts.our_team.next_match}` : ""}`]] : [])
                    ]
                }
            };
        }
    },

    scouting_coverage: {
        description: "Which teams and matches are missing scouting reports: played vs scouted matches per team, plus played matches with gaps.",
        params: { n: "optional number of teams to list" },
        async run(ctx, args) {
            const scouted = new Set();
            ctx.entries.filter((e) => !e._prescout && e.matchNumber).forEach((e) => scouted.add(`${Number(e.targetTeamNumber)}:${e.matchNumber}`));
            const perTeam = new Map();
            let gaps = 0;
            ctx.matches.filter((m) => isPlayed(m) && (m.compLevel || "qm") === "qm").forEach((m) => {
                const s = sides(m);
                let missingHere = 0;
                [...s.red, ...s.blue].forEach((team) => {
                    const row = perTeam.get(team) || { team, played: 0, scouted: 0, missing: [] };
                    row.played++;
                    if (scouted.has(`${team}:${m.matchNumber}`)) row.scouted++;
                    else { row.missing.push(Data.matchLabel(m)); missingHere++; }
                    perTeam.set(team, row);
                });
                if (missingHere) gaps++;
            });
            if (!perTeam.size) return { facts: { error: "No played qualification matches yet, so there is nothing to check." } };
            const rows = Array.from(perTeam.values()).sort((a, b) => b.missing.length - a.missing.length || a.team - b.team);
            const playedSlots = rows.reduce((n, r) => n + r.played, 0);
            const scoutedSlots = rows.reduce((n, r) => n + r.scouted, 0);
            const n = args.n ? Math.max(1, parseInt(args.n, 10) || rows.length) : rows.length;
            return {
                facts: {
                    played_team_matches: playedSlots, scouted_team_matches: scoutedSlots,
                    coverage_pct: Math.round((scoutedSlots / playedSlots) * 100), matches_with_missing_reports: gaps,
                    teams_fully_scouted: rows.filter((r) => !r.missing.length).length,
                    teams_missing_reports: rows.filter((r) => r.missing.length).map((r) => ({ team: r.team, missing: r.missing.length }))
                },
                table: {
                    columns: [t("ai.col.team", "Team"), t("ai.col.played", "Played"), t("ai.col.scouted", "Scouted"), t("ai.col.missing", "Missing")],
                    rows: rows.slice(0, n).map((r) => [Data.teamLabel(ctx, r.team), r.played, r.scouted, r.missing.length ? r.missing.join(", ") : "-"])
                }
            };
        }
    }
};
