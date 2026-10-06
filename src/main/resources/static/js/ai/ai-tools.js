/**
 * Local AI assistant tools + orchestration - ObsidianScout
 *
 * The model never computes statistics. Questions are answered by:
 *   1. a deterministic rule router (handles most common questions instantly), else
 *   2. the model picking a read-only tool (JSON; grammar-constrained on WebLLM tiers), possibly several (Advanced),
 *   3. JavaScript executing the tool against ai-data.js (tables + charts are built by code),
 *   4. the model writing a short answer grounded only in the tool results,
 *   5. a guardrail flagging any number in the answer that does not appear in the tool results.
 */

import AI from "./local-ai.js";
import UI, { parseThoughtAndContent } from "./ai-ui.js";
import Data from "./ai-data.js";
import Features from "./ai-features.js";

const LANGUAGE_NAMES = { en: "English", es: "Spanish", he: "Hebrew", tr: "Turkish" };

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function currentLanguage() {
    let lang = "en";
    try { lang = localStorage.getItem("obsidianscout:lang") || "en"; } catch (_) { /* ignore */ }
    return LANGUAGE_NAMES[lang] || "English";
}

const r1 = (v) => Data.round(v, 1);

function metricOrDefault(ctx, phrase, fallbackId = "score_total") {
    return Data.findMetric(ctx, phrase, { minScore: 1 }) || ctx.metrics.find((m) => m.id === fallbackId) || ctx.metrics[0];
}

function toTeamNumbers(ctx, value) {
    const list = Array.isArray(value) ? value : (value === undefined || value === null ? [] : [value]);
    return Array.from(new Set(list.map((v) => parseInt(String(v).replace(/^frc/i, ""), 10)).filter((n) => Number.isFinite(n) && ctx.stats.has(n))));
}

function unknownTeams(ctx, value) {
    const list = Array.isArray(value) ? value : (value === undefined || value === null ? [] : [value]);
    return list.map((v) => parseInt(String(v).replace(/^frc/i, ""), 10)).filter((n) => Number.isFinite(n) && !ctx.stats.has(n));
}

// ------------------------------------------------------------------ tools

