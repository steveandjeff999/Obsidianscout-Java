/**
 * Local AI data context - ObsidianScout
 * Loads the team's scouting data through the normal authenticated API (so the assistant can only see what
 * the signed-in user can already see) and computes per-team statistics deterministically.
 * Every number the assistant shows comes from here, never from the model.
 *
 * Point/phase scoring mirrors graphs.js (entryScore / fieldPoints / normalizePhase) so values match the Graphs page.
 */

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
const COMP_LEVEL_ORDER = { qm: 0, ef: 1, qf: 2, sf: 3, f: 4 };

function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

function localizeLabel(label, fallback) {
    if (window.Obsidianscout && typeof window.Obsidianscout.localize === "function") {
        return window.Obsidianscout.localize(label) || fallback;
    }
    return (typeof label === "string" ? label : "") || fallback;
}

async function safeRequest(path) {
    try {
        return await window.Obsidianscout.request(path);
    } catch (err) {
        console.warn("[LocalAI] Data request failed:", path, err && err.message);
        return null;
    }
}

// ------------------------------------------------------------------ value helpers (mirrors graphs.js)

export function entryData(entry) {
    if (!entry) return {};
    if (entry.data && typeof entry.data === "object") return entry.data;
    if (typeof entry.data === "string") {
        try { return JSON.parse(entry.data); } catch (_) { return {}; }
    }
    return {};
}

export function readNumber(value) {
    if (value === null || value === undefined || value === "") return null;
    if (typeof value === "number") return Number.isFinite(value) ? value : null;
    if (typeof value === "boolean") return value ? 1 : 0;
    const parsed = Number(value);
    return Number.isNaN(parsed) ? null : parsed;
}

function readBoolean(value) {
    if (typeof value === "boolean") return value;
    if (typeof value === "string") {
        const v = value.toLowerCase();
        if (v === "true" || v === "yes" || v === "1") return true;
        if (v === "false" || v === "no" || v === "0") return false;
    }
    if (typeof value === "number") return value !== 0;
    return null;
}

function normalizePhase(rawPhase) {
    if (!rawPhase) return null;
    const p = String(typeof rawPhase === "object" ? (rawPhase.en || "") : rawPhase).toLowerCase().trim();
    if (!p) return null;
    if (p.includes("auto") || p.includes("autónomo") || p.includes("autonomo")) return "auto";
    if (p.includes("teleop") || p.includes("teleoperado") || p.includes("general")) return "teleop";
    if (p.includes("endgame") || p.includes("end") || p.includes("fin")) return "endgame";
    if (p.includes("post")) return "postmatch";
    return p;
}

function fieldPoints(field, value) {
    if (!field || value === null || value === undefined) return 0;
    const type = String(field.type || "").toLowerCase();
    const pointsPer = Number(field.pointsPer || 0);
    if (type === "counter" || type === "number" || type === "rating") return (readNumber(value) || 0) * pointsPer;
    if (type === "checkbox") return readBoolean(value) ? pointsPer : 0;
    if (type === "select") {
        const match = (field.options || []).find((o) => o.value === value || o.label === value);
        return match ? Number(match.points || 0) : 0;
    }
    return 0;
}

/** Field id -> phase, following section headers like graphs.js. */
function fieldPhases(config) {
    const phases = new Map();
    let current = "auto";
    (config && config.fields || []).forEach((field) => {
        if (field.type === "section") {
            current = normalizePhase(field.phase) || normalizePhase(field.label) || current;
            return;
        }
        let phase = normalizePhase(field.phase);
        if (!phase) {
            const id = String(field.id || "").toLowerCase();
            if (id.startsWith("auto")) phase = "auto";
            else if (id.startsWith("teleop")) phase = "teleop";
            else if (id.startsWith("endgame")) phase = "endgame";
            else phase = current;
        }
        phases.set(field.id, phase);
    });
    return phases;
}

