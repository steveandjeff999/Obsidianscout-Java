/**
 * Read-only assistant tools. Each tool's run(ctx, args) returns { facts, table?, chart?, artifact?, ... } built by code.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import AI from "../local-ai.js";
import Data from "../ai-data.js";
import Features from "../ai-features.js";
import { getDocGuide } from "./docs-guide.js";
import { BUILDER_TOOLS } from "./builder.js";
import { EXTRA_TOOLS } from "./tools-extra.js";
import { evaluate, fmtTeamPage, metricOrDefault, r1, t, toTeamNumbers, unknownTeams } from "./shared.js";

// ------------------------------------------------------------------ tools

/** Admin-settings flag + metric id for each external data source. */
const EXTERNAL_SOURCES = {
    epa: { id: "ext:epa", label: "EPA", setting: "useStatboticsEpa", source: "Statbotics EPA" },
    opr: { id: "ext:opr", label: "OPR", setting: "useTbaOpr", source: "TBA / FTC Scout OPR" },
    xp: { id: "ext:exp", label: "xP", setting: "useMatch13Exp", source: "Match 13 xP" }
};

function externalMetricTool(kind) {
    const src = EXTERNAL_SOURCES[kind];
    return {
        description: `${src.label} data (${src.source}) for one team or ranked for all teams. Returns a "metric not enabled" error if ${src.label} is disabled in admin settings.`,
        params: { team: `optional team number (omit to rank all teams by ${src.label})`, n: "how many teams when ranking", order: "desc or asc", chart: "true to include visual chart" },
        async run(ctx, args) {
            const flag = ctx.settings ? ctx.settings[src.setting] : undefined;
            const metric = ctx.metrics.find((m) => m.id === src.id);
            // Admin setting wins; if the server didn't expose it, fall back to whether data exists.
            const enabled = flag === undefined || flag === null ? !!metric : !!flag;
            if (!enabled) {
                return { facts: { error: `Metric not enabled: ${src.label} (${src.source}) is not enabled in admin settings.` } };
            }
            if (!metric) {
                return { facts: { error: `Metric not enabled: no ${src.label} data is available for this event.` } };
            }
            const [team] = args.team ? toTeamNumbers(ctx, args.team) : [];
            if (args.team && !team) return { facts: { error: `Team ${args.team} has no data at this event.` } };
            if (team) {
                const s = ctx.stats.get(team);
                const m = s && s.metrics[metric.id];
                if (!m || m.avg === null || m.avg === undefined) {
                    return { facts: { team, error: `Metric not enabled: ${src.label} is not available for team ${team}.` } };
                }
                const ranked = Data.rankTeams(ctx, metric);
                const rank = ranked.findIndex((r) => r.teamNumber === team) + 1;
                const series = (s.perMatch || []).filter((p) => p.values && p.values[metric.id] !== null && p.values[metric.id] !== undefined);
                return {
                    facts: { team, name: s.name || undefined, metric: src.label, source: src.source, average: r1(m.avg), max: r1(m.max), min: r1(m.min), samples: m.n, rank: rank ? `${rank} of ${ranked.length}` : undefined },
                    table: { columns: [t("ai.col.metric", "Metric"), t("ai.col.avg", "Avg"), t("ai.col.max", "Max"), t("ai.col.n", "N")], rows: [[src.label, r1(m.avg), r1(m.max), m.n]] },
                    chart: (args.chart && series.length > 1) ? {
                        type: "line", title: `${Data.teamLabel(ctx, team)} - ${src.label}`, xTitle: t("ai.chart.match", "Match"), yTitle: src.label,
                        series: [{ name: src.label, x: series.map((p) => (p.matchNumber ? `Q${p.matchNumber}` : "?")), y: series.map((p) => r1(p.values[metric.id])) }]
                    } : null
                };
            }
            return TOOLS.top_teams.run(ctx, { metric: src.label, n: args.n, order: args.order, chart: args.chart });
        }
    };
}

