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
        case "epa_data":
        case "opr_data":
        case "xp_data": {
            if (f.team) {
                const tmName = f.name ? `Team ${f.team} (${f.name})` : `Team ${f.team}`;
                return `${tmName} has an average ${f.metric} of ${f.average} (${f.source}, rank ${f.rank || "-"}, max ${f.max}, N=${f.samples}).`;
            }
            if (f.teams) {
                return [fmt(t("ai.brief.top", "Top {n} by {metric}:"), { n: f.teams.length, metric: f.metric }),
                    ...f.teams.map((r) => `${r.rank}. ${nameOf(r)} - ${r.avg}`)].join("\n");
            }
            return "";
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
        case "compare_all_teams": {
            const leaders = Object.entries(f.leaders || {}).filter(([, v]) => v).map(([k, v]) => `${k.replace(/_/g, " ")}: ${v.team} (${v.value})`);
            return `The table compares all ${f.total_teams} teams at ${f.event ? f.event.toUpperCase() : "the event"} on scouted data, EPA, OPR and xP.`
                + (leaders.length ? ` Leaders - ${leaders.join("; ")}.` : "");
        }
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
        case "make_table":
        case "make_chart": {
            const what = result.tool === "make_chart"
                ? `The ${f.chart_type || "chart"} shows ${f.metrics ? f.metrics.join(", ") : "the data"} for ${f.teams_shown} team(s)`
                : `The table has ${f.row_count} row(s) (${f.rows_are || "one per team"}) with ${f.columns ? f.columns.join(", ") : ""}`;
            const extras = [f.sorted_by && `sorted by ${f.sorted_by}`, f.filters && `filtered to ${f.filters.join(" & ")}`].filter(Boolean).join(", ");
            const lines = [`${what}${extras ? `, ${extras}` : ""}.`];
            Object.entries(f.highlights || {}).slice(0, 6).forEach(([label, h]) => {
                lines.push(h.highest.team === h.lowest.team
                    ? `- ${label}: ${h.highest.team} ${h.highest.value}.`
                    : `- ${label}: highest ${h.highest.team} (${h.highest.value}), lowest ${h.lowest.team} (${h.lowest.value}), average ${h.average} over ${h.teams_with_data}.`);
            });
            if (f.unknown_columns || f.unknown_metrics) lines.push(f.unknown_columns || f.unknown_metrics);
            if (f.note) lines.push(f.note);
            return lines.join("\n");
        }
        case "team_schedule": {
            const tmName = f.name ? `Team ${f.team} (${f.name})` : `Team ${f.team}`;
            const lines = [`${tmName}: record ${f.record} over ${f.matches_played} played match${f.matches_played === 1 ? "" : "es"}, ${f.matches_upcoming} still to play.`];
            const nx = f.next_match;
            if (nx) lines.push(`Next: ${nx.match} on ${nx.alliance} with ${nx.partners.join(" & ") || "-"} against ${nx.opponents.join(", ") || "-"} (expected ${nx.expected}, win chance ${nx.win_chance}).`);
            return lines.join("\n");
        }
        case "head_to_head": {
            if (f.never_met) return `${f.team_a} and ${f.team_b} have not been in the same match at this event.`;
            return `${f.team_a} and ${f.team_b}: ${(f.matches_as_partners || []).length} match(es) as partners, ${(f.matches_as_opponents || []).length} as opponents (head-to-head ${f.head_to_head_record}).`;
        }
        case "consistency":
            return [`${f.order === "least consistent first" ? "Least" : "Most"} consistent on ${f.metric} (lower spread = more predictable):`,
                ...(f.teams || []).slice(0, 8).map((r) => `${r.rank}. ${nameOf(r)} - avg ${r.avg}, std dev ${r.stdev} (${r.cv_pct}%)`)].join("\n");
        case "recent_form": {
            const lastKey = `last_${f.recent_matches}_avg`;
            return [`${f.metric}, last ${f.recent_matches} matches vs overall (${f.order}):`,
                ...(f.teams || []).slice(0, 8).map((r) => `- ${nameOf(r)}: ${r.overall_avg} → ${r[lastKey]} (${r.change >= 0 ? "+" : ""}${r.change})`)].join("\n");
        }
        case "alliance_builder": {
            const lines = [`Alliance ${(f.alliance || []).map((a) => a.team).join(", ")} expects about ${f.alliance_expected_total} points${f.weakest_phase ? ` (weakest phase: ${f.weakest_phase})` : ""}.`];
            if (f.opponents) lines.push(`Opponents ${f.opponents.map((a) => a.team).join(", ")} expect about ${f.opponents_expected_total}; win chance ${f.alliance_win_chance}.`);
            return lines.join("\n");
        }
        case "metric_summary": {
            const lines = [`${f.metric} across ${f.teams_with_data} teams: mean ${f.mean}, median ${f.median}, middle half ${f.q1}-${f.q3}, range ${f.min} (${f.worst_team}) to ${f.max} (${f.best_team}).`];
            if (f.team) lines.push(`Team ${f.team.team}: ${f.team.value}, rank ${f.team.rank}.`);
            return lines.join("\n");
        }
        case "team_percentiles":
            return `Team ${f.team}${f.name ? ` (${f.name})` : ""} is strongest in ${(f.strongest || []).join(", ")} and weakest in ${(f.weakest || []).join(", ")}.`;
        case "search_notes":
            return f.matching_notes
                ? `${f.matching_notes} note(s) about ${f.teams_mentioned} team(s) mention "${f.query}": ${(f.teams || []).slice(0, 10).map((x) => x.team).join(", ")}.`
                : `No scout notes mention "${f.query}".`;
        case "pit_search":
            return f.matching_answers
                ? `${f.matching_answers} pit answer(s)${f.query ? ` mention "${f.query}"` : ""} for team(s) ${(f.teams_matching || []).slice(0, 12).join(", ")}.`
                : `No pit answers${f.query ? ` mention "${f.query}"` : ""} (pit data exists for ${f.teams_with_pit_data} team(s)).`;
        case "list_metrics":
            return `${f.metric_count} metrics are available: ${(f.metrics || []).map((m) => m.metric).join(", ")}.`;
        case "event_summary":
            return `${f.teams} teams, ${f.matches_played} of ${f.matches_scheduled} matches played, ${f.scouting_reports} scouting reports${f.scouting_coverage_pct !== undefined ? ` (${f.scouting_coverage_pct}% coverage)` : ""}.`;
        case "scouting_coverage":
            return `Scouting coverage ${f.coverage_pct}% (${f.scouted_team_matches} of ${f.played_team_matches} team-matches); ${(f.teams_missing_reports || []).length} team(s) have missing reports across ${f.matches_with_missing_reports} match(es).`;
        default:
            return "";
    }
}