function entryScore(config, phases, data, scope) {
    let total = 0;
    (config && config.fields || []).forEach((field) => {
        if (field.type === "section" || RESERVED_FIELDS.has(field.id)) return;
        if (scope !== "total" && phases.get(field.id) !== scope) return;
        total += fieldPoints(field, data[field.id]);
    });
    return total;
}

function sameEvent(a, b) {
    if (!a || !b) return false;
    return String(a).trim().toLowerCase() === String(b).trim().toLowerCase();
}

// ------------------------------------------------------------------ metric catalog

function words(text) {
    // Unicode-aware so translated labels (Spanish, Hebrew, Turkish) match too.
    return String(text || "").toLocaleLowerCase().replace(/[^\p{L}\p{N}%+ ]+/gu, " ").split(/\s+/).filter(Boolean);
}

function buildMetrics(config, qualConfig, teams) {
    const metrics = [
        // Aliases include Spanish / Turkish / Hebrew words so questions match whatever the UI language is.
        { id: "score_total", label: t("ai.metric.total", "Total points"), kind: "score", scope: "total", aliases: ["total points", "total point", "total", "points", "score", "overall", "scoring", "scouted total", "scouted points", "scouted score", "scouted data", "scouted", "puntos", "puntaje", "toplam", "puan", "סך", "נקודות"] },
        { id: "score_auto", label: t("ai.metric.auto", "Auto points"), kind: "score", scope: "auto", aliases: ["auto points", "auto point", "auto", "autonomous", "scouted auto", "scouted autonomous", "autónomo", "autonomo", "otonom", "אוטונומי", "אוטו"] },
        { id: "score_teleop", label: t("ai.metric.teleop", "Teleop points"), kind: "score", scope: "teleop", aliases: ["teleop points", "teleop point", "teleop", "tele op", "tele-op", "driver controlled", "scouted teleop", "teleoperado", "טלאופ"] },
        { id: "score_endgame", label: t("ai.metric.endgame", "Endgame points"), kind: "score", scope: "endgame", aliases: ["endgame points", "endgame point", "endgame", "end game", "scouted endgame", "juego final", "oyun sonu", "סיום"] }
    ];
    (config && config.fields || []).forEach((field) => {
        if (field.type === "section" || RESERVED_FIELDS.has(field.id)) return;
        const type = String(field.type || "").toLowerCase();
        const label = localizeLabel(field.label, field.id);
        const base = { label, fieldId: field.id, field, aliases: [label.toLowerCase(), String(field.id).toLowerCase()] };
        if (type === "counter" || type === "number" || type === "rating") {
            metrics.push({ ...base, id: `field:${field.id}`, kind: "numeric" });
        } else if (type === "checkbox") {
            metrics.push({ ...base, id: `field:${field.id}`, kind: "rate", label: `${label} %` });
        } else if (type === "select" && (field.options || []).some((o) => Number(o.points || 0) !== 0)) {
            metrics.push({ ...base, id: `field:${field.id}`, kind: "selectPoints", label: `${label} (pts)` });
        }
    });
    (qualConfig && qualConfig.fields || []).forEach((field) => {
        if (String(field.type || "").toLowerCase() !== "rating") return;
        const label = localizeLabel(field.label, field.id);
        metrics.push({ id: `qual:${field.id}`, label: `${label} (${t("ai.metric.qual", "qualitative")})`, kind: "qual", fieldId: field.id, field, aliases: [label.toLowerCase(), "rating", "qualitative"] });
    });
    const hasExternal = (key) => teams.some((tm) => tm[key] !== null && tm[key] !== undefined);
    if (hasExternal("epa")) metrics.push({ id: "ext:epa", label: "EPA", kind: "external", key: "epa", aliases: ["epa", "statbotics", "statbotics epa", "expected points added", "epa datasource", "epa data"] });
    if (hasExternal("opr")) metrics.push({ id: "ext:opr", label: "OPR", kind: "external", key: "opr", aliases: ["opr", "tba opr", "ftc scout opr", "offensive power rating", "opr datasource", "opr data"] });
    if (hasExternal("exp")) metrics.push({ id: "ext:exp", label: "xP", kind: "external", key: "exp", aliases: ["xp", "exp", "expected points", "expected pts", "match 13 xp", "match 13 exp", "match 13", "match13", "xp datasource", "xp data"] });
    metrics.forEach((m) => {
        m.aliases = Array.from(new Set([m.label.toLowerCase(), ...(m.aliases || [])]));
    });
    return metrics;
}

