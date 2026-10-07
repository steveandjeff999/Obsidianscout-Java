/**
 * Shared helpers for the assistant: i18n, language, rounding, team/metric argument parsing, safe arithmetic.
 * Part of the Local AI assistant (see ../ai-tools.js for the orchestration).
 */

import Data from "../ai-data.js";

export const LANGUAGE_NAMES = { en: "English", es: "Spanish", he: "Hebrew", tr: "Turkish" };

export function t(key, fallback) {
    return (window.Obsidianscout && typeof window.Obsidianscout.t === "function") ? window.Obsidianscout.t(key, fallback) : fallback;
}

export function currentLanguage() {
    let lang = "en";
    try { lang = localStorage.getItem("obsidianscout:lang") || "en"; } catch (_) { /* ignore */ }
    return LANGUAGE_NAMES[lang] || "English";
}

export const r1 = (v) => Data.round(v, 1);

export function metricOrDefault(ctx, phrase, fallbackId = "score_total") {
    return Data.findMetric(ctx, phrase, { minScore: 1 }) || ctx.metrics.find((m) => m.id === fallbackId) || ctx.metrics[0];
}

export function toTeamNumbers(ctx, value) {
    const list = Array.isArray(value) ? value : (value === undefined || value === null ? [] : [value]);
    return Array.from(new Set(list.map((v) => parseInt(String(v).replace(/^frc/i, ""), 10)).filter((n) => Number.isFinite(n) && ctx.stats.has(n))));
}

export function unknownTeams(ctx, value) {
    const list = Array.isArray(value) ? value : (value === undefined || value === null ? [] : [value]);
    return list.map((v) => parseInt(String(v).replace(/^frc/i, ""), 10)).filter((n) => Number.isFinite(n) && !ctx.stats.has(n));
}


export function fmtTeamPage(team) {
    return t("ai.link.team_page", "Open team {team}").replace("{team}", team);
}

/** Enhanced safe arithmetic & statistics evaluator (no eval): supports + - * / % ( ) decimals and mean(), avg(), diff(), sum(), min(), max(), abs(). */
export function evaluate(expression) {
    let clean = String(expression || "").trim();
    if (!clean) throw new Error("Empty expression");

    // Replace mathematical and statistical functions
    const fnRegex = /\b(mean|avg|average|sum|diff|difference|abs|min|max)\s*\(([^()]+)\)/gi;
    let guard = 0;
    while (fnRegex.test(clean) && guard++ < 20) {
        clean = clean.replace(fnRegex, (_, fn, argsStr) => {
            const nums = argsStr.split(",").map((s) => evaluate(s.trim()));
            if (!nums.length || nums.some((n) => !Number.isFinite(n))) throw new Error("Invalid function arguments");
            const name = fn.toLowerCase();
            if (name === "mean" || name === "avg" || name === "average") {
                return String(nums.reduce((a, b) => a + b, 0) / nums.length);
            }
            if (name === "sum") {
                return String(nums.reduce((a, b) => a + b, 0));
            }
            if (name === "diff" || name === "difference") {
                return String(nums.length === 2 ? (nums[0] - nums[1]) : Math.abs(nums[0] - (nums[1] || 0)));
            }
            if (name === "abs") {
                return String(Math.abs(nums[0]));
            }
            if (name === "min") {
                return String(Math.min(...nums));
            }
            if (name === "max") {
                return String(Math.max(...nums));
            }
            return String(nums[0]);
        });
    }

    const tokens = clean.match(/\d+(?:\.\d+)?|[-+*/%()]/g);
    if (!tokens || tokens.join("") !== clean.replace(/\s+/g, "")) throw new Error("Unsupported expression: " + clean);
    let i = 0;
    const peek = () => tokens[i];
    const next = () => tokens[i++];
    function primary() {
        const tok = next();
        if (tok === "(") { const v = sum(); if (next() !== ")") throw new Error("Missing )"); return v; }
        if (tok === "-") return -primary();
        if (tok === "+") return primary();
        const n = Number(tok);
        if (!Number.isFinite(n)) throw new Error("Bad number");
        return n;
    }
    function product() {
        let v = primary();
        while (["*", "/", "%"].includes(peek())) {
            const op = next();
            const r = primary();
            v = op === "*" ? v * r : op === "/" ? v / r : v % r;
        }
        return v;
    }
    function sum() {
        let v = product();
        while (["+", "-"].includes(peek())) {
            const op = next();
            const r = product();
            v = op === "+" ? v + r : v - r;
        }
        return v;
    }
    const value = sum();
    if (i !== tokens.length) throw new Error("Unexpected input");
    return value;
}


export function fmt(template, values) {
    return String(template).replace(/\{(\w+)\}/g, (_, k) => (values[k] !== undefined && values[k] !== null ? values[k] : "-"));
}

export function nameOf(entry) {
    return entry.name ? `${entry.team} ${entry.name}` : String(entry.team);
}
