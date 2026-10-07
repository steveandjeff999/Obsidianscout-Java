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
    // Table cells count too: long lists are abbreviated in the model's facts, but every row is on screen.
    results.forEach((r) => { collectNumbers(r.facts, allowed); collectNumbers(r.args, allowed); if (r.table) collectNumbers(r.table.rows, allowed); });
    collectNumbers(question, allowed);
    ctx.stats.forEach((_, team) => allowed.add(String(team)));
    for (let i = 0; i <= 10; i++) allowed.add(String(i));
    const found = (String(answer).replace(/\b(?:q|qm|match)\s*\d+\b/gi, "").match(/-?\d+(?:\.\d+)?/g) || []).map((n) => String(Number(n)));
    return Array.from(new Set(found.filter((n) => !allowed.has(n) && !allowed.has(String(Math.abs(Number(n)))))));
}

/**
 * Removes markdown tables the model wrote. Used when the app already draws the real table above the answer: a
 * model-typed copy is redundant, often has shifted columns, and burns the token budget before any takeaways.
 */
export function stripMarkdownTables(text) {
    const lines = String(text || "").split("\n");
    const isRow = (line) => /^\s*\|/.test(line);
    const out = lines.filter((line, i) => !isRow(line) || !(isRow(lines[i - 1] || "") || isRow(lines[i + 1] || "") || i === lines.length - 1));
    return out.join("\n").replace(/\n{3,}/g, "\n\n");
}

/**
 * Cleans canned LLM refusal boilerplate regarding inability to create graphs/charts or tool access.
 * hasTable: a table is on screen, so tables typed by the model are dropped too.
 */