const WEAK_ALIAS_WORDS = new Set(["match", "data", "datasource", "points", "point", "score", "scouted", "team", "teams", "rating", "power", "pts"]);

/** Finds the metric a free-text phrase refers to (deterministic fuzzy match). Returns null when unsure. */
export function findMetric(ctx, phrase, { minScore = 2 } = {}) {
    if (!phrase) return null;
    const text = ` ${words(phrase).join(" ")} `;
    const exact = ctx.metrics.find((m) => m.id === phrase || m.label.toLowerCase() === String(phrase).toLowerCase());
    if (exact) return exact;
    const textWords = text.trim().split(" ");
    // "climbing" ~ "climb", "autos" ~ "auto": shared stem of 4+ letters.
    const stemHit = (w) => textWords.some((tw) => tw === w || (w.length >= 4 && tw.length >= 4 && (tw.startsWith(w) || w.startsWith(tw))));
    let best = null;
    let bestScore = 0;
    ctx.metrics.forEach((metric) => {
        let score = 0;
        metric.aliases.forEach((alias) => {
            const a = words(alias).join(" ");
            if (!a) return;
            if (text.includes(` ${a} `)) {
                const isShortAcronym = ["xp", "epa", "opr", "exp"].includes(a);
                const phraseScore = isShortAcronym ? 15 : (10 + a.length);
                score = Math.max(score, phraseScore);
            } else {
                // Generic words never count as a partial hit ("match 12" is not xP's alias "match 13").
                const aliasWords = a.split(" ").filter((w) => w.length > 2 && !WEAK_ALIAS_WORDS.has(w) && !/\d/.test(w));
                const hits = aliasWords.filter(stemHit).length;
                if (aliasWords.length && hits) score = Math.max(score, (hits / aliasWords.length) * 2);
            }
        });
        if (score > bestScore) {
            bestScore = score;
            best = metric;
        }
    });
    return bestScore >= minScore ? best : null;
}

// ------------------------------------------------------------------ context loading

const contextCache = new Map();

/**
 * Loads (and caches per event) everything the assistant and summaries need.
 * @returns {Promise<object>} ctx
 */
