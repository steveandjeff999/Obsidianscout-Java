/**
 * Deterministic rule router (English, Spanish, Turkish, Hebrew keywords): maps common questions straight to a tool.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
import { chartTypeIn, editSpec, isEditRequest, lastVisualSpec, specFromQuestion } from "./builder.js";
import { t } from "./shared.js";

export const INTENTS = {
    next: { en: /\bnext match\b|\bour next\b/, intl: ["próximo partido", "proximo partido", "siguiente partido", "sıradaki maç", "sonraki maç", "המשחק הבא"] },
    match: { en: /\b(match|qm)\b/, intl: ["partido", "maç", "משחק"] },
    strategy: { en: /\b(strategy|game plan|tactics|briefing|pre-match brief|match plan|plan for match|dossier for match)\b/, intl: ["estrategia", "plan de juego", "taktik", "maç planı", "אסטרטגיה", "תוכנית משחק"] },
    worksheet: { en: /\b(worksheet|draft board|pick sheet|alliance sheet|draft list)\b/, intl: ["hoja de selección", "seçim tablosu", "דף בחירה"] },
    dossier: { en: /\b(dossier|scout report|full report|profile card|team report|deep dive)\b/, intl: ["informe", "reporte", "rapor", "דוח"] },
    stacked: { en: /\b(stacked|breakdown|phase breakdown|auto teleop endgame|phases)\b/, intl: ["apilad", "desglose", "fases"] },
    radar: { en: /\b(radar|spider|polar|skills? profile)\b/, intl: ["radar", "örümcek"] },
    box: { en: /\b(box plot|distribution|spread|variance plot|consistency plot)\b/, intl: ["diagrama de caja", "kutu grafiği"] },
    notes: { en: /\b(summar|notes?\b|scouts? (say|said|think|wrote)|qualitative|comments?)/, intl: ["notas", "resum", "comentario", "notlar", "özet", "yorum", "הערות", "סכם", "סיכום"] },
    pick: { en: /\b(pick|picks|picklist|pick\s*list|alliance\s*partner|alliance\s*selection|alliance|draft(?:\s*list|\s*board)?|who should we (?:pick|choose|select))\b/i, intl: ["elegir", "escoger", "seleccionar", "selección", "seçmeli", "seçelim", "seçim", "לבחור", "בחירה"] },
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
    capabilities: { en: /\b(what (?:can|do) you do|what are (?:you|your capabilities|your features|your tools|your functions|your apis)|how (?:can|do) (?:you|i) (?:help|use)|who are you|help me|help|what tools?|what apis?|what tool\s*calls?|what functions?|available tools?|available apis?|avalible tools?|avalible apis?|apis and toolcalls|datasources?|data\s*sources?|what datasources?|what data\s*sources?|available datasources?|available data\s*sources?|avalible datasources?|avalible data\s*sources?|available metrics?|what metrics?)\b/i, intl: ["qué puedes hacer", "que puedes hacer", "ayuda", "ne yapabilirsin", "yardım", "מה אתה יכול לעשות", "עזרה"] },
    greeting: { en: /^(?:\s*(?:hi|hello|hey|greetings|good\s*(?:morning|afternoon|evening)|what(?:'s| is) that\??|who are you\??)\s*)+$/i, intl: ["hola", "buenos días", "buenas tardes", "merhaba", "selam", "שלום", "היי"] },
    consistency: { en: /\b(consistent|consistency|reliable|reliability|steady|predictable|inconsistent|volatile)\b/i, intl: ["consistente", "constancia", "fiable", "tutarlı", "istikrarlı", "עקבי", "יציב"] },
    form: { en: /\b(recent form|form lately|lately|recently|last \d+ (?:matches|games)|last few (?:matches|games)|hot streak|on fire|improved the most|improving|slumping|slump|declining|momentum|heating up|cooling off)\b/i, intl: ["forma reciente", "últimos partidos", "ultimos partidos", "son maçlar", "son maçlarda", "משחקים אחרונים"] },
    schedule: { en: /\b(our schedule|our matches|our upcoming|when do we play|when are we playing|when does \d+ play|schedule for|matches for|upcoming matches|remaining matches|our partners|our opponents|who are we (?:with|playing|against)|our record)\b/i, intl: ["nuestro calendario", "nuestros partidos", "maçlarımız", "programımız", "המשחקים שלנו", "לוח הזמנים שלנו"] },
    h2h: { en: /\b(head[ -]to[ -]head|h2h|against each other|played each other|faced each other|met each other|played (?:against|with))\b/i, intl: ["cara a cara", "kafa kafaya", "ראש בראש"] },
    alliance_build: { en: /\b(alliance (?:of|with)|together|team(?:ed)? up|combined|as an alliance|if we (?:pick|picked|choose|chose|took|take)|with us)\b/i, intl: ["juntos", "alianza de", "birlikte", "ביחד", "ברית של"] },
    stats: { en: /\b(event (?:average|avg|mean|median|stats|statistics)|median|average (?:across|for) (?:the )?(?:event|all teams)|across the event|league average|typical team|histogram)\b/i, intl: ["promedio del evento", "mediana", "etkinlik ortalaması", "medyan", "ממוצע האירוע", "חציון"] },
    percentile: { en: /\b(percentiles?|strengths?|weakness(?:es)?|stack up|weak at|strong at)\b/i, intl: ["fortalezas", "debilidades", "percentil", "güçlü yön", "zayıf yön", "חוזקות", "חולשות"] },
    note_search: { en: /\b(mention(?:s|ed|ing)?|notes? (?:that )?(?:say|said|mention)|noted for|described as|search (?:the )?notes|scouts? (?:noted|flagged|reported)|who (?:tipped|broke|disconnected))\b/i, intl: ["mencion", "notas que", "bahsed", "הוזכר", "מוזכר"] },
    pit: { en: /\b(pit data|pit scouting data|pit info|drive ?train|swerve|tank drive|mecanum|west coast|robot weight|mechanisms?|intake type)\b/i, intl: ["tracción", "pit verisi", "şasi", "הנעה"] },
    metrics_list: { en: /\b(?:what|which|list|available|all)(?: the)? metrics\b|\bmetrics (?:can|do) you\b/i, intl: ["qué métricas", "métricas disponibles", "hangi metrikler", "אילו מדדים"] },
    event_summary: { en: /\b(event summary|summari[sz]e the event|event overview|how is the event going|how's the event|state of the event|event at a glance|event status)\b/i, intl: ["resumen del evento", "etkinlik özeti", "סיכום האירוע"] },
    coverage: { en: /\b(scouting coverage|coverage|missing (?:scouting )?reports?|unscouted|not been scouted|haven'?t (?:been )?scouted|still need(?:s)? scouting|need(?:s)? to be scouted|gaps in scouting)\b/i, intl: ["cobertura", "eksik rapor", "kapsam", "כיסוי", "חסרים דוחות"] }
};

/** The words to search for in notes / pit data: quoted text, or the phrase after "mention", "noted for", "have", ... */
export function searchQuery(question) {
    const q = String(question || "");
    const quoted = q.match(/["“']([^"”']{2,40})["”']/);
    if (quoted) return quoted[1];
    const m = q.match(/\b(?:mention(?:s|ed|ing)?|noted for|described as|notes? (?:that )?(?:say|said|mention)|search (?:the )?notes for|reported|flagged|have|has|use|uses|run|runs|with)\s+(?:a |an |the |any )?([^?.,!]{2,40}?)(?:\s+(?:in|at|during|on|for)\b.*)?(?:[?.,!]|$)/i);
    if (m) return m[1].trim();
    const who = q.match(/\bwho\s+([a-z]+(?:ed)?)\b/i);
    return who ? who[1] : q;
}

