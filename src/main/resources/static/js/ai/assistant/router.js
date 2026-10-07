/**
 * Deterministic rule router (English, Spanish, Turkish, Hebrew keywords): maps common questions straight to a tool.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";
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

export function hasIntent(q, name) {
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