export async function loadContext({ eventKey = null, force = false } = {}) {
    const settingsRes = await safeRequest("/api/settings");
    const settings = (settingsRes && (settingsRes.settings || settingsRes)) || {};
    const key = String(eventKey || settings.eventKey || "").toLowerCase();
    if (!force && contextCache.has(key)) return contextCache.get(key);

    const promise = (async () => {
        const enc = encodeURIComponent(key);
        const [me, config, qualConfig, pitConfig, teamsRaw, matchesRaw, entriesRaw, qualRaw, pitRaw, projectedRaw, pickListsRaw, eventsRaw, statsHistoryRaw] = await Promise.all([
            window.Obsidianscout.getMe(),
            safeRequest("/api/config"),
            safeRequest("/api/qual-config"),
            safeRequest("/api/pit-config"),
            key ? safeRequest(`/api/teams?eventKey=${enc}`) : Promise.resolve([]),
            key ? safeRequest(`/api/matches?eventKey=${enc}`) : Promise.resolve([]),
            safeRequest("/api/scouting?includePrescout=true"),
            safeRequest("/api/qual-scouting?includePrescout=true"),
            safeRequest("/api/pit-scouting?includePrescout=true"),
            key ? safeRequest(`/api/matches/projected-rankings?eventKey=${enc}`) : Promise.resolve([]),
            key ? safeRequest(`/api/scouting/pick-lists?eventKey=${enc}`) : Promise.resolve([]),
            safeRequest(`/api/events?year=${settings.year || new Date().getFullYear()}&cached=1`),
            key ? safeRequest(`/api/stats/history?eventKey=${enc}`) : Promise.resolve(null)
        ]);

        const allEntries = Array.isArray(entriesRaw) ? entriesRaw : [];
        const allQual = Array.isArray(qualRaw) ? qualRaw : [];
        const allPit = Array.isArray(pitRaw) ? pitRaw : [];
        const forEvent = (list) => key ? list.filter((e) => !e.isPrescout && sameEvent(e.eventKey, key)) : list.filter((e) => !e.isPrescout);

        // Event entries; fall back to prescout data for teams with no event data.
        const entries = forEvent(allEntries);
        const scoutedTeams = new Set(entries.map((e) => Number(e.targetTeamNumber)).filter(Boolean));

        const teams = new Map();
        (Array.isArray(teamsRaw) ? teamsRaw : []).forEach((tm) => {
            if (tm && tm.teamNumber) teams.set(Number(tm.teamNumber), tm);
        });
        const eventTeamNumbers = teams.size ? new Set(teams.keys()) : null;
        allEntries.filter((e) => e.isPrescout && e.targetTeamNumber && !scoutedTeams.has(Number(e.targetTeamNumber)) &&
            (!eventTeamNumbers || eventTeamNumbers.has(Number(e.targetTeamNumber))))
            .forEach((e) => entries.push({ ...e, _prescout: true }));
        entries.forEach((e) => {
            const n = Number(e.targetTeamNumber);
            if (n && !teams.has(n)) teams.set(n, { teamNumber: n });
        });
        (Array.isArray(matchesRaw) ? matchesRaw : []).forEach((m) => {
            [...(m.redTeams || []), ...(m.blueTeams || [])].forEach((tStr) => {
                const n = teamNumberFromKey(tStr);
                if (n && !teams.has(n)) teams.set(n, { teamNumber: n });
            });
        });

        const ctx = {
            eventKey: key,
            settings,
            me,
            ourTeam: me ? Number(me.teamNumber) : null,
            config: config || { fields: [] },
            qualConfig: qualConfig || { fields: [] },
            pitConfig: pitConfig || { fields: [] },
            teams,
            matches: (Array.isArray(matchesRaw) ? matchesRaw : []).slice().sort(compareMatches),
            entries,
            qualEntries: forEvent(allQual).concat(allQual.filter((e) => e.isPrescout)),
            pitEntries: allPit,
            projectedRankings: Array.isArray(projectedRaw) ? projectedRaw : [],
            pickLists: Array.isArray(pickListsRaw) ? pickListsRaw : [],
            events: Array.isArray(eventsRaw) ? eventsRaw : [],
            statsHistory: statsHistoryRaw || { oprs: {}, epaHistory: [], match13History: [] },
            phases: fieldPhases(config || { fields: [] }),
            loadedAt: new Date()
        };
        ctx.metrics = buildMetrics(ctx.config, ctx.qualConfig, Array.from(teams.values()));
        ctx.stats = computeStats(ctx);
        return ctx;
    })();
    contextCache.set(key, promise);
    try {
        return await promise;
    } catch (err) {
        contextCache.delete(key);
        throw err;
    }
}

export function clearContextCache() {
    contextCache.clear();
}

function compareMatches(a, b) {
    const la = COMP_LEVEL_ORDER[a.compLevel] ?? 9;
    const lb = COMP_LEVEL_ORDER[b.compLevel] ?? 9;
    if (la !== lb) return la - lb;
    if ((a.setNumber || 0) !== (b.setNumber || 0)) return (a.setNumber || 0) - (b.setNumber || 0);
    return (a.matchNumber || 0) - (b.matchNumber || 0);
}