export function hasIntent(q, name) {
    const intent = INTENTS[name];
    if (!intent || !intent.en) return false;
    const str = String(q || "").toLowerCase();
    return intent.en.test(str) || (Array.isArray(intent.intl) && intent.intl.some((w) => str.includes(w)));
}

const EXTERNAL_TOOL_BY_KEY = [
    { re: /\b(?:epa|statbotics)\b/i, tool: "epa_data", label: "EPA" },
    { re: /\bopr\b/i, tool: "opr_data", label: "OPR" },
    { re: /\b(?:xp|exp|expected points)\b/i, tool: "xp_data", label: "xP" }
];

/**
 * Rule routing. Follow-ups that change the table / chart on screen ("remove OPR from that table", "only top 10",
 * "as a pie chart") are applied to the previous spec in code first. EPA / OPR / xP questions go to their dedicated
 * tools (which enforce the admin-settings gate).
 * A route with refine:true is a draft a capable model should check (see answerQuestion); unchanged:true means the
 * edit could not be understood in code at all.
 */
export function ruleRoute(question, ctx, history = [], { skipEdit = false } = {}) {
    if (!skipEdit && isEditRequest(question)) {
        const previous = lastVisualSpec(history, ctx);
        if (previous) {
            const edit = editSpec(previous, question, ctx);
            return { tool: edit.tool, args: edit.args, refine: !edit.confident, edit: true, unchanged: !edit.changed, previous, leftover: edit.leftover };
        }
    }
    const routed = baseRuleRoute(question, ctx, history);
    if (routed && (routed.tool === "make_table" || routed.tool === "make_chart")) return routed;
    const q = String(question || "");
    const kinds = EXTERNAL_TOOL_BY_KEY.filter((k) => k.re.test(q));
    if (kinds.length !== 1) return routed; // none, or several metrics (compare/scatter handle those)
    const kind = kinds[0];
    if (routed && !["top_teams", "team_overview", "pick_candidates"].includes(routed.tool)) return routed;
    if (routed && routed.tool === "pick_candidates") return routed;
    const nums = Data.findTeamsInText(ctx, q);
    const small = (q.match(/\b(?:top|best|worst|bottom|lowest|highest)\s+(\d{1,2})\b/i) || [])[1];
    return {
        tool: kind.tool,
        args: {
            team: nums.length === 1 ? nums[0] : undefined,
            n: small ? Number(small) : (routed && routed.args ? routed.args.n : undefined),
            order: /\b(?:worst|bottom|lowest|least)\b/i.test(q) ? "asc" : "desc",
            chart: hasIntent(q, "chart")
        }
    };
}

