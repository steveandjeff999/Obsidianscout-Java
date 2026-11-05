/**
 * Whole-event views of the three scouting data sets, so questions about "all teams" have a tool:
 *   qualitative_overview  every team's qualitative ratings, report counts, recurring note themes and example notes
 *   pit_overview          every team's pit scouting answers side by side
 *   match_reports         the individual scouting reports (values + notes) for one match, all six teams
 * Like every tool, these only read data the user's account can already see, and the numbers come from code.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { r1, t, toTeamNumbers } from "./shared.js";

const RESERVED = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber", "teamNumber", "scoutName", "scouter"]);
const SKIP_TYPES = new Set(["section", "image", "file", "photo", "signature"]);
const STOP = new Set(("the and for with that this they them their was were are has have had but not very also just into from when then than "
    + "only some more most much good great really well like lot lots its it's our out over under team robot robots match matches did does doing "
    + "could would should can will about there here what which who all any one two three been being").split(" "));

const cut = (text, max) => (text.length > max ? `${text.slice(0, max - 3)}...` : text);
const latestFirst = (a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || ""));

function teamList(ctx, args) {
    const named = args.teams ? toTeamNumbers(ctx, args.teams) : (args.team ? toTeamNumbers(ctx, args.team) : []);
    return named.length ? named : Array.from(ctx.stats.keys()).sort((a, b) => a - b);
}

/** Words that recur across a team's notes (at least twice), most frequent first. */
function themes(notes, max = 4) {
    const counts = new Map();
    notes.forEach((n) => {
        const seen = new Set();
        String(n.text).toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, " ").split(/\s+/).forEach((w) => {
            if (w.length < 4 || STOP.has(w) || /^\d+$/.test(w) || seen.has(w)) return;
            seen.add(w);
            counts.set(w, (counts.get(w) || 0) + 1);
        });
    });
    return Array.from(counts.entries()).filter(([, c]) => c >= 2).sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0])).slice(0, max).map(([w, c]) => `${w} (${c})`);
}

function pitFields(ctx) {
    return (ctx.pitConfig && ctx.pitConfig.fields ? ctx.pitConfig.fields : [])
        .filter((f) => !SKIP_TYPES.has(String(f.type || "").toLowerCase()) && !RESERVED.has(f.id));
}

function pitValue(value) {
    if (value === undefined || value === null || value === "") return null;
    if (Array.isArray(value)) value = value.join(", ");
    else if (typeof value === "object") value = JSON.stringify(value);
    else if (typeof value === "boolean") value = value ? "yes" : "no";
    value = String(value).trim();
    return !value || value.startsWith("data:") ? null : value;
}