// ------------------------------------------------------------------ statistics

function metricValueForEntry(ctx, metric, entry) {
    const data = entryData(entry);
    switch (metric.kind) {
        case "score": return entryScore(ctx.config, ctx.phases, data, metric.scope);
        case "numeric": return readNumber(data[metric.fieldId]);
        case "rate": {
            const b = readBoolean(data[metric.fieldId]);
            return b === null ? null : (b ? 100 : 0);
        }
        case "selectPoints": return data[metric.fieldId] === undefined ? null : fieldPoints(metric.field, data[metric.fieldId]);
        default: return null;
    }
}

function summarize(values) {
    const v = values.filter((x) => x !== null && x !== undefined && Number.isFinite(x));
    if (!v.length) return null;
    const avg = v.reduce((a, b) => a + b, 0) / v.length;
    const variance = v.reduce((s, x) => s + (x - avg) ** 2, 0) / v.length;
    return { avg, max: Math.max(...v), min: Math.min(...v), n: v.length, stdev: Math.sqrt(variance) };
}

function computeStats(ctx) {
    const stats = new Map();
    const byTeam = new Map();
    ctx.entries.forEach((e) => {
        const n = Number(e.targetTeamNumber);
        if (!n) return;
        if (!byTeam.has(n)) byTeam.set(n, []);
        byTeam.get(n).push(e);
    });
    const qualByTeam = new Map();
    ctx.qualEntries.forEach((e) => {
        const n = Number(e.targetTeamNumber);
        if (!n) return;
        if (!qualByTeam.has(n)) qualByTeam.set(n, []);
        qualByTeam.get(n).push(e);
    });

    // Index external match histories (Statbotics EPA & Match 13 xP)
    const epaByTeamMatch = new Map();
    const rawEpaList = (ctx.statsHistory && Array.isArray(ctx.statsHistory.epaHistory)) ? ctx.statsHistory.epaHistory : [];
    rawEpaList.forEach((item) => {
        if (!item) return;
        const tNum = Number(item.team || item.teamNumber || item.team_number || String(item.teamKey || "").replace(/\D/g, ""));
        if (!tNum) return;
        const matchRaw = String(item.match || item.matchKey || item.match_key || item.key || "").toLowerCase();
        const matchNum = parseInt(matchRaw.replace(/[^0-9]/g, ""), 10);
        const epaObj = (typeof item.epa === "object" && item.epa !== null) ? item.epa : item;
        const val = epaObj.total_points ?? epaObj.epa ?? epaObj.total ?? (typeof item.epa === "number" ? item.epa : null);
        if (val !== undefined && val !== null && Number.isFinite(Number(val))) {
            const numVal = Number(val);
            if (matchRaw) epaByTeamMatch.set(`${tNum}:${matchRaw}`, numVal);
            if (matchNum) epaByTeamMatch.set(`${tNum}:m${matchNum}`, numVal);
        }
    });

    const expByTeamMatch = new Map();
    const rawExpList = (ctx.statsHistory && Array.isArray(ctx.statsHistory.match13History)) ? ctx.statsHistory.match13History : [];
    rawExpList.forEach((matchObj) => {
        if (!matchObj) return;
        const matchRaw = String(matchObj.key || matchObj.matchKey || matchObj.match_key || matchObj.match || "").toLowerCase();
        const matchNum = parseInt(matchRaw.replace(/[^0-9]/g, ""), 10);
        const teams = matchObj.teams;
        if (Array.isArray(teams)) {
            teams.forEach((t) => {
                const tNum = Number(t.teamNumber || t.team_number || t.team || String(t.teamKey || "").replace(/\D/g, ""));
                if (!tNum) return;
                const val = t.xpPost ?? t.xp ?? t.xpPre ?? t.exp ?? t.total ?? t.total_points;
                if (val !== undefined && val !== null && Number.isFinite(Number(val))) {
                    const numVal = Number(val);
                    if (matchRaw) expByTeamMatch.set(`${tNum}:${matchRaw}`, numVal);
                    if (matchNum) expByTeamMatch.set(`${tNum}:m${matchNum}`, numVal);
                }
            });
        } else if (typeof teams === "object" && teams !== null) {
            Object.entries(teams).forEach(([k, t]) => {
                const tNum = Number(String(k).replace(/\D/g, ""));
                if (!tNum || !t) return;
                const val = t.xpPost ?? t.xp ?? t.xpPre ?? t.exp ?? t.total ?? t.total_points;
                if (val !== undefined && val !== null && Number.isFinite(Number(val))) {
                    const numVal = Number(val);
                    if (matchRaw) expByTeamMatch.set(`${tNum}:${matchRaw}`, numVal);
                    if (matchNum) expByTeamMatch.set(`${tNum}:m${matchNum}`, numVal);
                }
            });
        }
    });

    const matchesByTeam = new Map();
    (ctx.matches || []).forEach((m) => {
        const red = (m.redTeams || []).map(teamNumberFromKey).filter(Boolean);
        const blue = (m.blueTeams || []).map(teamNumberFromKey).filter(Boolean);
        [...red, ...blue].forEach((tNum) => {
            if (!matchesByTeam.has(tNum)) matchesByTeam.set(tNum, []);
            matchesByTeam.get(tNum).push(m);
        });
    });

    ctx.teams.forEach((team, teamNumber) => {
        const entries = (byTeam.get(teamNumber) || []).slice().sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));
        const qual = qualByTeam.get(teamNumber) || [];
        const teamSchedMatches = matchesByTeam.get(teamNumber) || [];

        // Build integrated match map
        const matchMap = new Map();

        teamSchedMatches.forEach((m) => {
            const mKey = m.matchKey || (m.matchNumber ? `qm${m.matchNumber}` : "");
            const mNum = m.matchNumber;
            const key = mKey || `m${mNum}`;
            matchMap.set(key, { matchNumber: mNum, matchKey: mKey, values: {}, notes: undefined });
        });

        entries.forEach((e) => {
            const mKey = e.matchKey || (e.matchNumber ? `qm${e.matchNumber}` : "");
            const key = mKey || `m${e.matchNumber}` || `entry_${e.id}`;
            let existing = matchMap.get(key);
            if (!existing && e.matchNumber) existing = matchMap.get(`m${e.matchNumber}`) || matchMap.get(`qm${e.matchNumber}`);
            if (!existing) {
                existing = { matchNumber: e.matchNumber, matchKey: mKey, values: {}, notes: undefined };
                matchMap.set(key, existing);
            }
            ctx.metrics.forEach((metric) => {
                if (metric.kind !== "external" && metric.kind !== "qual") {
                    existing.values[metric.id] = metricValueForEntry(ctx, metric, e);
                }
            });
            const d = entryData(e);
            existing.notes = d.notes || d.comment || d.comments || undefined;
        });

        // Attach external metrics per match (Statbotics EPA, Match 13 xP)
        matchMap.forEach((entry) => {
            const mKey = String(entry.matchKey || "").toLowerCase();
            const mNum = entry.matchNumber;
            const epaVal = epaByTeamMatch.get(`${teamNumber}:${mKey}`) ?? (mNum ? epaByTeamMatch.get(`${teamNumber}:m${mNum}`) : null);
            const expVal = expByTeamMatch.get(`${teamNumber}:${mKey}`) ?? (mNum ? expByTeamMatch.get(`${teamNumber}:m${mNum}`) : null);
            if (epaVal !== null && epaVal !== undefined) entry.values["ext:epa"] = epaVal;
            else if (team.epa !== null && team.epa !== undefined) entry.values["ext:epa"] = readNumber(team.epa);

            if (expVal !== null && expVal !== undefined) entry.values["ext:exp"] = expVal;
            else if (team.exp !== null && team.exp !== undefined) entry.values["ext:exp"] = readNumber(team.exp);
            else if (team.match13Exp !== null && team.match13Exp !== undefined) entry.values["ext:exp"] = readNumber(team.match13Exp);

            if (team.opr !== null && team.opr !== undefined) entry.values["ext:opr"] = readNumber(team.opr);
        });

        const perMatch = Array.from(matchMap.values()).sort((a, b) => (a.matchNumber || 0) - (b.matchNumber || 0));

        const metrics = {};
        ctx.metrics.forEach((metric) => {
            if (metric.kind === "external") {
                const vals = perMatch.map((p) => p.values[metric.id]).filter((v) => v !== null && v !== undefined);
                if (vals.length > 0) {
                    metrics[metric.id] = summarize(vals);
                } else {
                    const val = readNumber(team[metric.key]);
                    metrics[metric.id] = val === null ? null : { avg: val, max: val, min: val, n: 1, stdev: 0 };
                }
                return;
            }
            if (metric.kind === "qual") {
                metrics[metric.id] = summarize(qual.map((q) => readNumber(entryData(q)[metric.fieldId])));
                return;
            }
            const values = entries.map((e) => metricValueForEntry(ctx, metric, e));
            metrics[metric.id] = summarize(values);
        });

        const teamPlayedCount = team.matchesPlayed ?? teamSchedMatches.filter((m) => m.redScore !== null && m.redScore !== undefined && m.redScore >= 0).length;
        const teamSchedCount = teamSchedMatches.length;

        stats.set(teamNumber, {
            teamNumber,
            name: team.nickname || team.teamName || team.name || "",
            matchesScouted: entries.length,
            matchesPlayed: teamPlayedCount || (entries.length > 0 ? entries.length : 0),
            matchesScheduled: teamSchedCount,
            prescoutOnly: entries.length > 0 && entries.every((e) => e._prescout),
            metrics,
            perMatch
        });
    });
    return stats;
}