function baseRuleRoute(question, ctx, history = []) {
    const q = ` ${String(question).toLocaleLowerCase()} `;
    // "match 35" / "partido 35" / "משחק 35", or Turkish word order "35. maç".
    const matchRef = q.match(/(?:match|qm|q|partido|maç|משחק)\s*#?\s*(\d{1,3})(?!\d)/) || q.match(/(?<!\d)(\d{1,3})\.?\s*(?:maç|partido)/);
    const matchNum = matchRef ? Number(matchRef[1]) : null;
    const allNumbers = (q.match(/\d{1,5}/g) || []).map(Number);
    const rawTeams = Data.findTeamsInText(ctx, question, { excludeMatch: matchNum });

    // Filter out teams that are explicitly negated ("1209 and 1561 arent in...", "not 1209", "dont include Citrus Circuits")
    const negatedTeams = new Set();
    rawTeams.forEach((t) => {
        const tName = Data.teamName(ctx, t);
        const escapedName = tName ? tName.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") : "";
        const namePart = escapedName ? `|\\b${escapedName}\\b` : "";
        const negPattern = new RegExp(`(?:aren'?t|isn'?t|not|except|excluding|dont|don'?t|without)\\s+(?:in\\s+)?(?:match\\s+\\d+\\s+)?(?:[\\w\\s,]*?)(?:\\b${t}\\b${namePart})|(?:\\b${t}\\b${namePart})\\s+(?:(?:and\\s+(?:\\d+|[\\w\\s]+)\\s+)?(?:aren'?t|isn'?t|are\\s+not|not\\s+in))`, "i");
        if (negPattern.test(q)) negatedTeams.add(t);
    });
    const teams = rawTeams.filter((t) => !negatedTeams.has(t));
    const hasAllTeams = /\b(?:all|all teams|all of the teams|every team|everyone|todos)\b/i.test(q);

    // Fallback: if no team is in the query, extract the most recent team referenced in history
    let conversationTeams = [...teams];
    if (conversationTeams.length === 0 && Array.isArray(history) && history.length > 0) {
        for (let i = history.length - 1; i >= 0; i--) {
            const hText = String(history[i]?.content || "");
            const hTeams = Data.findTeamsInText(ctx, hText);
            if (hTeams.length > 0) {
                conversationTeams = hTeams;
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

    // "top 5" / "5 mejores" / "ilk 5": a small number that isn't a team or match number.
    const n = hasIntent(q, "top") || hasIntent(q, "pick")
        ? allNumbers.find((x) => x >= 1 && x <= 30 && !ctx.stats.has(x) && (!matchRef || x !== Number(matchRef[1])))
        : undefined;
    const metric = Data.findMetric(ctx, q);
    const metricPhrases = q.split(/\bvs\.?\b|\bversus\b|\band\b|\by\b|\bve\b|\bfrente a\b|,|\/|\||וגם|\sו/).map((p) => Data.findMetric(ctx, p.trim())).filter(Boolean);
    const distinctMetrics = metricPhrases.filter((m, i) => metricPhrases.findIndex((x) => x.id === m.id) === i);
    const wantsChart = hasIntent(q, "chart");

    // Explicit tool call command or retry ("use the apis and toolcalls to get the data", "use tools", "query the database")
    if (/\b(?:use (?:the )?(?:apis?|tools?|tool\s*calls?|functions?)|call (?:the )?tools?|query (?:the )?database)\b/i.test(q) && teams.length === 0 && !metric) {
        if (Array.isArray(history) && history.length > 0) {
            for (let i = history.length - 1; i >= 0; i--) {
                const prev = history[i]?.content;
                if (prev && typeof prev === "string" && !/use (?:the )?apis/i.test(prev)) {
                    const prevRouted = baseRuleRoute(prev, ctx, history.slice(0, i));
                    if (prevRouted) return prevRouted;
                }
            }
        }
        return { tool: "compare_all_teams", args: {} };
    }

    const mentionsUs = /\b(?:we|us|our|ours)\b/i.test(q) || ["nosotros", "nuestro", "bizim", "שלנו"].some((w) => q.includes(w));
    const ourTeam = ctx.ourTeam && ctx.stats.has(ctx.ourTeam) ? ctx.ourTeam : null;

    // Event-level status questions.
    if (hasIntent(q, "event_summary") && teams.length === 0) return { tool: "event_summary", args: {} };
    if (hasIntent(q, "coverage") && teams.length === 0) return { tool: "scouting_coverage", args: {} };
    if (hasIntent(q, "metrics_list") && teams.length === 0) return { tool: "list_metrics", args: {} };

    // Searching notes and pit data.
    if (hasIntent(q, "note_search") && teams.length <= 1) {
        return { tool: "search_notes", args: { query: searchQuery(question), team: teams[0] } };
    }
    if (hasIntent(q, "notes") && teams.length === 0 && /\b(?:which|what|who|any)\s+teams?\b/i.test(q)) {
        return { tool: "search_notes", args: { query: searchQuery(question) } };
    }
    if (hasIntent(q, "pit") && teams.length <= 1) {
        const query = /\bpit (?:data|scouting data|info)\b/i.test(q) && teams.length === 1 ? undefined : searchQuery(question);
        return { tool: "pit_search", args: { query, team: teams[0] } };
    }

    // A team's own schedule ("our upcoming matches", "schedule for 254").
    if (hasIntent(q, "schedule") || (hasIntent(q, "all_matches") && (teams.length === 1 || mentionsUs))) {
        if (teams.length <= 1 && (teams.length === 1 || ourTeam)) {
            return { tool: "team_schedule", args: { team: teams[0] || ourTeam, unplayed_only: /\b(?:upcoming|remaining|next|left)\b/i.test(q) || undefined } };
        }
    }

    // Two teams' shared history.
    if (hasIntent(q, "h2h") && (teams.length === 2 || (teams.length === 1 && mentionsUs && ourTeam))) {
        return { tool: "head_to_head", args: { teams: teams.length === 2 ? teams : [ourTeam, teams[0]] } };
    }

    // Hypothetical alliances ("how strong would 254, 1678 and 118 be together", "if we pick 254 and 1678").
    if (hasIntent(q, "alliance_build") && (teams.length >= 2 || (teams.length >= 1 && mentionsUs && ourTeam))) {
        const [ownSide, otherSide] = q.split(/\bvs\.?\b|\bversus\b|\bagainst\b/);
        const sideTeams = (text) => (text ? teams.filter((x) => new RegExp(`\\b${x}\\b`).test(text)) : []);
        let alliance = otherSide ? sideTeams(ownSide) : teams.slice();
        const opponents = otherSide ? sideTeams(otherSide) : [];
        if (mentionsUs && ourTeam && !alliance.includes(ourTeam) && alliance.length < 3) alliance = [ourTeam, ...alliance];
        if (alliance.length >= 2) return { tool: "alliance_builder", args: { teams: alliance.slice(0, 3), opponents: opponents.length ? opponents : undefined } };
    }

    // Match Predictions:
    if (hasIntent(q, "predictions")) {
        return { tool: "match_predictions", args: { match: matchRef ? matchRef[1] : undefined, team: teams[0] || conversationTeams[0] || undefined } };
    }

    // Current Official Standings / Rankings:
    if (hasIntent(q, "rankings") && !hasIntent(q, "projected")) {
        return { tool: "current_rankings", args: { n: n, chart: wantsChart } };
    }

    // Any table ("table with EPA, scouted, OPR and xP for all teams", "table of 254, 1678 and 118 auto by match",
    // "compare all teams", "put it in a document"): a make_table draft built from the words in the question.
    const chartType = chartTypeIn(q);
    const tableWords = /\b(?:table|matrix|spreadsheet|columns?|tabla|tablo\w*)\b/i.test(q) || q.includes("טבלה");
    const wantsTable = (tableWords && !hasIntent(q, "pick") && !hasIntent(q, "all_matches")) ||
        ((hasAllTeams || teams.length === 0) && hasIntent(q, "compare") && (hasAllTeams || distinctMetrics.length >= 3)) ||
        ((hasAllTeams || teams.length === 0) && hasIntent(q, "artifact") && (hasAllTeams || /\b(?:all|table|teams?)\b/i.test(q) || distinctMetrics.length >= 2));
    if (wantsTable) {
        const draft = specFromQuestion(question, ctx);
        const args = draft.tool === "make_chart"
            ? { ...draft.args, columns: draft.args.metrics, metrics: undefined, type: undefined, by: undefined, rows: draft.args.by === "match" ? "matches" : undefined }
            : { ...draft.args };
        if (wantsChart) args.chart = chartType || true;
        if (hasIntent(q, "artifact")) args.document = true;
        Object.keys(args).forEach((k) => args[k] === undefined && delete args[k]);
        return { tool: "make_table", args, refine: !draft.confident, leftover: draft.leftover };
    }

    // Custom charts the specialised tools don't cover: pie / bar charts, several metrics, filters, exclusions.
    if (wantsChart && !["scatter", "radar", "box", "stackedBar"].includes(chartType)) {
        const draft = specFromQuestion(question, ctx, { chart: true });
        const metricCount = (draft.args.metrics || []).length;
        if (chartType === "pie" || chartType === "bar" || draft.args.conditions || draft.args.exclude || (metricCount >= 2 && !hasIntent(q, "difference"))) {
            return { tool: "make_chart", args: draft.args, refine: !draft.confident, leftover: draft.leftover };
        }
    }

    // All Matches schedule request:
    if (hasIntent(q, "all_matches") || (hasIntent(q, "match") && (hasAllTeams || /\bschedule\b/i.test(q)))) {
        return { tool: "all_matches", args: { team: teams[0] || undefined } };
    }

    // All Teams roster request:
    if (hasIntent(q, "all_teams") || (hasAllTeams && !metric && teams.length === 0)) {
        return { tool: "all_teams", args: {} };
    }

    const isProjection = hasIntent(q, "projected") || /\b(?:where will|where does|what rank will|what rank is|what place|where in rankings|finishing rank|finish|final rank|end up)\b/i.test(q);

    if ((hasIntent(q, "capabilities") || hasIntent(q, "greeting") || /^\s*(?:hi|hello|hey|what(?:'s| is) that\??)\s*$/i.test(q.trim())) && teams.length === 0 && !metric) {
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

    // Consistency, recent form, event-wide statistics and one team's percentiles.
    if (hasIntent(q, "consistency") && !hasIntent(q, "box") && teams.length === 0) {
        return { tool: "consistency", args: { metric: metric ? metric.label : undefined, n: n, order: /\b(?:least (?:consistent|reliable|predictable)|inconsistent|unreliable|unpredictable|volatile)\b/i.test(q) ? "least" : undefined } };
    }
    if (hasIntent(q, "form") && !hasIntent(q, "trend")) {
        const last = (q.match(/\blast\s+(\d{1,2})\b/) || [])[1];
        return { tool: "recent_form", args: { metric: metric ? metric.label : undefined, last: last ? Number(last) : undefined, teams: teams.length ? teams : undefined, order: /\b(?:slump|declin|cooling|worse|dropp)/i.test(q) ? "cold" : undefined } };
    }
    if (hasIntent(q, "stats") && teams.length <= 1) {
        return { tool: "metric_summary", args: { metric: metric ? metric.label : undefined, team: teams[0] } };
    }
    if (hasIntent(q, "percentile") && (teams.length === 1 || (teams.length === 0 && mentionsUs && ourTeam))) {
        return { tool: "team_percentiles", args: { team: teams[0] || ourTeam } };
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

    // Follow-up metric switch (e.g. "use xP as the datasource", "same thing with xP", "rank by EPA", "switch to OPR")
    const isMetricSwitch = metric && /\b(?:use|switch to|as (?:the )?(?:data\s*source|datasource|metric)|by|same (?:thing|with)|instead|rank by|sort by)\b/i.test(q);
    if (isMetricSwitch && teams.length === 0) {
        let lastIntent = null;
        if (Array.isArray(history) && history.length > 0) {
            for (let i = history.length - 1; i >= 0; i--) {
                const hText = String(history[i]?.content || "");
                if (/\b(?:pick|alliance|draft)\b/i.test(hText)) { lastIntent = "pick"; break; }
                if (/\b(?:compare|vs\.?|versus)\b/i.test(hText)) { lastIntent = "compare"; break; }
                if (/\b(?:trend|match by match|progression|line)\b/i.test(hText)) { lastIntent = "trend"; break; }
                if (/\b(?:top|rank|leaderboard|best)\b/i.test(hText)) { lastIntent = "top"; break; }
            }
        }
        if (lastIntent === "pick" || hasIntent(q, "pick")) {
            return { tool: "pick_candidates", args: { n: n || 8, focus: metric.label, chart: wantsChart } };
        }
        if (lastIntent === "compare" && conversationTeams.length >= 2) {
            return { tool: "compare_teams", args: { teams: conversationTeams, metrics: [metric.label], chart: wantsChart } };
        }
        if (lastIntent === "trend") {
            return { tool: "match_by_match", args: { teams: conversationTeams.length ? conversationTeams : undefined, metric: metric.label, n: n || 5, chart: true } };
        }
        return { tool: "top_teams", args: { metric: metric.label, n: n, order: hasIntent(q, "worst") ? "asc" : "desc", chart: wantsChart } };
    }

    if (hasIntent(q, "pick")) return { tool: "pick_candidates", args: { n: n || 8, focus: metric ? metric.label : undefined, chart: wantsChart } };

    if (teams.length >= 2 || (teams.length >= 1 && hasIntent(q, "compare"))) {
        return { tool: "compare_teams", args: { teams, metrics: metric ? [metric.label] : undefined, chart: wantsChart } };
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
    // Bare metric mention or availability question ("epa", "the EPA metric you mentioned", "can you get epa and opr and xP").
    if (teams.length === 0 && conversationTeams.length === 0 || (metric && teams.length === 0)) {
        if (metric && q.trim().split(/\s+/).length <= 10) {
            if (/\b(?:can you|could you|do you (?:have|get)|are you able|able to|access|available|avalible)\b/i.test(q)) {
                return { tool: "capabilities_help", args: {} };
            }
            return { tool: "top_teams", args: { metric: metric.label, n: n, order: hasIntent(q, "worst") ? "asc" : "desc", chart: wantsChart } };
        }
    }
    return null;
}
