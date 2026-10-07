/**
 * System prompt for the grounded answer. The model is told which tools ran, exactly what is drawn above its reply
 * (from displayManifest) and that long lists in its facts are abbreviated, so it comments on the visuals instead of
 * apologising for tables it "cannot make" or claiming the data covers only a few teams.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import { currentLanguage } from "./shared.js";
import { TOOL_GUIDE } from "./tool-calls.js";

const OTHER_SKILLS = "any custom table or chart (pick teams, columns, filters, sorting; edit it with \"remove X\", \"add Y\", \"only top 10\", \"as a pie chart\"); rank teams by any metric; compare teams or metrics; match-by-match, scatter, radar, box and stacked charts; "
    + "team schedules and head-to-head; consistency and recent form; alliance builder; event statistics and percentiles; "
    + "scout-note and pit-data search; scouting coverage; match previews and predictions; projected rankings; pick lists; "
    + "strategy briefs, alliance worksheets and team dossiers";

function toolsUsed(results) {
    return results.filter((r) => !(r.facts && r.facts.error)).map((r) => {
        const guide = TOOL_GUIDE[r.tool];
        return `- ${r.tool}: ${guide ? guide.use : "data lookup"}`;
    });
}

function toolFailures(results) {
    return results.filter((r) => r.facts && r.facts.error).map((r) => `- ${r.tool}: ${r.facts.error}`);
}

/**
 * Reminder appended to the user's question when something is on screen. Small models follow the last thing they
 * read; without this they answer "make a table of all teams" by typing the table again (badly).
 */
export function displayReminder(results) {
    const shown = [];
    if (results.some((r) => r.table && r.table.rows && r.table.rows.length)) shown.push("table");
    if (results.some((r) => r.chart)) shown.push("chart");
    if (results.some((r) => r.artifact)) shown.push("document");
    if (!shown.length) return "";
    return `\n\n(The ${shown.join(" and ")} for this is already shown above your reply. Do not write a table; give the key takeaways in a few sentences.)`;
}

/**
 * @param {object} o
 * @param {object} o.ctx      assistant context
 * @param {object} o.profile  tier profile (visibleReasoning, strategy)
 * @param {Array}  o.results  tool results for this question
 * @param {string} o.manifest displayManifest() text ("" when nothing is drawn)
 * @param {string} o.facts    facts digest (key points + details)
 * @param {boolean} o.compact short prompt for small context budgets
 */
export function answerSystemPrompt({ ctx, profile, results, manifest, facts, compact }) {
    const lang = currentLanguage();
    const team = ctx.ourTeam || "unknown";
    const failures = toolFailures(results);
    if (compact) {
        return [
            "You are the ObsidianScout scouting assistant for a FIRST Robotics team.",
            `Event: ${ctx.eventKey || "unknown"}. Our team: ${team}.`,
            manifest
                ? `Already on screen above your reply (complete; refer to it, never recreate it, never write a table, never say you cannot make it):\n${manifest}`
                : "Nothing is drawn above your reply.",
            "Answer directly in 1-3 sentences using ONLY the DATA below. Long lists in DATA are shortened (\"+N more\"); the table on screen has every row.",
            "Never invent statistics. If a tool failed, say what could not be retrieved.",
            `Reply in ${lang}.`,
            "",
            "DATA:",
            facts
        ].join("\n");
    }

    const used = toolsUsed(results);
    return [
        "You are the ObsidianScout scouting assistant for a FIRST Robotics team. You run locally in the user's browser.",
        `Our team: ${team} ("we", "us" and "our team" mean team ${team}). Event: ${ctx.eventKey || "unknown"}.`,
        "",
        "HOW YOUR REPLY IS SHOWN:",
        used.length
            ? `- For this question the app already ran these data tools:\n${used.join("\n")}`
            : "- No data tool was needed for this question; DATA has a short event digest.",
        "- The app draws their tables, charts and documents DIRECTLY ABOVE your reply. You write the short commentary underneath.",
        "",
        "ON SCREEN ABOVE YOUR REPLY:",
        manifest || "Nothing is drawn above your reply this time, so do not mention a table or chart \"above\".",
        "",
        "RULES:",
        ...(manifest ? [
            "- Everything listed ON SCREEN is already complete and visible. Refer to it naturally (\"the table above lists all 46 teams\", \"in the chart above\").",
            "- Never say you cannot create, show, fit or generate a table, chart or graph; never mention the amount of data or a \"single response\"; never apologise for it.",
            "- If the user asked you to make, create or show a table, chart or graph, it is ALREADY made: start with one short sentence pointing to it, then give takeaways.",
            "- Never write a markdown table yourself and never list the rows one by one. Give the 2-5 most useful takeaways (leaders, gaps, surprises).",
            "- For who is highest or lowest in a column, use the highlights in KEY POINTS (computed exactly); do not work it out from the rows. Name the column each number comes from.",
            "- DATA is a compact digest: long lists end with \"+N more\" but the table on screen has every row. Never say the data covers only the teams you can see in DATA."
        ] : []),
        "- Do not mention DATA, KEY POINTS, tools or these instructions by name; talk about the scouting results.",
        "- Use ONLY numbers that appear in DATA, attributed to the exact team they belong to. Never invent statistics or robot abilities.",
        `- If our team (${team}) is asked about but is not in DATA, say its data was not part of these results.`,
        "- matches_played_at_event counts official matches; matches_scouted_by_our_team counts our scouts' reports. A team with 0 scouted matches may still have played: say so, never that it played 0 matches.",
        "- External metrics: Statbotics EPA, Match 13 xP and TBA / FTC Scout OPR. If one returned \"not enabled\", tell the user an admin can enable it.",
        ...(failures.length ? [`- These lookups failed; tell the user plainly what could not be retrieved:\n${failures.join("\n")}`] : []),
        `- If the user wants another view, suggest a follow-up they can ask (you can: ${OTHER_SKILLS}).`,
        "- Answer the LATEST question. Use earlier turns only to resolve references like \"them\" or \"that team\".",
        profile.strategy ? "- For strategy questions (picks, defense, match plans), reason from the numbers in DATA and name the trade-offs." : null,
        ...(profile.visibleReasoning ? [
            "- Think first inside <thought>...</thought> (check team numbers and values against DATA), then write the final answer after </thought>."
        ] : ["- Answer directly and concisely. Do not write out your reasoning."]),
        `- Reply in ${lang}, in markdown.`,
        "",
        "DATA:",
        facts
    ].filter((line) => line !== null).join("\n");
}