export function teamName(ctx, teamNumber) {
    const s = ctx.stats.get(Number(teamNumber));
    return s && s.name ? s.name : "";
}

export function teamLabel(ctx, teamNumber) {
    const name = teamName(ctx, teamNumber);
    return name ? `${teamNumber} (${name})` : String(teamNumber);
}

export function round(value, digits = 1) {
    if (value === null || value === undefined || !Number.isFinite(value)) return null;
    const f = 10 ** digits;
    return Math.round(value * f) / f;
}

/** Teams ranked by a metric's average. */
export function rankTeams(ctx, metric, { order = "desc", minMatches = 1, exclude = [] } = {}) {
    const rows = [];
    ctx.stats.forEach((s) => {
        if (exclude.includes(s.teamNumber)) return;
        const m = metric ? s.metrics[metric.id] : null;
        if (minMatches > 0 && metric && metric.kind !== "external" && metric.kind !== "qual" && s.matchesScouted < minMatches) return;
        rows.push({ teamNumber: s.teamNumber, name: s.name, avg: m?.avg ?? null, max: m?.max ?? null, min: m?.min ?? null, n: m?.n ?? 0, stdev: m?.stdev ?? 0 });
    });
    rows.sort((a, b) => {
        if (a.avg === null && b.avg === null) return a.teamNumber - b.teamNumber;
        if (a.avg === null) return 1;
        if (b.avg === null) return -1;
        return order === "asc" ? a.avg - b.avg : b.avg - a.avg;
    });
    return rows;
}

