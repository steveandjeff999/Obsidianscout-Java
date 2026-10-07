/**
 * Code-built plain-language summaries of tool results (Lite answers with these; larger models get them as key points).
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import { fmt, nameOf, t } from "./shared.js";

// ------------------------------------------------------------------ deterministic briefs
// A plain-language summary of each tool result, built by code. Lite shows these instead of model prose;
// larger models get them as key points to build on.

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
