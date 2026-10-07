/**
 * Number guardrail and answer verification against tool results.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import AI from "../local-ai.js";
import { parseThoughtAndContent } from "../ai-ui.js";
import Data from "../ai-data.js";
import { briefFor } from "./briefs.js";
import { t } from "./shared.js";

// ------------------------------------------------------------------ number guardrail & fact verification

export function collectNumbers(value, out) {
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