export function matchLabel(match) {
    if (!match) return "";
    if (match.label) return match.label;
    const level = String(match.compLevel || "qm").toUpperCase();
    return match.setNumber && match.compLevel !== "qm" ? `${level} ${match.setNumber}-${match.matchNumber}` : `${level} ${match.matchNumber}`;
}

export function teamNumberFromKey(key) {
    const n = parseInt(String(key).replace(/^frc|^ftc/i, ""), 10);
    return Number.isFinite(n) ? n : null;
}

/** Finds a match by number ("12" -> QM 12), label, or "next" (our team's next unplayed match). */
export function findMatch(ctx, ref) {
    if (!ctx.matches.length) return null;
    const raw = String(ref ?? "").trim().toLowerCase();
    if (!raw || raw === "next") {
        const ours = ctx.ourTeam;
        const upcoming = ctx.matches.filter((m) => (m.redScore === null || m.redScore === undefined || m.redScore < 0) &&
            (!ours || [...(m.redTeams || []), ...(m.blueTeams || [])].map(teamNumberFromKey).includes(ours)));
        return upcoming[0] || null;
    }
    const byLabel = ctx.matches.find((m) => matchLabel(m).toLowerCase() === raw || String(m.matchKey).toLowerCase().endsWith(`_${raw}`));
    if (byLabel) return byLabel;
    const num = parseInt(raw.replace(/[^0-9]/g, ""), 10);
    if (!Number.isFinite(num)) return null;
    return ctx.matches.find((m) => m.compLevel === "qm" && m.matchNumber === num) || null;
}