export const DATA_TOOLS = {
    qualitative_overview: {
        description: "Qualitative scouting for ALL teams (or the listed teams): average qualitative ratings, how many qualitative reports and notes each team has, recurring themes in the notes and example notes.",
        params: { teams: "optional team numbers (omit for every team)", sort_by: "optional qualitative rating to sort by", n: "optional number of teams" },
        async run(ctx, args) {
            const ratings = ctx.metrics.filter((m) => m.kind === "qual");
            const sortMetric = args.sort_by ? (Data.findMetric(ctx, args.sort_by) || null) : null;
            const rows = [];
            const without = [];
            teamList(ctx, args).forEach((team) => {
                const s = ctx.stats.get(team);
                const notes = Data.teamNotes(ctx, team);
                const reports = ctx.qualEntries.filter((e) => Number(e.targetTeamNumber) === team).length;
                const values = Object.fromEntries(ratings.map((m) => {
                    const v = s && s.metrics[m.id] ? s.metrics[m.id].avg : null;
                    return [m.label, typeof v === "number" && Number.isFinite(v) ? r1(v) : null];
                }));
                if (!notes.length && !reports && Object.values(values).every((v) => v === null)) { without.push(team); return; }
                const recent = notes.slice().reverse();
                rows.push({ team, name: Data.teamName(ctx, team) || undefined, qualitative_reports: reports, notes: notes.length, ratings: values,
                    themes: themes(notes), examples: recent.slice(0, 2).map((n) => `${n.match ? `${n.match}: ` : ""}${cut(n.text, 140)}`) });
            });
            const key = sortMetric && ratings.includes(sortMetric) ? sortMetric.label : null;
            rows.sort((a, b) => (key ? (b.ratings[key] ?? -1) - (a.ratings[key] ?? -1) : 0) || b.notes - a.notes || a.team - b.team);
            const shown = args.n ? rows.slice(0, Math.max(1, parseInt(args.n, 10))) : rows;
            const ratingCols = ratings.slice(0, 4).map((m) => m.label);
            return {
                facts: {
                    teams_with_qualitative_data: rows.length, teams_without: without.length ? without : undefined,
                    ratings: ratings.map((m) => m.label), sorted_by: key ? `${key} (highest first)` : "number of notes",
                    teams: shown
                },
                table: {
                    columns: [t("ai.col.team", "Team"), t("ai.col.reports", "Reports"), t("ai.col.notes", "Notes"), ...ratingCols, t("ai.col.themes", "Themes"), t("ai.col.example", "Example note")],
                    rows: shown.map((r) => [Data.teamLabel(ctx, r.team), r.qualitative_reports, r.notes, ...ratingCols.map((c) => r.ratings[c] ?? "-"),
                        r.themes.map((x) => x.replace(/ \(\d+\)$/, "")).join(", ") || "-", r.examples[0] || "-"])
                }
            };
        }
    },

    pit_overview: {
        description: "Pit scouting for ALL teams (or the listed teams) side by side: drivetrain, mechanisms, weight and every other pit question, from each team's latest pit report.",
        params: { teams: "optional team numbers (omit for every team)", fields: "optional pit questions to show, e.g. [\"drivetrain\", \"climb\"]" },
        async run(ctx, args) {
            const fields = pitFields(ctx);
            if (!fields.length) return { facts: { error: "Pit scouting has no questions configured for this season." } };
            const latest = new Map();
            ctx.pitEntries.slice().sort(latestFirst).forEach((e) => {
                const n = Number(e.targetTeamNumber);
                if (!latest.has(n)) latest.set(n, Data.entryData(e));
            });
            const label = (f) => String(f.label || f.id);
            const rows = [];
            const without = [];
            teamList(ctx, args).forEach((team) => {
                const data = latest.get(team);
                if (!data) { without.push(team); return; }
                const answers = {};
                fields.forEach((f) => { const v = pitValue(data[f.id]); if (v !== null) answers[label(f)] = cut(v, 80); });
                rows.push({ team, name: Data.teamName(ctx, team) || undefined, answers });
            });
            const wanted = (Array.isArray(args.fields) ? args.fields : (args.fields ? [args.fields] : [])).map((x) => String(x).toLowerCase());
            let cols = wanted.length ? fields.filter((f) => wanted.some((w) => label(f).toLowerCase().includes(w) || String(f.id).toLowerCase().includes(w))) : [];
            if (!cols.length) {
                // The most-answered questions, in form order.
                const answered = (f) => rows.filter((r) => r.answers[label(f)] !== undefined).length;
                const top = new Set(fields.slice().sort((a, b) => answered(b) - answered(a)).slice(0, 6));
                cols = fields.filter((f) => top.has(f) && answered(f) > 0);
            }
            return {
                facts: {
                    teams_with_pit_data: rows.length, teams_without_pit_data: without.length ? without : undefined,
                    questions: fields.map(label), shown_questions: cols.map(label),
                    teams: rows
                },
                table: {
                    columns: [t("ai.col.team", "Team"), ...cols.map(label)],
                    rows: rows.map((r) => [Data.teamLabel(ctx, r.team), ...cols.map((f) => r.answers[label(f)] ?? "-")])
                }
            };
        }
    },

    match_reports: {
        description: "The individual scouting reports for ONE match: every team's scouted points (auto, teleop, endgame, total), the scouts' match notes and qualitative notes, plus the official result.",
        params: { match: "match number, e.g. 12 or 'QM 12'", team: "optional team number to show only that team" },
        async run(ctx, args) {
            const m = Data.findMatch(ctx, args.match);
            if (!m) return { facts: { error: `Match ${args.match} is not in the schedule.` } };
            const key = String(m.matchKey || "").toLowerCase();
            const sameMatch = (e) => (key && String(e.matchKey || "").toLowerCase() === key) || (m.compLevel === "qm" && Number(e.matchNumber) === m.matchNumber && !e.isPrescout);
            const phases = ctx.metrics.filter((x) => x.kind === "score");
            const only = args.team ? toTeamNumbers(ctx, args.team) : null;
            const side = (keys, alliance) => (keys || []).map((k) => ({ team: Data.teamNumberFromKey(k), alliance }));
            const qualTextFields = (ctx.qualConfig.fields || []).filter((f) => f.type === "textarea" || f.type === "text");
            const reports = [...side(m.redTeams, "red"), ...side(m.blueTeams, "blue")].filter((x) => !only || only.includes(x.team)).map(({ team, alliance }) => {
                const p = ((ctx.stats.get(team) || {}).perMatch || []).find((x) => (key && String(x.matchKey || "").toLowerCase() === key) || x.matchNumber === m.matchNumber);
                const values = p ? Object.fromEntries(phases.map((x) => [x.label, typeof p.values[x.id] === "number" ? r1(p.values[x.id]) : null])) : {};
                const scouted = Object.values(values).some((v) => v !== null);
                const qualNotes = ctx.qualEntries.filter((e) => Number(e.targetTeamNumber) === team && sameMatch(e)).flatMap((e) => {
                    const d = Data.entryData(e);
                    return qualTextFields.map((f) => (typeof d[f.id] === "string" ? d[f.id].trim() : "")).filter((x) => x.length >= 3);
                });
                return { team, name: Data.teamName(ctx, team) || undefined, alliance, scouted, ...values,
                    match_notes: p && p.notes ? cut(String(p.notes), 200) : undefined,
                    qualitative_notes: qualNotes.length ? qualNotes.map((x) => cut(x, 200)) : undefined };
            });
            const played = m.redScore !== null && m.redScore !== undefined && m.redScore >= 0;
            return {
                facts: {
                    match: Data.matchLabel(m), played, red_score: played ? m.redScore : undefined, blue_score: played ? m.blueScore : undefined,
                    scouted_reports: reports.filter((r) => r.scouted).length, unscouted_teams: reports.filter((r) => !r.scouted).map((r) => r.team),
                    reports
                },
                table: {
                    columns: [t("ai.col.team", "Team"), t("ai.col.alliance", "Alliance"), ...phases.map((x) => x.label), t("ai.col.note", "Note")],
                    rows: reports.map((r) => [Data.teamLabel(ctx, r.team), r.alliance, ...phases.map((x) => r[x.label] ?? "-"),
                        [r.match_notes, ...(r.qualitative_notes || [])].filter(Boolean).join(" | ") || "-"])
                }
            };
        }
    }
};