export function cleanGraphRefusalText(text, hasVisual, hasTable = false) {
    if (!text) return "";
    if (hasTable) text = stripMarkdownTables(text);
    const generalRefusals = [
        /As a text-based AI, I (?:do not|don't) have access to (?:external )?(?:apis?|tool\s*calls?)[^.]*\.\s*/gi,
        /My abilities are confined to processing and generating text[^.]*\.\s*/gi,
        /I (?:cannot|can't) interact with the real world or access any external systems\.\s*/gi,
        /I (?:do not|don't) have access to (?:a |the )?(?:comprehensive|real-time|live|external)?\s*(?:database|apis?|external tools?|team_data)[^.]*\.\s*/gi,
        /I can only process the data provided in the (?:context|prompt)[^.]*\.\s*/gi,
        /Note: The data provided in the prompt is a list of specific team performance metrics, not a live database I can query\.[^.]*\.\s*/gi,
        /I cannot generate a live, comprehensive table of all teams at the event\.[^.]*\.\s*/gi,
        /nor do I have a specific "team_data" table[^.]*\.\s*/gi
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
    return dropSentences(cleaned, hasVisual).trim();
}

// Sentences that talk about the prompt itself ("the KEY POINTS section", "the data provided in the prompt").
const PROMPT_TALK = [
    /\bKEY POINTS\b/,
    /\b(?:initial|system|the) prompt\b/i,
    /\bDATA (?:section|block)\b/
];
// With a table / chart / document on screen: "I cannot generate a full table...", "due to the volume of data",
// "the data provided only included 4 teams" - all false, because the visual above already holds everything.
const VISUAL_DENIALS = [
    /\bI\s+(?:cannot|can't|can ?not|am unable to|am not able to|won't be able to|do(?:n't| not) have the (?:ability|capability) to)\b[^.!?\n]*\b(?:table|chart|graph|plot|list|compar|visuali[sz]|display|show|generat|produc|creat|render|draw|provid|fit|include)/i,
    /\b(?:it is|it's) not possible to\b[^.!?\n]*\b(?:table|chart|graph|plot|list|display|show|generat|creat|fit)/i,
    /\bdue to the (?:large )?(?:volume|amount|size|length|number) of (?:the )?(?:data|teams|rows|information)\b/i,
    /\bin a single (?:response|reply|message|answer)\b/i,
    /\b(?:data|information|results?)\b[^.!?\n]*\bonly (?:includ|contain|cover|list|provid|show|ha[sd]\b)[^.!?\n]*\bteams?\b/i,
    /\bonly (?:includ|contain|cover|list|provid|show)\w* (?:data |information |stats )?(?:for |on |about )?(?:\d+|a few|some|two|three|four|five|six) (?:specific |of the )?teams?\b/i,
    /\b(?:would|will) need (?:more|the full|the complete|additional|complete) data\b/i
];

function dropSentences(text, hasVisual) {
    const patterns = hasVisual ? [...PROMPT_TALK, ...VISUAL_DENIALS] : PROMPT_TALK;
    return String(text).split("\n").map((line) => {
        if (/^\s*\|/.test(line) || !line.trim()) return line; // keep markdown tables and paragraph breaks
        const sentences = line.split(/(?<=[.!?])\s+/);
        const kept = sentences.filter((s) => !patterns.some((re) => re.test(s)));
        if (kept.length === sentences.length) return line;
        const out = kept.join(" ").trim();
        return out && !/^[-*#>\s]*(?:\*\*)?\s*(?:note|nota|not|הערה)\s*:?\s*(?:\*\*)?\s*$/i.test(out) ? out : null;
    }).filter((line) => line !== null).join("\n").replace(/\n{3,}/g, "\n\n");
}

// ------------------------------------------------------------------ claims checked against the tables on screen

// Column names that are not "a value per team" (ranks, counts, per-match stats).
const NON_VALUE_COLUMNS = /^(?:#|n|rank|official rank|max|min|std dev|cv %|matches scouted|scouted matches|scouted|played|missing|record|matches played|percentile|change|last \d+)$/i;
const LABEL_SYNONYMS = {
    xp: "xp|expected points",
    "total points": "total points|total score|scouted total|scouted points|scouted average",
    "auto points": "auto(?:nomous)?(?: points)?", auto: "auto(?:nomous)?(?: points)?",
    "teleop points": "tele-?op(?: points)?", teleop: "tele-?op(?: points)?",
    "endgame points": "end-?game(?: points)?", endgame: "end-?game(?: points)?"
};
const escapeRe = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

function labelRegex(label) {
    const base = String(label).replace(/\s*\((?:avg|average)\)\s*$/i, "").trim().toLowerCase();
    if (!base) return null;
    const pattern = LABEL_SYNONYMS[base] || escapeRe(base).replace(/\s+/g, "\\s+");
    return new RegExp(`(?:^|[^\\p{L}\\p{N}])(?:${pattern})(?![\\p{L}\\p{N}])`, "iu");
}

/** Per-team values of every numeric column in the team tables of this answer: [{label, re, values: Map(team -> number)}]. */
function tableIndex(results, ctx) {
    const byLabel = new Map();
    results.forEach((r) => {
        const tb = r.table;
        if (!tb || !Array.isArray(tb.rows) || !tb.rows.length) return;
        const cols = (tb.columns || []).map((c) => String(c).trim());
        const teamIdx = cols.findIndex((c) => /^(?:team|equipo|takım|קבוצה)$/i.test(c));
        if (teamIdx < 0 || cols.some((c) => /^(?:match|maç|partido|משחק|side|alliance)$/i.test(c))) return; // per-match / alliance tables
        cols.forEach((c, i) => {
            if (i === teamIdx || NON_VALUE_COLUMNS.test(c)) return;
            const re = labelRegex(c);
            if (!re) return;
            const label = c.replace(/\s*\((?:avg|average)\)\s*$/i, "");
            const entry = byLabel.get(label.toLowerCase()) || { label, re, values: new Map() };
            tb.rows.forEach((row) => {
                const team = parseInt(String(row[teamIdx]), 10);
                const v = Number(row[i]);
                if (ctx.stats.has(team) && row[i] !== "-" && row[i] !== null && row[i] !== "" && Number.isFinite(v)) entry.values.set(team, v);
            });
            if (entry.values.size) byLabel.set(label.toLowerCase(), entry);
        });
    });
    return Array.from(byLabel.values());
}

const HIGH_WORDS = /\b(?:highest|leads?|leading|leader|top|best|most|first|#1|number one|strongest|tops)\b/i;
const LOW_WORDS = /\b(?:lowest|worst|least|bottom|weakest|last place|fewest)\b/i;
const SKIP_PAIRS = /\b(?:more than|less than|fewer than|difference|gap|ahead of|behind|margin|higher than|lower than|max(?:imum)?|peak|single match|best match|high score|per match|in match|q\d+|qm\s*\d+|match \d+)\b/i;

/** [{team, value, at, raw}] - the first number after each team mentioned in a sentence. */
/** Team names that identify one team (4+ characters, not shared), longest first, for spotting "The Cheesy Poofs ...". */
const nameCache = new WeakMap();
function teamNames(ctx) {
    if (nameCache.has(ctx)) return nameCache.get(ctx);
    const counts = new Map();
    ctx.stats.forEach((s) => { const n = String(s.name || "").trim().toLowerCase(); if (n.length >= 4) counts.set(n, (counts.get(n) || 0) + 1); });
    const list = [];
    ctx.stats.forEach((s) => {
        const n = String(s.name || "").trim();
        if (n.length >= 4 && counts.get(n.toLowerCase()) === 1 && !/^team\s*\d+$/i.test(n)) list.push({ team: s.teamNumber, re: new RegExp(`(?<![\\p{L}\\p{N}])${escapeRe(n)}(?![\\p{L}\\p{N}])`, "giu") });
    });
    nameCache.set(ctx, list);
    return list;
}

/** Teams mentioned in a sentence by number ("254", not "Q12") or by unique name, in order of position. */
function teamMentions(sentence, ctx) {
    const mentions = [];
    const re = /\b(\d{1,5})\b/g;
    let m;
    while ((m = re.exec(sentence))) {
        const n = Number(m[1]);
        if (ctx.stats.has(n) && !/(?:q|qm|match|#)\s*$/i.test(sentence.slice(Math.max(0, m.index - 6), m.index))) mentions.push({ team: n, start: m.index, end: m.index + m[1].length });
    }
    teamNames(ctx).forEach(({ team, re: nameRe }) => {
        nameRe.lastIndex = 0;
        let hit;
        while ((hit = nameRe.exec(sentence))) {
            const span = { team, start: hit.index, end: hit.index + hit[0].length };
            // "254 (The Cheesy Poofs)" is one mention.
            if (!mentions.some((x) => x.team === team && Math.abs(x.end - span.start) <= 3)) mentions.push(span);
        }
    });
    return mentions.sort((a, b) => a.start - b.start);
}

function teamValuePairs(sentence, ctx) {
    const mentions = teamMentions(sentence, ctx);
    const pairs = [];
    mentions.forEach((mt, i) => {
        const stop = i + 1 < mentions.length ? mentions[i + 1].start : sentence.length;
        const seg = sentence.slice(mt.end, Math.min(stop, mt.end + 70));
        // Skip a parenthesised team name right after the number ("1209 (RoboHornets)").
        const masked = seg.replace(/^\s*\([^)]*\p{L}[^)]*\)+/u, (s) => " ".repeat(s.length));
        const num = /-?\d+(?:\.\d+)?(?![\d.]|\s*(?:%|matches|teams|games|of \d))/.exec(masked);
        if (num) pairs.push({ team: mt.team, value: Number(num[0]), at: mt.end + num.index, raw: num[0] });
    });
    return { mentions, pairs };
}

function rankingSentence(entry, ctx, low) {
    const sorted = Array.from(entry.values.entries()).sort((a, b) => (low ? a[1] - b[1] : b[1] - a[1])).slice(0, 3);
    return `${low ? "Lowest" : "Highest"} ${entry.label}: ${sorted.map(([team, v]) => `${team} (${Data.round(v, 1)})`).join(", ")}.`;
}

/**
 * Checks "X has the highest / lowest <metric>" claims and "team (number)" pairs against the tables on screen.
 * A wrong leader or a wrong number in a ranking sentence rewrites the sentence from the table; a wrong number in
 * a plain sentence is replaced inline with the table's value. Repeated sentences are dropped.
 */
export function correctTableClaims(text, results, ctx) {
    const index = tableIndex(results, ctx);
    let corrected = 0;
    const seen = new Set();
    const fixSentence = (sentence) => {
        const key = sentence.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, " ").trim();
        if (key.length > 12 && seen.has(key)) { corrected++; return null; }
        seen.add(key);
        if (!index.length) return sentence;
        let named = index.filter((e) => e.re.test(sentence));
        // With one value column on screen, "leads with 65.3 points" is about that column even without its name.
        if (!named.length && index.length === 1) named = index;
        const labels = new Set(named.map((e) => e.label.toLowerCase()));
        if (labels.size !== 1) return sentence;
        const entry = named[0];
        const { mentions, pairs } = teamValuePairs(sentence, ctx);
        if (!mentions.length) return sentence;
        const low = LOW_WORDS.test(sentence);
        const high = !low && HIGH_WORDS.test(sentence);
        if ((high || low) && !/\b(?:second|third|2nd|3rd|pick|choose|select|recommend)\b/i.test(sentence)) {
            const values = Array.from(entry.values.entries());
            const best = values.reduce((a, b) => ((low ? b[1] < a[1] : b[1] > a[1]) ? b : a));
            const wrongLeader = mentions[0].team !== best[0] && entry.values.has(mentions[0].team);
            const wrongNumber = pairs.some((p) => entry.values.has(p.team) && Math.abs(entry.values.get(p.team) - p.value) > 0.15);
            if (wrongLeader || wrongNumber) { corrected++; return rankingSentence(entry, ctx, low); }
            return sentence;
        }
        if (SKIP_PAIRS.test(sentence)) return sentence;
        let out = sentence;
        pairs.slice().reverse().forEach((p) => {
            const actual = entry.values.get(p.team);
            if (actual === undefined || Math.abs(actual - p.value) <= 0.15) return;
            out = out.slice(0, p.at) + String(Data.round(actual, 1)) + out.slice(p.at + p.raw.length);
            corrected++;
        });
        return out;
    };
    const lines = String(text).split("\n").map((line) => {
        if (/^\s*\|/.test(line) || !line.trim()) return line;
        const lead = (line.match(/^\s*(?:[-*+]\s+|\d+\.\s+)?/) || [""])[0];
        const kept = line.slice(lead.length).split(/(?<=[.!?])\s+/).map(fixSentence).filter((s) => s !== null);
        return kept.length ? lead + kept.join(" ") : null;
    }).filter((line) => line !== null);
    return { text: lines.join("\n"), corrected };
}

/**
 * Removes row-by-row lists (4+ consecutive lines that each restate a team from the visual on screen with a
 * number), plus the "The top 8 teams are:" line that introduces them. The table / chart already shows the rows,
 * and re-typed rows are where small models mix up names and columns.
 */
export function dropRowLists(text, results, ctx) {
    const shown = new Set();
    results.forEach((r) => {
        const tb = r.table;
        if (!tb || !Array.isArray(tb.rows)) return;
        const idx = (tb.columns || []).findIndex((c) => /^(?:team|equipo|takım|קבוצה)$/i.test(String(c).trim()));
        if (idx >= 0) tb.rows.forEach((row) => { const n = parseInt(String(row[idx]), 10); if (ctx.stats.has(n)) shown.add(n); });
    });
    if (shown.size < 4) return text;
    const lines = String(text).split("\n");
    const isRow = (line) => {
        const t = line.trim();
        if (!t || t.length > 160 || !/\d/.test(t.replace(/\b\d{1,5}\b/, ""))) return false;
        const teams = teamMentions(t, ctx).map((m) => m.team);
        return teams.length >= 1 && teams.length <= 2;
    };
    const onScreen = (line) => teamMentions(line.trim(), ctx).every((m) => shown.has(m.team));
    const keep = lines.map(() => true);
    for (let i = 0; i < lines.length;) {
        let j = i;
        while (j < lines.length && (isRow(lines[j]) || (j > i && !lines[j].trim() && j + 1 < lines.length && isRow(lines[j + 1])))) j++;
        const run = lines.slice(i, j).filter((l) => l.trim());
        // Mostly teams that are on screen (a re-typed row with the wrong name still counts).
        if (run.length >= 4 && run.filter(onScreen).length >= run.length * 0.6) {
            for (let k = i; k < j; k++) keep[k] = false;
            let p = i - 1;
            while (p >= 0 && !lines[p].trim()) p--;
            if (p >= 0 && /:\s*$/.test(lines[p])) keep[p] = false;
        }
        i = Math.max(j, i + 1);
    }
    return lines.filter((_, i) => keep[i]).join("\n").replace(/\n{3,}/g, "\n\n").trim();
}

/** Kept for callers of the earlier name. */
export function correctLeaderClaims(text, results, ctx) {
    return correctTableClaims(text, results, ctx);
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
    const hasTable = results.some((r) => r.table && r.table.rows && r.table.rows.length);
    // A table the model re-typed under the real one is dropped before checking (see stripMarkdownTables).
    const rawCheck = content || (isThinking ? "" : text);
    const checkText = hasTable ? stripMarkdownTables(rawCheck) : rawCheck;

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

    // Clean any graph denial boilerplates, and tables the model re-typed under the real one.
    const hasVisual = results.some((r) => r.chart || r.table || r.artifact);
    finalOutput = cleanGraphRefusalText(finalOutput, hasVisual, hasTable);
    if (hasVisual) finalOutput = dropRowLists(finalOutput, results, ctx);
    if (!hadCriticalHallucination) finalOutput = correctTableClaims(finalOutput, results, ctx).text;
    if (!finalOutput.trim() && briefs.length) finalOutput = briefs.join("\n\n");
    if (!finalOutput.trim() && briefs.length) finalOutput = briefs.join("\n\n");

    const fullText = thought ? `<thought>\n${thought}\n</thought>\n\n${finalOutput}` : finalOutput;

    return { text: fullText, unverified: hadCriticalHallucination ? [] : unverified, hadHallucination: hadCriticalHallucination };
}