export const TOOLS = {
    epa_data: externalMetricTool("epa"),
    opr_data: externalMetricTool("opr"),
    xp_data: externalMetricTool("xp"),

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

    compare_all_teams: {
        description: "Generate a comprehensive comparative matrix and artifact of all teams (or top N) at the event across Scouted data (Total, Auto, Teleop, Endgame) and external analytics (EPA, OPR, xP).",
        params: { n: "optional number of teams (default all)", sort_by: "optional metric name to sort by", order: "desc or asc", chart: "true to include visual comparison chart" },
        async run(ctx, args) {
            const allTeams = Array.from(ctx.stats.keys());
            if (!allTeams.length) return { facts: { error: "No teams found for this event." } };

            const sortMetric = args.sort_by ? Data.findMetric(ctx, args.sort_by, { minScore: 1 }) : (ctx.metrics.find((m) => m.id === "score_total") || ctx.metrics[0]);
            const order = args.order === "asc" ? "asc" : "desc";

            const ranked = Data.rankTeams(ctx, sortMetric || ctx.metrics[0], { order, minMatches: 0 });
            const count = args.n ? Math.max(1, Math.min(ranked.length, parseInt(args.n, 10))) : ranked.length;
            const targetTeams = ranked.slice(0, count).map((r) => r.teamNumber);

            const autoM = ctx.metrics.find((m) => m.id === "score_auto");
            const teleM = ctx.metrics.find((m) => m.id === "score_teleop");
            const endM = ctx.metrics.find((m) => m.id === "score_endgame");
            const totM = ctx.metrics.find((m) => m.id === "score_total") || { id: "score_total", label: "Scouted Total" };
            const epaM = ctx.metrics.find((m) => m.id === "ext:epa");
            const oprM = ctx.metrics.find((m) => m.id === "ext:opr");
            const expM = ctx.metrics.find((m) => m.id === "ext:exp");

            const rowsData = targetTeams.map((team, idx) => {
                const s = ctx.stats.get(team);
                const scoutedN = s ? s.matchesScouted : 0;
                const totAvg = s?.metrics.score_total?.avg;
                const autoAvg = autoM ? s?.metrics[autoM.id]?.avg : undefined;
                const teleAvg = teleM ? s?.metrics[teleM.id]?.avg : undefined;
                const endAvg = endM ? s?.metrics[endM.id]?.avg : undefined;
                const epaVal = epaM ? s?.metrics[epaM.id]?.avg : undefined;
                const oprVal = oprM ? s?.metrics[oprM.id]?.avg : undefined;
                const expVal = expM ? s?.metrics[expM.id]?.avg : undefined;

                return {
                    rank: idx + 1,
                    team,
                    name: s ? s.name : "",
                    scouted_matches: scoutedN,
                    scouted_total: totAvg !== undefined && totAvg !== null ? r1(totAvg) : "-",
                    auto: autoAvg !== undefined && autoAvg !== null ? r1(autoAvg) : "-",
                    teleop: teleAvg !== undefined && teleAvg !== null ? r1(teleAvg) : "-",
                    endgame: endAvg !== undefined && endAvg !== null ? r1(endAvg) : "-",
                    epa: epaVal !== undefined && epaVal !== null ? r1(epaVal) : "-",
                    opr: oprVal !== undefined && oprVal !== null ? r1(oprVal) : "-",
                    xp: expVal !== undefined && expVal !== null ? r1(expVal) : "-"
                };
            });

            const columns = ["#", t("ai.col.team", "Team"), "Scouted Matches", totM.label, "Auto", "Teleop", "Endgame", "EPA", "OPR", "xP"];
            const tableRows = rowsData.map((r) => [
                r.rank,
                Data.teamLabel(ctx, r.team),
                r.scouted_matches,
                r.scouted_total,
                r.auto,
                r.teleop,
                r.endgame,
                r.epa,
                r.opr,
                r.xp
            ]);

            const md = [
                `# 📊 Comprehensive Team Analytics Matrix: ${ctx.eventKey.toUpperCase()}`,
                `**Total Teams:** ${rowsData.length} | **Sorted by:** ${sortMetric?.label || "Total points"} (${order === "asc" ? "lowest first" : "highest first"})`,
                "",
                "---",
                "",
                `## 📋 Team Performance & Cross-Metric Data Table`,
                `| # | Team | Scouted N | Scouted Total | Auto | Teleop | Endgame | EPA | OPR | xP |`,
                `| :---: | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |`,
                ...rowsData.map((r) => `| **${r.rank}** | **${Data.teamLabel(ctx, r.team)}** | ${r.scouted_matches} | ${r.scouted_total} | ${r.auto} | ${r.teleop} | ${r.endgame} | ${r.epa} | ${r.opr} | ${r.xp} |`),
                "",
                "---",
                "",
                "### 🔍 Key Takeaways & Source Summary:",
                `- **Scouted Match Data:** Locally logged reports from our scouting team.`,
                `- **Statbotics EPA:** Expected Points Added contribution rating.`,
                `- **TBA / FTC Scout OPR:** Offensive Power Rating.`,
                `- **Match 13 xP:** Predictive statistical model expected scoring.`
            ].join("\n");

            const artifact = {
                id: `team-matrix-${ctx.eventKey}`,
                title: `Team Analytics Matrix (${ctx.eventKey.toUpperCase()})`,
                type: "worksheet",
                summary: `Comprehensive cross-metric comparison table of all ${rowsData.length} teams at ${ctx.eventKey.toUpperCase()} comparing Scouted data, EPA, OPR, and xP.`,
                markdown: md
            };

            const chartRows = rowsData.slice(0, 15);
            const chart = args.chart ? {
                type: "groupedBar",
                title: `${sortMetric?.label || "Total points"} - top ${chartRows.length} (${ctx.eventKey.toUpperCase()})`,
                x: chartRows.map((r) => String(r.team)),
                xTitle: t("ai.col.team", "Team"),
                yTitle: t("ai.chart.points", "Points"),
                series: [["scouted_total", totM.label], ["epa", "EPA"], ["opr", "OPR"], ["xp", "xP"]]
                    .filter(([key]) => chartRows.some((r) => r[key] !== "-"))
                    .map(([key, name]) => ({ name, y: chartRows.map((r) => (r[key] === "-" ? null : Number(r[key]))) }))
            } : null;

            // Leaders per column, so the model can comment on the whole table without seeing every row.
            const leader = (key) => {
                const best = rowsData.filter((r) => r[key] !== "-").sort((a, b) => Number(b[key]) - Number(a[key]))[0];
                return best ? { team: best.team, value: best[key] } : undefined;
            };
            return {
                facts: {
                    event: ctx.eventKey,
                    total_teams: rowsData.length,
                    table_rows: rowsData.length,
                    sorted_by: sortMetric?.label || "Total points",
                    leaders: { scouted_total: leader("scouted_total"), auto: leader("auto"), teleop: leader("teleop"), endgame: leader("endgame"), epa: leader("epa"), opr: leader("opr"), xp: leader("xp") },
                    teams_without_scouting: rowsData.filter((r) => !r.scouted_matches).length,
                    teams: rowsData.map((r) => ({ rank: r.rank, team: r.team, scouted: r.scouted_total, epa: r.epa, opr: r.opr, xp: r.xp }))
                },
                table: {
                    columns,
                    rows: tableRows
                },
                chart,
                artifact,
                markdown: `Created Artifact: **[${artifact.title}](#)**. Click **Open Artifact** to inspect the full event comparison spreadsheet.`
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
                `| 🔵 **Blue Alliance** (${blue.join(", ")}) | **${blueAvg}** pts | ${prediction?.blueWinProbability ? `${Math.round(prediction.blueWinProbability * 100)}%` : "-"} | ${blueStats.map((b) => `T${b.team} (Auto: ${r1(b.stats?.metrics.score_auto?.avg || 0)})`).join(", ")} |`,
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
            // Model-written markdown is checked against the other tool results in answerQuestion().
            const modelAuthored = !!(md && md.trim().length >= 20);
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
                markdown: md,
                modelAuthored
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
                `# 🧭 ObsidianScout AI Assistant Capabilities & Data Sources`,
                `The Local AI Assistant runs 100% locally on your device with access to all event scouting data, match schedules, and analytics tools.`,
                "",
                "---",
                "",
                "## 🗄️ Available Data Sources",
                "- **Scouted Match Data:** Local scouting match reports (Total points, Auto, Teleop, Endgame, custom game counters, and qualitative notes).",
                "- **Statbotics EPA (Expected Points Added):** Model-computed rating of expected scoring contribution per match.",
                "- **Match 13 xP (Expected Points):** Predictive statistical metric reflecting expected point generation.",
                "- **TBA / FTC Scout OPR (Offensive Power Rating):** Traditional linear algebra offensive scoring power.",
                "",
                "## 📊 Charts & Visualizations",
                "- **Match-by-Match Line Trends:** Multi-line progression across qualification matches (`metric_trend` / `match_by_match`).",
                "- **2D Scatter Plots:** Two-metric correlation analysis (e.g., Total Points vs Auto, OPR vs EPA, xP vs Total) (`scatter`).",
                "- **Phase Breakdown Stacked Bars:** Auto, Teleop, and Endgame phase contributions (`stacked_breakdown`).",
                "- **Radar / Spider Charts:** Multi-axis robot skill profiling (`team_radar`).",
                "- **Box Plot Distributions:** Score variance, spread, and match-to-match consistency (`score_distribution`).",
                "- **Ranking Bar Charts:** Top teams by any metric (`top_teams`).",
                "",
                "## 📑 Tactical Artifacts & Reports",
                "- **Match Strategy Briefs:** Full pre-match tactical game plan and win probabilities (`create_strategy_brief`).",
                "- **Alliance Selection Worksheets:** Tiered draft pick list with first-pick anchors & specialists (`create_alliance_sheet`).",
                "- **Team Scouting Dossiers:** In-depth profiles with pit specs, match history, and scoring metrics (`create_team_dossier`).",
                "- **Tournament Projections:** Monte Carlo simulation of final qualification standings and RP (`projected_rankings`).",
                "",
                "## 🔍 Data Query & Team Analytics",
                "- **Team Overviews & Comparisons:** Compare 2+ teams across all scoring metrics (`compare_teams`, `team_overview`).",
                "- **Match Schedules & Previews:** Upcoming alliance strength and match lists (`match_preview`, `all_matches`).",
                "- **Scout Notes Summaries:** Synthesize qualitative notes and observer comments (`summarize_notes`).",
                "- **Pick Recommendations:** Filter candidates based on defense, consistency, or autonomous power (`pick_candidates`).",
                "- **Documentation Guide:** Site manuals, export instructions, and setup help (`read_docs`).",
                "",
                "## 🛠️ Custom Tables & Charts",
                "- **Any table:** pick the teams, columns (any metric, max / min / std dev, matches scouted or played, official rank, record, or arithmetic like `EPA - xP`), filters, sorting and row limit (`make_table`).",
                "- **Any chart:** bar, grouped, stacked, line, scatter, radar, box or pie, for any metrics and teams (`make_chart`).",
                "- **Edit what is on screen:** \"remove OPR from that table\", \"only the top 10\", \"sort by xP\", \"add auto\", \"as a pie chart\".",
                "",
                "## 🧮 More Analysis Tools",
                "- **Schedules & Head-to-Head:** Any team's matches with partners, opponents, results and expected scores (`team_schedule`), and two teams' history together or against each other (`head_to_head`).",
                "- **Consistency & Recent Form:** Most / least consistent teams (`consistency`) and who is improving or slumping over their last matches (`recent_form`).",
                "- **Alliance Builder:** Expected points by phase for any 2-3 teams, optionally against an opposing alliance with a win chance (`alliance_builder`).",
                "- **Event Statistics & Percentiles:** Mean, median and spread of any metric with a histogram (`metric_summary`), and where one team ranks on every metric (`team_percentiles`).",
                "- **Search:** Find words in scout notes (`search_notes`) or pit answers such as drivetrain type (`pit_search`).",
                "- **Event Status:** Event at a glance (`event_summary`), missing scouting reports (`scouting_coverage`) and every available metric (`list_metrics`)."
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
                    data_sources: ["Scouted Match Data", "Statbotics EPA", "Match 13 xP", "TBA / FTC Scout OPR"],
                    metrics_available: ctx.metrics.map((m) => m.label).slice(0, 20),
                    summary: "I can query 4 data sources (Scouted Data, Statbotics EPA, Match 13 xP, TBA/FTC OPR) and generate match-by-match trend lines, 2D scatter plots, phase stacked bars, radar charts, box plot distributions, alliance selection worksheets, match strategy briefs, team dossiers, Monte Carlo tournament projections, match schedules, team schedules, head-to-head records, consistency and recent-form rankings, alliance strength estimates, event-wide statistics, team percentiles, scout-note and pit-data search, scouting coverage checks, full team rosters, win predictions, and site manuals."
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

    match_preview: {
        description: "Preview a match: both alliances' scouting averages and the site's prediction. Use match 'next' for our next match.",
        params: { match: "match number, label like 'QM 12', or 'next'", metric: "optional metric to chart", chart: "true to include visual chart" },
        async run(ctx, args) {
            const match = Data.findMatch(ctx, args.match);
            if (!match) return { facts: { error: ctx.matches.length ? `Match ${args.match ?? "next"} not found.` : "No match schedule is loaded for this event." } };
            const scoreMetrics = ctx.metrics.filter((m) => m.kind === "score");
            const focus = args.metric ? Data.findMetric(ctx, args.metric, { minScore: 1 }) : null;
            const metrics = focus && !scoreMetrics.includes(focus) ? [...scoreMetrics, focus] : scoreMetrics;
            const side = (keys) => keys.map(Data.teamNumberFromKey).filter(Boolean).map((team) => {
                const s = ctx.stats.get(team);
                return { team, name: (s && s.name) || undefined, matches: s ? s.matchesScouted : 0, ...Object.fromEntries(metrics.map((m) => [m.label, s && s.metrics[m.id] ? r1(s.metrics[m.id].avg) : null])) };
            });
            const red = side(match.redTeams || []);
            const blue = side(match.blueTeams || []);
            const total = scoreMetrics[0] ? scoreMetrics[0].label : null;
            const sum = (list, label) => r1(list.reduce((acc, x) => acc + (x[label] || 0), 0));
            const facts = {
                match: Data.matchLabel(match),
                red_alliance: red,
                blue_alliance: blue,
                red_expected_total_points: total ? sum(red, total) : null,
                blue_expected_total_points: total ? sum(blue, total) : null,
                our_team: ctx.ourTeam || undefined
            };
            if (match.redScore !== null && match.redScore !== undefined && match.redScore >= 0) {
                facts.final_score = { red: match.redScore, blue: match.blueScore };
            }
            try {
                const pred = await window.Obsidianscout.request(`/api/matches/predict?matchKey=${encodeURIComponent(match.matchKey)}&eventKey=${encodeURIComponent(ctx.eventKey)}`);
                if (pred) {
                    const keep = {};
                    ["redScore", "blueScore", "redWinProbability", "blueWinProbability", "predictedWinner", "winner", "confidence"].forEach((k) => {
                        if (pred[k] !== undefined && pred[k] !== null) keep[k] = typeof pred[k] === "number" ? r1(pred[k]) : pred[k];
                    });
                    if (Object.keys(keep).length) facts.site_prediction = keep;
                }
            } catch (_) { /* predictor optional */ }
            const chartMetrics = focus ? [focus] : metrics;
            return {
                facts,
                table: {
                    columns: [t("ai.col.alliance", "Alliance"), t("ai.col.team", "Team"), ...metrics.map((m) => m.label)],
                    rows: [...red.map((r) => ["Red", Data.teamLabel(ctx, r.team), ...metrics.map((m) => r[m.label] ?? "-")]),
                        ...blue.map((r) => ["Blue", Data.teamLabel(ctx, r.team), ...metrics.map((m) => r[m.label] ?? "-")])]
                },
                chart: args.chart || focus ? {
                    type: "groupedBar",
                    title: `${Data.matchLabel(match)} - ${t("ai.chart.alliances", "alliance averages")}`,
                    x: chartMetrics.map((m) => m.label),
                    series: [
                        { name: "Red", color: "#ef4444", y: chartMetrics.map((m) => sum(red, m.label)) },
                        { name: "Blue", color: "#3b82f6", y: chartMetrics.map((m) => sum(blue, m.label)) }
                    ]
                } : null
            };
        }
    },

    pick_candidates: {
        description: "Best alliance-pick candidates (excludes our team): strength, consistency and phase breakdown.",
        params: { n: "how many (default 8)", focus: "optional metric to prioritise", chart: "true to include visual chart" },
        async run(ctx, args) {
            const focus = args.focus ? metricOrDefault(ctx, args.focus) : ctx.metrics[0];
            const n = Math.max(1, Math.min(24, parseInt(args.n, 10) || 8));
            const minMatches = focus.kind === "external" ? 0 : (ctx.stats && Array.from(ctx.stats.values()).some((s) => s.matchesScouted >= 2) ? 2 : 1);
            const rows = Data.rankTeams(ctx, focus, { exclude: ctx.ourTeam ? [ctx.ourTeam] : [], minMatches }).slice(0, n);
            const phases = ctx.metrics.filter((m) => m.kind === "score" && m.id !== "score_total");
            return {
                facts: {
                    ranked_by: focus.label,
                    candidates: rows.map((r, i) => {
                        const s = ctx.stats.get(r.teamNumber);
                        return { rank: i + 1, team: r.teamNumber, name: r.name || undefined, avg: r1(r.avg), stdev: r1(r.stdev), matches: r.n, ...Object.fromEntries(phases.map((m) => [m.label, s.metrics[m.id] ? r1(s.metrics[m.id].avg) : null])) };
                    })
                },
                table: {
                    columns: ["#", t("ai.col.team", "Team"), focus.label, t("ai.col.stdev", "Std dev"), ...phases.map((m) => m.label)],
                    rows: rows.map((r, i) => {
                        const s = ctx.stats.get(r.teamNumber);
                        return [i + 1, Data.teamLabel(ctx, r.teamNumber), r1(r.avg), r1(r.stdev), ...phases.map((m) => (s.metrics[m.id] ? r1(s.metrics[m.id].avg) : "-"))];
                    })
                },
                chart: args.chart && rows.length ? {
                    type: "bar", title: `${t("ai.card.picks", "Pick candidates")} - ${focus.label}`,
                    x: rows.map((r) => String(r.teamNumber)), xTitle: t("ai.col.team", "Team"), yTitle: focus.label,
                    series: [{ name: focus.label, y: rows.map((r) => r1(r.avg)) }]
                } : null
            };
        }
    },

    filter_teams: {
        description: "Find teams meeting conditions, e.g. auto points > 10.",
        params: { conditions: "list of {metric, op (> >= < <= =), value}" },
        async run(ctx, args) {
            const conditions = (Array.isArray(args.conditions) ? args.conditions : [args]).map((c) => ({
                metric: Data.findMetric(ctx, c && c.metric, { minScore: 1 }), op: String((c && c.op) || ">"), value: Number(c && c.value)
            })).filter((c) => c.metric && Number.isFinite(c.value));
            if (!conditions.length) return { facts: { error: "Could not understand the conditions." } };
            const test = (v, op, x) => ({ ">": v > x, ">=": v >= x, "<": v < x, "<=": v <= x, "=": v === x, "==": v === x }[op] ?? v > x);
            const rows = [];
            ctx.stats.forEach((s) => {
                const ok = conditions.every((c) => s.metrics[c.metric.id] && test(r1(s.metrics[c.metric.id].avg), c.op, c.value));
                if (ok) rows.push([s.teamNumber, ...conditions.map((c) => r1(s.metrics[c.metric.id].avg))]);
            });
            rows.sort((a, b) => b[1] - a[1]);
            return {
                facts: { conditions: conditions.map((c) => `${c.metric.label} ${c.op} ${c.value}`), matching_teams: rows.map((r) => r[0]), count: rows.length },
                table: { columns: [t("ai.col.team", "Team"), ...conditions.map((c) => c.metric.label)], rows: rows.map((r) => [Data.teamLabel(ctx, r[0]), ...r.slice(1)]) }
            };
        }
    },

    summarize_notes: {
        description: "Summarize what scouts wrote about a team (qualitative notes).",
        params: { team: "team number" },
        async run(ctx, args, hooks = {}) {
            const [team] = toTeamNumbers(ctx, args.team);
            if (!team) return { facts: { error: `Team ${args.team} has no data at this event.` } };
            try {
                const result = await Features.summarizeTeam({ teamNumber: team, eventKey: ctx.eventKey, ctx, signal: hooks.signal });
                return { facts: { team, notes_summary: result.text, notes_count: result.notesCount }, markdown: result.text, final: true };
            } catch (err) {
                if (err.name === "AbortError") throw err;
                return { facts: { team, error: err.message } };
            }
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

Object.assign(TOOLS, EXTRA_TOOLS, BUILDER_TOOLS);

/** Built-in ObsidianScout documentation knowledge base. */