/** Pit entries for a team, most recent first, as short "label: value" strings. */
export function pitHighlights(ctx, teamNumber, limit = 8) {
    const entries = ctx.pitEntries.filter((e) => Number(e.targetTeamNumber) === Number(teamNumber))
        .sort((a, b) => String(b.createdAt || "").localeCompare(String(a.createdAt || "")));
    if (!entries.length) return [];
    const data = entryData(entries[0]);
    const out = [];
    (ctx.pitConfig.fields || []).forEach((field) => {
        if (out.length >= limit || field.type === "section" || RESERVED_FIELDS.has(field.id)) return;
        const type = String(field.type || "").toLowerCase();
        if (type === "image" || type === "file" || type === "photo") return;
        let value = data[field.id];
        if (value === undefined || value === null || value === "") return;
        if (typeof value === "object") value = JSON.stringify(value);
        value = String(value);
        if (value.startsWith("data:")) return;
        out.push(`${localizeLabel(field.label, field.id)}: ${value.length > 80 ? value.slice(0, 77) + "..." : value}`);
    });
    return out;
}

/** Free-text notes about a team: qualitative text fields plus match-scouting comment fields. */
export function teamNotes(ctx, teamNumber) {
    const notes = [];
    const collect = (entries, config, source) => {
        const textFields = (config.fields || []).filter((f) => f.type === "textarea" || f.type === "text");
        entries.filter((e) => Number(e.targetTeamNumber) === Number(teamNumber)).forEach((e) => {
            const data = entryData(e);
            textFields.forEach((field) => {
                const value = typeof data[field.id] === "string" ? data[field.id].trim() : "";
                if (value.length < 3) return;
                notes.push({
                    id: `${e.id || ""}:${field.id}`,
                    match: e.matchNumber ? `Q${e.matchNumber}` : (e.isPrescout ? "prescout" : ""),
                    source,
                    text: value.replace(/\s+/g, " ")
                });
            });
        });
    };
    collect(ctx.qualEntries, ctx.qualConfig, "qual");
    collect(ctx.entries, ctx.config, "match");
    const seen = new Set();
    return notes.filter((n) => {
        const k = n.text.toLowerCase();
        if (seen.has(k)) return false;
        seen.add(k);
        return true;
    });
}

const api = { loadContext, clearContextCache, findMetric, rankTeams, findMatch, matchLabel, teamLabel, teamName, teamNumberFromKey, pitHighlights, teamNotes, round, entryData, readNumber };
export default api;