export const TOOLS = {
    team_overview: {
        description: "Key stats, pit info, match counts (played vs scouted vs scheduled), and notes summary for one team.",
        params: { team: "team number", chart: "true to include visual chart" },
        async run(ctx, args) {
            const [team] = toTeamNumbers(ctx, args.team);
            if (!team || !ctx.stats.has(team)) {
                return TOOLS.projected_rankings.run(ctx, { team: args.team, chart: args.chart });
            }
            const s = ctx.stats.get(team);
            const ranked = Data.rankTeams(ctx, ctx.metrics[0]);
            const rank = ranked.findIndex((r) => r.teamNumber === team) + 1;
            const played = s.matchesPlayed ?? s.matchesScouted;
            const scouted = s.matchesScouted ?? 0;
            const sched = s.matchesScheduled ?? 0;
            const shown = ctx.metrics.filter((m) => s.metrics[m.id] && s.metrics[m.id].avg !== null && s.metrics[m.id].avg !== undefined);
            const epaN = s.metrics["ext:epa"]?.n;
            const expN = s.metrics["ext:exp"]?.n;
            const seasonN = epaN || expN || undefined;

            const facts = {
                team,
                name: s.name || undefined,
                matches_played_at_event: played,
                matches_scheduled_at_event: sched,
                matches_scouted_by_our_team: scouted,
                season_matches_in_external_database: seasonN,
                scouting_status: scouted === 0
                    ? `Team has played ${played} match${played === 1 ? "" : "es"} at this event (${sched > 0 ? `out of ${sched} scheduled` : "on schedule"}), but 0 match reports have been locally submitted by our scouting team yet.${seasonN ? ` Their EPA/OPR/xP statistics are derived from ${seasonN} season matches.` : ""}`
                    : `Team has ${scouted} locally scouted match${scouted === 1 ? "" : "es"} out of ${played} matches played at the event (${sched} scheduled).`,
                prescout_data_only: s.prescoutOnly || undefined,
                rank_by_total_points: rank ? `${rank} of ${ranked.length}` : undefined,
                averages: Object.fromEntries(shown.map((m) => [m.label, r1(s.metrics[m.id].avg)])),
                best_match_total_points: s.metrics.score_total ? r1(s.metrics.score_total.max) : undefined,
                consistency_stdev_total_points: s.metrics.score_total ? r1(s.metrics.score_total.stdev) : undefined,
                pit: Data.pitHighlights(ctx, team, 6)
            };
            const summary = await Features.getCachedSummary(team, ctx.eventKey);
            if (summary) facts.scout_notes_summary = summary.text.slice(0, 900);
            const chart = (args.chart && s.perMatch.length > 1) ? {
                type: "line",
                title: `${Data.teamLabel(ctx, team)} - ${ctx.metrics[0].label}`,
                xTitle: t("ai.chart.match", "Match"),
                yTitle: ctx.metrics[0].label,
                x: s.perMatch.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?"),
                series: [{ name: String(team), y: s.perMatch.map((p) => r1(p.values.score_total)) }]
            } : null;
            return {
                facts,
                table: {
                    columns: [t("ai.col.metric", "Metric"), t("ai.col.avg", "Avg"), t("ai.col.max", "Max"), t("ai.col.n", "N")],
                    rows: shown.map((m) => [m.label, r1(s.metrics[m.id].avg), r1(s.metrics[m.id].max), s.metrics[m.id].n])
                },
                chart,
                links: [{ label: fmtTeamPage(team), href: `/team?teamNumber=${team}${ctx.eventKey ? `&eventKey=${encodeURIComponent(ctx.eventKey)}` : ""}` }]
            };
        }
    },

    top_teams: {
        description: "Rank teams by a metric (best first, or worst first with order=asc). Returns all event teams unless count n is specified.",
        params: { metric: "metric name", n: "how many teams to return (default is all teams at event)", order: "desc or asc", chart: "true to include visual chart", chart_type: "bar or line" },
        async run(ctx, args) {
            const metric = metricOrDefault(ctx, args.metric);
            const allTeams = Data.rankTeams(ctx, metric, { order: args.order === "asc" ? "asc" : "desc" });
            const n = args.n ? Math.max(1, Math.min(allTeams.length, parseInt(args.n, 10))) : allTeams.length;
            const rows = allTeams.slice(0, n);
            const chartType = args.chart_type === "line" ? "line" : "bar";
            const vals = rows.map((r) => r.avg).filter((v) => v !== null && v !== undefined);
            const meanVal = vals.length ? r1(vals.reduce((a, b) => a + b, 0) / vals.length) : null;
            return {
                facts: {
                    metric: metric.label,
                    order: args.order === "asc" ? "lowest first" : "highest first",
                    total_teams: allTeams.length,
                    showing_teams: rows.length,
                    average: meanVal,
                    teams: rows.map((r, i) => ({ rank: i + 1, team: r.teamNumber, name: r.name || undefined, avg: r1(r.avg), max: r1(r.max), matches: r.n }))
                },
                table: {
                    columns: ["#", t("ai.col.team", "Team"), `${metric.label} (${t("ai.col.avg", "Avg")})`, t("ai.col.max", "Max"), t("ai.col.n", "N")],
                    rows: rows.map((r, i) => [i + 1, Data.teamLabel(ctx, r.teamNumber), r1(r.avg), r1(r.max), r.n])
                },
                chart: (args.chart && rows.length) ? {
                    type: chartType,
                    title: `${metric.label} - ${args.n ? `top ${rows.length} teams` : "all teams"}`,
                    x: rows.map((r) => String(r.teamNumber)),
                    xTitle: t("ai.col.team", "Team"),
                    yTitle: metric.label,
                    series: [{ name: metric.label, y: rows.map((r) => r1(r.avg)) }]
                } : null
            };
        }
    },

    compare_metrics: {
        description: "Compare two metrics across teams (e.g. EPA vs xP, Auto vs Teleop) with computed mean, average difference, and team breakdown.",
        params: { metric_1: "first metric name (e.g. EPA)", metric_2: "second metric name (e.g. xP)", n: "optional team count (default all)", chart: "true to include visual chart" },
        async run(ctx, args) {
            const m1 = metricOrDefault(ctx, args.metric_1, "score_total");
            let m2 = args.metric_2 ? Data.findMetric(ctx, args.metric_2) : null;
            if (!m2 || m2.id === m1.id) {
                m2 = ctx.metrics.find((m) => m.id !== m1.id && (m.id === "score_auto" || m.id === "score_teleop" || m.kind === "score" || m.kind === "external")) || ctx.metrics[1] || ctx.metrics[0];
            }

            const items = [];
            ctx.stats.forEach((s, team) => {
                const v1 = s.metrics[m1.id]?.avg;
                const v2 = s.metrics[m2.id]?.avg;
                if (v1 !== undefined && v1 !== null && v2 !== undefined && v2 !== null) {
                    items.push({
                        team,
                        name: s.name,
                        v1: r1(v1),
                        v2: r1(v2),
                        diff: r1(v1 - v2),
                        absDiff: r1(Math.abs(v1 - v2))
                    });
                }
            });

            if (!items.length) return { facts: { error: `No teams have data for both ${m1.label} and ${m2.label}.` } };

            items.sort((a, b) => (b.v1 + b.v2) - (a.v1 + a.v2));
            const count = args.n ? Math.max(1, Math.min(items.length, parseInt(args.n, 10))) : items.length;
            const chosen = items.slice(0, count);

            const avg1 = r1(chosen.reduce((a, b) => a + b.v1, 0) / chosen.length);
            const avg2 = r1(chosen.reduce((a, b) => a + b.v2, 0) / chosen.length);
            const avgDiff = r1(chosen.reduce((a, b) => a + b.diff, 0) / chosen.length);
            const avgAbsDiff = r1(chosen.reduce((a, b) => a + b.absDiff, 0) / chosen.length);

            return {
                facts: {
                    metric_1: m1.label,
                    metric_2: m2.label,
                    teams_compared: chosen.length,
                    average_metric_1: avg1,
                    average_metric_2: avg2,
                    average_difference: avgDiff,
                    average_absolute_difference: avgAbsDiff,
                    summary: `Across ${chosen.length} teams, ${m1.label} averages ${avg1} and ${m2.label} averages ${avg2}, with an average points difference of ${avgDiff} (absolute difference: ${avgAbsDiff}).`,
                    teams: chosen.map((p) => ({ team: p.team, name: p.name, [m1.label]: p.v1, [m2.label]: p.v2, difference: p.diff }))
                },
                table: {
                    columns: [t("ai.col.team", "Team"), m1.label, m2.label, "Difference"],
                    rows: chosen.map((p) => [Data.teamLabel(ctx, p.team), p.v1, p.v2, p.diff])
                },
                chart: (args.chart !== false) ? {
                    type: "scatter",
                    title: `${m1.label} vs ${m2.label}`,
                    xTitle: m1.label,
                    yTitle: m2.label,
                    points: chosen.map((p) => ({ team: p.team, name: Data.teamLabel(ctx, p.team), x: p.v1, y: p.v2 })),
                    series: [{
                        name: "Teams",
                        text: chosen.map((p) => Data.teamLabel(ctx, p.team)),
                        x: chosen.map((p) => p.v1),
                        y: chosen.map((p) => p.v2)
                    }]
                } : null
            };
        }
    },

    team_matches: {
        description: "List every scouted/scheduled match individually for a team with actual per-match scores (Total, Auto, Teleop, Endgame, EPA, xP) and notes (NOT averages).",
        params: { team: "team number", metric: "optional metric name (e.g. Total points, EPA, xP, Auto)", chart: "true to include visual match-by-match chart" },
        async run(ctx, args) {
            const [team] = toTeamNumbers(ctx, args.team);
            if (!team || !ctx.stats.has(team)) return { facts: { error: `Team ${args.team} has no match data.` } };
            const s = ctx.stats.get(team);
            const matches = s.perMatch || [];
            if (!matches.length) return { facts: { error: `No individual match entries logged for Team ${team}.` } };

            const targetMetric = args.metric ? Data.findMetric(ctx, args.metric, { minScore: 1 }) : null;
            const autoM = ctx.metrics.find((m) => m.id === "score_auto") || { id: "score_auto", label: "Auto" };
            const teleM = ctx.metrics.find((m) => m.id === "score_teleop") || { id: "score_teleop", label: "Teleop" };
            const endM = ctx.metrics.find((m) => m.id === "score_endgame") || { id: "score_endgame", label: "Endgame" };
            const totM = ctx.metrics.find((m) => m.id === "score_total") || { id: "score_total", label: "Total points" };

            let chart;
            let columns;
            let rows;

            if (targetMetric && targetMetric.id !== "score_total") {
                const metricId = targetMetric.id;
                const metricLabel = targetMetric.label;
                rows = matches.map((p) => ({
                    match: p.matchNumber ? `Q${p.matchNumber}` : (p.matchKey || "?"),
                    value: p.values && p.values[metricId] !== undefined && p.values[metricId] !== null ? r1(p.values[metricId]) : "-",
                    notes: p.notes || undefined
                }));

                const validPoints = rows.filter((r) => r.value !== "-");
                chart = {
                    type: "line",
                    title: `Team ${team} - ${metricLabel} Match-by-Match`,
                    xTitle: t("ai.chart.match", "Match"),
                    yTitle: metricLabel,
                    series: [
                        { name: metricLabel, y: validPoints.map((r) => r.value), x: validPoints.map((r) => r.match) }
                    ]
                };
                columns = [t("ai.col.match", "Match"), metricLabel];
                rows = rows.map((m) => [m.match, m.value]);
            } else {
                const epaM = ctx.metrics.find((m) => m.id === "ext:epa");
                const expM = ctx.metrics.find((m) => m.id === "ext:exp");
                const hasEpa = matches.some((p) => p.values && p.values["ext:epa"] !== undefined && p.values["ext:epa"] !== null);
                const hasExp = matches.some((p) => p.values && p.values["ext:exp"] !== undefined && p.values["ext:exp"] !== null);

                rows = matches.map((p) => ({
                    match: p.matchNumber ? `Q${p.matchNumber}` : (p.matchKey || "?"),
                    total: p.values && p.values.score_total !== undefined && p.values.score_total !== null ? r1(p.values.score_total) : "-",
                    auto: p.values && p.values[autoM.id] !== undefined && p.values[autoM.id] !== null ? r1(p.values[autoM.id]) : "-",
                    teleop: p.values && p.values[teleM.id] !== undefined && p.values[teleM.id] !== null ? r1(p.values[teleM.id]) : "-",
                    endgame: p.values && p.values[endM.id] !== undefined && p.values[endM.id] !== null ? r1(p.values[endM.id]) : "-",
                    epa: hasEpa ? (p.values && p.values["ext:epa"] !== undefined && p.values["ext:epa"] !== null ? r1(p.values["ext:epa"]) : "-") : undefined,
                    exp: hasExp ? (p.values && p.values["ext:exp"] !== undefined && p.values["ext:exp"] !== null ? r1(p.values["ext:exp"]) : "-") : undefined,
                    notes: p.notes || undefined
                }));

                const seriesList = [];
                const hasScouted = matches.some((p) => p.values && p.values.score_total !== undefined && p.values.score_total !== null);
                if (hasScouted) {
                    seriesList.push({ name: totM.label, y: matches.map((p) => p.values.score_total || 0), x: matches.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?") });
                    seriesList.push({ name: autoM.label, y: matches.map((p) => p.values[autoM.id] || 0), x: matches.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?") });
                    seriesList.push({ name: teleM.label, y: matches.map((p) => p.values[teleM.id] || 0), x: matches.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?") });
                }
                if (hasEpa) {
                    seriesList.push({ name: "EPA", y: matches.map((p) => p.values["ext:epa"] || 0), x: matches.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?") });
                }
                if (hasExp) {
                    seriesList.push({ name: "xP", y: matches.map((p) => p.values["ext:exp"] || 0), x: matches.map((p) => p.matchNumber ? `Q${p.matchNumber}` : "?") });
                }

                chart = {
                    type: "line",
                    title: `Team ${team} Individual Match Scores`,
                    xTitle: t("ai.chart.match", "Match"),
                    yTitle: "Points",
                    series: seriesList
                };

                columns = [t("ai.col.match", "Match"), totM.label, autoM.label, teleM.label, endM.label];
                if (hasEpa) columns.push("EPA");
                if (hasExp) columns.push("xP");
                rows = rows.map((m) => {
                    const row = [m.match, m.total, m.auto, m.teleop, m.endgame];
                    if (hasEpa) row.push(m.epa);
                    if (hasExp) row.push(m.exp);
                    return row;
                });
            }

            const played = s.matchesPlayed ?? matches.length;
            const scouted = s.matchesScouted ?? 0;
            const sched = s.matchesScheduled ?? matches.length;
            const seasonN = s.metrics["ext:epa"]?.n || s.metrics["ext:exp"]?.n || undefined;

            return {
                facts: {
                    team,
                    name: s.name || undefined,
                    matches_played_at_event: played,
                    matches_scheduled_at_event: sched,
                    matches_scouted_by_our_team: scouted,
                    season_matches_in_external_database: seasonN,
                    individual_matches: rows
                },
                table: {
                    columns,
                    rows
                },
                chart: args.chart !== false ? chart : null
            };
        }
    },

    compare_teams: {
        description: "Compare 2-6 teams side by side on several metrics.",
        params: { teams: "list of team numbers", metrics: "optional list of metric names", chart: "true to include visual chart" },
        async run(ctx, args) {
            const teams = toTeamNumbers(ctx, args.teams).slice(0, 6);
            const missing = unknownTeams(ctx, args.teams);
            if (teams.length < 1) return { facts: { error: "None of those teams have data at this event.", unknown_teams: missing } };
            let metrics = (Array.isArray(args.metrics) ? args.metrics : []).map((p) => Data.findMetric(ctx, p, { minScore: 1 })).filter(Boolean);
            if (!metrics.length) metrics = ctx.metrics.filter((m) => m.kind === "score");
            metrics = Array.from(new Set(metrics)).slice(0, 6);
            const facts = { teams: {}, unknown_teams: missing.length ? missing : undefined };
            teams.forEach((team) => {
                const s = ctx.stats.get(team);
                facts.teams[team] = { name: s ? s.name : undefined, matches: s ? s.matchesScouted : 0, ...Object.fromEntries(metrics.map((m) => [m.label, s && s.metrics[m.id] ? r1(s.metrics[m.id].avg) : null])) };
            });
            const chart = args.chart ? (metrics.length === 1 ? {
                type: "bar",
                title: `${metrics[0].label} - ${t("ai.chart.compare", "Team comparison")}`,
                x: teams.map((t) => String(t)),
                xTitle: t("ai.col.team", "Team"),
                yTitle: metrics[0].label,
                series: [{ name: metrics[0].label, y: teams.map((team) => { const v = ctx.stats.get(team)?.metrics[metrics[0].id]; return v ? r1(v.avg) : null; }) }]
            } : {
                type: "groupedBar",
                title: t("ai.chart.compare", "Team comparison (averages)"),
                x: metrics.map((m) => m.label),
                series: teams.map((team) => ({ name: String(team), y: metrics.map((m) => { const v = ctx.stats.get(team)?.metrics[m.id]; return v ? r1(v.avg) : null; }) }))
            }) : null;
            return {
                facts,
                table: {
                    columns: [t("ai.col.metric", "Metric"), ...teams.map(String)],
                    rows: metrics.map((m) => [m.label, ...teams.map((team) => { const v = ctx.stats.get(team)?.metrics[m.id]; return v ? r1(v.avg) : "-"; })])
                },
                chart
            };
        }
    },

    match_by_match: {
        description: "Plot and list match-by-match individual scores and progression line chart for 1 or more teams across all matches played (supports EPA, xP, Total points, Auto, Teleop).",
        params: { teams: "list of team numbers (optional)", metric: "metric name (e.g. Total points, EPA, xP, Auto, Teleop)", n: "number of teams if teams omitted (default 5)", chart: "true to generate line chart" },
        async run(ctx, args) {
            let teams = toTeamNumbers(ctx, args.teams);
            const count = args.n ? Math.max(1, parseInt(args.n, 10)) : 5;
            const metric = args.metric ? Data.findMetric(ctx, args.metric, { minScore: 1 }) : (ctx.metrics.find((m) => m.id === "score_total") || ctx.metrics[0]);
            const metricId = metric ? metric.id : "score_total";
            const metricLabel = metric ? metric.label : "Total points";

            if (!teams.length) {
                if (args.metric) {
                    teams = Data.rankTeams(ctx, metric || ctx.metrics[0]).slice(0, count).map((r) => r.teamNumber);
                } else {
                    const scouted = Array.from(ctx.stats.values())
                        .filter((s) => (s.perMatch && s.perMatch.length > 0) || s.matchesScouted > 0)
                        .sort((a, b) => ((b.metrics[metricId]?.avg || b.metrics.score_total?.avg) || 0) - ((a.metrics[metricId]?.avg || a.metrics.score_total?.avg) || 0))
                        .map((s) => s.teamNumber);
                    const ranked = Data.rankTeams(ctx, metric || ctx.metrics[0]).map((r) => r.teamNumber);
                    teams = Array.from(new Set([...scouted, ...ranked])).slice(0, count);
                }
            }

            if (teams.length === 1) {
                return TOOLS.team_matches.run(ctx, { team: teams[0], metric: args.metric ? metricLabel : undefined, chart: args.chart !== false });
            }

            const allMatchLabels = new Set();
            const series = [];
            const factsByTeam = {};

            teams.forEach((t) => {
                const s = ctx.stats.get(t);
                const matches = s ? (s.perMatch || []) : [];
                const points = matches.map((p) => {
                    const mNum = p.matchNumber ? `Q${p.matchNumber}` : (p.matchKey || `Match`);
                    allMatchLabels.add(mNum);
                    let val = p.values ? p.values[metricId] : null;
                    if (val === undefined || val === null) {
                        if (!args.metric || metricId === "score_total") {
                            val = p.values ? p.values.score_total : null;
                        }
                    }
                    return { match: mNum, value: (val !== null && val !== undefined && Number.isFinite(Number(val))) ? r1(val) : null };
                }).filter((p) => p.value !== null);

                factsByTeam[t] = {
                    name: s ? s.name : undefined,
                    matches: points
                };

                if (points.length > 0) {
                    series.push({
                        name: Data.teamLabel(ctx, t),
                        x: points.map((p) => p.match),
                        y: points.map((p) => p.value)
                    });
                }
            });

            // Sort matches numerically (Q1, Q2, Q3...)
            const sortedMatches = Array.from(allMatchLabels).sort((a, b) => {
                const numA = parseInt(a.replace(/\D/g, ""), 10) || 0;
                const numB = parseInt(b.replace(/\D/g, ""), 10) || 0;
                return numA - numB;
            });

            let chart = null;
            if (series.length > 0 && args.chart !== false) {
                chart = {
                    type: "line",
                    title: `${metricLabel} - ${t("ai.chart.by_match", "Match-by-Match Trend")}`,
                    xTitle: t("ai.chart.match", "Match"),
                    yTitle: metricLabel,
                    series
                };
            } else if (args.chart !== false && teams.length > 0) {
                chart = {
                    type: "line",
                    title: `${metricLabel} - ${t("ai.chart.by_match", "Match-by-Match Trend")}`,
                    xTitle: t("ai.chart.match", "Match"),
                    yTitle: metricLabel,
                    series: teams.map((t) => {
                        const s = ctx.stats.get(t);
                        const v = s?.metrics[metricId]?.avg;
                        return {
                            name: Data.teamLabel(ctx, t),
                            x: sortedMatches.length ? sortedMatches : ["All Matches"],
                            y: sortedMatches.length ? sortedMatches.map(() => r1(v) || 0) : [r1(v) || 0]
                        };
                    })
                };
            }

            const tableRows = sortedMatches.map((mLabel) => {
                const row = [mLabel];
                teams.forEach((t) => {
                    const s = ctx.stats.get(t);
                    const matchEntry = (s && s.perMatch || []).find((p) => (p.matchNumber ? `Q${p.matchNumber}` : (p.matchKey || "")) === mLabel);
                    let v = matchEntry && matchEntry.values ? matchEntry.values[metricId] : null;
                    if (v === undefined || v === null) {
                        if (!args.metric || metricId === "score_total") {
                            v = matchEntry && matchEntry.values ? matchEntry.values.score_total : null;
                        }
                    }
                    row.push(v !== undefined && v !== null ? r1(v) : "-");
                });
                return row;
            });

            const hasTableData = tableRows.length > 0;
            const columns = [t("ai.col.match", "Match"), ...teams.map((t) => Data.teamLabel(ctx, t))];
            const finalRows = hasTableData ? tableRows : teams.map((t) => {
                const s = ctx.stats.get(t);
                return [Data.teamLabel(ctx, t), s ? s.matchesScouted : 0, s?.metrics[metricId]?.avg ? r1(s.metrics[metricId].avg) : "-"];
            });
            const finalCols = hasTableData ? columns : [t("ai.col.team", "Team"), "Matches Scouted", `${metricLabel} (Avg)`];

            return {
                facts: {
                    metric: metricLabel,
                    teams_match_by_match: factsByTeam
                },
                table: {
                    columns: finalCols,
                    rows: finalRows
                },
                chart
            };
        }
    },

    metric_trend: {
        description: "How a metric changed match by match for 1-6 teams (line chart and match table).",
        params: { teams: "list of team numbers", metric: "metric name", n: "number of teams (default 5)" },
        async run(ctx, args) {
            return TOOLS.match_by_match.run(ctx, { ...args, chart: true });
        }
    },

    scatter: {
        description: "2D scatter plot correlating two metrics across all teams (e.g. Total points vs Auto, OPR vs EPA, xP vs Total points).",
        params: { metric_x: "X-axis metric name", metric_y: "Y-axis metric name" },
        async run(ctx, args) {
            const mx = metricOrDefault(ctx, args.metric_x, "score_total");
            let my = args.metric_y ? Data.findMetric(ctx, args.metric_y) : null;
            if (!my || my.id === mx.id) {
                my = ctx.metrics.find((m) => m.id !== mx.id && (m.id === "score_auto" || m.id === "score_teleop" || m.kind === "score" || m.kind === "external")) || ctx.metrics[1] || ctx.metrics[0];
            }

            const points = [];
            ctx.stats.forEach((s, team) => {
                const vx = s.metrics[mx.id]?.avg;
                const vy = s.metrics[my.id]?.avg;
                if (vx !== undefined && vx !== null && vy !== undefined && vy !== null) {
                    points.push({ team, name: s.name, x: r1(vx), y: r1(vy) });
                }
            });

            if (!points.length) return { facts: { error: `No teams have data for both ${mx.label} and ${my.label}.` } };

            points.sort((a, b) => (b.x + b.y) - (a.x + a.y));
            const meanX = r1(points.reduce((a, b) => a + b.x, 0) / points.length);
            const meanY = r1(points.reduce((a, b) => a + b.y, 0) / points.length);
            const meanDiff = r1(points.reduce((a, b) => a + (b.x - b.y), 0) / points.length);

            return {
                facts: {
                    x_metric: mx.label,
                    y_metric: my.label,
                    total_teams: points.length,
                    average_x: meanX,
                    average_y: meanY,
                    average_difference: meanDiff,
                    strongest_on_both: points.slice(0, 5)
                },
                table: {
                    columns: [t("ai.col.team", "Team"), mx.label, my.label],
                    rows: points.map((p) => [Data.teamLabel(ctx, p.team), p.x, p.y])
                },
                chart: {
                    type: "scatter",
                    title: `${mx.label} vs ${my.label} (Scatter Plot)`,
                    xTitle: mx.label,
                    yTitle: my.label,
                    points: points.map((p) => ({ team: p.team, name: Data.teamLabel(ctx, p.team), x: p.x, y: p.y })),
                    series: [{
                        name: "Teams",
                        text: points.map((p) => Data.teamLabel(ctx, p.team)),
                        x: points.map((p) => p.x),
                        y: points.map((p) => p.y)
                    }]
                }
            };
        }
    },

    stacked_breakdown: {
        description: "Stacked bar chart breaking down Auto, Teleop, and Endgame points for top or specified teams.",
        params: { teams: "optional list of team numbers", n: "how many teams (default 6)" },
        async run(ctx, args) {
            let teams = toTeamNumbers(ctx, args.teams).slice(0, 8);
            if (!teams.length) {
                teams = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, parseInt(args.n, 10) || 6).map((r) => r.teamNumber);
            }
            if (!teams.length) return { facts: { error: "No team scoring data found." } };

            const autoM = ctx.metrics.find((m) => m.id === "score_auto") || { id: "score_auto", label: "Auto" };
            const teleM = ctx.metrics.find((m) => m.id === "score_teleop") || { id: "score_teleop", label: "Teleop" };
            const endM = ctx.metrics.find((m) => m.id === "score_endgame") || { id: "score_endgame", label: "Endgame" };

            const facts = {
                teams: teams.map((t) => {
                    const s = ctx.stats.get(t);
                    return {
                        team: t,
                        auto: s?.metrics[autoM.id] ? r1(s.metrics[autoM.id].avg) : 0,
                        teleop: s?.metrics[teleM.id] ? r1(s.metrics[teleM.id].avg) : 0,
                        endgame: s?.metrics[endM.id] ? r1(s.metrics[endM.id].avg) : 0,
                        total: s?.metrics.score_total ? r1(s.metrics.score_total.avg) : 0
                    };
                })
            };

            return {
                facts,
                table: {
                    columns: [t("ai.col.team", "Team"), autoM.label, teleM.label, endM.label, t("ai.col.total", "Total")],
                    rows: facts.teams.map((f) => [Data.teamLabel(ctx, f.team), f.auto, f.teleop, f.endgame, f.total])
                },
                chart: {
                    type: "stackedBar",
                    title: "Phase Scoring Breakdown (Auto + Teleop + Endgame)",
                    x: teams.map(String),
                    xTitle: t("ai.col.team", "Team"),
                    yTitle: "Points",
                    series: [
                        { name: autoM.label, color: "#f59e0b", y: facts.teams.map((f) => f.auto) },
                        { name: teleM.label, color: "#3b82f6", y: facts.teams.map((f) => f.teleop) },
                        { name: endM.label, color: "#10b981", y: facts.teams.map((f) => f.endgame) }
                    ]
                }
            };
        }
    },

    team_radar: {
        description: "Multi-dimensional radar/spider chart comparing 1-4 teams across scoring phases.",
        params: { teams: "list of team numbers" },
        async run(ctx, args) {
            const teams = toTeamNumbers(ctx, args.teams).slice(0, 4);
            if (!teams.length) return { facts: { error: "Please specify at least one valid team number." } };

            const axes = [
                { id: "score_auto", label: "Auto" },
                { id: "score_teleop", label: "Teleop" },
                { id: "score_endgame", label: "Endgame" },
                { id: "score_total", label: "Total Points" }
            ];

            const facts = {
                teams: teams.map((t) => {
                    const s = ctx.stats.get(t);
                    return {
                        team: t,
                        name: s?.name || undefined,
                        ...Object.fromEntries(axes.map((a) => [a.label, s?.metrics[a.id] ? r1(s.metrics[a.id].avg) : 0]))
                    };
                })
            };

            return {
                facts,
                table: {
                    columns: [t("ai.col.team", "Team"), ...axes.map((a) => a.label)],
                    rows: facts.teams.map((f) => [Data.teamLabel(ctx, f.team), ...axes.map((a) => f[a.label])])
                },
                chart: {
                    type: "radar",
                    title: `Team Radar Profile (${teams.join(", ")})`,
                    theta: axes.map((a) => a.label),
                    series: facts.teams.map((f) => ({
                        name: String(f.team),
                        r: axes.map((a) => f[a.label])
                    }))
                }
            };
        }
    },

    score_distribution: {
        description: "Box plot chart showing score distribution and consistency across matches for teams.",
        params: { teams: "list of team numbers", metric: "optional metric name" },
        async run(ctx, args) {
            let teams = toTeamNumbers(ctx, args.teams).slice(0, 6);
            const metric = metricOrDefault(ctx, args.metric, "score_total");
            if (!teams.length) {
                teams = Data.rankTeams(ctx, metric).slice(0, 5).map((r) => r.teamNumber);
            }
            if (!teams.length) return { facts: { error: "No match score data found." } };

            const facts = {};
            const series = teams.map((t) => {
                const s = ctx.stats.get(t);
                const values = (s?.perMatch || []).map((p) => p.values[metric.id]).filter((v) => v !== null && v !== undefined);
                facts[t] = { min: Math.min(...values), max: Math.max(...values), avg: r1(s?.metrics[metric.id]?.avg), matches: values.length };
                return { name: String(t), y: values };
            }).filter((s) => s.y.length > 0);

            return {
                facts: { metric: metric.label, distribution: facts },
                chart: {
                    type: "box",
                    title: `${metric.label} Distribution (Box Plot)`,
                    yTitle: metric.label,
                    series
                }
            };
        }
    },

    create_strategy_brief: {
        description: "Generate a comprehensive Match Strategy & Tactical Game Plan Artifact for an upcoming match.",
        params: { match: "match number, e.g. 13 or 'next'" },
        async run(ctx, args) {
            const match = Data.findMatch(ctx, args.match);
            if (!match) return { facts: { error: `Match ${args.match ?? "next"} not found.` } };

            const mLabel = Data.matchLabel(match);
            const red = (match.redTeams || []).map(Data.teamNumberFromKey).filter(Boolean);
            const blue = (match.blueTeams || []).map(Data.teamNumberFromKey).filter(Boolean);

            const redStats = red.map((t) => ({ team: t, name: Data.teamName(ctx, t), stats: ctx.stats.get(t) }));
            const blueStats = blue.map((t) => ({ team: t, name: Data.teamName(ctx, t), stats: ctx.stats.get(t) }));

            const redAvg = r1(redStats.reduce((acc, x) => acc + (x.stats?.metrics.score_total?.avg || 0), 0));
            const blueAvg = r1(blueStats.reduce((acc, x) => acc + (x.stats?.metrics.score_total?.avg || 0), 0));

            let prediction = null;
            try {
                prediction = await window.Obsidianscout.request(`/api/matches/predict?matchKey=${encodeURIComponent(match.matchKey)}&eventKey=${encodeURIComponent(ctx.eventKey)}`);
            } catch (_) { /* optional */ }

            const md = [
                `# 🎯 Strategic Game Plan: ${mLabel}`,
                `**Event:** ${ctx.eventKey.toUpperCase()} | **Generated by:** ObsidianScout Tactical AI`,
                "",
                "---",
                "",
                "## 📊 Match Projection & Win Probability",
                `| Alliance | Expected Score | Win Probability | Primary Strengths |`,
                `| :--- | :---: | :---: | :--- |`,
                `| 🔴 **Red Alliance** (${red.join(", ")}) | **${redAvg}** pts | ${prediction?.redWinProbability ? `${Math.round(prediction.redWinProbability * 100)}%` : "-"} | ${redStats.map((r) => `T${r.team} (Auto: ${r1(r.stats?.metrics.score_auto?.avg || 0)})`).join(", ")} |`,
                `| 🔵 **Blue Alliance** (${blue.join(", ")}) | **${blueAvg}** pts | ${prediction?.blueWinProbability ? `${Math.round(prediction.blueWinProbability * 100)}%` : "-"} | ${blueStats.map((b) => `T${b.team} (Auto: ${b1 = r1(b.stats?.metrics.score_auto?.avg || 0)})`).join(", ")} |`,
                "",
                "---",
                "",
                "## 🤖 Team Dossiers & Scoring Breakdown",
                "### 🔴 Red Alliance",
                ...redStats.map((r) => `- **Team ${r.team} ${r.name ? `(${r.name})` : ""}**: Total: **${r1(r.stats?.metrics.score_total?.avg || 0)}** (Auto: ${r1(r.stats?.metrics.score_auto?.avg || 0)}, Teleop: ${r1(r.stats?.metrics.score_teleop?.avg || 0)}, Endgame: ${r1(r.stats?.metrics.score_endgame?.avg || 0)})`),
                "",
                "### 🔵 Blue Alliance",
                ...blueStats.map((b) => `- **Team ${b.team} ${b.name ? `(${b.name})` : ""}**: Total: **${r1(b.stats?.metrics.score_total?.avg || 0)}** (Auto: ${r1(b.stats?.metrics.score_auto?.avg || 0)}, Teleop: ${r1(b.stats?.metrics.score_teleop?.avg || 0)}, Endgame: ${r1(b.stats?.metrics.score_endgame?.avg || 0)})`),
                "",
                "---",
                "",
                "## ⚔️ Tactical Action Checklist",
                "- [ ] **Autonomous Execution:** Prioritize clean lane clearance to avoid collisions.",
                "- [ ] **Teleop Cycling:** Maintain high-tempo feeder station cycles.",
                "- [ ] **Defense & Disruption:** Identify and challenge the opponent's primary scorer.",
                "- [ ] **Endgame Timing:** Return to hangar/barge staging at T-30s."
            ].join("\n");

            const artifact = {
                id: `strategy-${match.matchKey || match.matchNumber}`,
                title: `${mLabel} Strategy Brief & Tactical Plan`,
                type: "strategy",
                summary: `Pre-match tactical dossier for ${mLabel} with projected scores: Red ${redAvg} vs Blue ${blueAvg}.`,
                markdown: md
            };

            return {
                facts: { match: mLabel, red_teams: red, blue_teams: blue, red_expected: redAvg, blue_expected: blueAvg },
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**. Click **Open Artifact** to inspect the complete game plan.`
            };
        }
    },

    create_alliance_sheet: {
        description: "Generate an Alliance Selection Draft Worksheet Artifact with tiered pick rankings.",
        params: { focus: "optional focus metric" },
        async run(ctx, args) {
            const focus = args.focus ? metricOrDefault(ctx, args.focus) : ctx.metrics[0];
            const ranked = Data.rankTeams(ctx, focus, { exclude: ctx.ourTeam ? [ctx.ourTeam] : [] });

            const tier1 = ranked.slice(0, 4);
            const tier2 = ranked.slice(4, 10);
            const tier3 = ranked.slice(10, 18);

            const md = [
                `# 📋 Alliance Selection Draft Worksheet`,
                `**Event:** ${ctx.eventKey.toUpperCase()} | **Ranked by:** ${focus.label}`,
                "",
                "---",
                "",
                "## 🥇 Tier 1: First-Pick Anchors (Primary Scoring)",
                `| Rank | Team | ${focus.label} Avg | Max | Std Dev | Matches | Notes |`,
                `| :---: | :--- | :---: | :---: | :---: | :---: | :--- |`,
                ...tier1.map((r, i) => `| ${i + 1} | **${Data.teamLabel(ctx, r.teamNumber)}** | ${r1(r.avg)} | ${r1(r.max)} | ${r1(r.stdev)} | ${r.n} | High consistency primary scorer |`),
                "",
                "## 🥈 Tier 2: Complementary Specialists (2nd Pick)",
                `| Rank | Team | ${focus.label} Avg | Max | Std Dev | Matches | Notes |`,
                `| :---: | :--- | :---: | :---: | :---: | :---: | :--- |`,
                ...tier2.map((r, i) => `| ${i + 5} | **${Data.teamLabel(ctx, r.teamNumber)}** | ${r1(r.avg)} | ${r1(r.max)} | ${r1(r.stdev)} | ${r.n} | Strong autonomous / teleop synergy |`),
                "",
                "## 🥉 Tier 3: Depth & Defense Candidates (Backups)",
                `| Rank | Team | ${focus.label} Avg | Max | Std Dev | Matches | Notes |`,
                `| :---: | :--- | :---: | :---: | :---: | :---: | :--- |`,
                ...tier3.map((r, i) => `| ${i + 11} | **${Data.teamLabel(ctx, r.teamNumber)}** | ${r1(r.avg)} | ${r1(r.max)} | ${r1(r.stdev)} | ${r.n} | Durable support & endgame anchor |`),
                "",
                "---",
                "",
                "### 💡 Alliance Selection Rules of Thumb:",
                "1. **Never pick for potential; pick for proven reliability under defense.**",
                "2. **Verify autonomous path compatibility before locking in First Pick.**"
            ].join("\n");

            const artifact = {
                id: `alliance-selection-${ctx.eventKey}`,
                title: `Alliance Selection Draft Board (${focus.label})`,
                type: "worksheet",
                summary: `Comprehensive draft worksheet for ${ctx.eventKey} with ${ranked.length} ranked candidates organized into 3 tactical tiers.`,
                markdown: md
            };

            return {
                facts: { ranked_by: focus.label, total_candidates: ranked.length },
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**. Click **Open Artifact** to inspect the tiered draft board.`
            };
        }
    },

    create_team_dossier: {
        description: "Generate a comprehensive Team Scouting Dossier Artifact for a specific robot.",
        params: { team: "team number" },
        async run(ctx, args) {
            const [team] = toTeamNumbers(ctx, args.team);
            if (!team) return { facts: { error: `Team ${args.team} has no data at this event.` } };
            const s = ctx.stats.get(team);

            const md = [
                `# 🤖 Scouting Dossier: Team ${team} ${s.name ? `(${s.name})` : ""}`,
                `**Event:** ${ctx.eventKey.toUpperCase()} | **Scouted Matches:** ${s.matchesScouted}`,
                "",
                "---",
                "",
                "## 📈 Performance Summary",
                `| Metric | Average | Max | Std Dev | N |`,
                `| :--- | :---: | :---: | :---: | :---: |`,
                ...ctx.metrics.filter((m) => s.metrics[m.id]).map((m) => {
                    const st = s.metrics[m.id];
                    return `| **${m.label}** | ${r1(st.avg)} | ${r1(st.max)} | ${r1(st.stdev)} | ${st.n} |`;
                }),
                "",
                "## 🛠️ Pit Scouting Specifications",
                ...(Data.pitHighlights(ctx, team, 8).map((p) => `- ${p}`) || ["- No pit scouting notes logged."]),
                "",
                "## 📝 Match-by-Match Log",
                `| Match | Total Points | Auto | Teleop | Endgame |`,
                `| :---: | :---: | :---: | :---: | :---: |`,
                ...s.perMatch.map((p) => `| Q${p.matchNumber || "?"} | ${r1(p.values.score_total) ?? "-"} | ${r1(p.values.score_auto) ?? "-"} | ${r1(p.values.score_teleop) ?? "-"} | ${r1(p.values.score_endgame) ?? "-"} |`)
            ].join("\n");

            const artifact = {
                id: `team-dossier-${team}`,
                title: `Team ${team} Comprehensive Dossier`,
                type: "dossier",
                summary: `Complete scouting dossier for Team ${team} with pit specs, match history, and performance breakdown.`,
                markdown: md
            };

            return {
                facts: { team, name: s.name, matches: s.matchesScouted },
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**. Click **Open Artifact** to inspect the full team dossier.`
            };
        }
    },

    projected_rankings: {
        description: "Calculate and display projected end-of-event rankings, expected ranking points (RP), and playoff qualification probabilities via Monte Carlo tournament simulations.",
        params: { n: "number of top teams to return (default 10)", team: "optional specific team number", chart: "true to include visual chart" },
        async run(ctx, args) {
            let data = null;
            try {
                if (window.Obsidianscout && typeof window.Obsidianscout.request === "function") {
                    data = await window.Obsidianscout.request(`/api/matches/projected-rankings?eventKey=${encodeURIComponent(ctx.eventKey)}`);
                } else {
                    const res = await fetch(`/api/matches/projected-rankings?eventKey=${encodeURIComponent(ctx.eventKey)}`, { credentials: "same-origin" });
                    if (res.ok) data = await res.json();
                }
            } catch (e) {
                console.warn("[LocalAI] projected-rankings API call failed, falling back to local calculation:", e);
            }

            let teamsList = [];
            const rawTeams = (data && Array.isArray(data.teams)) ? data.teams : (data && Array.isArray(data.rankings) ? data.rankings : null);
            if (rawTeams && rawTeams.length > 0) {
                teamsList = rawTeams;
            } else {
                // Fallback: estimate based on scouted averages and played match standings
                const ranked = Data.rankTeams(ctx, ctx.metrics[0]);
                teamsList = ranked.map((r, i) => ({
                    projectedRank: i + 1,
                    teamNumber: r.teamNumber,
                    nickname: Data.teamName(ctx, r.teamNumber),
                    expectedFinalRp: r1(r.avg / 10),
                    expectedRankingScore: r1(r.avg / 10),
                    currentRank: i + 1,
                    currentRp: 0,
                    wins: 0,
                    losses: 0,
                    ties: 0,
                    probFirst: i === 0 ? 1.0 : 0.0,
                    probTopSeeds: i < (data?.captainSlots || 8) ? 1.0 : 0.0,
                    matchesPlayed: r.n,
                    matchesRemaining: Math.max(0, 12 - r.n)
                }));
            }

            const targetTeamNum = args.team ? Number(String(args.team).replace(/\D/g, "")) : (args.teams && args.teams.length ? Number(args.teams[0]) : null);
            let targetTeamFact = null;
            let targetRank = null;
            if (targetTeamNum) {
                const targetIdx = teamsList.findIndex((r) => Number(r.teamNumber) === targetTeamNum);
                if (targetIdx !== -1) {
                    const r = teamsList[targetIdx];
                    targetRank = targetIdx + 1;
                    targetTeamFact = {
                        rank: targetRank,
                        team: r.teamNumber,
                        name: r.nickname || Data.teamName(ctx, r.teamNumber),
                        record: (r.wins !== undefined && r.losses !== undefined) ? `${r.wins}-${r.losses}-${r.ties || 0}` : "-",
                        ranking_score: r1(r.expectedRankingScore || r.expectedFinalRp || 0),
                        current_rank: r.currentRank ?? targetRank,
                        top_seed_prob: r.probTopSeeds !== undefined ? `${Math.round(r.probTopSeeds * 100)}%` : "-",
                        prob_first: r.probFirst !== undefined ? `${Math.round(r.probFirst * 100)}%` : "-"
                    };
                }
            }

            const isFinal = !!(data && data.qualsComplete);
            let displayTeams = [];
            if (args.n) {
                const count = parseInt(args.n, 10);
                if (targetTeamFact && targetRank > count) {
                    const topN = teamsList.slice(0, count);
                    const start = Math.max(count, targetRank - 3);
                    const end = Math.min(teamsList.length, targetRank + 2);
                    const neighbors = teamsList.slice(start, end);
                    displayTeams = [...topN, ...neighbors];
                } else {
                    displayTeams = teamsList.slice(0, count);
                }
            } else {
                displayTeams = teamsList;
            }

            const allRankMap = {};
            teamsList.forEach((r, idx) => {
                allRankMap[r.teamNumber] = {
                    rank: idx + 1,
                    name: r.nickname || Data.teamName(ctx, r.teamNumber),
                    ranking_score: r1(r.expectedRankingScore || r.expectedFinalRp || 0),
                    record: (r.wins !== undefined && r.losses !== undefined) ? `${r.wins}-${r.losses}-${r.ties || 0}` : "-",
                    top_seed_prob: r.probTopSeeds !== undefined ? `${Math.round(r.probTopSeeds * 100)}%` : "-"
                };
            });

            const facts = {
                event: ctx.eventKey,
                is_final: isFinal,
                total_teams: teamsList.length,
                simulations: data?.simulations || 2000,
                matches_played: data?.qualMatchesPlayed || ctx.matches.length,
                total_matches: data?.qualMatchesTotal || ctx.matches.length,
                target_team: targetTeamFact || undefined,
                all_team_rankings: allRankMap,
                projected_rankings: displayTeams.map((r) => {
                    const idx = teamsList.indexOf(r);
                    return {
                        rank: idx + 1,
                        team: r.teamNumber,
                        name: r.nickname || Data.teamName(ctx, r.teamNumber),
                        record: (r.wins !== undefined && r.losses !== undefined) ? `${r.wins}-${r.losses}-${r.ties || 0}` : "-",
                        ranking_score: r1(r.expectedRankingScore || r.expectedFinalRp || 0),
                        current_rank: r.currentRank ?? (idx + 1),
                        top_seed_prob: r.probTopSeeds !== undefined ? `${Math.round(r.probTopSeeds * 100)}%` : "-",
                        prob_first: r.probFirst !== undefined ? `${Math.round(r.probFirst * 100)}%` : "-"
                    };
                })
            };

            const tableRows = displayTeams.map((r) => {
                const idx = teamsList.indexOf(r);
                return [
                    idx + 1,
                    Data.teamLabel(ctx, r.teamNumber),
                    (r.wins !== undefined && r.losses !== undefined) ? `${r.wins}-${r.losses}-${r.ties || 0}` : "-",
                    r1(r.expectedRankingScore || r.expectedFinalRp || 0),
                    isFinal ? (r.currentRank ?? (idx + 1)) : (r.currentRank ?? "-"),
                    r.probTopSeeds !== undefined ? `${Math.round(r.probTopSeeds * 100)}%` : "-"
                ];
            });

            const md = [
                `# ${isFinal ? "🏆 Final Qualification Standings" : "🔮 Projected Final Rankings"}: ${ctx.eventKey.toUpperCase()}`,
                isFinal
                    ? `**Status:** Qualifications complete (${data?.qualMatchesTotal || facts.total_matches} matches) · Official Final Standings`
                    : `**Simulations:** ${data?.simulations || 2000} Monte Carlo iterations · ${data?.qualMatchesPlayed || 0}/${data?.qualMatchesTotal || 0} matches played`,
                "",
                targetTeamFact ? `### 🎯 Focus: Team ${targetTeamFact.team} ${targetTeamFact.name ? `(${targetTeamFact.name})` : ""} is ${isFinal ? "officially ranked" : "projected"} **#${targetTeamFact.rank}** (RS: **${targetTeamFact.ranking_score}**, Record: **${targetTeamFact.record}**)` : "",
                "",
                "---",
                "",
                `## 📊 ${isFinal ? "Official Final Standings" : "Projected Standings"} Table`,
                `| ${isFinal ? "Final Rank" : "Proj. Rank"} | Team | Record | Ranking Score (RS) | Rank Now | Top-8 Prob |`,
                `| :---: | :--- | :---: | :---: | :---: | :---: |`,
                ...displayTeams.map((r) => {
                    const idx = teamsList.indexOf(r);
                    return `| **#${idx + 1}** | **${Data.teamLabel(ctx, r.teamNumber)}** | ${(r.wins !== undefined && r.losses !== undefined) ? `${r.wins}-${r.losses}-${r.ties || 0}` : "-"} | **${r1(r.expectedRankingScore || r.expectedFinalRp || 0)}** | ${r.currentRank ?? (idx + 1)} | ${r.probTopSeeds !== undefined ? `${Math.round(r.probTopSeeds * 100)}%` : "-"} |`;
                }),
                "",
                "---",
                "",
                "### 📌 Methodology & Source:",
                isFinal
                    ? "- Official qualification matches complete. Standings are final based on official match results and tiebreakers."
                    : "- Simulates all remaining unplayed qualification matches using scouted offensive power and opponent difficulty."
            ].filter(Boolean).join("\n");

            const artifact = {
                id: `projected-standings-${ctx.eventKey}`,
                title: `${isFinal ? "Final Standings" : "Projected Final Standings"} (${ctx.eventKey.toUpperCase()})`,
                type: "worksheet",
                summary: isFinal
                    ? `Official final qualification standings for ${ctx.eventKey.toUpperCase()} across all ${facts.total_matches} matches.`
                    : `Monte Carlo tournament projections for ${ctx.eventKey.toUpperCase()} with expected ranking scores and playoff probabilities.`,
                markdown: md
            };

            const chart = args.chart ? {
                type: "bar",
                title: `${isFinal ? "Final" : "Projected"} Ranking Scores (${ctx.eventKey.toUpperCase()})`,
                x: displayTeams.map((r) => String(r.teamNumber)),
                y: displayTeams.map((r) => r1(r.expectedRankingScore || r.expectedFinalRp || 0)),
                xTitle: t("ai.col.team", "Team"),
                yTitle: "Ranking Score",
                color: "#3b82f6"
            } : null;

            return {
                facts,
                table: {
                    columns: [isFinal ? "Final Rank" : "Proj. Rank", t("ai.col.team", "Team"), "Record", "RS Final", "Rank Now", "Top 8 Prob"],
                    rows: tableRows
                },
                chart,
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**. Click **Open Artifact** to inspect the complete tournament standings.`
            };
        }
    },

    create_artifact: {
        description: "Create a custom structured artifact document (strategy, worksheet, dossier, or report).",
        params: { title: "artifact title", type: "strategy, worksheet, dossier, or report", markdown: "markdown content" },
        async run(ctx, args) {
            let md = args.markdown;
            const title = args.title || `Scouting Report: ${ctx.eventKey.toUpperCase()}`;
            const type = args.type || "report";
            if (!md || md.trim().length < 20) {
                const top = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, 10);
                md = [
                    `# 📑 ${title}`,
                    `**Event:** ${ctx.eventKey.toUpperCase()} | **Generated:** ${new Date().toLocaleDateString()}`,
                    "",
                    "---",
                    "",
                    "## 📊 Event Overview & Top Teams",
                    `| Rank | Team | ${ctx.metrics[0]?.label || "Total Points"} Avg | Max | Matches |`,
                    `| :---: | :--- | :---: | :---: | :---: |`,
                    ...top.map((r, i) => `| ${i + 1} | **${Data.teamLabel(ctx, r.teamNumber)}** | ${r1(r.avg)} | ${r1(r.max)} | ${r.n} |`),
                    "",
                    "## 🔍 Key Performance Insights",
                    `- Total Teams Scouted: **${ctx.stats.size}**`,
                    `- Matches in Schedule: **${ctx.matches.length}**`,
                    `- Highest Scoring Team: **${top[0] ? Data.teamLabel(ctx, top[0].teamNumber) : "-"}**`
                ].join("\n");
            }
            const artifact = {
                id: `custom-artifact-${Date.now()}`,
                title,
                type,
                summary: title,
                markdown: md
            };
            return {
                facts: { title: artifact.title, type: artifact.type },
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**.`
            };
        }
    },

    capabilities_help: {
        description: "List all assistant skills, chart capabilities, artifact generation abilities, and data query tools.",
        params: {},
        async run(ctx) {
            const md = [
                `# 🧭 ObsidianScout AI Assistant Capabilities & Skills`,
                `The Local AI Assistant runs 100% locally on your device with access to all event scouting data, match schedules, and analytics tools.`,
                "",
                "---",
                "",
                "## 📊 1. Charts & Visualizations",
                "- **Match-by-Match Line Trends:** Multi-line progression across qualification matches (`metric_trend`).",
                "- **2D Scatter Plots:** Two-metric correlation analysis (e.g., Total Points vs Auto, OPR vs EPA) (`scatter`).",
                "- **Phase Breakdown Stacked Bars:** Auto, Teleop, and Endgame phase contributions (`stacked_breakdown`).",
                "- **Radar / Spider Charts:** Multi-axis robot skill profiling (`team_radar`).",
                "- **Box Plot Distributions:** Score variance, spread, and match-to-match consistency (`score_distribution`).",
                "- **Ranking Bar Charts:** Top teams by any metric (`top_teams`).",
                "",
                "## 📑 2. Tactical Artifacts & Reports",
                "- **Match Strategy Briefs:** Full pre-match tactical game plan and win probabilities (`create_strategy_brief`).",
                "- **Alliance Selection Worksheets:** Tiered draft pick list with first-pick anchors & specialists (`create_alliance_sheet`).",
                "- **Team Scouting Dossiers:** In-depth profiles with pit specs, match history, and scoring metrics (`create_team_dossier`).",
                "- **Tournament Projections:** Monte Carlo simulation of final qualification standings and RP (`projected_rankings`).",
                "",
                "## 🔍 3. Data Query & Team Analytics",
                "- **Team Overviews & Comparisons:** Compare 2+ teams across all scoring metrics (`compare_teams`).",
                "- **Match Previews:** Upcoming alliance strength and expected scores (`match_preview`).",
                "- **Scout Notes Summaries:** Synthesize qualitative notes and observer comments (`summarize_notes`).",
                "- **Pick Recommendations:** Filter candidates based on defense, consistency, or autonomous power (`pick_candidates`)."
            ].join("\n");

            const artifact = {
                id: "assistant-skills-guide",
                title: "ObsidianScout AI Assistant Skills & Tools Catalog",
                type: "report",
                summary: "Complete reference guide of all available visual chart types, artifacts, and analytics queries.",
                markdown: md
            };

            return {
                facts: {
                    event: ctx.eventKey,
                    total_teams: ctx.stats.size,
                    metrics_available: ctx.metrics.map((m) => m.label).slice(0, 15),
                    summary: "I can generate match-by-match trend lines, 2D scatter plots, phase stacked bars, radar charts, box plot distributions, alliance selection worksheets, match strategy briefs, team dossiers, Monte Carlo tournament projections, match schedules, full team rosters, win predictions, and site manuals."
                },
                artifact,
                markdown: md
            };
        }
    },

    all_matches: {
        description: "Get the complete match schedule for the event including match numbers, alliance rosters, scores, status, and scheduled time.",
        params: { team: "optional team number filter", unplayed_only: "true to only show unplayed matches" },
        async run(ctx, args) {
            let list = ctx.matches || [];
            if (args.team) {
                const tNum = Number(args.team);
                list = list.filter((m) => {
                    const red = (m.redTeams || []).map(Data.teamNumberFromKey);
                    const blue = (m.blueTeams || []).map(Data.teamNumberFromKey);
                    return red.includes(tNum) || blue.includes(tNum);
                });
            }
            if (args.unplayed_only) {
                list = list.filter((m) => m.redScore === null || m.redScore === undefined || m.redScore < 0);
            }
            const completed = list.filter((m) => m.redScore !== null && m.redScore !== undefined && m.redScore >= 0);
            const upcoming = list.filter((m) => m.redScore === null || m.redScore === undefined || m.redScore < 0);
            return {
                facts: {
                    event: ctx.eventKey,
                    total_matches: list.length,
                    completed_matches: completed.length,
                    upcoming_matches: upcoming.length,
                    matches: list.slice(0, 35).map((m) => ({
                        match: Data.matchLabel(m),
                        red_teams: (m.redTeams || []).map(Data.teamNumberFromKey).filter(Boolean),
                        blue_teams: (m.blueTeams || []).map(Data.teamNumberFromKey).filter(Boolean),
                        red_score: m.redScore,
                        blue_score: m.blueScore,
                        winner: m.winner || (m.redScore > m.blueScore ? "red" : (m.blueScore > m.redScore ? "blue" : undefined)),
                        status: (m.redScore !== null && m.redScore >= 0) ? "completed" : "upcoming"
                    }))
                },
                table: {
                    columns: [t("ai.col.match", "Match"), t("match.red_alliance", "Red Alliance"), t("match.blue_alliance", "Blue Alliance"), t("match.result", "Result / Status")],
                    rows: list.slice(0, 50).map((m) => {
                        const red = (m.redTeams || []).map(Data.teamNumberFromKey).filter(Boolean).join(", ");
                        const blue = (m.blueTeams || []).map(Data.teamNumberFromKey).filter(Boolean).join(", ");
                        const result = (m.redScore !== null && m.redScore !== undefined && m.redScore >= 0)
                            ? `${m.redScore} - ${m.blueScore} (${(m.winner || (m.redScore > m.blueScore ? "Red" : "Blue")).toUpperCase()})`
                            : t("match.upcoming", "Upcoming");
                        return [Data.matchLabel(m), red || "-", blue || "-", result];
                    })
                }
            };
        }
    },

    all_teams: {
        description: "List all teams registered at the event with team numbers, team names, location (city/state), scouted matches count, EPA, and xP.",
        params: { order_by: "team_number, rank, epa, or xp" },
        async run(ctx, args) {
            const teamList = Array.from(ctx.teams.values()).map((tm) => {
                const s = ctx.stats.get(tm.teamNumber);
                return {
                    team: tm.teamNumber,
                    name: tm.teamName || tm.name || (s ? s.name : ""),
                    city: tm.city || "",
                    state: tm.state || tm.stateProv || "",
                    country: tm.country || "",
                    matches_scouted: s ? s.matchesScouted : 0,
                    epa: tm.epa ?? (s && s.metrics["ext:epa"] ? s.metrics["ext:epa"].avg : null),
                    xp: tm.exp ?? tm.match13Exp ?? (s && s.metrics["ext:exp"] ? s.metrics["ext:exp"].avg : null),
                    rank: tm.rank ?? (s ? s.rank : null)
                };
            });
            if (args.order_by === "rank") {
                teamList.sort((a, b) => (a.rank || 999) - (b.rank || 999));
            } else if (args.order_by === "epa") {
                teamList.sort((a, b) => (b.epa || 0) - (a.epa || 0));
            } else if (args.order_by === "xp") {
                teamList.sort((a, b) => (b.xp || 0) - (a.xp || 0));
            } else {
                teamList.sort((a, b) => a.team - b.team);
            }
            return {
                facts: {
                    event: ctx.eventKey,
                    total_teams: teamList.length,
                    teams: teamList.slice(0, 50).map((t) => ({
                        team: t.team,
                        name: t.name || undefined,
                        location: [t.city, t.state].filter(Boolean).join(", ") || undefined,
                        matches_scouted: t.matches_scouted,
                        epa: t.epa !== null ? r1(t.epa) : undefined,
                        xp: t.xp !== null ? r1(t.xp) : undefined
                    }))
                },
                table: {
                    columns: ["#", t("ai.col.team", "Team"), t("teams.location", "Location"), t("teams.scouted", "Scouted"), "EPA", "xP"],
                    rows: teamList.map((t) => [
                        t.team,
                        t.name ? `${t.team} (${t.name})` : String(t.team),
                        [t.city, t.state].filter(Boolean).join(", ") || "-",
                        t.matches_scouted,
                        t.epa !== null ? r1(t.epa) : "-",
                        t.xp !== null ? r1(t.xp) : "-"
                    ])
                }
            };
        }
    },

    match_predictions: {
        description: "Get win probabilities and projected match scores for upcoming or specified matches at the event.",
        params: { match: "optional match number", team: "optional team number", n: "how many upcoming matches to predict (default 10)" },
        async run(ctx, args) {
            let matches = (ctx.matches || []).filter((m) => m.redScore === null || m.redScore === undefined || m.redScore < 0);
            if (args.match) {
                const found = Data.findMatch(ctx, args.match);
                if (found) matches = [found];
            } else if (args.team) {
                const tNum = Number(args.team);
                matches = matches.filter((m) => {
                    const red = (m.redTeams || []).map(Data.teamNumberFromKey);
                    const blue = (m.blueTeams || []).map(Data.teamNumberFromKey);
                    return red.includes(tNum) || blue.includes(tNum);
                });
            }
            const count = args.n ? parseInt(args.n, 10) : 10;
            const predRows = matches.slice(0, count).map((m) => {
                const redTeams = (m.redTeams || []).map(Data.teamNumberFromKey).filter(Boolean);
                const blueTeams = (m.blueTeams || []).map(Data.teamNumberFromKey).filter(Boolean);
                const redExp = m.predictedRedScore ?? r1(redTeams.reduce((sum, t) => {
                    const s = ctx.stats.get(t);
                    return sum + (s?.metrics["ext:epa"]?.avg ?? s?.metrics["ext:exp"]?.avg ?? s?.metrics.score_total?.avg ?? 0);
                }, 0));
                const blueExp = m.predictedBlueScore ?? r1(blueTeams.reduce((sum, t) => {
                    const s = ctx.stats.get(t);
                    return sum + (s?.metrics["ext:epa"]?.avg ?? s?.metrics["ext:exp"]?.avg ?? s?.metrics.score_total?.avg ?? 0);
                }, 0));
                const diff = redExp - blueExp;
                const redProb = m.winProb ?? (1 / (1 + Math.exp(-diff / 15)));
                const winner = redExp >= blueExp ? "red" : "blue";
                const probPct = Math.round((winner === "red" ? redProb : (1 - redProb)) * 100);
                return {
                    match: Data.matchLabel(m),
                    red_teams: redTeams,
                    blue_teams: blueTeams,
                    predicted_winner: winner,
                    win_probability: `${probPct}%`,
                    projected_red_score: redExp,
                    projected_blue_score: blueExp
                };
            });
            return {
                facts: {
                    event: ctx.eventKey,
                    count: predRows.length,
                    predictions: predRows
                },
                table: {
                    columns: [t("ai.col.match", "Match"), t("match.red_alliance", "Red Alliance"), t("match.blue_alliance", "Blue Alliance"), t("predictor.predicted_winner", "Predicted Winner"), t("predictor.win_prob", "Win Prob"), t("predictor.projected_score", "Projected Score")],
                    rows: predRows.map((p) => [
                        p.match,
                        p.red_teams.join(", "),
                        p.blue_teams.join(", "),
                        p.predicted_winner.toUpperCase(),
                        p.win_probability,
                        `Red ${p.projected_red_score} - Blue ${p.projected_blue_score}`
                    ])
                }
            };
        }
    },

    current_rankings: {
        description: "Get the current official qualification standings table for the event, including rank, team, ranking score (RS), record (W-L-T), and matches played.",
        params: { n: "how many teams to return (default all)", chart: "true to include visual ranking chart" },
        async run(ctx, args) {
            const list = Array.from(ctx.teams.values()).map((tm) => {
                const s = ctx.stats.get(tm.teamNumber);
                return {
                    rank: tm.rank ?? tm.ranking ?? 999,
                    team: tm.teamNumber,
                    name: tm.teamName || tm.name || (s ? s.name : ""),
                    ranking_score: tm.rankingScore ?? tm.sortOrder1 ?? tm.rs ?? "-",
                    record: tm.record ? `${tm.record.wins || 0}-${tm.record.losses || 0}-${tm.record.ties || 0}` : (tm.recordStr || "-"),
                    matches_played: tm.matchesPlayed ?? tm.played ?? (s ? s.matchesScouted : 0),
                    high_score: tm.highestScore ?? tm.highScore ?? (s?.metrics?.score_total ? s.metrics.score_total.max : "-")
                };
            }).sort((a, b) => a.rank - b.rank);
            const n = args.n ? parseInt(args.n, 10) : list.length;
            const rows = list.slice(0, n);
            return {
                facts: {
                    event: ctx.eventKey,
                    total_teams: list.length,
                    rankings: rows
                },
                table: {
                    columns: [t("rankings.rank", "Rank"), t("ai.col.team", "Team"), t("rankings.rs", "Ranking Score (RS)"), t("rankings.record", "Record (W-L-T)"), t("rankings.played", "Played"), t("rankings.high_score", "High Score")],
                    rows: rows.map((r) => [
                        r.rank !== 999 ? r.rank : "-",
                        r.name ? `${r.team} (${r.name})` : String(r.team),
                        r.ranking_score,
                        r.record,
                        r.matches_played,
                        r.high_score
                    ])
                },
                chart: args.chart ? {
                    type: "bar",
                    title: `Rankings - Top ${rows.length} Teams`,
                    x: rows.map((r) => String(r.team)),
                    xTitle: t("ai.col.team", "Team"),
                    series: [{ name: "Ranking Score", y: rows.map((r) => typeof r.ranking_score === "number" ? r.ranking_score : (Number(r.ranking_score) || 0)) }]
                } : null
            };
        }
    },

    read_docs: {
        description: "Search and read ObsidianScout documentation, tutorials, user guides, and feature workflows. Use when the user asks how to use the site, how to scout, how offline mode works, how passkeys work, how to configure gamepads, or how specific features operate.",
        params: { topic: "topic or feature keyword (e.g. 'scouting', 'pit', 'offline', 'passkeys', 'gamepad', 'qr', 'export', 'strategy', 'predictions', 'general')" },
        async run(ctx, args) {
            const guide = getDocGuide(args.topic || "");
            const artifact = {
                id: `docs-${guide.topic}`,
                title: guide.title,
                type: "guide",
                summary: guide.summary,
                markdown: guide.markdown
            };
            return {
                facts: {
                    topic: guide.topic,
                    title: guide.title,
                    summary: guide.summary,
                    key_steps: guide.keySteps
                },
                artifact,
                markdown: guide.markdown
            };
        }
    },

    calculate: {
        description: "Evaluate arithmetic exactly, e.g. (12.5+8)/2. Use instead of doing math yourself.",
        params: { expression: "arithmetic expression" },
        async run(_ctx, args) {
            const expr = String(args.expression || "");
            try {
                return { facts: { expression: expr, result: r1(evaluate(expr)) } };
            } catch (err) {
                return { facts: { expression: expr, error: err.message } };
            }
        }
    }
};

/** Built-in ObsidianScout documentation knowledge base. */
export function getDocGuide(rawQuery = "") {
    const q = String(rawQuery).toLowerCase();
    if (q.includes("pit")) {
        return {
            topic: "pit_scouting",
            title: "Pit Scouting Guide",
            summary: "How to inspect robot mechanisms, record specifications, take robot photos, and evaluate pit interviews.",
            keySteps: [
                "Navigate to Pit Scouting from the sidebar menu (/pit-scout).",
                "Select the target team number from the team list or search bar.",
                "Inspect the robot drivetrain (Swerve, Tank, Mecanum), dimensions, weight, intake, and scoring mechanisms.",
                "Take or upload robot photos directly from your device camera.",
                "Fill in qualitative notes regarding driver experience and auto routines, then press 'Save Entry'."
            ],
            markdown: [
                "# 🛠️ ObsidianScout Pit Scouting User Guide",
                "Pit Scouting collects mechanical specifications, dimensions, drivetrain architecture, and qualitative notes prior to qualification matches.",
                "",
                "### Step-by-Step Workflow:",
                "1. **Access Pit Scouting:** Click **Pit Scouting** in the navigation sidebar or visit [`/pit-scout`](/pit-scout).",
                "2. **Select Team:** Type or select the team number you are currently visiting in the pit area.",
                "3. **Record Drivetrain & Dimensions:** Document motor types, gearbox ratios, frame perimeter, and weight.",
                "4. **Robot Photos:** Use the built-in camera capture button to attach high-resolution intake and mechanism photos.",
                "5. **Auto Capabilities:** Ask the drive team about starting positions, pre-loaded scoring, and autonomous paths.",
                "6. **Save & Sync:** Tap **Save Entry**. If offline, the entry is safely stored in IndexedDB and syncs automatically when online."
            ].join("\n")
        };
    }
    if (q.includes("offline") || q.includes("sync") || q.includes("cache")) {
        return {
            topic: "offline_sync",
            title: "Offline Mode & Data Synchronization Guide",
            summary: "How ObsidianScout operates 100% offline in stadium venues with zero Wi-Fi, using IndexedDB and automatic syncing.",
            keySteps: [
                "ObsidianScout installs as a Progressive Web App (PWA) and caches all app assets.",
                "All match, pit, and qualitative entries saved while offline are stored locally in IndexedDB.",
                "When internet connectivity is detected, the background sync service automatically uploads pending entries.",
                "Use the Cache & Storage Manager (/cache-manager) to view pending queue status, download backups, or force sync."
            ],
            markdown: [
                "# 📶 Offline Mode & Sync Protocol Guide",
                "ObsidianScout is engineered to work reliably in arenas with jammed or restricted Wi-Fi.",
                "",
                "### Key Offline Features:",
                "- **IndexedDB Local Storage:** Every scouting form submission is instantly committed to local browser database storage.",
                "- **Automatic Background Sync:** As soon as Wi-Fi or cellular data reconnects, pending entries upload seamlessly.",
                "- **Conflict Resolution:** If two scouts submit data for the same team match, the conflict resolver lets you compare side-by-side.",
                "- **Cache Manager:** Visit [`/cache-manager`](/cache-manager) to inspect queued entries, export JSON/CSV backups, or trigger manual sync."
            ].join("\n")
        };
    }
    if (q.includes("qr") || q.includes("scanner") || q.includes("transfer")) {
        return {
            topic: "qr_transfer",
            title: "QR Code Data Transfer Guide",
            summary: "Air-gapped data transmission using high-density QR codes to transfer scouting entries from stands to the lead scout station.",
            keySteps: [
                "Scouts complete match entries on offline phones or tablets.",
                "On the completed form or cache manager, tap 'Generate QR Code'.",
                "The lead scout or data lead opens the QR Scanner (/qr-scanner) on their connected laptop.",
                "Scan the QR code with the camera to instantly ingest and verify the match entry into the server database."
            ],
            markdown: [
                "# 📷 QR Code Air-Gapped Transfer Guide",
                "When venue regulations prohibit Wi-Fi hotspots, ObsidianScout uses animated high-density QR codes to transfer scouting entries.",
                "",
                "### How to Use QR Transfer:",
                "1. **On Scout Device:** Complete a match form and click **Generate QR Code** (or open it from Cache Manager).",
                "2. **On Lead Scout Station:** Open **QR Scanner** in the sidebar or go to [`/qr-scanner`](/qr-scanner).",
                "3. **Scan Code:** Hold the scout device up to the webcam/camera. The scanner ingests and parses the entry in milliseconds.",
                "4. **Verification:** A green confirmation banner confirms data receipt and updates the central leaderboard."
            ].join("\n")
        };
    }
    if (q.includes("gamepad") || q.includes("controller")) {
        return {
            topic: "gamepad_setup",
            title: "Gamepad & Controller Scouting Guide",
            summary: "Configure USB or Bluetooth gamepads (Xbox, PlayStation, Logitech) for tactile, rapid match scouting without looking at the screen.",
            keySteps: [
                "Connect a gamepad via USB or Bluetooth to your device.",
                "Go to Settings (/settings) -> Gamepad Profiles.",
                "Press any button on the controller to detect the device.",
                "Map controller buttons (A, B, X, Y, Bumpers, Triggers, D-Pad) to scouting counters and scoring phases.",
                "Open Live Scouting (/scout) to record game cycles using the controller with haptic feedback vibration."
            ],
            markdown: [
                "# 🎮 Gamepad Controller Scouting Setup",
                "Gamepad support allows scouts to keep their eyes on the field at all times while recording cycles via controller buttons.",
                "",
                "### Setup Instructions:",
                "1. **Connect Gamepad:** Pair an Xbox, DualShock, Switch Pro, or generic USB controller.",
                "2. **Configure Mappings:** Open [`/settings`](/settings) and navigate to **Gamepad Profiles**.",
                "3. **Button Assignment:** Bind actions like *Speaker Cycle*, *Amp Note*, *Coral Placement*, or *Undo* to specific buttons.",
                "4. **Haptics:** Enable vibration feedback so the controller buzzes on every successful increment.",
                "5. **Live Scouting:** Open [`/scout`](/scout) and start recording without touching the screen."
            ].join("\n")
        };
    }
    if (q.includes("passkey") || q.includes("auth") || q.includes("biometric") || q.includes("login")) {
        return {
            topic: "passkeys_auth",
            title: "Passkey & Biometric Authentication Guide",
            summary: "Passwordless authentication using WebAuthn, Face ID, Touch ID, or Windows Hello for instant secure login.",
            keySteps: [
                "Sign in to your ObsidianScout account.",
                "Go to Settings (/settings) -> Security & Passkeys.",
                "Click 'Register New Passkey' and authenticate with your device fingerprint or facial recognition.",
                "On future logins, click 'Sign in with Passkey' for instant one-touch access."
            ],
            markdown: [
                "# 🔑 Passkey & Security Guide",
                "ObsidianScout supports modern WebAuthn Passkeys for fast, phishing-resistant, passwordless authentication.",
                "",
                "### How to Set Up Passkeys:",
                "1. **Navigate to Settings:** Visit [`/settings`](/settings) and scroll to **Passkeys & Security**.",
                "2. **Register Device:** Click **Register New Passkey**.",
                "3. **Biometric Prompt:** Follow your browser prompt (Touch ID, Face ID, Windows Hello, or YubiKey).",
                "4. **Passwordless Login:** You can now sign in from the login screen with a single touch."
            ].join("\n")
        };
    }
    if (q.includes("strategy") || q.includes("plan") || q.includes("alliance") || q.includes("pick")) {
        return {
            topic: "strategy_planning",
            title: "Match Strategy & Alliance Selection Guide",
            summary: "Pre-match tactical planning, partner synergy analysis, opponent defensive counters, and draft pick sheets.",
            keySteps: [
                "Open Match Planning (/match-planning) and select your upcoming match.",
                "Inspect Red vs Blue projected score breakdowns, auto routing conflicts, and teleop cycle ceilings.",
                "Generate custom Pre-Match Strategy Brief artifacts with defense assignments and key objectives.",
                "For elimination rounds, open Alliance Selection (/alliances) to build tiered pick lists, draft anchors, and specialist rankings."
            ],
            markdown: [
                "# 🎯 Match Strategy & Alliance Selection Guide",
                "Turn quantitative scouting telemetry into match wins and optimal alliance draft strategies.",
                "",
                "### Strategy Tools:",
                "- **Pre-Match Briefing ([`/match-planning`](/match-planning)):** Compares all 6 robots, highlights opponent weak points, and checks autonomous path conflicts.",
                "- **Alliance Selection Board ([`/alliances`](/alliances)):** Organizes teams into 1st-pick anchors, 2nd-pick defense/endgame specialists, and Do-Not-Pick lists.",
                "- **Strategy Brief Artifacts:** Ask the AI assistant (*'create strategy brief for match 12'*) to generate a printable tactical PDF-ready document."
            ].join("\n")
        };
    }
    // General default site guide
    return {
        topic: "general_guide",
        title: "ObsidianScout Complete User Guide & Site Manual",
        summary: "Comprehensive manual of all ObsidianScout features: match scouting, pit scouting, offline sync, analytics, predictor, gamepads, and AI.",
        keySteps: [
            "1. Live Match Scouting (/scout, /qual-scout): Record robot auto/teleop/endgame cycles.",
            "2. Pit Scouting (/pit-scout): Robot dimensions, drive specs, and mechanism photos.",
            "3. Analytics & Graphs (/graphs, /predictor, /rankings): Multi-metric scatter plots, EPA/xP trends, and Monte Carlo win simulations.",
            "4. Match Planning & Alliances (/match-planning, /alliances): Pre-match game plans and alliance selection pick lists.",
            "5. Local AI Assistant: 100% on-device private LLM for voice/text scouting intelligence and automated visual chart creation."
        ],
        markdown: [
            "# 📖 ObsidianScout Complete User Manual",
            "Welcome to ObsidianScout, the premier quantitative scouting and tactical analytics platform for FIRST Robotics (FRC & FTC).",
            "",
            "---",
            "",
            "### 🧭 Core Features & Quick Links:",
            "- **📊 Live Match Scouting ([`/scout`](/scout) / [`/qual-scout`](/qual-scout)):** Real-time cycle tracking with gamepad support, auto-calculation, and offline draft protection.",
            "- **🛠️ Pit Scouting ([`/pit-scout`](/pit-scout)):** In-depth mechanical specifications, drive types, weight, and camera photo capture.",
            "- **📈 Analytics & Graphs ([`/graphs`](/graphs) / [`/predictor`](/predictor) / [`/rankings`](/rankings)):** 2D scatter plots, radar skills charts, phase stacked bars, and official standings.",
            "- **🎯 Match Planning & Alliances ([`/match-planning`](/match-planning) / [`/alliances`](/alliances)):** Pre-match strategy briefs, alliance win probabilities, and draft pick sheets.",
            "- **📶 Offline Mode & QR Sync ([`/cache-manager`](/cache-manager) / [`/qr-scanner`](/qr-scanner)):** Full zero-connectivity arena support with IndexedDB and air-gapped QR scanning.",
            "- **🤖 Local AI Assistant:** Runs 100% on your device (WebLLM / WebGPU) to analyze trends, generate multi-series line charts, simulate Monte Carlo projections, and produce strategic dossiers."
        ].join("\n")
    };
}

function fmtTeamPage(team) {
    return t("ai.link.team_page", "Open team {team}").replace("{team}", team);
}

/** Enhanced safe arithmetic & statistics evaluator (no eval): supports + - * / % ( ) decimals and mean(), avg(), diff(), sum(), min(), max(), abs(). */
export function evaluate(expression) {
    let clean = String(expression || "").trim();
    if (!clean) throw new Error("Empty expression");

    // Replace mathematical and statistical functions
    const fnRegex = /\b(mean|avg|average|sum|diff|difference|abs|min|max)\s*\(([^()]+)\)/gi;
    let guard = 0;
    while (fnRegex.test(clean) && guard++ < 20) {
        clean = clean.replace(fnRegex, (_, fn, argsStr) => {
            const nums = argsStr.split(",").map((s) => evaluate(s.trim()));
            if (!nums.length || nums.some((n) => !Number.isFinite(n))) throw new Error("Invalid function arguments");
            const name = fn.toLowerCase();
            if (name === "mean" || name === "avg" || name === "average") {
                return String(nums.reduce((a, b) => a + b, 0) / nums.length);
            }
            if (name === "sum") {
                return String(nums.reduce((a, b) => a + b, 0));
            }
            if (name === "diff" || name === "difference") {
                return String(nums.length === 2 ? (nums[0] - nums[1]) : Math.abs(nums[0] - (nums[1] || 0)));
            }
            if (name === "abs") {
                return String(Math.abs(nums[0]));
            }
            if (name === "min") {
                return String(Math.min(...nums));
            }
            if (name === "max") {
                return String(Math.max(...nums));
            }
            return String(nums[0]);
        });
    }

    const tokens = clean.match(/\d+(?:\.\d+)?|[-+*/%()]/g);
    if (!tokens || tokens.join("") !== clean.replace(/\s+/g, "")) throw new Error("Unsupported expression: " + clean);
    let i = 0;
    const peek = () => tokens[i];
    const next = () => tokens[i++];
    function primary() {
        const tok = next();
        if (tok === "(") { const v = sum(); if (next() !== ")") throw new Error("Missing )"); return v; }
        if (tok === "-") return -primary();
        if (tok === "+") return primary();
        const n = Number(tok);
        if (!Number.isFinite(n)) throw new Error("Bad number");
        return n;
    }
    function product() {
        let v = primary();
        while (["*", "/", "%"].includes(peek())) {
            const op = next();
            const r = primary();
            v = op === "*" ? v * r : op === "/" ? v / r : v % r;
        }
        return v;
    }
    function sum() {
        let v = product();
        while (["+", "-"].includes(peek())) {
            const op = next();
            const r = product();
            v = op === "+" ? v + r : v - r;
        }
        return v;
    }
    const value = sum();
    if (i !== tokens.length) throw new Error("Unexpected input");
    return value;
}

const INTENTS = {
    next: { en: /\bnext match\b|\bour next\b/, intl: ["próximo partido", "proximo partido", "siguiente partido", "sıradaki maç", "sonraki maç", "המשחק הבא"] },
    match: { en: /\b(match|qm)\b/, intl: ["partido", "maç", "משחק"] },
    strategy: { en: /\b(strategy|game plan|tactics|briefing|pre-match brief|match plan|plan for match|dossier for match)\b/, intl: ["estrategia", "plan de juego", "taktik", "maç planı", "אסטרטגיה", "תוכנית משחק"] },
    worksheet: { en: /\b(worksheet|draft board|pick sheet|alliance sheet|draft list)\b/, intl: ["hoja de selección", "seçim tablosu", "דף בחירה"] },
    dossier: { en: /\b(dossier|scout report|full report|profile card|team report|deep dive)\b/, intl: ["informe", "reporte", "rapor", "דוח"] },
    stacked: { en: /\b(stacked|breakdown|phase breakdown|auto teleop endgame|phases)\b/, intl: ["apilad", "desglose", "fases"] },
    radar: { en: /\b(radar|spider|polar|skills? profile)\b/, intl: ["radar", "örümcek"] },
    box: { en: /\b(box plot|distribution|spread|variance plot|consistency plot)\b/, intl: ["diagrama de caja", "kutu grafiği"] },
    notes: { en: /\b(summar|notes?\b|scouts? (say|said|think|wrote)|qualitative|comments?)/, intl: ["notas", "resum", "comentario", "notlar", "özet", "yorum", "הערות", "סכם", "סיכום"] },
    pick: { en: /\b(pick|picks|picklist|pick list|alliance partner|who should we (pick|choose|select))\b/, intl: ["elegir", "escoger", "seleccionar", "selección", "seçmeli", "seçelim", "seçim", "לבחור", "בחירה"] },
    both: { en: /\bboth\b|\bas well as\b/, intl: ["ambos", "tanto en", " hem ", "וגם", "גם ב"] },
    trend: { en: /\b(trend|trends|trending|match by match|match-by-match|by match|each match|per match|over time|timeline|progress|progression|game by game|match-to-match|match to match|match graph|match chart|by-match)\b/i, intl: ["tendencia", "evolución", "partido a partido", "por partido", "zamanla", "maç maç", "gelişim", "מגמה", "לאורך"] },
    line: { en: /\b(line\s*graph|line\s*chart|line\s*plot|lines)\b/i, intl: ["gráfico de líneas", "çizgi grafiği", "גרף קווי"] },
    scatter: { en: /\b(scatter|scatter\s*plot|scatter\s*chart|correlat|scatter\s*graph)\b/i, intl: ["dispersión", "correlación", "dağılım", "korelasyon", "פיזור", "מתאם"] },
    difference: { en: /\b(diff|difference|average difference|mean difference|how far apart|gap)\b/i, intl: ["diferencia", "fark", "הפרש"] },
    versus: { en: /\bvs\.?\b|\bversus\b|\bagainst\b/, intl: [" frente a ", " contra ", "karşı", "מול"] },
    compare: { en: /\b(compare|vs\.?|versus)\b/, intl: ["compara", "karşılaştır", "השווה", "השוואה"] },
    top: { en: /\b(top|best|highest|most|leading|rank|ranking|worst|lowest|least|bottom|total|averages?)\b/, intl: ["mejor", "más ", "en iyi", "en çok", "en yüksek", "ilk ", "sıralama", "המוביל", "הטוב", "הכי", "דירוג", "peor", "en kötü", "en düşük", "הגרוע", "הנמוכ"] },
    worst: { en: /\b(worst|lowest|least|bottom)\b/, intl: ["peor", "menos", "en kötü", "en düşük", "הגרוע", "הנמוכ"] },
    overview: { en: /\b(tell me about|overview|how good|how is|how are|info|stats|about|profile|scouting on)\b/, intl: ["cuéntame", "qué tal", "cómo es", "información", "hakkında", "nasıl", "bilgi", "ספר לי", "מה עם", "סקירה", "מידע"] },
    chart: { en: /\b(graph|chart|plot|visuali[sz]e|show me|draw)\b/i, intl: ["gráfic", "grafik", "göster", "גרף", "תרשים", "הצג"] },
    docs: { en: /\b(how (?:do|can) (?:i|we) (?:use|scout|export|sync|login|set up|setup|configure)|how does .* work|help with|documentation|site manual|user guide|user manual|how to use (?:the site|obsidianscout)|tutorial|walkthrough|manual|docs?)\b/i, intl: ["cómo usar", "como usar", "ayuda", "nasıl kullanılır", "מדריך"] },
    all_matches: { en: /\b(all matches|match schedule|matches schedule|match list|all games|qualification matches|list of matches|show matches|show all matches|matches at event|event matches|schedule)\b/i, intl: ["todos los partidos", "tüm maçlar", "כל המשחקים", "לוח משחקים"] },
    all_teams: { en: /\b(all teams|team list|list of teams|teams attending|attending teams|teams at event|roster|all competitors|team roster)\b/i, intl: ["todos los equipos", "tüm takımlar", "כל הקבוצות", "רשימת קבוצות"] },
    predictions: { en: /\b(match predictions?|predict matches|prediction list|all predictions|win probability for matches|upcoming predictions|match win prob|match odds)\b/i, intl: ["predicciones", "tahminler", "תחזיות"] },
    rankings: { en: /\b(current rankings?|official rankings?|current standings?|standings? table|rankings? table|event standings?|leaderboard|current rank)\b/i, intl: ["clasificación actual", "mevcut sıralama", "דירוג נוכחי"] },
    projected: { en: /\b(projected|projection|simulat|predicted ranking|predicted rankings|predicted end|end ranking|end rankings|end results|end resaults|end of event|final standing|final standings|playoff chance)\b/i, intl: ["proyectado", "simulado", "tahmin"] },
    artifact: { en: /\b(artifact|document|artifact report)\b/, intl: ["artefacto", "belge"] },
    match_count: { en: /\b(?:how many (?:matches|games|events)|how many has|did (?:they|it|\d+) play|have (?:they|it|\d+) played|played matches|no scouting reports|not played matches|not scouted|how many|played any matches|confirm that|please confirm|check again|check that)\b/i, intl: ["cuántos partidos", "cuantos partidos", "kaç maç", "כמה משחקים"] },
    capabilities: { en: /\b(what (?:can|do) you do|what are (?:you|your capabilities|your features|your tools|your functions|your apis)|how (?:can|do) (?:you|i) (?:help|use)|who are you|help me|help|what tools?|what apis?|what tool\s*calls?|what functions?|available tools?|available apis?|avalible tools?|avalible apis?|apis and toolcalls)\b/i, intl: ["qué puedes hacer", "que puedes hacer", "ayuda", "ne yapabilirsin", "yardım", "מה אתה יכול לעשות", "עזרה"] }
};

function hasIntent(q, name) {
    const intent = INTENTS[name];
    if (!intent || !intent.en) return false;
    const str = String(q || "").toLowerCase();
    return intent.en.test(str) || (Array.isArray(intent.intl) && intent.intl.some((w) => str.includes(w)));
}

export function ruleRoute(question, ctx, history = []) {
    const q = ` ${String(question).toLocaleLowerCase()} `;
    // "match 35" / "partido 35" / "משחק 35", or Turkish word order "35. maç".
    const matchRef = q.match(/(?:match|qm|q|partido|maç|משחק)\s*#?\s*(\d{1,3})(?!\d)/) || q.match(/(?<!\d)(\d{1,3})\.?\s*(?:maç|partido)/);
    const allNumbers = (q.match(/\d{1,5}/g) || []).map(Number);
    const rawTeams = Array.from(new Set(allNumbers.filter((x) => ctx.stats.has(x) && (!matchRef || x !== Number(matchRef[1])))));

    // Filter out teams that are explicitly negated ("1209 and 1561 arent in...", "not 1209", "dont include 1209")
    const negatedTeams = new Set();
    rawTeams.forEach((t) => {
        const negPattern = new RegExp(`(?:aren'?t|isn'?t|not|except|excluding|dont|don'?t|without)\\s+(?:in\\s+)?(?:match\\s+\\d+\\s+)?(?:[\\w\\s,]*?)\\b${t}\\b|\\b${t}\\b\\s+(?:(?:and\\s+\\d+\\s+)?(?:aren'?t|isn'?t|are\\s+not|not\\s+in))`, "i");
        if (negPattern.test(q)) negatedTeams.add(t);
    });
    const teams = rawTeams.filter((t) => !negatedTeams.has(t));
    const hasAllTeams = /\b(?:all|all teams|all of the teams|every team|everyone|todos)\b/i.test(q);

    // Fallback: if no team is in the query, extract the most recent team referenced in history
    let conversationTeams = [...teams];
    if (conversationTeams.length === 0 && Array.isArray(history) && history.length > 0) {
        for (let i = history.length - 1; i >= 0; i--) {
            const hText = String(history[i]?.content || "");
            const hNumbers = (hText.match(/\d{1,5}/g) || []).map(Number);
            const hTeams = hNumbers.filter((x) => ctx.stats.has(x));
            if (hTeams.length > 0) {
                conversationTeams = Array.from(new Set(hTeams));
                break;
            }
        }
    }

    // Resolve match teams if match reference exists
    let matchObj = null;
    let matchTeams = [];
    if (matchRef) {
        matchObj = Data.findMatch(ctx, matchRef[1]);
        if (matchObj) {
            matchTeams = [...(matchObj.redTeams || []), ...(matchObj.blueTeams || [])]
                .map(Data.teamNumberFromKey)
                .filter((t) => t && ctx.stats.has(t));
        }
    }

    // Documentation / how-to-use-site questions (high priority)
    if (hasIntent(q, "docs") || /\b(?:how to use|how do i use|how does the site|guide for|instructions for)\b/i.test(q)) {
        return { tool: "read_docs", args: { topic: q } };
    }

    // Match Predictions:
    if (hasIntent(q, "predictions")) {
        return { tool: "match_predictions", args: { match: matchRef ? matchRef[1] : undefined, team: teams[0] || conversationTeams[0] || undefined } };
    }

    // Current Official Standings / Rankings:
    if (hasIntent(q, "rankings") && !hasIntent(q, "projected")) {
        return { tool: "current_rankings", args: { n: n, chart: wantsChart } };
    }

    // All Matches schedule request:
    if (hasIntent(q, "all_matches") || (hasIntent(q, "match") && (hasAllTeams || /\bschedule\b/i.test(q)))) {
        return { tool: "all_matches", args: { team: teams[0] || undefined } };
    }

    // All Teams roster request:
    if (hasIntent(q, "all_teams") || (hasAllTeams && !metric && teams.length === 0)) {
        return { tool: "all_teams", args: {} };
    }

    // "top 5" / "5 mejores" / "ilk 5": a small number that isn't a team or match number.
    const n = hasIntent(q, "top") || hasIntent(q, "pick")
        ? allNumbers.find((x) => x >= 1 && x <= 30 && !ctx.stats.has(x) && (!matchRef || x !== Number(matchRef[1])))
        : undefined;
    const metric = Data.findMetric(ctx, q);
    const metricPhrases = q.split(/\bvs\.?\b|\bversus\b|\band\b|\by\b|\bve\b|\bfrente a\b|,|וגם|\sו/).map((p) => Data.findMetric(ctx, p)).filter(Boolean);
    const distinctMetrics = metricPhrases.filter((m, i) => metricPhrases.findIndex((x) => x.id === m.id) === i);

    const wantsChart = hasIntent(q, "chart");
    const isProjection = hasIntent(q, "projected") || /\b(?:where will|where does|what rank will|what rank is|what place|where in rankings|finishing rank|finish|final rank|end up)\b/i.test(q);

    if (hasIntent(q, "capabilities") && teams.length === 0 && !metric) {
        return { tool: "capabilities_help", args: {} };
    }
    if (isProjection) {
        const targetTeam = teams[0] || conversationTeams[0] || (allNumbers.length === 1 && (!matchRef || allNumbers[0] !== Number(matchRef[1])) ? allNumbers[0] : undefined);
        return { tool: "projected_rankings", args: { team: targetTeam, n: n, chart: wantsChart } };
    }

    // Match count or scouting presence queries (e.g. "how many matches have they played", "did they play", "no scouting reports")
    if (hasIntent(q, "match_count") && (teams.length > 0 || conversationTeams.length > 0)) {
        const targetTeam = teams[0] || conversationTeams[0];
        return { tool: "team_overview", args: { team: targetTeam, chart: wantsChart } };
    }

    // Comparing metrics or difference calculation (e.g. "average points difference between EPA and xP", "EPA vs xP")
    if (hasIntent(q, "difference") || (distinctMetrics.length >= 2 && (hasIntent(q, "versus") || hasIntent(q, "compare") || hasIntent(q, "both")))) {
        return {
            tool: "compare_metrics",
            args: {
                metric_1: distinctMetrics[0] ? distinctMetrics[0].label : (metric ? metric.label : "EPA"),
                metric_2: distinctMetrics[1] ? distinctMetrics[1].label : (distinctMetrics[0] ? undefined : "xP"),
                n: n || (hasAllTeams ? undefined : (teams.length >= 2 ? teams.length : undefined)),
                chart: wantsChart
            }
        };
    }

    // Artifact requests:
    if (hasIntent(q, "strategy") || (matchRef && hasIntent(q, "artifact"))) {
        return { tool: "create_strategy_brief", args: { match: matchRef ? matchRef[1] : "next" } };
    }
    if (hasIntent(q, "worksheet") || (hasIntent(q, "pick") && hasIntent(q, "artifact"))) {
        return { tool: "create_alliance_sheet", args: { focus: metric ? metric.label : undefined } };
    }
    if (hasIntent(q, "dossier") && (teams.length === 1 || conversationTeams.length === 1 || allNumbers.length === 1)) {
        return { tool: "create_team_dossier", args: { team: teams[0] || conversationTeams[0] || allNumbers[0] } };
    }

    // Specialized Chart Types:
    if (hasIntent(q, "radar") && (teams.length > 0 || conversationTeams.length > 0)) {
        return { tool: "team_radar", args: { teams: teams.length ? teams : conversationTeams } };
    }
    if (hasIntent(q, "stacked") || (wantsChart && q.includes("breakdown"))) {
        return { tool: "stacked_breakdown", args: { teams: teams.length ? teams : (conversationTeams.length ? conversationTeams : undefined), n: n || 6 } };
    }
    if (hasIntent(q, "box")) {
        return { tool: "score_distribution", args: { teams: teams.length ? teams : (conversationTeams.length ? conversationTeams : undefined), metric: metric ? metric.label : undefined } };
    }

    // Scatter plot (explicitly requested or 2-metric correlation)
    if (hasIntent(q, "scatter") || (wantsChart && /\bscatter\b/i.test(q))) {
        return {
            tool: "scatter",
            args: {
                metric_x: distinctMetrics[0] ? distinctMetrics[0].label : (metric ? metric.label : undefined),
                metric_y: distinctMetrics[1] ? distinctMetrics[1].label : undefined
            }
        };
    }

    // Match-by-match / trend / progression / line chart requests (HIGH PRIORITY for "match by match" / "trend")
    if (hasIntent(q, "trend") || hasIntent(q, "line")) {
        const effectiveTeams = teams.length ? teams : conversationTeams;
        if (effectiveTeams.length === 1) {
            return { tool: "team_matches", args: { team: effectiveTeams[0], metric: metric ? metric.label : undefined, chart: true } };
        }
        return {
            tool: "match_by_match",
            args: {
                teams: effectiveTeams.length ? effectiveTeams : undefined,
                metric: metric ? metric.label : undefined,
                n: n || (hasAllTeams ? ctx.stats.size : 5),
                chart: true
            }
        };
    }

    if (hasIntent(q, "next")) return { tool: "match_preview", args: { match: "next", metric: metric ? metric.label : undefined, chart: wantsChart } };

    // Match-scoped queries:
    if (matchRef) {
        if (metric || wantsChart || hasIntent(q, "compare") || teams.length === 0) {
            if (matchTeams.length > 0 && (metric || wantsChart || hasIntent(q, "compare"))) {
                return { tool: "compare_teams", args: { teams: matchTeams, metrics: metric ? [metric.label] : undefined, chart: wantsChart } };
            }
            return { tool: "match_preview", args: { match: matchRef[1], metric: metric ? metric.label : undefined, chart: wantsChart } };
        }
    }

    if (hasIntent(q, "notes") && (teams.length === 1 || conversationTeams.length === 1 || allNumbers.length === 1)) {
        return { tool: "summarize_notes", args: { team: teams[0] || conversationTeams[0] || allNumbers[0] } };
    }
    if (hasIntent(q, "pick")) return { tool: "pick_candidates", args: { n: n || 8, focus: metric ? metric.label : undefined } };

    if (teams.length >= 2 || (teams.length >= 1 && hasIntent(q, "compare"))) {
        return { tool: "compare_teams", args: { teams, metrics: metric && metric.kind !== "score" ? [metric.label] : undefined, chart: wantsChart } };
    }
    if (hasIntent(q, "top") && teams.length === 0) {
        return { tool: "top_teams", args: { metric: metric ? metric.label : "Total points", n: n, order: hasIntent(q, "worst") ? "asc" : "desc", chart: wantsChart } };
    }
    if (wantsChart) {
        if (teams.length >= 2) {
            return { tool: "compare_teams", args: { teams, metrics: metric ? [metric.label] : undefined, chart: true } };
        }
        if (teams.length === 1 || conversationTeams.length === 1) {
            return { tool: "team_matches", args: { team: teams[0] || conversationTeams[0], chart: true } };
        }
        return { tool: "top_teams", args: { metric: metric ? metric.label : (ctx.metrics[0]?.label || "Total points"), n: n, chart: true } };
    }
    if ((teams.length === 1 || conversationTeams.length === 1 || allNumbers.length === 1) && (hasIntent(q, "overview") || hasIntent(q, "match_count") || q.trim().split(/\s+/).length <= 4)) {
        return { tool: "team_overview", args: { team: teams[0] || conversationTeams[0] || allNumbers[0], chart: wantsChart } };
    }
    return null;
}

// ------------------------------------------------------------------ model-driven routing

function toolCatalog(ctx, allowed) {
    return Object.entries(TOOLS).filter(([name]) => allowed.includes(name))
        .map(([name, tool]) => `- ${name}(${Object.entries(tool.params).map(([k, v]) => `${k}: ${v}`).join(", ")}): ${tool.description}`)
        .join("\n");
}

function metricList(ctx) {
    return ctx.metrics.map((m) => m.label).slice(0, 40).join(", ");
}

const TOOL_ARGS_SCHEMA = {
    type: "object",
    properties: {
        team: { type: "integer" },
        teams: { type: "array", items: { type: "integer" } },
        metric: { type: "string" },
        metrics: { type: "array", items: { type: "string" } },
        metric_x: { type: "string" },
        metric_y: { type: "string" },
        n: { type: "integer" },
        order: { type: "string", enum: ["desc", "asc"] },
        match: { type: "string" },
        focus: { type: "string" },
        chart: { type: "boolean" },
        title: { type: "string" },
        type: { type: "string" },
        markdown: { type: "string" },
        expression: { type: "string" },
        topic: { type: "string" },
        unplayed_only: { type: "boolean" },
        order_by: { type: "string" },
        conditions: { type: "array", items: { type: "object", properties: { metric: { type: "string" }, op: { type: "string" }, value: { type: "number" } } } }
    }
};

function stepSchema(allowed) {
    return {
        type: "object",
        properties: {
            action: { type: "string", enum: ["call", "calls", "answer"] },
            tool: { type: "string", enum: allowed },
            args: TOOL_ARGS_SCHEMA,
            calls: {
                type: "array",
                items: {
                    type: "object",
                    properties: {
                        tool: { type: "string", enum: allowed },
                        args: TOOL_ARGS_SCHEMA
                    },
                    required: ["tool"]
                }
            },
            tools: {
                type: "array",
                items: {
                    type: "object",
                    properties: {
                        tool: { type: "string", enum: allowed },
                        args: TOOL_ARGS_SCHEMA
                    },
                    required: ["tool"]
                }
            }
        },
        required: ["action"]
    };
}

export function parseJsonLoose(text) {
    if (!text) return null;
    const start = text.indexOf("{");
    if (start < 0) return null;
    let depth = 0;
    for (let i = start; i < text.length; i++) {
        if (text[i] === "{") depth++;
        else if (text[i] === "}") {
            depth--;
            if (depth === 0) {
                try { return JSON.parse(text.slice(start, i + 1)); } catch (_) { return null; }
            }
        }
    }
    return null;
}

/**
 * Extracts a list of tool call objects [{ tool, args }] from model output.
 * Supports:
 * - Single call: {"action":"call", "tool":"...", "args":{...}}
 * - Multiple calls in array: {"action":"calls", "calls":[{"tool":"...", "args":{...}}, ...]}
 * - Multiple calls in tools: {"action":"call", "tools":[{"tool":"...", "args":{...}}, ...]}
 * - Raw array of calls: [{"tool":"...", "args":{...}}, ...]
 * - Multiple separate JSON blocks emitted in text
 */
export function parseToolCalls(text, allowed) {
    if (!text) return [];
    const calls = [];
    const isAllowed = (name) => name && (!allowed || allowed.includes(name)) && TOOLS[name];

    const ingest = (obj) => {
        if (!obj) return;
        if (Array.isArray(obj)) {
            obj.forEach(ingest);
            return;
        }
        if (typeof obj !== "object") return;
        if (obj.action === "answer") return;

        if (Array.isArray(obj.calls)) {
            obj.calls.forEach(ingest);
        }
        if (Array.isArray(obj.tools)) {
            obj.tools.forEach(ingest);
        }
        if (obj.tool && isAllowed(obj.tool)) {
            calls.push({ tool: obj.tool, args: obj.args || {} });
        }
    };

    try {
        const parsed = JSON.parse(text.trim());
        ingest(parsed);
        if (calls.length > 0) return calls;
    } catch (_) { /* fall through to balanced block extractor */ }

    let i = 0;
    while (i < text.length) {
        const startBrace = text.indexOf("{", i);
        const startBracket = text.indexOf("[", i);
        let start = -1;
        let isArray = false;

        if (startBrace >= 0 && startBracket >= 0) {
            if (startBrace < startBracket) {
                start = startBrace;
                isArray = false;
            } else {
                start = startBracket;
                isArray = true;
            }
        } else if (startBrace >= 0) {
            start = startBrace;
            isArray = false;
        } else if (startBracket >= 0) {
            start = startBracket;
            isArray = true;
        } else {
            break;
        }

        const openChar = isArray ? "[" : "{";
        const closeChar = isArray ? "]" : "}";
        let depth = 0;
        let end = -1;

        for (let j = start; j < text.length; j++) {
            if (text[j] === openChar) depth++;
            else if (text[j] === closeChar) {
                depth--;
                if (depth === 0) {
                    end = j;
                    break;
                }
            }
        }

        if (end > start) {
            try {
                const subObj = JSON.parse(text.slice(start, end + 1));
                ingest(subObj);
            } catch (_) { /* continue */ }
            i = end + 1;
        } else {
            i = start + 1;
        }
    }

    return calls;
}

function routerSystemPrompt(ctx, allowed, maxCalls) {
    return [
        "You route questions for a FIRST Robotics scouting assistant to data tools. Reply with JSON only.",
        `Event: ${ctx.eventKey || "unknown"}. Our team: ${ctx.ourTeam || "unknown"}.`,
        `Teams with data: ${Array.from(ctx.stats.keys()).slice(0, 80).join(", ")}.`,
        `Metrics: ${metricList(ctx)}.`,
        "Tools:",
        toolCatalog(ctx, allowed),
        "",
        "TOOL CALLING INSTRUCTIONS (Single or Multiple Tools):",
        "- You may call a SINGLE tool: {\"action\":\"call\",\"tool\":...,\"args\":{...}}",
        "- You may call MULTIPLE tools in a single response: {\"action\":\"calls\",\"calls\":[{\"tool\":\"...\",\"args\":{...}},{\"tool\":\"...\",\"args\":{...}}]}",
        "- When you have enough data to answer, reply with: {\"action\":\"answer\"}",
        "",
        'Example single: "who has the best auto?" -> {"action":"call","tool":"top_teams","args":{"metric":"Auto points","n":5}}',
        'Example multiple: "how does 254 compare with 1678 and what are the projected rankings?" -> {"action":"calls","calls":[{"tool":"compare_teams","args":{"teams":[254,1678]}},{"tool":"projected_rankings","args":{"n":10}}]}',
        'Example multiple: "show me top teams by xP and by OPR" -> {"action":"calls","calls":[{"tool":"top_teams","args":{"metric":"xP","n":5}},{"tool":"top_teams","args":{"metric":"OPR","n":5}}]}',
        'Example: "scatter total points vs auto points" -> {"action":"call","tool":"scatter","args":{"metric_x":"Total points","metric_y":"Auto points"}}',
        'Example: "graph team 1209 match by match" -> {"action":"call","tool":"metric_trend","args":{"teams":[1209],"metric":"Total points"}}',
        'Example: "match 13 xP graph" -> {"action":"call","tool":"match_preview","args":{"match":"13","metric":"xP"}}',
        'Example: "create a game plan for match 13" -> {"action":"call","tool":"create_strategy_brief","args":{"match":"13"}}',
        'Example: "alliance selection worksheet" -> {"action":"call","tool":"create_alliance_sheet","args":{}}',
        'Example: "radar chart for team 254" -> {"action":"call","tool":"team_radar","args":{"teams":[254]}}',
        'Example: "phase breakdown of top 5" -> {"action":"call","tool":"stacked_breakdown","args":{"n":5}}',
        'Example: "score distribution box plot" -> {"action":"call","tool":"score_distribution","args":{}}',
        'Example: "what is the projected end of the event / predicted rankings?" -> {"action":"call","tool":"projected_rankings","args":{"n":10}}',
        'Example: "what can you do?" -> {"action":"call","tool":"capabilities_help","args":{}}',
        "Only pass team numbers the user mentioned or that a previous tool result returned; never guess teams or carry forward stale teams from previous questions. To find teams by ability use top_teams, scatter or filter_teams."
    ].join("\n");
}

function compactFacts(results, budgetChars) {
    let text = results.map((r) => `### ${r.tool}(${JSON.stringify(r.args)})\n${JSON.stringify(r.facts)}`).join("\n\n");
    if (text.length > budgetChars) text = text.slice(0, budgetChars) + "\n...(truncated)";
    return text;
}

function eventDigest(ctx) {
    const top = Data.rankTeams(ctx, ctx.metrics[0]).slice(0, 8);
    return {
        event: ctx.eventKey, our_team: ctx.ourTeam, teams_with_data: ctx.stats.size, matches_in_schedule: ctx.matches.length,
        top_by_total_points: top.map((r) => ({ team: r.teamNumber, avg: r1(r.avg) })),
        metrics_available: ctx.metrics.map((m) => m.label).slice(0, 25)
    };
}

// ------------------------------------------------------------------ deterministic briefs
// A plain-language summary of each tool result, built by code. Lite shows these instead of model prose;
// larger models get them as key points to build on.

function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined && values[k] !== null ? values[k] : "-"));
}

function nameOf(entry) {
    return entry.name ? `${entry.team} ${entry.name}` : String(entry.team);
}

export function briefFor(result) {
    const f = result.facts || {};
    if (f.error) return f.error;
    switch (result.tool) {
        case "team_overview": {
            const avg = f.averages || {};
            const keys = Object.keys(avg);
            const tmName = f.name ? `Team ${f.team} (${f.name})` : `Team ${f.team}`;
            const played = f.matches_played_at_event ?? 0;
            const sched = f.matches_scheduled_at_event ?? 0;
            const scouted = f.matches_scouted_by_our_team ?? 0;
            const seasonN = f.season_matches_in_external_database;
            const matchSummary = scouted === 0
                ? `${tmName} has played ${played} match${played === 1 ? "" : "es"} on the field at this event${sched > 0 ? ` (out of ${sched} scheduled)` : ""}, but 0 match reports have been locally submitted by our scouting team yet.${seasonN ? ` Their EPA/OPR/xP statistics are based on ${seasonN} season matches.` : ""}`
                : `${tmName} averages ${avg[keys[0]] || "-"} total points over ${scouted} locally scouted match${scouted === 1 ? "" : "es"} (${played} played at event${sched > 0 ? ` of ${sched} scheduled` : ""}, rank ${f.rank_by_total_points || "-"}).`;
            const lines = [matchSummary];
            if (keys.length > 1) lines.push(keys.slice(1, 6).map((k) => `${k}: ${avg[k]}`).join(" · "));
            if (f.pit && f.pit.length) lines.push(`${t("ai.brief.pit", "Pit")}: ${f.pit.slice(0, 3).join("; ")}`);
            return lines.join("\n\n");
        }
        case "top_teams":
            return [fmt(t("ai.brief.top", "Top {n} by {metric}:"), { n: f.teams.length, metric: f.metric }),
                ...f.teams.map((r) => `${r.rank}. ${nameOf(r)} - ${r.avg}`)].join("\n");
        case "compare_teams": {
            const teams = Object.entries(f.teams || {});
            if (!teams.length) return "";
            const metrics = Object.keys(teams[0][1]).filter((k) => k !== "name" && k !== "matches");
            return metrics.map((m) => {
                const ranked = teams.filter(([, v]) => v[m] !== null && v[m] !== undefined).sort((a, b) => b[1][m] - a[1][m]);
                if (!ranked.length) return null;
                return fmt(t("ai.brief.compare", "{metric}: {team} leads with {value}"), { metric: m, team: ranked[0][0], value: ranked[0][1][m] }) +
                    (ranked.length > 1 ? ` (${ranked.slice(1).map(([team, v]) => `${team}: ${v[m]}`).join(", ")})` : "");
            }).filter(Boolean).map((l) => `- ${l}`).join("\n");
        }
        case "compare_metrics":
            return f.summary || `${f.metric_1} averages ${f.average_metric_1} vs ${f.metric_2} ${f.average_metric_2} (diff: ${f.average_difference})`;
        case "team_matches": {
            const played = f.matches_played_at_event ?? f.matches_played ?? (f.individual_matches ? f.individual_matches.length : 0);
            const scouted = f.matches_scouted_by_our_team ?? 0;
            const sched = f.matches_scheduled_at_event ?? played;
            const tmName = f.name ? `Team ${f.team} (${f.name})` : `Team ${f.team}`;
            const header = scouted === 0
                ? `${tmName} has played ${played} match${played === 1 ? "" : "es"} at this event (${sched} scheduled, 0 scouted locally by our team):`
                : `Individual match scores for ${tmName} (${scouted} locally scouted of ${played} played):`;
            return [
                header,
                ...(f.individual_matches || []).slice(0, 15).map((m) => {
                    if (Array.isArray(m)) return `- ${m[0]}: ${m.slice(1).join(" | ")}`;
                    return `- ${m.match}: Total ${m.total ?? "-"} (Auto: ${m.auto ?? "-"}, Teleop: ${m.teleop ?? "-"}, Endgame: ${m.endgame ?? "-"})`;
                })
            ].join("\n");
        }
        case "metric_trend":
            return Object.entries(f.by_match || {}).map(([team, pts]) => pts.length
                ? `- ${fmt(t("ai.brief.trend", "{team}: {first} → {last} over {n} matches"), { team, first: pts[0].value, last: pts[pts.length - 1].value, n: pts.length })}`
                : null).filter(Boolean).join("\n");
        case "scatter":
            return [fmt(t("ai.brief.scatter", "Strongest on both {x} and {y}:"), { x: f.x_metric, y: f.y_metric }),
                ...(f.strongest_on_both || []).map((p, i) => `${i + 1}. ${p.team} - ${p.x} / ${p.y}`)].join("\n");
        case "projected_rankings": {
            if (f.target_team) {
                const t = f.target_team;
                return `Team ${t.team} (${t.name || ""}) is ${f.is_final ? "officially ranked" : "projected"} #${t.rank} of ${f.total_teams || "the event"} with a Ranking Score of ${t.ranking_score} (Record: ${t.record}, Top-8 Alliance Captain Chance: ${t.top_seed_prob}).`;
            }
            return (f.projected_rankings || []).slice(0, 8).map((r) => `${r.rank}. Team ${r.team} (${r.name || ""}) - ${r.ranking_score} RS (Record: ${r.record || "-"})`).join("\n");
        }
        case "match_preview": {
            const lines = [fmt(t("ai.brief.match", "{match}: from scouting averages Red expects about {red} points and Blue about {blue}."), {
                match: f.match, red: f.red_expected_total_points, blue: f.blue_expected_total_points
            })];
            if (f.final_score) lines.push(fmt(t("ai.brief.final", "Final score: Red {red} - Blue {blue}."), f.final_score));
            if (f.site_prediction) lines.push(`${t("ai.brief.prediction", "Site prediction")}: ${Object.entries(f.site_prediction).map(([k, v]) => `${k} ${v}`).join(", ")}`);
            return lines.join("\n\n");
        }
        case "pick_candidates":
            return [fmt(t("ai.brief.picks", "Best pick candidates by {metric} (excluding our team):"), { metric: f.ranked_by }),
                ...(f.candidates || []).map((c) => `${c.rank}. ${nameOf(c)} - ${c.avg} (${t("ai.col.stdev", "Std dev")} ${c.stdev})`)].join("\n");
        case "filter_teams":
            return fmt(t("ai.brief.filter", "{count} teams match {conditions}: {teams}"), {
                count: f.count, conditions: (f.conditions || []).join(" & "), teams: (f.matching_teams || []).join(", ") || "-"
            });
        case "capabilities_help":
            return f.summary || "I can analyze match data, plot trends, generate alliance selection worksheets, simulate projected event rankings, and build tactical strategy artifacts.";
        case "all_matches":
            return `The event schedule contains ${f.total_matches} matches (${f.completed_matches} completed, ${f.upcoming_matches} upcoming).`;
        case "all_teams":
            return `There are ${f.total_teams} teams registered at this event.`;
        case "match_predictions":
            return (f.predictions || []).slice(0, 5).map((p) => `- ${p.match}: ${p.predicted_winner.toUpperCase()} favored (${p.win_probability}) with projected score ${p.projected_red_score} - ${p.projected_blue_score}`).join("\n");
        case "current_rankings":
            return (f.rankings || []).slice(0, 8).map((r) => `${r.rank !== 999 ? `${r.rank}.` : "-"} Team ${r.team} (${r.name || ""}) - ${r.ranking_score} RS (Record: ${r.record || "-"})`).join("\n");
        case "read_docs":
            return f.summary || `ObsidianScout Documentation: ${f.title}`;
        case "calculate":
            return `${f.expression} = ${f.result}`;
        default:
            return "";
    }
}

// ------------------------------------------------------------------ number guardrail & fact verification

function collectNumbers(value, out) {
    if (value === null || value === undefined) return;
    if (typeof value === "number") {
        [0, 1, 2].forEach((d) => out.add(String(Data.round(value, d))));
        out.add(String(Math.round(value)));
        return;
    }
    if (typeof value === "string") {
        (value.match(/-?\d+(?:\.\d+)?/g) || []).forEach((n) => { out.add(String(Number(n))); });
        return;
    }
    if (Array.isArray(value)) { value.forEach((v) => collectNumbers(v, out)); return; }
    if (typeof value === "object") Object.entries(value).forEach(([k, v]) => { collectNumbers(k, out); collectNumbers(v, out); });
}

/** Numbers in the answer that don't appear in the tool facts or the question. */
export function unverifiedNumbers(answer, results, question, ctx) {
    const allowed = new Set();
    results.forEach((r) => { collectNumbers(r.facts, allowed); collectNumbers(r.args, allowed); });
    collectNumbers(question, allowed);
    ctx.stats.forEach((_, team) => allowed.add(String(team)));
    for (let i = 0; i <= 10; i++) allowed.add(String(i));
    const found = (String(answer).replace(/\b(?:q|qm|match)\s*\d+\b/gi, "").match(/-?\d+(?:\.\d+)?/g) || []).map((n) => String(Number(n)));
    return Array.from(new Set(found.filter((n) => !allowed.has(n) && !allowed.has(String(Math.abs(Number(n)))))));
}

/** Cleans canned LLM refusal boilerplate regarding inability to create graphs/charts or tool access. */
export function cleanGraphRefusalText(text, hasVisual) {
    if (!text) return "";
    const generalRefusals = [
        /As a text-based AI, I (?:do not|don't) have access to (?:external )?(?:apis?|tool\s*calls?)[^.]*\.\s*/gi,
        /My abilities are confined to processing and generating text[^.]*\.\s*/gi,
        /I (?:cannot|can't) interact with the real world or access any external systems\.\s*/gi
    ];
    const visualRefusals = [
        /I (?:do not|don't) have the (?:capability|ability) to (?:generate|create|display|render|draw) graphs?(?: directly)?(?: from the provided data)?[^.]*\.\s*/gi,
        /As an AI(?: language model)?, I cannot (?:generate|create|draw|display|render) (?:graphs?|charts?|plots?|visualizations?)[^.]*\.\s*/gi,
        /I am unable to (?:produce|create|plot|display|render) (?:graphs?|charts?|plots?)[^.]*\.\s*/gi,
        /I cannot directly (?:create|generate|produce) (?:graphs?|charts?)[^.]*\.\s*/gi
    ];
    let cleaned = text;
    generalRefusals.forEach((regex) => { cleaned = cleaned.replace(regex, ""); });
    if (hasVisual) {
        visualRefusals.forEach((regex) => { cleaned = cleaned.replace(regex, ""); });
    }
    return cleaned.trim();
}

/**
 * Stricter Fact Checking Engine:
 * 1. Extracts verified (teamNumber, metricLabel, value) triples from tool results.
 * 2. Scans generated text for sentences attributing numbers to teams.
 * 3. Catches hallucinations (such as attributing another team's 95 pts to 'our team' 5454).
 * 4. Replaces or corrects hallucinated text with 100% verified deterministic briefs.
 */
export function verifyAndCorrectAnswer(text, results, question, ctx) {
    if (!text || !results || !results.length) return { text: text || "", unverified: [], hadHallucination: false };

    // Extract thoughts and user-facing content so internal scratchpad reasoning isn't wiped or falsely flagged
    const { thought, content, isThinking } = parseThoughtAndContent(text);
    const checkText = content || (isThinking ? "" : text);

    // Build verified fact lookup
    // Map: teamNumber -> Map<metricKeyOrLower, number>
    const teamStats = new Map();
    const verifiedNumbers = new Set();
    const allAllowedNumbers = new Set();
    const matchRosters = new Map(); // matchNumberOrKey -> Set<teamNumber>

    results.forEach((r) => {
        collectNumbers(r.facts, allAllowedNumbers);
        collectNumbers(r.args, allAllowedNumbers);
        const f = r.facts || {};
        // team_overview
        if (f.team && f.averages) {
            const m = teamStats.get(f.team) || new Map();
            Object.entries(f.averages).forEach(([k, v]) => {
                if (typeof v === "number") {
                    m.set(k.toLowerCase(), v);
                    verifiedNumbers.add(v);
                }
            });
            teamStats.set(f.team, m);
        }
        // top_teams
        if (f.teams && Array.isArray(f.teams)) {
            f.teams.forEach((tm) => {
                const m = teamStats.get(tm.team) || new Map();
                if (tm.avg !== undefined) {
                    m.set((f.metric || "total points").toLowerCase(), tm.avg);
                    verifiedNumbers.add(tm.avg);
                }
                if (tm.max !== undefined) verifiedNumbers.add(tm.max);
                teamStats.set(tm.team, m);
            });
        }
        // compare_teams
        if (f.teams && typeof f.teams === "object" && !Array.isArray(f.teams)) {
            Object.entries(f.teams).forEach(([tmStr, metrics]) => {
                const tm = Number(tmStr);
                const m = teamStats.get(tm) || new Map();
                Object.entries(metrics || {}).forEach(([k, v]) => {
                    if (typeof v === "number") {
                        m.set(k.toLowerCase(), v);
                        verifiedNumbers.add(v);
                    }
                });
                teamStats.set(tm, m);
            });
        }
        // match_preview
        if (f.match && (f.red_alliance || f.blue_alliance)) {
            const roster = new Set();
            (f.red_alliance || []).forEach((tm) => {
                roster.add(tm.team);
                const m = teamStats.get(tm.team) || new Map();
                Object.entries(tm).forEach(([k, v]) => {
                    if (typeof v === "number" && k !== "team") {
                        m.set(k.toLowerCase(), v);
                        verifiedNumbers.add(v);
                    }
                });
                teamStats.set(tm.team, m);
            });
            (f.blue_alliance || []).forEach((tm) => {
                roster.add(tm.team);
                const m = teamStats.get(tm.team) || new Map();
                Object.entries(tm).forEach(([k, v]) => {
                    if (typeof v === "number" && k !== "team") {
                        m.set(k.toLowerCase(), v);
                        verifiedNumbers.add(v);
                    }
                });
                teamStats.set(tm.team, m);
            });
            matchRosters.set(String(f.match).toLowerCase(), roster);
            const numMatch = String(f.match).match(/\d+/);
            if (numMatch) matchRosters.set(numMatch[0], roster);
        }
        // scatter
        if (f.strongest_on_both && Array.isArray(f.strongest_on_both)) {
            f.strongest_on_both.forEach((p) => {
                const m = teamStats.get(p.team) || new Map();
                if (p.x !== undefined) m.set((f.x_metric || "x").toLowerCase(), p.x);
                if (p.y !== undefined) m.set((f.y_metric || "y").toLowerCase(), p.y);
                teamStats.set(p.team, m);
            });
        }
    });

    collectNumbers(question, allAllowedNumbers);
    ctx.stats.forEach((_, team) => allAllowedNumbers.add(String(team)));
    for (let i = 0; i <= 10; i++) allAllowedNumbers.add(String(i));

    const unverified = unverifiedNumbers(checkText, results, question, ctx);

    // If there are unverified numbers in the text that are not in allowed list, that's a critical hallucination!
    let hadCriticalHallucination = unverified.length > 0;

    // Check sentence by sentence for entity misattribution or match roster hallucinations
    const sentences = checkText.split(/(?<=[.?!])\s+/);
    const ourTeam = ctx.ourTeam ? Number(ctx.ourTeam) : null;

    for (const sentence of sentences) {
        if (hadCriticalHallucination) break;

        // Check if sentence references a match (e.g. "In match 13...")
        const matchMentions = sentence.match(/(?:match|qm|q)\s*#?\s*(\d{1,3})/i);
        if (matchMentions && matchRosters.has(matchMentions[1])) {
            const roster = matchRosters.get(matchMentions[1]);
            const mentionedTeams = Array.from(ctx.stats.keys()).filter((t) => new RegExp(`\\b${t}\\b`).test(sentence));
            for (const t of mentionedTeams) {
                if (!roster.has(t) && !/\b(?:not|aren'?t|isn'?t|exclude|except)\b/i.test(sentence)) {
                    // Hallucination: claimed team t is in match when they are not!
                    hadCriticalHallucination = true;
                    break;
                }
            }
        }

        // Check for team score misattribution for ANY team
        const numbers = (sentence.replace(/\b(?:q|qm|match)\s*\d+\b/gi, "").match(/-?\d+(?:\.\d+)?/g) || []).map(Number).filter((n) => n > 10 || (n > 0 && n % 1 !== 0));
        const mentionedTeams = Array.from(ctx.stats.keys()).filter((t) => new RegExp(`\\b${t}\\b`).test(sentence));
        if (ourTeam && /\b(?:our team|we|our|nosotros|bizim|שלנו)\b/i.test(sentence) && !mentionedTeams.includes(ourTeam)) {
            mentionedTeams.push(ourTeam);
        }

        if (mentionedTeams.length === 1 && numbers.length > 0) {
            const tm = mentionedTeams[0];
            const stats = teamStats.get(tm);
            for (const num of numbers) {
                let matchesStat = false;
                if (stats) {
                    for (const val of stats.values()) {
                        if (Math.abs(val - num) < 0.2) { matchesStat = true; break; }
                    }
                }
                if (!matchesStat) {
                    // Check if this number belongs to a different team in the tool results
                    for (const [otherTm, otherMetrics] of teamStats.entries()) {
                        if (otherTm !== tm) {
                            for (const val of otherMetrics.values()) {
                                if (Math.abs(val - num) < 0.2) {
                                    hadCriticalHallucination = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        // Check rank 1 / leader claim
        const topTeamsResult = results.find((r) => r.tool === "top_teams" && r.facts && Array.isArray(r.facts.teams));
        if (topTeamsResult && topTeamsResult.facts.teams.length > 0) {
            const rank1Team = topTeamsResult.facts.teams[0].team;
            const mentionsLeader = /\b(?:highest|leads|leading|top score|first place|#1|best|most)\b/i.test(sentence);
            if (mentionsLeader) {
                const mentionedTeamsInLeaderSentence = Array.from(ctx.stats.keys()).filter((t) => new RegExp(`\\b${t}\\b`).test(sentence));
                if (mentionedTeamsInLeaderSentence.length > 0 && !mentionedTeamsInLeaderSentence.includes(rank1Team)) {
                    // Critical hallucination: claimed another team was highest when rank1Team is #1!
                    hadCriticalHallucination = true;
                    break;
                }
            }
        }
    }

    const briefs = results.map(briefFor).filter(Boolean);
    let finalOutput = checkText;
    if (hadCriticalHallucination && briefs.length) {
        // Replace hallucinated output with the mathematically exact brief
        finalOutput = briefs.join("\n\n");
    }

    // Clean any graph denial boilerplates
    const hasVisual = results.some((r) => r.chart || r.table || r.artifact);
    finalOutput = cleanGraphRefusalText(finalOutput, hasVisual);

    const fullText = thought ? `<thought>\n${thought}\n</thought>\n\n${finalOutput}` : finalOutput;

    return { text: fullText, unverified: hadCriticalHallucination ? [] : unverified, hadHallucination: hadCriticalHallucination };
}

// ------------------------------------------------------------------ orchestration

/**
 * Answers one question. onEvent receives:
 *   {type:'status', text} | {type:'tool', name, args, result} | {type:'token', full} | {type:'done', text, unverified, results}
 */
export async function answerQuestion({ question, history = [], ctx, tier, signal, onEvent = () => {}, route = null }) {
    const profile = AI.TIER_PROFILES[tier.id] || AI.TIER_PROFILES.lite;
    const allowed = Object.keys(TOOLS).filter((name) => name !== "calculate" || tier.id === "advanced");
    const results = [];

    const wantsChart = hasIntent(question, "chart");
    const runTool = async (name, args) => {
        const tool = TOOLS[name];
        if (!tool) return { facts: { error: `Tool ${name} does not exist.` } };
        const toolArgs = { ...(args || {}) };
        if (wantsChart && toolArgs.chart === undefined && ["top_teams", "compare_teams", "team_overview", "projected_rankings"].includes(name)) {
            toolArgs.chart = true;
        }
        onEvent({ type: "status", text: t("ai.status.tool", "Looking up data ({tool})...").replace("{tool}", name.replace(/_/g, " ")) });
        try {
            const result = await tool.run(ctx, toolArgs, { signal });
            const entry = { tool: name, args: toolArgs, ...result };
            results.push(entry);
            onEvent({ type: "tool", name, args: toolArgs, result: entry });
            return entry;
        } catch (err) {
            const errEntry = { tool: name, args: toolArgs, facts: { error: err.message } };
            results.push(errEntry);
            onEvent({ type: "tool", name, args: toolArgs, result: errEntry });
            return errEntry;
        }
    };

    // 1. Deterministic routing first.
    const routed = route || ruleRoute(question, ctx, history);
    if (routed) await runTool(routed.tool, routed.args);

    // 2. Model routing (more steps on bigger tiers).
    const maxCalls = routed ? (tier.id === "advanced" ? 2 : 0) : profile.maxToolCalls;
    if (maxCalls > 0) {
        onEvent({ type: "status", text: t("ai.status.planning", "Working out what data is needed...") });
        const routerMessages = [{ role: "system", content: routerSystemPrompt(ctx, allowed, maxCalls) }];
        history.slice(-2).forEach((h) => routerMessages.push({ role: h.role, content: String(h.content).slice(0, 250) }));
        let pending = `Question: ${question}`;
        if (results.length) pending += `\nAlready retrieved:\n${compactFacts(results, 800)}`;
        for (let step = 0; step < maxCalls; step++) {
            if (signal && signal.aborted) throw new DOMException("Aborted", "AbortError");
            routerMessages.push({ role: "user", content: pending });
            const cappedRouterMessages = enforceMessageBudget(routerMessages, 3500);
            const raw = await AI.generate(cappedRouterMessages, {
                maxTokens: 280, temperature: 0, signal,
                jsonSchema: profile.toolMode === "json" ? stepSchema(allowed) : null
            });
            routerMessages.push({ role: "assistant", content: raw });
            const toolCalls = parseToolCalls(raw, allowed);
            if (!toolCalls.length) break;

            const subject = (a) => JSON.stringify((a && (a.teams || a.team || a.match)) ?? null);
            const stepFeedback = [];
            let executedCount = 0;

            for (const call of toolCalls) {
                const dup = results.some((r) => r.tool === call.tool &&
                    (JSON.stringify(r.args) === JSON.stringify(call.args || {}) ||
                     (["compare_teams", "team_overview", "match_preview", "summarize_notes", "projected_rankings"].includes(r.tool) && subject(r.args) === subject(call.args))));
                if (dup) continue;

                const entry = await runTool(call.tool, call.args || {});
                executedCount++;
                if (entry && entry.facts && entry.facts.error) {
                    stepFeedback.push(`Execution result for ${call.tool}: ERROR - ${entry.facts.error}`);
                } else {
                    stepFeedback.push(`Execution result for ${call.tool}: SUCCESS\nData:\n${JSON.stringify(entry.facts).slice(0, 1000)}`);
                }
            }

            if (executedCount === 0) break;
            pending = `${stepFeedback.join("\n\n")}\n\nCall additional tools if needed, or reply with {"action":"answer"}.`;
        }
    }

    // Lite's free-form tool picks are unreliable: drop model-chosen calls that failed, so the answer falls
    // back to the event digest instead of the model improvising around an error.
    if (tier.id === "lite") {
        for (let i = results.length - 1; i >= 0; i--) {
            const r = results[i];
            if (r.facts && r.facts.error && !(routed && r.tool === routed.tool)) results.splice(i, 1);
        }
    }

    // A notes summary is already a finished answer.
    const finalTool = results.find((r) => r.final);
    if (finalTool && results.length === 1) {
        onEvent({ type: "done", text: finalTool.markdown, unverified: [], results });
        return { text: finalTool.markdown, results };
    }

    // 3a. Lite: answer with the code-built briefs (accurate by construction) rather than 0.5B prose.
    const briefs = results.map(briefFor).filter(Boolean);
    if (tier.id === "lite" && briefs.length) {
        const text = briefs.join("\n\n");
        onEvent({ type: "done", text, unverified: [], results });
        return { text, results, unverified: [] };
    }

    // 3b. Grounded answer.
    onEvent({ type: "status", text: t("ai.status.writing", "Writing answer...") });
    let factsText = results.length ? compactFacts(results, profile.contextChars - 1800) : JSON.stringify(eventDigest(ctx));
    if (briefs.length) factsText = `KEY POINTS (computed, 100% verified facts):\n${briefs.join("\n")}\n\n${factsText}`;
    const system = [
        "You are the ObsidianScout scouting assistant for a FIRST Robotics team.",
        `Our team: ${ctx.ourTeam || "unknown"}. Event: ${ctx.eventKey || "unknown"}.`,
        "",
        "SYSTEM CAPABILITIES & LOCAL TOOLS:",
        "- You have full access to local scouting analytics tools: match schedule (`all_matches`), team roster (`all_teams`), win predictions (`match_predictions`), official rankings (`current_rankings`), site user manuals and documentation (`read_docs`), match-by-match line graphs (`match_by_match`, `metric_trend`), ranking bar charts (`top_teams`), team comparisons (`compare_teams`), 2D scatter plots (`scatter`), radar skill charts (`team_radar`), phase breakdown stacked charts (`stacked_breakdown`), score distributions (`score_distribution`), match previews (`match_preview`), strategy briefs (`create_strategy_brief`), alliance pick sheets (`create_alliance_sheet`), team dossiers (`create_team_dossier`), and Monte Carlo projections (`projected_rankings`).",
        "- The UI system AUTOMATICALLY plots and renders interactive charts, graphs, and tables directly above your message whenever data or visualisations are requested.",
        "- NEVER state 'As a text-based AI, I don't have access to tools/APIs' or 'I cannot create charts'. You are connected to local ObsidianScout tools and data.",
        "- When the user asks to graph/chart or when a chart is shown, refer directly to the displayed graph/chart above (e.g., 'As shown in the graph above...').",
        "",
        "REASONING & CHAIN-OF-THOUGHT:",
        "- You can think step-by-step before producing your final response.",
        "- Wrap your internal chain-of-thought, calculations, and data checks inside <thought>...</thought> tags at the beginning of your response.",
        "- In your <thought> block: check team numbers, verify match numbers/averages, review rankings, and reason through tactical trade-offs.",
        "- After </thought>, output only your clear, direct, and verified final markdown answer for the user.",
        "",
        "CONVERSATIONAL FOCUS & FRESHNESS:",
        "- Always address the USER'S LATEST QUESTION directly as your primary focus. Never repeat old answers or keep analyzing a previously discussed team unless the user specifically asks a follow-up about them.",
        "- Use conversation history ONLY for pronoun resolution or context when the latest question is a direct continuation (e.g., 'how does that compare?', 'what about their teleop?').",
        "- If the latest question is general, greeting, or meta (such as 'what can you do?', 'help', 'who are you?'), answer the question directly without referring back to older team graphs.",
        "",
        "SCOUTED VS PLAYED MATCHES DISTINCTION:",
        "- 'matches_scouted_by_our_team' indicates how many match reports OUR team's scouts submitted locally.",
        "- 'matches_played_at_event' indicates how many official matches the team has competed in at the event (from match schedule / Statbotics).",
        "- If a team has matches_scouted = 0 but has played matches (or has EPA/OPR/xP data from N > 0 matches), ALWAYS state that the team has played in matches at the event, but our scouting team has not submitted local match reports for them yet.",
        "- NEVER say a team has played 0 matches when only their locally scouted count is 0.",
        "",
        "STRICT FACT-CHECKING RULES:",
        "- Answer using ONLY the DATA below. Never state any statistic or number not present in the DATA.",
        "- STRICT TEAM ATTRIBUTION: Only attribute scores, averages, or rankings to the EXACT team that achieved them. Never confuse 'our team' with other teams.",
        `- If asked about our team (${ctx.ourTeam || "our team"}) and our team is not in the DATA, explicitly state that data for our team is not in the retrieved results.`,
        "- Never invent robot capabilities (defense, game strategy, shooter speed) not explicitly mentioned in the scout notes or pit data.",
        "- Do not repeat entire tables row by row: summarise the key takeaways in 2-5 concise sentences or short bullets.",
        tier.id === "advanced" ? "When asked for strategy (picks, defense, match plans), reason step by step from the verified DATA." : "",
        `Reply in ${currentLanguage()}.`,
        "",
        "DATA:",
        factsText
    ].filter(Boolean).join("\n");
function enforceMessageBudget(messages, maxChars = 5000) {
    let total = messages.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    if (total <= maxChars) return messages;

    // 1. Trim history items (keep system at index 0 and latest user at last index)
    const result = [...messages];
    if (result.length > 2) {
        for (let i = 1; i < result.length - 1; i++) {
            if (result[i].content && result[i].content.length > 200) {
                result[i] = { ...result[i], content: result[i].content.slice(0, 200) + "..." };
            }
        }
        total = result.reduce((sum, m) => sum + (m.content ? m.content.length : 0), 0);
    }

    // 2. Drop older history if still over budget
    while (result.length > 2 && total > maxChars) {
        const removed = result.splice(1, 1)[0];
        total -= (removed.content ? removed.content.length : 0);
    }

    // 3. Truncate system prompt DATA section if still over budget
    if (total > maxChars && result[0] && result[0].content) {
        const excess = total - maxChars;
        const sys = result[0].content;
        const dataIdx = sys.indexOf("DATA:");
        if (dataIdx > 0) {
            const head = sys.slice(0, dataIdx + 5);
            const dataBody = sys.slice(dataIdx + 5);
            const keepLen = Math.max(200, dataBody.length - excess - 50);
            result[0] = { ...result[0], content: head + "\n" + dataBody.slice(0, keepLen) + "\n...(truncated for context limit)" };
        } else {
            result[0] = { ...result[0], content: sys.slice(0, Math.max(500, sys.length - excess)) + "..." };
        }
    }
    return result;
}

    const messages = [{ role: "system", content: system }];
    history.slice(-profile.historyTurns * 2).forEach((h) => messages.push({ role: h.role, content: String(h.content).slice(0, 300) }));
    messages.push({ role: "user", content: question });

    const cappedMessages = enforceMessageBudget(messages, profile.contextChars || 5000);

    const rawText = await AI.generate(cappedMessages, {
        maxTokens: profile.maxAnswerTokens,
        temperature: 0,
        signal,
        onToken: (_d, full) => onEvent({ type: "token", full: cleanGraphRefusalText(full, results.some((r) => r.chart || r.table)) })
    });

    const verification = verifyAndCorrectAnswer(rawText, results.length ? results : [{ facts: eventDigest(ctx), args: {} }], question, ctx);
    const text = verification.text;
    const unverified = verification.unverified;

    onEvent({ type: "done", text, unverified, results });
    return { text, results, unverified };
}

const tools = { TOOLS, ruleRoute, answerQuestion, unverifiedNumbers, cleanGraphRefusalText, verifyAndCorrectAnswer, evaluate, parseJsonLoose, parseToolCalls };
export default tools;
