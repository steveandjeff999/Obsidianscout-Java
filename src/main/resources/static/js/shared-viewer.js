/**
 * Shared Link Viewer for ObsidianScout
 * Renders full counterpart dashboards (Graphs, All Data, Predictor, Custom Analytics)
 * identically matching the main app pages, scoped directly to shared link data.
 */

import { request } from './base/http.js';
import { showToast } from './components/toast.js';
import { t, applyTranslations, loadLocale, currentLang } from './base/i18n.js';

const PLOTLY_CONFIG = {
    responsive: true,
    displayModeBar: false,
    displaylogo: false
};

const SVG_ICONS = {
    expand: `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 3h6v6M9 21H3v-6M21 3l-7 7M3 21l7-7"/></svg>`,
    close: `<svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="18" y1="6" x2="6" y2="18"></line><line x1="6" y1="6" x2="18" y2="18"></line></svg>`,
    warning: `<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/></svg>`
};

function resolveThemeTokens() {
    const isDark = document.body.classList.contains('theme-dark');
    return {
        isDark,
        text: isDark ? '#f8fafc' : '#0f172a',
        accent: '#6366f1',
        grid: isDark ? 'rgba(255, 255, 255, 0.12)' : 'rgba(0, 0, 0, 0.08)',
        cardBg: isDark ? '#25252d' : '#f8fafc'
    };
}

function initViewerTheme() {
    const saved = localStorage.getItem('obsidian-theme');
    const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    const isDark = saved ? saved === 'dark' : (prefersDark !== false);

    document.body.classList.toggle('theme-dark', isDark);
    updateThemeToggleUI(isDark);

    const toggleBtn = document.getElementById('shared-theme-toggle');
    if (toggleBtn) {
        toggleBtn.onclick = () => {
            const nowDark = document.body.classList.toggle('theme-dark');
            localStorage.setItem('obsidian-theme', nowDark ? 'dark' : 'light');
            updateThemeToggleUI(nowDark);
            if (window._currentSharedRerender) {
                window._currentSharedRerender();
            }
        };
    }
}

function updateThemeToggleUI(isDark) {
    const icon = document.getElementById('shared-theme-icon');
    const label = document.getElementById('shared-theme-label');
    if (icon) {
        icon.className = isDark ? 'fas fa-sun' : 'fas fa-moon';
    }
    if (label) {
        label.textContent = isDark ? t('shared_viewer.light_mode', 'Light Mode') : t('shared_viewer.dark_mode', 'Dark Mode');
    }
}

async function ensurePlotly() {
    if (window.Plotly) return window.Plotly;
    let attempts = 0;
    while (!window.Plotly && attempts < 30) {
        await new Promise(r => setTimeout(r, 100));
        attempts++;
    }
    return window.Plotly;
}

document.addEventListener('DOMContentLoaded', async () => {
    await loadLocale(currentLang);
    initViewerTheme();
    applyTranslations();

    // Extract token from path: /shared/<token> or query param ?token=<token>
    const pathParts = window.location.pathname.split('/');
    let token = pathParts[pathParts.length - 1];
    if (token === 'shared' || token === 'shared.html' || !token) {
        const urlParams = new URLSearchParams(window.location.search);
        token = urlParams.get('token');
    }

    if (!token) {
        showError('No share token provided in URL.');
        return;
    }

    await loadSharedPayload(token);
});

async function loadSharedPayload(token, pin = null) {
    try {
        let url = `/api/shares/resolve/${encodeURIComponent(token)}`;
        if (pin) url += `?pin=${encodeURIComponent(pin)}`;

        const res = await request(url);
        if (!res) {
            showError('Unable to reach server or share link does not exist.');
            return;
        }

        if (res.isRevoked) {
            showError(t('shared_viewer.link_revoked_desc', 'This share link has been revoked by the owner team.'));
            return;
        }

        if (res.isExpired) {
            showError(t('shared_viewer.link_expired_desc', `This share link has expired on ${new Date(res.expiresAt).toLocaleString()}.`));
            return;
        }

        if (res.requiresPin && !res.pinVerified) {
            showPinGate(token, res.errorMessage);
            return;
        }

        if (!res.isAllowed) {
            showError(res.errorMessage || t('shared_viewer.team_restricted_desc', 'Access denied for this shared link.'));
            return;
        }

        renderPayload(res);
    } catch (err) {
        console.error('Failed to resolve share:', err);
        showError(err.message || 'Error loading shared content.');
    }
}

function showPinGate(token, msg) {
    document.getElementById('shared-status-banner').style.display = 'none';
    document.getElementById('shared-main-content').style.display = 'none';
    const pinGate = document.getElementById('shared-pin-gate');
    pinGate.style.display = 'flex';

    applyTranslations();

    const titleEl = pinGate.querySelector('h2');
    if (titleEl) {
        titleEl.textContent = t('shared_viewer.pin_required_title', 'Protected Share Link');
    }

    const promptEl = document.getElementById('pin-prompt-msg');
    if (promptEl) {
        promptEl.textContent = msg || t('shared_viewer.pin_prompt', 'This shared data is secured with a PIN code.');
    }

    const pinInput = document.getElementById('pin-entry-input');
    const submitBtn = document.getElementById('pin-submit-btn');

    if (pinInput) {
        pinInput.placeholder = t('shared_viewer.pin_placeholder', 'Enter PIN code...');
        pinInput.value = '';
        pinInput.focus();
    }

    if (submitBtn) {
        submitBtn.innerHTML = `<i class="fas fa-unlock"></i> <span>${t('shared_viewer.unlock_data', 'Unlock Data')}</span>`;
    }

    const doSubmit = async () => {
        const pinVal = pinInput.value.trim();
        if (!pinVal) {
            showToast(t('shared_viewer.enter_pin_toast', 'Please enter the access PIN'), 'warning');
            return;
        }
        submitBtn.disabled = true;
        submitBtn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${t('shared_viewer.checking', 'Checking...')}`;
        await loadSharedPayload(token, pinVal);
        submitBtn.disabled = false;
        submitBtn.innerHTML = `<i class="fas fa-unlock"></i> <span>${t('shared_viewer.unlock_data', 'Unlock Data')}</span>`;
    };

    submitBtn.onclick = doSubmit;
    pinInput.onkeydown = (e) => {
        if (e.key === 'Enter') doSubmit();
    };
}

function showError(msg) {
    document.getElementById('shared-pin-gate').style.display = 'none';
    document.getElementById('shared-main-content').style.display = 'none';
    const banner = document.getElementById('shared-status-banner');
    banner.className = 'card';
    banner.style.display = 'block';
    banner.style.borderLeft = '4px solid var(--danger, #ef4444)';
    banner.innerHTML = `
        <div style="display: flex; align-items: center; gap: 12px; padding: 20px;">
            <i class="fas fa-exclamation-triangle fa-2x" style="color: var(--danger, #ef4444);"></i>
            <div>
                <h3 style="margin: 0 0 6px 0;">Access Notice</h3>
                <p style="margin: 0; color: var(--muted);">${msg}</p>
            </div>
        </div>
    `;
}

function renderPayload(payload) {
    document.getElementById('shared-pin-gate').style.display = 'none';
    document.getElementById('shared-status-banner').style.display = 'none';
    document.getElementById('shared-main-content').style.display = 'block';

    document.getElementById('view-title-text').innerText = payload.title || 'Shared Scouting View';
    document.getElementById('view-owner-team').innerText = payload.ownerTeamNumber;
    document.getElementById('view-program').innerText = payload.program || 'FRC';

    const descElem = document.getElementById('view-desc');
    if (payload.description) {
        descElem.innerText = payload.description;
        descElem.style.display = 'block';
    } else {
        descElem.style.display = 'none';
    }

    // Badges
    const scopeBadge = document.getElementById('view-scope-badge');
    scopeBadge.className = `share-badge share-badge-${payload.accessScope}`;
    scopeBadge.innerText = payload.accessScope.toUpperCase();

    const modeBadge = document.getElementById('view-mode-badge');
    modeBadge.className = payload.shareMode === 'frozen_snapshot' ? 'share-badge share-badge-pin' : 'share-badge share-badge-active';
    modeBadge.innerText = payload.shareMode === 'frozen_snapshot' ? 'SNAPSHOT' : 'LIVE FEED';

    // Event & Expiry
    if (payload.targetEventKey) {
        document.getElementById('view-event-key').innerText = payload.targetEventKey;
    } else {
        document.getElementById('view-event-key').innerText = 'All Events';
    }

    if (payload.expiresAt) {
        document.getElementById('view-expiry-text').innerText = `Expires ${new Date(payload.expiresAt).toLocaleDateString()}`;
    } else {
        document.getElementById('view-expiry-text').innerText = 'Permanent Link';
    }

    // Icon
    const icon = document.getElementById('view-icon');
    if (payload.resourceType === 'graph') icon.className = 'fas fa-chart-line';
    else if (payload.resourceType.includes('predictor')) icon.className = 'fas fa-brain';
    else if (payload.resourceType === 'custom_analytics') icon.className = 'fas fa-chart-pie';
    else icon.className = 'fas fa-table';

    // Parse Data
    let queryConfig = {};
    try { queryConfig = JSON.parse(payload.queryConfigJson || '{}'); } catch (_) {}
    let snapshotData = null;
    try { if (payload.snapshotDataJson) snapshotData = JSON.parse(payload.snapshotDataJson); } catch (_) {}
    let liveData = null;
    try { if (payload.liveDataJson) liveData = JSON.parse(payload.liveDataJson); } catch (_) {}

    const target = document.getElementById('view-render-target');
    target.innerHTML = '';
    target.style.background = 'transparent';
    target.style.border = 'none';
    target.style.boxShadow = 'none';
    target.style.padding = '0';

    if (payload.resourceType === 'graph') {
        renderGraphView(target, queryConfig, snapshotData, liveData);
    } else if (payload.resourceType === 'predictor' || payload.resourceType === 'event_predictor') {
        renderPredictorView(target, queryConfig, snapshotData, liveData);
    } else if (payload.resourceType === 'custom_analytics') {
        renderCustomAnalyticsView(target, queryConfig, snapshotData, liveData);
    } else {
        renderDataTableView(target, queryConfig, snapshotData, liveData);
    }
}

// ==========================================================================
// 1. FULL GRAPHS DASHBOARD IMPLEMENTATION (Matching graphs.html & graphs.js)
// ==========================================================================

const GRAPH_TYPES = [
    { id: "bar", label: "Bar" },
    { id: "line", label: "Line" },
    { id: "scatter", label: "Scatter" },
    { id: "area", label: "Area" },
    { id: "box", label: "Box" },
    { id: "violin", label: "Violin" },
    { id: "histogram", label: "Histogram" }
];

const RESERVED_FIELDS = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);

function normalizePhase(rawPhase) {
    if (!rawPhase) return null;
    const p = String(rawPhase).toLowerCase().trim();
    if (p.includes("auto") || p.includes("autónomo") || p.includes("autonomo")) return "auto";
    if (p.includes("teleop") || p.includes("teleoperado") || p.includes("general")) return "teleop";
    if (p.includes("endgame") || p.includes("end") || p.includes("fin")) return "endgame";
    if (p.includes("post")) return "postmatch";
    return p;
}

function readNumber(value) {
    if (value === null || value === undefined) return null;
    if (typeof value === "number") return value;
    if (typeof value === "string") {
        const parsed = Number(value);
        return Number.isNaN(parsed) ? null : parsed;
    }
    return null;
}

function readLabel(value) {
    if (value === null || value === undefined) return null;
    if (typeof value === "string") return value;
    if (typeof value === "number") return String(value);
    return null;
}

function readBoolean(value) {
    if (value === null || value === undefined) return null;
    if (typeof value === "boolean") return value;
    if (typeof value === "string") {
        if (value.toLowerCase() === "true") return true;
        if (value.toLowerCase() === "false") return false;
    }
    return null;
}

function fieldPoints(field, value) {
    if (!field || value === null || value === undefined) return 0;
    const type = String(field.type || "").toLowerCase();
    const pointsPer = Number(field.pointsPer || 0);

    if (type === "counter" || type === "number" || type === "rating") {
        const number = readNumber(value) || 0;
        return number * pointsPer;
    }
    if (type === "checkbox") {
        const enabled = readBoolean(value) || false;
        return enabled ? pointsPer : 0;
    }
    if (type === "select") {
        const label = readLabel(value);
        const options = field.options || [];
        const match = options.find((option) => option.value === label || option.label === label);
        return match ? Number(match.points || 0) : 0;
    }
    return 0;
}

function entryScore(config, entry, scope = "total") {
    if (!config || !entry || !config.fields) return 0;
    const data = getEntryData(entry);
    const targetScope = normalizePhase(scope) || "total";
    let currentSectionPhase = "auto";

    return config.fields.reduce((total, field) => {
        if (RESERVED_FIELDS.has(field.id)) return total;
        if (field.type === "section") {
            const secPhase = normalizePhase(field.phase) || normalizePhase(field.label);
            if (secPhase) currentSectionPhase = secPhase;
            return total;
        }

        let fieldPhase = normalizePhase(field.phase);
        if (!fieldPhase) {
            const id = String(field.id || "").toLowerCase();
            if (id.startsWith("auto")) fieldPhase = "auto";
            else if (id.startsWith("teleop")) fieldPhase = "teleop";
            else if (id.startsWith("endgame")) fieldPhase = "endgame";
            else if (id.startsWith("post")) fieldPhase = "postmatch";
            else fieldPhase = currentSectionPhase;
        }

        if (targetScope !== "total" && fieldPhase !== targetScope) return total;
        return total + fieldPoints(field, data[field.id]);
    }, 0);
}

function getEntryData(entry) {
    if (!entry) return {};
    if (typeof entry.data === "object" && entry.data !== null) return entry.data;
    if (typeof entry.dataJson === "string") {
        try { return JSON.parse(entry.dataJson); } catch (_) {}
    }
    if (typeof entry.data === "string") {
        try { return JSON.parse(entry.data); } catch (_) {}
    }
    return entry;
}

function metricValue(entry, metric, config) {
    if (!metric) return null;
    if (metric.kind === "count") return 1;
    if (metric.kind === "score") return entryScore(config, entry, metric.scope);
    const data = getEntryData(entry);
    if (metric.kind === "numeric") return readNumber(data[metric.fieldId]);
    if (metric.kind === "category") return readLabel(data[metric.fieldId]);
    return null;
}

function buildMetricOptions(config) {
    const options = [
        { id: "score_total", label: t('graphs.total_points', "Total points"), kind: "score", scope: "total" },
        { id: "score_auto", label: t('graphs.auto_points', "Auto points"), kind: "score", scope: "auto" },
        { id: "score_teleop", label: t('graphs.teleop_points', "Teleop points"), kind: "score", scope: "teleop" },
        { id: "score_endgame", label: t('graphs.endgame_points', "Endgame points"), kind: "score", scope: "endgame" },
        { id: "count", label: t('graphs.entry_count', "Entry count"), kind: "count" }
    ];

    if (config && Array.isArray(config.fields)) {
        config.fields.forEach((field) => {
            if (RESERVED_FIELDS.has(field.id) || field.type === "section") return;
            const type = String(field.type || "").toLowerCase();
            const label = field.label || field.id;
            if (type === "number" || type === "counter" || type === "rating") {
                options.push({ id: `field:${field.id}`, label, kind: "numeric", fieldId: field.id, field });
            } else if (type === "select" || type === "checkbox") {
                options.push({ id: `category:${field.id}`, label, kind: "category", fieldId: field.id, field });
            }
        });
    }

    return options;
}

function extractTeamExpData(matchObj, teamNumber) {
    if (!matchObj || typeof matchObj !== "object") return null;
    const teams = matchObj.teams;
    if (!teams) return null;
    const num = Number(teamNumber);
    if (Array.isArray(teams)) {
        return teams.find(t => {
            const tNum = t.teamNumber || t.team_number || t.team || String(t.teamKey || "").replace(/\D/g, "");
            return Number(tNum) === num;
        }) || null;
    }
    if (typeof teams === "object") {
        return teams[num]
            || teams[String(num)]
            || teams[`frc${num}`]
            || teams[`ftc${num}`]
            || teams[`frc_${num}`]
            || null;
    }
    return null;
}

function getTeamExpMetricValue(teamExpData, metricId) {
    if (!teamExpData) return 0;
    if (metricId === "score_auto") {
        return Number(teamExpData.xAutoPost ?? teamExpData.xAuto ?? teamExpData.xAutoPre ?? teamExpData.auto ?? teamExpData.auto_exp ?? teamExpData.autoExp ?? 0);
    }
    if (metricId === "score_teleop") {
        return Number(teamExpData.xTelePost ?? teamExpData.xTele ?? teamExpData.xTelePre ?? teamExpData.teleop ?? teamExpData.teleop_exp ?? teamExpData.teleopExp ?? 0);
    }
    if (metricId === "score_endgame") {
        return Number(teamExpData.xEndPost ?? teamExpData.xEnd ?? teamExpData.xEndPre ?? teamExpData.endgame ?? teamExpData.endgame_exp ?? teamExpData.endgameExp ?? 0);
    }
    return Number(teamExpData.xpPost ?? teamExpData.xp ?? teamExpData.xpPre ?? teamExpData.exp ?? teamExpData.total ?? teamExpData.total_points ?? 0);
}

function getDatasourceLabel(datasource, isFtc = false) {
    if (datasource === "epa") return t('predictor.statbotics_epa', "Statbotics EPA");
    if (datasource === "exp") return t('alliance-selection.match13_exp', "Match 13 EXP");
    if (datasource === "opr") return isFtc ? t('predictor.ftcscout_opr', "FTC Scout OPR") : t('predictor.tba_opr', "TBA OPR");
    if (datasource === "all") return t('rankings.metric.all', "All Sources");
    return t('predictor.scouted_data', "Scouted Data");
}

function formatMatchKeyToLabel(matchKey) {
    if (!matchKey) return "Match";
    const str = String(matchKey).trim();
    const parts = str.split('_');
    const compPart = parts.length > 1 ? parts[1].toLowerCase() : str.toLowerCase();
    if (compPart.startsWith("qm")) {
        return `QM ${compPart.replace("qm", "")}`;
    }
    if (compPart.startsWith("qf")) {
        const match = compPart.match(/qf(\d+)m(\d+)/);
        return match ? `QF ${match[1]}-${match[2]}` : compPart.toUpperCase();
    }
    if (compPart.startsWith("sf")) {
        const match = compPart.match(/sf(\d+)m(\d+)/);
        return match ? `SF ${match[1]}-${match[2]}` : compPart.toUpperCase();
    }
    if (compPart.startsWith("f")) {
        const match = compPart.match(/f(\d+)m(\d+)/);
        return match ? `Final ${match[2]}` : compPart.toUpperCase();
    }
    return compPart.toUpperCase();
}

function getMatchSortWeightFromEntry(entry) {
    if (!entry) return 0;
    if (entry.isPrescout) {
        return (entry.matchNumber || 0);
    }
    const matchKey = String(entry.matchKey || "").toLowerCase();
    const isPractice = entry.isPractice || matchKey.includes("practice") || matchKey.includes("_pm") || matchKey.includes("_pr");

    let levelWeight = 200000;
    if (isPractice) {
        levelWeight = 100000;
    } else if (matchKey.includes("_ef") || matchKey.startsWith("ef")) {
        levelWeight = 300000;
    } else if (matchKey.includes("_qf") || matchKey.startsWith("qf")) {
        levelWeight = 400000;
    } else if (matchKey.includes("_sf") || matchKey.startsWith("sf")) {
        levelWeight = 500000;
    } else if (matchKey.includes("_f") || matchKey.startsWith("f")) {
        levelWeight = 600000;
    }

    const matchNum = entry.matchNumber || 0;
    return levelWeight + (matchNum * 100);
}

function getMatchSortWeightFromLabel(label) {
    if (!label) return 0;
    const str = String(label).trim();
    if (/prescout/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return num ? parseInt(num, 10) : 0;
    }
    if (/^practice/i.test(str) || /^pm/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return 100000 + (num ? parseInt(num, 10) * 100 : 0);
    }
    if (/^qm/i.test(str) || /^q\s/i.test(str) || /^qual/i.test(str) || /^match/i.test(str)) {
        const num = (str.match(/\d+/) || [])[0];
        return 200000 + (num ? parseInt(num, 10) * 100 : 0);
    }
    if (/^ef/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 300000 + (setNum * 1000) + matchNum;
    }
    if (/^qf/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 400000 + (setNum * 1000) + matchNum;
    }
    if (/^sf/i.test(str) || /^semi/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 500000 + (setNum * 1000) + matchNum;
    }
    if (/^f\s/i.test(str) || /^final/i.test(str) || /^f\d/i.test(str)) {
        const nums = str.match(/\d+/g) || [];
        const setNum = nums[0] ? parseInt(nums[0], 10) : 0;
        const matchNum = nums[1] ? parseInt(nums[1], 10) : 0;
        return 600000 + (setNum * 1000) + matchNum;
    }
    const anyNum = (str.match(/\d+/) || [])[0];
    return 200000 + (anyNum ? parseInt(anyNum, 10) * 100 : 0);
}

function getSortedCategoriesFromSeries(seriesList) {
    const allLabels = new Set();
    seriesList.forEach((s) => {
        if (Array.isArray(s.x)) {
            s.x.forEach((label) => {
                if (label !== null && label !== undefined) {
                    allLabels.add(String(label));
                }
            });
        }
    });
    return Array.from(allLabels).sort((a, b) => {
        const weightA = getMatchSortWeightFromLabel(a);
        const weightB = getMatchSortWeightFromLabel(b);
        if (weightA !== weightB) {
            return weightA - weightB;
        }
        return a.localeCompare(b, undefined, { numeric: true, sensitivity: 'base' });
    });
}

function formatMatchLabel(entry, index) {
    let levelAbbrev = "QM";
    if (entry.isPrescout) {
        levelAbbrev = "Prescout";
    } else if (entry.isPractice) {
        levelAbbrev = "Practice";
    } else if (entry.matchKey) {
        const parts = String(entry.matchKey).split('_');
        if (parts.length > 1) {
            const rawLevel = parts[1].replace(/[0-9]/g, "");
            levelAbbrev = rawLevel.toUpperCase();
            if (levelAbbrev === "PM" || levelAbbrev === "PR") {
                levelAbbrev = "Practice";
            }
        }
    }
    const num = entry.matchNumber || (index + 1);
    const eventLabel = entry.isPrescout ? (entry.eventKey || "") : "";
    if (levelAbbrev === "Prescout") {
        return eventLabel ? `Prescout (${eventLabel})` : `Prescout ${num}`;
    }
    return eventLabel ? `${levelAbbrev} ${num} (${eventLabel})` : `${levelAbbrev} ${num}`;
}

async function renderGraphView(target, config, snapshot, live) {
    const plotly = await ensurePlotly();
    if (!plotly) {
        target.innerHTML = `<p class="notice" style="text-align: center; padding: 40px;">Interactive graphing engine failed to load.</p>`;
        return;
    }

    const dataSourceObj = snapshot || live || {};
    const entries = Array.isArray(dataSourceObj.entries) ? dataSourceObj.entries : [];
    const teams = Array.isArray(dataSourceObj.teams) ? dataSourceObj.teams : [];
    const statsHistory = dataSourceObj.statsHistory || { oprs: {}, epaHistory: [], match13History: [] };
    const settings = dataSourceObj.settings || {};
    const scoutingConfig = dataSourceObj.config || config.config || { fields: [] };
    const availableMetrics = dataSourceObj.metrics || buildMetricOptions(scoutingConfig);

    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (settings.program === "FTC");

    const effectiveUseEpa = !isFtc && Boolean(settings.useStatboticsEpa || teams.some(t => t.epa !== undefined && t.epa !== null && t.epa !== 0) || (statsHistory.epaHistory && statsHistory.epaHistory.length > 0));
    const effectiveUseExp = !isFtc && Boolean(settings.useMatch13Exp || (statsHistory.match13History && statsHistory.match13History.length > 0) || teams.some(t => (t.exp !== undefined && t.exp !== null && t.exp !== 0) || (t.match13Exp !== undefined && t.match13Exp !== null && t.match13Exp !== 0)));
    const effectiveUseOpr = Boolean(settings.useTbaOpr || teams.some(t => t.opr !== undefined && t.opr !== null && t.opr !== 0) || (statsHistory.oprs && Object.keys(statsHistory.oprs).length > 0));
    const hasAnyExternal = effectiveUseEpa || effectiveUseExp || effectiveUseOpr;

    const state = {
        entries,
        teams,
        eventTeamsMap: new Map(teams.map(t => [Number(t.teamNumber), t])),
        statsHistory,
        settings,
        isFtc,
        effectiveUseEpa,
        effectiveUseExp,
        effectiveUseOpr,
        config: scoutingConfig,
        metrics: availableMetrics,
        metricMap: new Map(availableMetrics.map(m => [m.id, m])),
        metricId: config.metricId || (snapshot && snapshot.metricId) || 'score_total',
        datasource: config.datasource || (snapshot && snapshot.datasource) || 'scouted',
        dataView: config.dataView || (snapshot && snapshot.dataView) || 'averages',
        sort: config.sort || (snapshot && snapshot.sort) || 'value_desc',
        includePrescout: config.includePrescout || (snapshot && snapshot.includePrescout) || false,
        selectedGraphTypes: new Set(
            config.selectedGraphTypes || (snapshot && snapshot.selectedGraphTypes) || ['bar']
        ),
        selectedTeams: new Set(
            config.selectedTeams || (snapshot && snapshot.selectedTeams) ||
            (teams.length > 0
                ? teams.map(t => Number(t.teamNumber)).filter(Boolean)
                : Array.from(new Set(entries.map(e => Number(e.targetTeamNumber || e.teamNumber)).filter(Boolean))))
        )
    };

    if (state.selectedTeams.size === 0) {
        if (teams.length > 0) {
            teams.forEach(t => state.selectedTeams.add(Number(t.teamNumber)));
        } else if (entries.length > 0) {
            entries.forEach(e => {
                const t = Number(e.targetTeamNumber || e.teamNumber);
                if (t) state.selectedTeams.add(t);
            });
        }
    }

    const allTeamNumbers = Array.from(
        new Set([
            ...teams.map(t => Number(t.teamNumber)),
            ...entries.map(e => Number(e.targetTeamNumber || e.teamNumber))
        ].filter(Boolean))
    ).sort((a, b) => a - b);

    // Identical HTML Layout matching graphs.html
    target.innerHTML = `
        <div class="graphs-page">
            <div class="card graphs-hero">
                <h1 data-i18n="graphs.title">${t('graphs.title', 'Graphs Dashboard')}</h1>
                <p class="notice" data-i18n="graphs.notice">${t('graphs.notice', 'Pick teams, metrics, and graph types to generate visuals from your scouting data.')}</p>
            </div>

            <div id="graphs-summary" class="metrics mt-20"></div>

            <div class="split mt-20">
                <section class="card graphs-panel">
                    <div class="graphs-panel-header">
                        <h2 data-i18n="graphs.team_selection">${t('graphs.team_selection', 'Team selection')}</h2>
                        <span class="badge" id="selection-summary-badge">${state.selectedTeams.size} selected</span>
                    </div>

                    <div class="form-grid mt-12">
                        <div class="field" style="grid-column: span 2;">
                            <label for="team-search-input" data-i18n="all-data.search_teams">${t('all-data.search_teams', 'Search teams')}</label>
                            <div class="row gap-12 wrap">
                                <input id="team-search-input" type="text" placeholder="${t('graphs.placeholder_type_team_number', 'Type team number')}" />
                                <button class="btn ghost" id="clear-search-btn" type="button" data-i18n="graphs.clear_search">${t('graphs.clear_search', 'Clear')}</button>
                            </div>
                        </div>
                    </div>

                    <div class="row gap-12 wrap mt-12">
                        <button class="btn ghost" id="select-all-teams" type="button" data-i18n="graphs.select_all">${t('graphs.select_all', 'Select all')}</button>
                        <button class="btn ghost" id="select-top-teams" type="button" data-i18n="graphs.select_top">${t('graphs.select_top', 'Top 8')}</button>
                        <button class="btn ghost" id="clear-teams" type="button" data-i18n="graphs.clear_all">${t('graphs.clear_all', 'Clear all')}</button>
                    </div>

                    <div class="selected-pills mt-12" id="selected-pills-container"></div>

                    <div class="team-list-scroll mt-12" id="team-list"></div>

                    <p class="notice mt-8" id="team-selection-status"></p>
                </section>

                <section class="card graphs-panel">
                    <div class="graphs-panel-header">
                        <h2 data-i18n="graphs.graph_options">${t('graphs.graph_options', 'Graph options')}</h2>
                        <span class="badge" id="graph-type-selected-count">${state.selectedGraphTypes.size} selected</span>
                    </div>

                    <div class="form-grid mt-12">
                        ${hasAnyExternal ? `
                            <div class="field" id="datasource-field">
                                <label for="datasource-select" data-i18n="predictor.data_source">${t('predictor.data_source', 'Data Source')}</label>
                                <select id="datasource-select" class="select-wide">
                                    <option value="all" ${state.datasource === 'all' ? 'selected' : ''}>${t('rankings.metric.all', 'All Sources')}</option>
                                    <option value="scouted" ${state.datasource === 'scouted' ? 'selected' : ''}>${t('predictor.scouted_data', 'Scouted Data')}</option>
                                    ${effectiveUseEpa ? `<option value="epa" ${state.datasource === 'epa' ? 'selected' : ''}>${t('predictor.statbotics_epa', 'Statbotics EPA')}</option>` : ''}
                                    ${effectiveUseExp ? `<option value="exp" ${state.datasource === 'exp' ? 'selected' : ''}>${t('alliance-selection.match13_exp', 'Match 13 EXP')}</option>` : ''}
                                    ${effectiveUseOpr ? `<option value="opr" ${state.datasource === 'opr' ? 'selected' : ''}>${isFtc ? t('predictor.ftcscout_opr', 'FTC Scout OPR') : t('predictor.tba_opr', 'TBA OPR')}</option>` : ''}
                                </select>
                            </div>
                        ` : ''}

                        <div class="field" id="metric-field">
                            <label for="graph-metric" data-i18n="graphs.metric">${t('graphs.metric', 'Metric')}</label>
                            <select id="graph-metric">
                                ${availableMetrics.map(m => `<option value="${m.id}" ${m.id === state.metricId ? 'selected' : ''}>${m.label}</option>`).join('')}
                            </select>
                        </div>

                        <div class="field" id="view-field">
                            <label for="graph-view" data-i18n="graphs.data_view">${t('graphs.data_view', 'Data view')}</label>
                            <select id="graph-view">
                                <option value="averages" ${state.dataView === 'averages' ? 'selected' : ''} data-i18n="graphs.team_averages">${t('graphs.team_averages', 'Team averages')}</option>
                                <option value="matches" ${state.dataView === 'matches' ? 'selected' : ''} data-i18n="graphs.match_by_match">${t('graphs.match_by_match', 'Match-by-match')}</option>
                            </select>
                        </div>

                        <div class="field">
                            <label for="graph-sort" data-i18n="graphs.sort_teams">${t('graphs.sort_teams', 'Sort teams')}</label>
                            <select id="graph-sort">
                                <option value="value_desc" ${state.sort === 'value_desc' ? 'selected' : ''}>Value high → low</option>
                                <option value="value_asc" ${state.sort === 'value_asc' ? 'selected' : ''}>Value low → high</option>
                                <option value="team_asc" ${state.sort === 'team_asc' ? 'selected' : ''}>Team # low → high</option>
                                <option value="team_desc" ${state.sort === 'team_desc' ? 'selected' : ''}>Team # high → low</option>
                            </select>
                        </div>

                        <div class="field" id="prescout-field" style="display: flex; align-items: center; gap: 8px; grid-column: span 2;">
                            <input type="checkbox" id="include-prescout-checkbox" style="width: auto; margin-top: 0;" ${state.includePrescout ? 'checked' : ''} />
                            <label for="include-prescout-checkbox" style="margin-bottom: 0; cursor: pointer;" data-i18n="graphs.include_prescout_data">${t('graphs.include_prescout_data', 'Include prescout data')}</label>
                        </div>
                    </div>

                    <div class="graph-type-grid mt-12" id="graph-type-grid">
                        ${GRAPH_TYPES.map(gt => `
                            <label class="graph-type-item">
                                <input class="graph-type-checkbox" type="checkbox" value="${gt.id}" ${state.selectedGraphTypes.has(gt.id) ? 'checked' : ''} /> ${gt.label}
                            </label>
                        `).join('')}
                    </div>

                    <div class="row gap-12 wrap mt-12">
                        <button class="btn ghost" id="select-all-graph-types" type="button" data-i18n="graphs.select_all">${t('graphs.select_all', 'Select all')}</button>
                        <button class="btn ghost" id="clear-graph-types" type="button" data-i18n="graphs.clear_types">${t('graphs.clear_types', 'Clear')}</button>
                    </div>

                    <div class="row gap-12 wrap mt-16">
                        <button class="btn" id="graph-generate" type="button" data-i18n="graphs.generate">${t('graphs.generate', 'Generate graphs')}</button>
                        <span class="badge hidden" id="graph-loading" data-i18n="graphs.generating">${t('graphs.generating', 'Generating…')}</span>
                    </div>
                </section>
            </div>

            <div class="card mt-24">
                <h2 data-i18n="graphs.title">${t('graphs.title', 'Generated graphs')}</h2>
                <p class="notice" id="graphs-empty" style="display: none;">${t('graphs.empty', 'Select teams and generate graphs.')}</p>
                <div id="graphs-output" class="graphs-output mt-16"></div>
            </div>
        </div>
    `;

    function renderSummary() {
        const summaryContainer = document.getElementById("graphs-summary");
        if (!summaryContainer) return;
        summaryContainer.innerHTML = "";

        const teamsSet = new Set(allTeamNumbers);
        const matchesSet = new Set(entries.map(e => e.matchKey || e.matchNumber).filter(Boolean));
        const eventsSet = new Set(entries.map(e => e.eventKey).filter(Boolean));

        const createMetricCard = (label, value) => {
            const card = document.createElement("div");
            card.className = "card";
            card.innerHTML = `<h3>${label}</h3><div class="metric-value">${value}</div>`;
            return card;
        };

        summaryContainer.appendChild(createMetricCard("Entries", entries.length));
        summaryContainer.appendChild(createMetricCard("Events", Math.max(1, eventsSet.size)));
        summaryContainer.appendChild(createMetricCard("Teams", teamsSet.size));
        summaryContainer.appendChild(createMetricCard("Matches", matchesSet.size));
    }

    function updateTeamList() {
        const list = document.getElementById("team-list");
        if (!list) return;
        list.innerHTML = "";

        const scoutedTeamNumbers = new Set(
            entries
                .filter(e => state.includePrescout || !e.isPrescout)
                .map(e => Number(e.targetTeamNumber || e.teamNumber))
                .filter(Boolean)
        );

        allTeamNumbers.forEach(teamNumber => {
            const item = document.createElement("label");
            item.className = "team-list-item";
            item.dataset.teamNumber = String(teamNumber);

            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.checked = state.selectedTeams.has(teamNumber);
            checkbox.addEventListener("change", () => {
                if (checkbox.checked) {
                    state.selectedTeams.add(teamNumber);
                } else {
                    state.selectedTeams.delete(teamNumber);
                }
                updateSelectionSummary();
            });

            const meta = document.createElement("div");
            meta.className = "team-list-meta";
            const title = document.createElement("strong");

            const teamRecord = state.eventTeamsMap ? state.eventTeamsMap.get(teamNumber) : null;
            const nickname = teamRecord ? (teamRecord.nickname || teamRecord.name) : "";
            title.textContent = nickname ? `Team ${teamNumber} - ${nickname}` : `Team ${teamNumber}`;

            const sub = document.createElement("small");
            const isScouted = scoutedTeamNumbers.has(teamNumber);
            sub.textContent = isScouted ? "Scouted" : "Not Scouted";
            if (!isScouted) {
                sub.style.color = "var(--ink-muted, #737373)";
            }

            meta.appendChild(title);
            meta.appendChild(sub);
            item.appendChild(checkbox);
            item.appendChild(meta);
            list.appendChild(item);
        });

        updateSelectionSummary();
    }

    function updateSelectionSummary() {
        const badge = document.getElementById("selection-summary-badge");
        const status = document.getElementById("team-selection-status");
        const pills = document.getElementById("selected-pills-container");

        if (badge) {
            badge.textContent = state.selectedTeams.size ? `${state.selectedTeams.size} selected` : "No teams selected";
        }
        if (status) {
            status.textContent = `${state.selectedTeams.size} teams selected from ${allTeamNumbers.length} available`;
        }
        if (pills) {
            pills.innerHTML = "";
            const sorted = Array.from(state.selectedTeams).sort((a, b) => a - b);
            sorted.forEach((teamNumber) => {
                const pill = document.createElement("span");
                pill.className = "team-pill";
                pill.textContent = `Team ${teamNumber}`;
                const remove = document.createElement("button");
                remove.type = "button";
                remove.textContent = "×";
                remove.addEventListener("click", () => {
                    state.selectedTeams.delete(teamNumber);
                    updateTeamList();
                    updateSelectionSummary();
                });
                pill.appendChild(remove);
                pills.appendChild(pill);
            });
        }
    }

    function filterTeamList(query) {
        const q = String(query || "").trim().toLowerCase();
        document.querySelectorAll("#team-list .team-list-item").forEach(item => {
            const teamNum = item.dataset.teamNumber || "";
            const text = item.textContent.toLowerCase();
            const matches = !q || teamNum.includes(q) || text.includes(q);
            item.style.display = matches ? "flex" : "none";
        });
    }

    function updateGraphTypeAvailability() {
        const isAverages = state.dataView === "averages" || (state.datasource === "epa" || state.datasource === "opr");
        const currentMetric = state.metricMap.get(state.metricId) || state.metrics[0];
        const isCategory = (state.datasource === "scouted") && currentMetric && currentMetric.kind === "category";

        document.querySelectorAll(".graph-type-checkbox").forEach(checkbox => {
            const gt = checkbox.value;
            let disabled = false;

            if (isCategory && gt !== "bar") {
                disabled = true;
            } else if (isAverages && (gt === "line" || gt === "area")) {
                disabled = true;
            }

            checkbox.disabled = disabled;
            const parent = checkbox.closest(".graph-type-item");
            if (parent) {
                parent.style.opacity = disabled ? "0.4" : "1";
                parent.style.cursor = disabled ? "not-allowed" : "pointer";
            }

            if (disabled && state.selectedGraphTypes.has(gt)) {
                state.selectedGraphTypes.delete(gt);
                checkbox.checked = false;
            }
        });

        if (state.selectedGraphTypes.size === 0) {
            state.selectedGraphTypes.add("bar");
            const barBox = document.querySelector('.graph-type-checkbox[value="bar"]');
            if (barBox) barBox.checked = true;
        }

        const countBadge = document.getElementById("graph-type-selected-count");
        if (countBadge) {
            countBadge.textContent = `${state.selectedGraphTypes.size} selected`;
        }
    }

    function toggleFieldsForDatasource() {
        const metricField = document.getElementById("metric-field");
        const viewField = document.getElementById("view-field");
        const prescoutField = document.getElementById("prescout-field");
        const viewSelect = document.getElementById("graph-view");

        if (state.datasource === "scouted") {
            metricField?.classList.remove("hidden");
            viewField?.classList.remove("hidden");
            prescoutField?.classList.remove("hidden");
        } else if (state.datasource === "exp" || state.datasource === "all") {
            metricField?.classList.remove("hidden");
            viewField?.classList.remove("hidden");
            prescoutField?.classList.add("hidden");
        } else {
            metricField?.classList.add("hidden");
            viewField?.classList.add("hidden");
            prescoutField?.classList.add("hidden");
            state.dataView = "averages";
            if (viewSelect) viewSelect.value = "averages";
        }
        updateGraphTypeAvailability();
    }

    // Wire Interactive Controls
    const searchInput = document.getElementById("team-search-input");
    const clearSearch = document.getElementById("clear-search-btn");
    const selectAllTeams = document.getElementById("select-all-teams");
    const selectTopTeams = document.getElementById("select-top-teams");
    const clearAllTeams = document.getElementById("clear-teams");

    if (searchInput) searchInput.addEventListener("input", () => filterTeamList(searchInput.value));
    if (clearSearch) clearSearch.addEventListener("click", () => { if (searchInput) searchInput.value = ""; filterTeamList(""); });
    if (selectAllTeams) selectAllTeams.addEventListener("click", () => {
        allTeamNumbers.forEach(t => state.selectedTeams.add(t));
        updateTeamList();
    });
    if (clearAllTeams) clearAllTeams.addEventListener("click", () => {
        state.selectedTeams.clear();
        updateTeamList();
    });
    if (selectTopTeams) selectTopTeams.addEventListener("click", () => {
        const metric = state.metricMap.get(state.metricId) || state.metrics[0];
        const teamStats = calculateTeamAverages(entries, metric, state.config);
        const top = teamStats.sort((a, b) => b.value - a.value).slice(0, 8).map(item => item.teamNumber);
        state.selectedTeams.clear();
        top.forEach(team => state.selectedTeams.add(team));
        updateTeamList();
    });

    const dsSelect = document.getElementById("datasource-select");
    if (dsSelect) {
        dsSelect.addEventListener("change", () => {
            state.datasource = dsSelect.value;
            toggleFieldsForDatasource();
        });
    }

    const metricSelect = document.getElementById("graph-metric");
    if (metricSelect) {
        metricSelect.addEventListener("change", () => {
            state.metricId = metricSelect.value;
            updateGraphTypeAvailability();
        });
    }

    const viewSelect = document.getElementById("graph-view");
    if (viewSelect) {
        viewSelect.addEventListener("change", () => {
            state.dataView = viewSelect.value;
            updateGraphTypeAvailability();
        });
    }

    const sortSelect = document.getElementById("graph-sort");
    if (sortSelect) {
        sortSelect.addEventListener("change", () => {
            state.sort = sortSelect.value;
        });
    }

    const prescoutCheckbox = document.getElementById("include-prescout-checkbox");
    if (prescoutCheckbox) {
        prescoutCheckbox.addEventListener("change", () => {
            state.includePrescout = prescoutCheckbox.checked;
            updateTeamList();
        });
    }

    document.querySelectorAll(".graph-type-checkbox").forEach(checkbox => {
        checkbox.addEventListener("change", () => {
            if (checkbox.checked) {
                state.selectedGraphTypes.add(checkbox.value);
            } else {
                state.selectedGraphTypes.delete(checkbox.value);
            }
            updateGraphTypeAvailability();
        });
    });

    const selectAllTypesBtn = document.getElementById("select-all-graph-types");
    if (selectAllTypesBtn) {
        selectAllTypesBtn.addEventListener("click", () => {
            const isAverages = state.dataView === "averages" || (state.datasource === "epa" || state.datasource === "opr");
            const allTypes = GRAPH_TYPES.map(g => g.id).filter(id => !isAverages || (id !== "line" && id !== "area"));
            state.selectedGraphTypes = new Set(allTypes);
            document.querySelectorAll(".graph-type-checkbox").forEach(cb => {
                cb.checked = state.selectedGraphTypes.has(cb.value);
            });
            updateGraphTypeAvailability();
        });
    }

    const clearTypesBtn = document.getElementById("clear-graph-types");
    if (clearTypesBtn) {
        clearTypesBtn.addEventListener("click", () => {
            state.selectedGraphTypes.clear();
            document.querySelectorAll(".graph-type-checkbox").forEach(cb => cb.checked = false);
            updateGraphTypeAvailability();
        });
    }

    const generateBtn = document.getElementById("graph-generate");
    if (generateBtn) {
        generateBtn.addEventListener("click", () => generateGraphs());
    }

    function createGraphCard(titleText, chartElement, noticeText) {
        const card = document.createElement("div");
        card.className = "card";

        const header = document.createElement("div");
        header.className = "graphs-card-header";

        const title = document.createElement("h3");
        title.className = "graphs-card-title";
        title.textContent = titleText;
        header.appendChild(title);

        if (chartElement) {
            const actions = document.createElement("div");
            actions.className = "graphs-card-actions";

            const expandBtn = document.createElement("button");
            expandBtn.type = "button";
            expandBtn.className = "btn ghost btn-sm graph-fullscreen-btn";
            expandBtn.title = "View Fullscreen";
            expandBtn.innerHTML = SVG_ICONS.expand;
            expandBtn.addEventListener("click", () => {
                openGraphFullscreen(titleText, chartElement);
            });
            actions.appendChild(expandBtn);
            header.appendChild(actions);
        }

        card.appendChild(header);

        if (noticeText) {
            const notice = document.createElement("p");
            notice.className = "notice";
            notice.textContent = noticeText;
            card.appendChild(notice);
        }

        if (chartElement) {
            card.appendChild(chartElement);
        }

        return card;
    }

    function openGraphFullscreen(titleText, sourceChart) {
        let modal = document.getElementById("graph-fullscreen-modal");
        if (!modal) {
            modal = document.createElement("div");
            modal.id = "graph-fullscreen-modal";
            modal.className = "graphs-fullscreen-overlay hidden";
            modal.innerHTML = `
                <div class="graphs-fullscreen-backdrop"></div>
                <div class="graphs-fullscreen-dialog">
                    <div class="graphs-fullscreen-header">
                        <h2 id="graph-fullscreen-title" class="graphs-fullscreen-title"></h2>
                        <button type="button" class="btn ghost btn-sm graphs-fullscreen-close" aria-label="Close fullscreen view">
                            ${SVG_ICONS.close}
                        </button>
                    </div>
                    <div class="graphs-fullscreen-body">
                        <div id="graph-fullscreen-chart" class="plotly-chart"></div>
                    </div>
                </div>
            `;
            document.body.appendChild(modal);

            const closeBtn = modal.querySelector(".graphs-fullscreen-close");
            const backdrop = modal.querySelector(".graphs-fullscreen-backdrop");
            const closeModal = () => {
                modal.classList.add("hidden");
                document.body.classList.remove("modal-open");
                const chartContainer = document.getElementById("graph-fullscreen-chart");
                if (chartContainer && window.Plotly) {
                    window.Plotly.purge(chartContainer);
                }
            };
            closeBtn.addEventListener("click", closeModal);
            backdrop.addEventListener("click", closeModal);
        }

        const titleEl = document.getElementById("graph-fullscreen-title");
        if (titleEl) titleEl.textContent = titleText;

        const chartContainer = document.getElementById("graph-fullscreen-chart");
        if (!chartContainer || !sourceChart) return;

        const actualChart = sourceChart.classList?.contains("plotly-chart")
            ? sourceChart
            : (sourceChart.querySelector?.(".plotly-chart") || sourceChart);

        modal.classList.remove("hidden");
        document.body.classList.add("modal-open");

        requestAnimationFrame(() => {
            if (window.Plotly && actualChart.data && actualChart.layout) {
                const fullLayout = JSON.parse(JSON.stringify(actualChart.layout));
                delete fullLayout.height;
                delete fullLayout.width;
                fullLayout.autosize = true;
                const fullData = JSON.parse(JSON.stringify(actualChart.data));

                window.Plotly.newPlot(chartContainer, fullData, fullLayout, {
                    ...PLOTLY_CONFIG,
                    responsive: true
                });
            }
        });
    }

    function createPlotlyContainer(height = 320) {
        const container = document.createElement("div");
        container.className = "plotly-chart";
        container.dataset.height = String(height);
        return container;
    }

    function generateGraphs() {
        const output = document.getElementById("graphs-output");
        const empty = document.getElementById("graphs-empty");
        const loading = document.getElementById("graph-loading");
        if (!output) return;

        if (loading) loading.classList.remove("hidden");
        output.innerHTML = "";
        if (empty) empty.style.display = "none";

        const selectedTeams = Array.from(state.selectedTeams);
        if (!selectedTeams.length) {
            output.appendChild(createNotice(t('graphs.select_team_prompt', "Select at least one team to generate graphs.")));
            if (empty) empty.style.display = "block";
            if (loading) loading.classList.add("hidden");
            return;
        }

        const selectedGraphTypes = Array.from(state.selectedGraphTypes);
        if (!selectedGraphTypes.length) {
            output.appendChild(createNotice(t('graphs.select_type_prompt', "Select at least one graph type.")));
            if (empty) empty.style.display = "block";
            if (loading) loading.classList.add("hidden");
            return;
        }

        const metric = state.metricMap.get(state.metricId) || state.metrics[0];
        const metricLabel = metric ? metric.label : "Total Points";

        // Non-Scouted / External Datasource Rendering
        if (state.datasource && state.datasource !== "scouted") {
            selectedGraphTypes.forEach(graphType => {
                if ((graphType === "line" || graphType === "area") && state.dataView === "averages") {
                    const typeLabel = graphType === "area" ? "Area" : "Line";
                    const card = createGraphCard(`${getDatasourceLabel(state.datasource, state.isFtc)} - ${typeLabel}`, null, t(`graphs.${graphType}_not_supported_averages`, `${typeLabel} graphs are not supported for team averages as they require multiple data points across matches. Switch to Match-by-match view to use ${typeLabel} graphs.`));
                    output.appendChild(card);
                    return;
                }
                if (graphType === "box" || graphType === "violin" || graphType === "histogram") {
                    const card = createGraphCard(`${getDatasourceLabel(state.datasource, state.isFtc)} - ${graphType}`, null, "Distribution graphs are only supported for Scouted Data.");
                    output.appendChild(card);
                    return;
                }

                const chart = createPlotlyContainer(320);
                const viewTitle = state.dataView === "matches" ? "Match-by-match" : "Team averages";
                const cardTitle = (state.datasource === "exp" || state.datasource === "all")
                    ? `${getDatasourceLabel(state.datasource, state.isFtc)} (${metricLabel}) - ${graphType} (${viewTitle})`
                    : `${getDatasourceLabel(state.datasource, state.isFtc)} - ${graphType} (${viewTitle})`;
                const card = createGraphCard(cardTitle, chart);
                output.appendChild(card);

                renderNonScoutedGraph(graphType, chart, selectedTeams, state);
            });

            if (loading) loading.classList.add("hidden");
            return;
        }

        // Scouted Data Rendering
        const filteredEntries = entries.filter(e => {
            const tNum = Number(e.targetTeamNumber || e.teamNumber);
            return state.selectedTeams.has(tNum) && (state.includePrescout || !e.isPrescout);
        });

        if (!filteredEntries.length) {
            output.appendChild(createNotice(t('shared_viewer.no_matching_entries', "No entries found for the selected teams.")));
            if (empty) empty.style.display = "block";
            if (loading) loading.classList.add("hidden");
            return;
        }

        selectedGraphTypes.forEach(graphType => {
            if ((graphType === "line" || graphType === "area") && state.dataView === "averages") {
                const typeLabel = graphType === "area" ? "Area" : "Line";
                const cardTitle = `${metricLabel} - ${typeLabel}`;
                const card = createGraphCard(cardTitle, null, t(`graphs.${graphType}_not_supported_averages`, `${typeLabel} graphs are not supported for Team averages because they require multiple data points across matches. Switch to Match-by-match view to use ${typeLabel} graphs.`));
                output.appendChild(card);
                return;
            }

            const chart = createPlotlyContainer(320);
            const cardTitle = `${metricLabel} - ${graphType.toUpperCase()} (${state.dataView === 'averages' ? 'Team Averages' : 'Match-by-match'})`;
            const card = createGraphCard(cardTitle, chart);
            output.appendChild(card);

            renderScoutedGraph(graphType, chart, filteredEntries, metric, state);
        });

        if (loading) loading.classList.add("hidden");
    }

    function createNotice(msg) {
        const p = document.createElement("p");
        p.className = "notice";
        p.textContent = msg;
        return p;
    }

    function renderScoutedGraph(graphType, container, filteredEntries, metric, st) {
        const theme = resolveThemeTokens();

        if (metric && metric.kind === "category" && graphType !== "bar") {
            container.appendChild(createNotice(t('graphs.category_only_bar', "This metric only supports bar charts.")));
            return;
        }

        if (graphType === "bar") {
            if (metric && metric.kind === "category") {
                const counts = new Map();
                filteredEntries.forEach(e => {
                    const d = getEntryData(e);
                    const val = readLabel(d[metric.fieldId]);
                    if (val) counts.set(val, (counts.get(val) || 0) + 1);
                });
                const catSeries = Array.from(counts.entries()).map(([label, value]) => ({ label, value })).sort((a, b) => b.value - a.value);
                if (!catSeries.length) {
                    container.appendChild(createNotice(t('graphs.no_data_yet', "No data yet.")));
                    return;
                }
                const trace = {
                    type: "bar",
                    orientation: "h",
                    x: catSeries.map(c => c.value),
                    y: catSeries.map(c => c.label),
                    marker: { color: theme.accent },
                    text: catSeries.map(c => String(c.value)),
                    textposition: "auto",
                    hovertemplate: "%{y}: %{x}<extra></extra>"
                };
                const layout = {
                    height: Math.max(260, catSeries.length * 32 + 80),
                    margin: { l: 120, r: 24, t: 15, b: 40 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
                };
                plotly.react(container, [trace], layout, PLOTLY_CONFIG);
                return;
            }

            if (st.dataView === "averages") {
                const teamStats = calculateTeamAverages(filteredEntries, metric, st.config);
                sortTeamStats(teamStats, st.sort);
                const trace = {
                    type: "bar",
                    orientation: "h",
                    x: teamStats.map(item => item.value),
                    y: teamStats.map(item => `Team ${item.teamNumber}`),
                    marker: { color: theme.accent },
                    text: teamStats.map(item => Number(item.value).toFixed(1)),
                    textposition: "auto",
                    hovertemplate: "<b>%{y}</b><br>Average: %{x:.2f}<extra></extra>"
                };
                const layout = {
                    height: Math.max(260, teamStats.length * 32 + 80),
                    margin: { l: 110, r: 24, t: 15, b: 40 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
                };
                plotly.react(container, [trace], layout, PLOTLY_CONFIG);
                return;
            } else {
                const seriesList = calculateTeamSeries(filteredEntries, metric, st.config);
                renderPlotlyMultiSeries(container, "bar", seriesList, theme);
                return;
            }
        }

        if (graphType === "line" || graphType === "area") {
            const seriesList = calculateTeamSeries(filteredEntries, metric, st.config);
            renderPlotlyMultiSeries(container, graphType, seriesList, theme);
            return;
        }

        if (graphType === "scatter") {
            const seriesList = calculateTeamSeries(filteredEntries, metric, st.config);
            renderPlotlyMultiSeries(container, "scatter", seriesList, theme);
            return;
        }

        if (graphType === "box" || graphType === "violin") {
            const traces = calculateDistributionTraces(filteredEntries, metric, st.config, graphType);
            const layout = {
                height: 320,
                margin: { l: 55, r: 20, t: 30, b: 65 },
                paper_bgcolor: "rgba(0,0,0,0)",
                plot_bgcolor: "rgba(0,0,0,0)",
                font: { color: theme.text },
                xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true, tickangle: -45 },
                yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                legend: { orientation: "h", y: 1.1, x: 0.5, xanchor: "center" }
            };
            plotly.react(container, traces, layout, PLOTLY_CONFIG);
            return;
        }

        if (graphType === "histogram") {
            const values = filteredEntries.map(e => metricValue(e, metric, st.config)).filter(v => v !== null && v !== undefined);
            const trace = {
                type: "histogram",
                x: values,
                marker: { color: theme.accent },
                hovertemplate: "Range: %{x}<br>Count: %{y}<extra></extra>"
            };
            const layout = {
                height: 320,
                margin: { l: 55, r: 20, t: 30, b: 65 },
                paper_bgcolor: "rgba(0,0,0,0)",
                plot_bgcolor: "rgba(0,0,0,0)",
                font: { color: theme.text },
                xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
            };
            plotly.react(container, [trace], layout, PLOTLY_CONFIG);
        }
    }

    function renderNonScoutedGraph(graphType, container, selectedTeams, st) {
        const theme = resolveThemeTokens();

        if (st.dataView === "matches") {
            if (st.datasource === "exp") {
                const rawMatch13History = st.statsHistory?.match13History || [];
                if (!rawMatch13History.length) {
                    container.appendChild(createNotice(t('graphs.no_match13_data', "No Match 13 match data available for this event.")));
                    return;
                }
                const series = [];
                selectedTeams.forEach(teamNumber => {
                    const teamMatches = [];
                    rawMatch13History.forEach(matchObj => {
                        const teamData = extractTeamExpData(matchObj, teamNumber);
                        if (!teamData) return;
                        const matchKey = matchObj.key || matchObj.matchKey || matchObj.match_key || matchObj.match || "";
                        const label = formatMatchKeyToLabel(matchKey);
                        const sortWeight = getMatchSortWeightFromLabel(label);
                        const value = getTeamExpMetricValue(teamData, st.metricId);
                        teamMatches.push({ label, value, sortWeight });
                    });
                    teamMatches.sort((a, b) => a.sortWeight - b.sortWeight);
                    if (teamMatches.length > 0) {
                        series.push({
                            name: `Team ${teamNumber}`,
                            x: teamMatches.map(m => m.label),
                            y: teamMatches.map(m => m.value)
                        });
                    }
                });
                if (!series.length) {
                    container.appendChild(createNotice(t('graphs.no_match13_team_data', "No Match 13 match data found for the selected teams.")));
                    return;
                }
                renderPlotlyMultiSeries(container, graphType, series, theme);
                return;
            }

            if (st.datasource === "all") {
                const rawMatch13History = st.statsHistory?.match13History || [];
                const series = [];
                const metric = st.metricMap.get(st.metricId) || st.metrics[0];

                selectedTeams.forEach(teamNumber => {
                    const teamEntries = entries.filter(e => Number(e.targetTeamNumber || e.teamNumber) === Number(teamNumber));
                    const sortedScouted = teamEntries
                        .filter(e => metricValue(e, metric, st.config) !== null)
                        .sort((a, b) => {
                            const weightA = getMatchSortWeightFromEntry(a);
                            const weightB = getMatchSortWeightFromEntry(b);
                            if (weightA !== weightB) return weightA - weightB;
                            return (a.matchNumber || 0) - (b.matchNumber || 0);
                        });

                    if (sortedScouted.length > 0) {
                        series.push({
                            name: `Team ${teamNumber} (Scouted)`,
                            x: sortedScouted.map((e, idx) => formatMatchLabel(e, idx)),
                            y: sortedScouted.map(e => metricValue(e, metric, st.config))
                        });
                    }

                    if (st.effectiveUseExp && rawMatch13History.length > 0) {
                        const expMatches = [];
                        rawMatch13History.forEach(matchObj => {
                            const teamData = extractTeamExpData(matchObj, teamNumber);
                            if (!teamData) return;
                            const matchKey = matchObj.key || matchObj.matchKey || matchObj.match_key || matchObj.match || "";
                            const label = formatMatchKeyToLabel(matchKey);
                            const sortWeight = getMatchSortWeightFromLabel(label);
                            const value = getTeamExpMetricValue(teamData, st.metricId);
                            expMatches.push({ label, value, sortWeight });
                        });
                        expMatches.sort((a, b) => a.sortWeight - b.sortWeight);
                        if (expMatches.length > 0) {
                            series.push({
                                name: `Team ${teamNumber} (Match 13 EXP)`,
                                x: expMatches.map(m => m.label),
                                y: expMatches.map(m => m.value)
                            });
                        }
                    }
                });

                if (!series.length) {
                    container.appendChild(createNotice(t('graphs.no_match_data_teams', "No match data found for the selected teams.")));
                    return;
                }
                renderPlotlyMultiSeries(container, graphType, series, theme);
                return;
            }
        }

        // Averages View
        const data = selectedTeams.map(teamNumber => {
            const team = st.eventTeamsMap ? st.eventTeamsMap.get(teamNumber) : null;

            // OPR resolution
            let oprVal = (team && team.opr !== null && team.opr !== undefined) ? Number(team.opr) : null;
            if (oprVal === null || isNaN(oprVal) || oprVal === 0) {
                const oprs = st.statsHistory?.oprs || {};
                const fallbackOpr = oprs[teamNumber] ?? oprs[String(teamNumber)] ?? oprs[`frc${teamNumber}`] ?? oprs[`ftc${teamNumber}`];
                if (fallbackOpr !== null && fallbackOpr !== undefined) {
                    oprVal = Number(fallbackOpr);
                } else {
                    oprVal = 0;
                }
            }

            // EPA resolution
            let epaVal = (team && team.epa !== null && team.epa !== undefined) ? Number(team.epa) : 0;
            if (isNaN(epaVal) || epaVal === 0) {
                epaVal = Number(team?.statboticsEpa ?? team?.normEpa ?? 0);
            }

            // EXP resolution
            let expVal = (team && team.exp !== null && team.exp !== undefined)
                ? Number(team.exp)
                : ((team && team.match13Exp !== null && team.match13Exp !== undefined) ? Number(team.match13Exp) : 0);
            if (isNaN(expVal) || expVal === 0) {
                expVal = Number(team?.match13_exp ?? 0);
            }

            // Scouted points resolution
            let scoutedAvg = (team && team.averagePoints !== null && team.averagePoints !== undefined) ? Number(team.averagePoints) : null;
            if (scoutedAvg === null || isNaN(scoutedAvg)) {
                const metric = st.metricMap?.get(st.metricId) || st.metrics?.[0] || { id: "score_total", kind: "score", scope: "total" };
                const teamEntries = entries.filter(e => Number(e.targetTeamNumber || e.teamNumber) === Number(teamNumber) && (st.includePrescout || !e.isPrescout));
                if (teamEntries.length > 0) {
                    const vals = teamEntries.map(e => metricValue(e, metric, st.config)).filter(v => typeof v === 'number');
                    scoutedAvg = vals.length ? (vals.reduce((a, b) => a + b, 0) / vals.length) : 0;
                } else {
                    scoutedAvg = 0;
                }
            }

            return {
                teamNumber,
                label: `Team ${teamNumber}`,
                epa: epaVal,
                exp: expVal,
                opr: oprVal,
                scouted: scoutedAvg
            };
        });

        const sortField = st.datasource === "all"
            ? (st.effectiveUseEpa ? "epa" : (st.effectiveUseExp ? "exp" : (st.effectiveUseOpr ? "opr" : "scouted")))
            : st.datasource;

        data.sort((a, b) => {
            if (st.sort === "team_asc") return a.teamNumber - b.teamNumber;
            if (st.sort === "team_desc") return b.teamNumber - a.teamNumber;
            if (st.sort === "value_asc") return a[sortField] - b[sortField];
            return b[sortField] - a[sortField];
        });

        const labels = data.map(item => item.label);

        if (graphType === "bar") {
            if (st.datasource === "epa" && st.effectiveUseEpa) {
                const trace = {
                    type: "bar",
                    orientation: "h",
                    x: data.map(i => i.epa),
                    y: labels,
                    marker: { color: theme.accent },
                    text: data.map(i => Number(i.epa).toFixed(1)),
                    textposition: "auto",
                    hovertemplate: "<b>%{y}</b><br>Statbotics EPA: %{x:.2f}<extra></extra>"
                };
                const layout = {
                    height: Math.max(260, data.length * 32 + 80),
                    margin: { l: 110, r: 24, t: 15, b: 40 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
                };
                plotly.react(container, [trace], layout, PLOTLY_CONFIG);
                return;
            }
            if (st.datasource === "exp" && st.effectiveUseExp) {
                const trace = {
                    type: "bar",
                    orientation: "h",
                    x: data.map(i => i.exp),
                    y: labels,
                    marker: { color: theme.accent },
                    text: data.map(i => Number(i.exp).toFixed(1)),
                    textposition: "auto",
                    hovertemplate: "<b>%{y}</b><br>Match 13 EXP: %{x:.2f}<extra></extra>"
                };
                const layout = {
                    height: Math.max(260, data.length * 32 + 80),
                    margin: { l: 110, r: 24, t: 15, b: 40 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
                };
                plotly.react(container, [trace], layout, PLOTLY_CONFIG);
                return;
            }
            if (st.datasource === "opr" && st.effectiveUseOpr) {
                const trace = {
                    type: "bar",
                    orientation: "h",
                    x: data.map(i => i.opr),
                    y: labels,
                    marker: { color: theme.accent },
                    text: data.map(i => Number(i.opr).toFixed(1)),
                    textposition: "auto",
                    hovertemplate: `<b>%{y}</b><br>${st.isFtc ? 'FTC Scout' : 'TBA'} OPR: %{x:.2f}<extra></extra>`
                };
                const layout = {
                    height: Math.max(260, data.length * 32 + 80),
                    margin: { l: 110, r: 24, t: 15, b: 40 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true }
                };
                plotly.react(container, [trace], layout, PLOTLY_CONFIG);
                return;
            }
            if (st.datasource === "all") {
                const series = [];
                series.push({
                    type: "bar",
                    name: "Scouted Average",
                    x: labels,
                    y: data.map(i => i.scouted),
                    hovertemplate: "<b>%{x}</b><br>Scouted: %{y:.2f}<extra></extra>"
                });
                if (st.effectiveUseEpa) {
                    series.push({
                        type: "bar",
                        name: "Statbotics EPA",
                        x: labels,
                        y: data.map(i => i.epa),
                        hovertemplate: "<b>%{x}</b><br>Statbotics EPA: %{y:.2f}<extra></extra>"
                    });
                }
                if (st.effectiveUseExp) {
                    series.push({
                        type: "bar",
                        name: "Match 13 EXP",
                        x: labels,
                        y: data.map(i => i.exp),
                        hovertemplate: "<b>%{x}</b><br>Match 13 EXP: %{y:.2f}<extra></extra>"
                    });
                }
                if (st.effectiveUseOpr) {
                    series.push({
                        type: "bar",
                        name: st.isFtc ? "FTC Scout OPR" : "TBA OPR",
                        x: labels,
                        y: data.map(i => i.opr),
                        hovertemplate: `<b>%{x}</b><br>${st.isFtc ? 'FTC Scout' : 'TBA'} OPR: %{y:.2f}<extra></extra>`
                    });
                }
                const layout = {
                    height: 340,
                    margin: { l: 55, r: 20, t: 30, b: 65 },
                    paper_bgcolor: "rgba(0,0,0,0)",
                    plot_bgcolor: "rgba(0,0,0,0)",
                    font: { color: theme.text },
                    xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true, tickangle: -45 },
                    yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                    barmode: "group",
                    legend: { orientation: "h", y: 1.1, x: 0.5, xanchor: "center" }
                };
                plotly.react(container, series, layout, PLOTLY_CONFIG);
                return;
            }
        }

        if (graphType === "scatter") {
            const series = [];
            if (st.datasource === "scouted" || st.datasource === "all") {
                series.push({ type: "scatter", name: "Scouted Average", x: labels, y: data.map(i => i.scouted), mode: "markers", marker: { size: 8 } });
            }
            if ((st.datasource === "epa" || st.datasource === "all") && st.effectiveUseEpa) {
                series.push({ type: "scatter", name: "Statbotics EPA", x: labels, y: data.map(i => i.epa), mode: "markers", marker: { size: 8 } });
            }
            if ((st.datasource === "exp" || st.datasource === "all") && st.effectiveUseExp) {
                series.push({ type: "scatter", name: "Match 13 EXP", x: labels, y: data.map(i => i.exp), mode: "markers", marker: { size: 8 } });
            }
            if ((st.datasource === "opr" || st.datasource === "all") && st.effectiveUseOpr) {
                series.push({ type: "scatter", name: st.isFtc ? "FTC Scout OPR" : "TBA OPR", x: labels, y: data.map(i => i.opr), mode: "markers", marker: { size: 8 } });
            }
            const layout = {
                height: 340,
                margin: { l: 55, r: 20, t: 30, b: 65 },
                paper_bgcolor: "rgba(0,0,0,0)",
                plot_bgcolor: "rgba(0,0,0,0)",
                font: { color: theme.text },
                xaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true, tickangle: -45 },
                yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                legend: { orientation: "h", y: 1.1, x: 0.5, xanchor: "center" }
            };
            plotly.react(container, series, layout, PLOTLY_CONFIG);
        }
    }

    function renderPlotlyMultiSeries(container, graphType, seriesList, theme) {
        const sortedCategories = getSortedCategoriesFromSeries(seriesList);

        if (graphType === "bar") {
            const traces = seriesList.map(s => ({
                type: "bar",
                name: s.name,
                x: s.x,
                y: s.y,
                text: s.y.map(v => typeof v === "number" ? v.toFixed(1) : v),
                textposition: "auto",
                hovertemplate: "<b>" + s.name + "</b><br>Match: %{x}<br>Value: %{y:.2f}<extra></extra>"
            }));
            const layout = {
                height: 320,
                margin: { l: 55, r: 20, t: 30, b: 65 },
                paper_bgcolor: "rgba(0,0,0,0)",
                plot_bgcolor: "rgba(0,0,0,0)",
                font: { color: theme.text },
                xaxis: {
                    gridcolor: theme.grid,
                    zerolinecolor: theme.grid,
                    automargin: true,
                    tickangle: -45,
                    categoryorder: "array",
                    categoryarray: sortedCategories
                },
                yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
                barmode: "group",
                legend: { orientation: "h", y: 1.1, x: 0.5, xanchor: "center" }
            };
            plotly.react(container, traces, layout, PLOTLY_CONFIG);
            return;
        }

        const traces = seriesList.map(item => ({
            type: "scatter",
            x: item.x,
            y: item.y,
            name: item.name,
            mode: graphType === "scatter" ? "markers" : "lines+markers",
            fill: graphType === "area" ? "tozeroy" : undefined,
            line: { width: 2.2 },
            marker: { size: graphType === "scatter" ? 7 : 6 },
            hovertemplate: "<b>" + item.name + "</b><br>Match: %{x}<br>Value: %{y:.2f}<extra></extra>"
        }));
        const layout = {
            height: 320,
            margin: { l: 55, r: 20, t: 30, b: 65 },
            paper_bgcolor: "rgba(0,0,0,0)",
            plot_bgcolor: "rgba(0,0,0,0)",
            font: { color: theme.text },
            xaxis: {
                gridcolor: theme.grid,
                zerolinecolor: theme.grid,
                automargin: true,
                tickangle: -45,
                categoryorder: "array",
                categoryarray: sortedCategories
            },
            yaxis: { gridcolor: theme.grid, zerolinecolor: theme.grid, automargin: true },
            legend: { orientation: "h", y: 1.1, x: 0.5, xanchor: "center" }
        };
        plotly.react(container, traces, layout, PLOTLY_CONFIG);
    }

    function calculateTeamAverages(entriesList, metric, cfg) {
        const map = new Map();
        entriesList.forEach(entry => {
            const tNum = Number(entry.targetTeamNumber || entry.teamNumber);
            if (!tNum) return;
            if (!map.has(tNum)) map.set(tNum, { teamNumber: tNum, total: 0, count: 0 });
            const val = metricValue(entry, metric, cfg);
            if (val === null || val === undefined) return;
            const slot = map.get(tNum);
            slot.total += val;
            slot.count += 1;
        });
        return Array.from(map.values()).map(item => ({
            teamNumber: item.teamNumber,
            value: metric.kind === "count" ? item.count : (item.count ? item.total / item.count : 0)
        }));
    }

    function sortTeamStats(stats, sortMode) {
        if (sortMode === "team_asc") return stats.sort((a, b) => a.teamNumber - b.teamNumber);
        if (sortMode === "team_desc") return stats.sort((a, b) => b.teamNumber - a.teamNumber);
        if (sortMode === "value_asc") return stats.sort((a, b) => a.value - b.value);
        return stats.sort((a, b) => b.value - a.value);
    }

    function calculateTeamSeries(entriesList, metric, cfg) {
        const groups = new Map();
        entriesList.forEach(entry => {
            const tNum = Number(entry.targetTeamNumber || entry.teamNumber);
            if (!tNum) return;
            if (!groups.has(tNum)) groups.set(tNum, []);
            groups.get(tNum).push(entry);
        });

        const series = [];
        groups.forEach((teamEntries, tNum) => {
            const sorted = teamEntries
                .filter(e => metricValue(e, metric, cfg) !== null)
                .sort((a, b) => {
                    const weightA = getMatchSortWeightFromEntry(a);
                    const weightB = getMatchSortWeightFromEntry(b);
                    if (weightA !== weightB) return weightA - weightB;
                    return (a.matchNumber || 0) - (b.matchNumber || 0);
                });

            series.push({
                name: `Team ${tNum}`,
                teamNumber: tNum,
                x: sorted.map((e, idx) => formatMatchLabel(e, idx)),
                y: sorted.map(e => metricValue(e, metric, cfg))
            });
        });

        return series;
    }

    function calculateDistributionTraces(entriesList, metric, cfg, graphType) {
        const valuesByTeam = new Map();
        entriesList.forEach(entry => {
            const tNum = Number(entry.targetTeamNumber || entry.teamNumber);
            if (!tNum) return;
            const val = metricValue(entry, metric, cfg);
            if (val === null || val === undefined) return;
            if (!valuesByTeam.has(tNum)) valuesByTeam.set(tNum, []);
            valuesByTeam.get(tNum).push(val);
        });

        const traces = [];
        valuesByTeam.forEach((values, tNum) => {
            traces.push({
                type: graphType,
                name: `Team ${tNum}`,
                y: values,
                boxpoints: graphType === "box" ? "outliers" : undefined,
                meanline: { visible: true }
            });
        });
        return traces;
    }

    renderSummary();
    updateTeamList();
    toggleFieldsForDatasource();
    updateGraphTypeAvailability();
    generateGraphs();

    window._currentSharedRerender = generateGraphs;
}

// ==========================================================================
// 2. FULL ALL SCOUTING DATA IMPLEMENTATION (Matching all-data.html & all-data.js)
// ==========================================================================

function renderDataTableView(target, config, snapshot, live) {
    const dataSourceObj = snapshot || live || {};
    let rawRows = [];
    if (Array.isArray(dataSourceObj.rows)) {
        rawRows = dataSourceObj.rows;
    } else if (Array.isArray(dataSourceObj.allEntries)) {
        rawRows = dataSourceObj.allEntries;
    } else if (Array.isArray(dataSourceObj.entries)) {
        const m = dataSourceObj.entries || [];
        const p = Array.isArray(dataSourceObj.pitEntries) ? dataSourceObj.pitEntries : [];
        const q = Array.isArray(dataSourceObj.qualEntries) ? dataSourceObj.qualEntries : [];
        if (p.length > 0 || q.length > 0) {
            rawRows = [...m, ...p, ...q];
        } else {
            rawRows = m;
        }
    }

    const configs = {
        match: dataSourceObj.config || config.config || { fields: [] },
        pit: dataSourceObj.pitConfig || config.pitConfig || { fields: [] },
        qualitative: dataSourceObj.qualConfig || config.qualConfig || { fields: [] }
    };

    const state = {
        entries: rawRows.map((e, idx) => ({
            id: e.id || `entry-${idx}`,
            originalId: e.originalId || e.id || idx,
            type: e.type || (e.isPit ? "Pit" : (e.isQualitative ? "Qualitative" : "Match")),
            ownerTeamNumber: e.ownerTeamNumber,
            targetTeamNumber: e.targetTeamNumber || e.teamNumber,
            eventKey: e.eventKey || "",
            isPrescout: e.isPrescout || false,
            matchNumber: e.matchNumber,
            matchKey: e.matchKey,
            scoutName: e.scoutName || e.username || "Scout",
            username: e.username || e.scoutName || "Scout",
            createdAt: e.createdAt,
            data: getEntryData(e),
            hasDiscrepancy: e.hasDiscrepancy || false
        })),
        config: configs.match,
        configs: configs,
        selectedEntryId: null,
        filters: {
            teamQuery: "",
            type: "all",
            matchNumber: "",
            sortBy: "match-type"
        }
    };

    target.innerHTML = `
        <div class="all-data-page">
            <div class="card">
                <h1 data-i18n="all-data.all_scouting_data">${t('all-data.all_scouting_data', 'All Scouting Data')}</h1>
                <p class="notice" data-i18n="all-data.browse_search_and_inspect_pit_">${t('all-data.browse_search_and_inspect_pit_', 'Browse, search, and inspect pit, match, and qualitative scouting entries in one unified place.')}</p>

                <div class="metrics mt-20">
                    <div>
                        <h4 data-i18n="all-data.total_entries">${t('all-data.total_entries', 'Total Entries')}</h4>
                        <div id="all-entry-count" class="metric-value">0</div>
                    </div>
                    <div>
                        <h4 data-i18n="all-data.pit_entries">${t('all-data.pit_entries', 'Pit Entries')}</h4>
                        <div id="pit-entry-count" class="metric-value">0</div>
                    </div>
                    <div>
                        <h4 data-i18n="all-data.match_entries">${t('all-data.match_entries', 'Match Entries')}</h4>
                        <div id="match-entry-count" class="metric-value">0</div>
                    </div>
                    <div>
                        <h4 data-i18n="all-data.qualitative_entries">${t('all-data.qualitative_entries', 'Qualitative Entries')}</h4>
                        <div id="qual-entry-count" class="metric-value">0</div>
                    </div>
                </div>
            </div>

            <div class="card mt-20">
                <div class="split">
                    <div class="field">
                        <label for="shared-team-search" data-i18n="all-data.search_teams">${t('all-data.search_teams', 'Search teams')}</label>
                        <input id="shared-team-search" type="search" placeholder="${t('all-data.placeholder_team_number_or_name', 'Team number or name')}" />
                    </div>
                    <div class="field">
                        <label for="shared-type-filter" data-i18n="all-data.entry_type">${t('all-data.entry_type', 'Entry Type')}</label>
                        <select id="shared-type-filter" class="select-wide">
                            <option value="all">${t('all-data.all_types', 'All Types')}</option>
                            <option value="pit">${t('all-data.pit_scouting', 'Pit Scouting')}</option>
                            <option value="match">${t('all-data.match_scouting', 'Match Scouting')}</option>
                            <option value="qualitative">${t('all-data.qualitative_scouting', 'Qualitative Scouting')}</option>
                        </select>
                    </div>
                    <div class="field">
                        <label for="shared-match-filter" data-i18n="matches.match_number">${t('matches.match_number', 'Match Number')}</label>
                        <input id="shared-match-filter" type="number" min="1" placeholder="e.g. 12" />
                    </div>
                    <div class="field">
                        <label for="shared-sort-select" data-i18n="all-data.sort_by">${t('all-data.sort_by', 'Sort By')}</label>
                        <select id="shared-sort-select" class="select-wide">
                            <option value="match-type">${t('all-data.match_type', 'Match & Type')}</option>
                            <option value="newest">${t('all-data.newest_first', 'Newest First')}</option>
                            <option value="oldest">${t('all-data.oldest_first', 'Oldest First')}</option>
                            <option value="team">${t('pitdata.csv.team_number', 'Team Number')}</option>
                        </select>
                    </div>
                </div>
                <div class="row gap-12 wrap mt-18">
                    <button id="shared-reset-filters" class="btn ghost" type="button">${t('all-data.reset_filters', 'Reset Filters')}</button>
                    <span id="shared-filter-status" class="badge">${t('all-data.showing_all_entries', 'Showing all entries')}</span>
                </div>
            </div>

            <div class="split mt-20">
                <div class="card">
                    <div class="row justify-between align-center wrap gap-12">
                        <h2 class="section-title">${t('all-data.unified_entries', 'Unified Entries')}</h2>
                        <span id="shared-table-count" class="badge">0 entries</span>
                    </div>
                    <div class="table-scroll mt-12">
                        <table class="table" id="shared-data-table">
                            <thead>
                                <tr>
                                    <th>${t('all-data.team', 'Team')}</th>
                                    <th>${t('matches.match', 'Match')}</th>
                                    <th>${t('settings.type', 'Type')}</th>
                                    <th>${t('pitdata.updated', 'Updated')}</th>
                                </tr>
                            </thead>
                            <tbody></tbody>
                        </table>
                    </div>
                </div>

                <div class="card">
                    <div class="row justify-between align-center wrap gap-12">
                        <h2 id="shared-detail-title" class="section-title">${t('all-data.select_an_entry', 'Select an entry')}</h2>
                        <span id="shared-detail-status" class="badge">${t('all-data.no_entry_selected', 'No entry selected')}</span>
                    </div>
                    <div id="shared-entry-detail" class="pit-detail mt-12">
                        <p class="notice">${t('all-data.choose_an_entry_from_the_list_', 'Choose an entry from the list to inspect its configuration-driven answers and notes.')}</p>
                    </div>
                </div>
            </div>
        </div>
    `;

    function updateMetrics() {
        const total = state.entries.length;
        const pit = state.entries.filter(e => String(e.type).toLowerCase().includes("pit")).length;
        const qual = state.entries.filter(e => String(e.type).toLowerCase().includes("qual")).length;
        const match = total - pit - qual;

        document.getElementById("all-entry-count").textContent = total;
        document.getElementById("pit-entry-count").textContent = pit;
        document.getElementById("match-entry-count").textContent = Math.max(0, match);
        document.getElementById("qual-entry-count").textContent = qual;
    }

    function filterAndSortEntries() {
        let list = [...state.entries];

        if (state.filters.teamQuery) {
            const q = state.filters.teamQuery.toLowerCase();
            list = list.filter(e => String(e.targetTeamNumber).includes(q));
        }

        if (state.filters.type && state.filters.type !== "all") {
            list = list.filter(e => String(e.type).toLowerCase().includes(state.filters.type));
        }

        if (state.filters.matchNumber) {
            const mNum = Number(state.filters.matchNumber);
            list = list.filter(e => Number(e.matchNumber) === mNum);
        }

        if (state.filters.sortBy === "newest") {
            list.sort((a, b) => new Date(b.createdAt || 0) - new Date(a.createdAt || 0));
        } else if (state.filters.sortBy === "oldest") {
            list.sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0));
        } else if (state.filters.sortBy === "team") {
            list.sort((a, b) => (Number(a.targetTeamNumber) || 0) - (Number(b.targetTeamNumber) || 0));
        } else {
            list.sort((a, b) => {
                const mA = Number(a.matchNumber) || 0;
                const mB = Number(b.matchNumber) || 0;
                if (mA !== mB) return mA - mB;
                return (Number(a.targetTeamNumber) || 0) - (Number(b.targetTeamNumber) || 0);
            });
        }

        return list;
    }

    function renderTable() {
        const tbody = document.querySelector("#shared-data-table tbody");
        const countBadge = document.getElementById("shared-table-count");
        if (!tbody) return;
        tbody.innerHTML = "";

        const visible = filterAndSortEntries();
        if (countBadge) countBadge.textContent = `${visible.length} entries`;

        if (!visible.length) {
            tbody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--muted); padding: 30px;">No matching scouting entries found.</td></tr>`;
            return;
        }

        visible.forEach(entry => {
            const tr = document.createElement("tr");
            tr.style.cursor = "pointer";
            if (state.selectedEntryId === entry.id) {
                tr.classList.add("selected-row");
                tr.style.background = "rgba(99, 102, 241, 0.15)";
            }

            const updatedStr = entry.createdAt ? new Date(entry.createdAt).toLocaleDateString() : "--";
            tr.innerHTML = `
                <td><strong>#${entry.targetTeamNumber || "--"}</strong></td>
                <td>${entry.matchNumber ? `Match ${entry.matchNumber}` : (entry.type === "Pit" ? "Pit Setup" : "--")}</td>
                <td><span class="badge ${entry.type === "Match" ? "badge-primary" : "badge-secondary"}">${entry.type}</span></td>
                <td><small style="color: var(--muted);">${updatedStr}</small></td>
            `;

            tr.onclick = () => {
                state.selectedEntryId = entry.id;
                renderTable();
                renderDetail(entry);
            };

            tbody.appendChild(tr);
        });

        if (state.selectedEntryId) {
            const found = state.entries.find(e => e.id === state.selectedEntryId);
            if (found) renderDetail(found);
        }
    }

    function renderDetail(entry) {
        const title = document.getElementById("shared-detail-title");
        const status = document.getElementById("shared-detail-status");
        const container = document.getElementById("shared-entry-detail");
        if (!container) return;

        if (title) title.textContent = `Team ${entry.targetTeamNumber} ${entry.matchNumber ? `• Match ${entry.matchNumber}` : ""}`;
        if (status) status.textContent = entry.type;

        const data = entry.data || {};
        const configKey = (entry.type || "match").toLowerCase();
        const activeConfig = state.configs?.[configKey] || state.config;
        const fields = activeConfig?.fields || [];

        let fieldsHtml = "";
        if (fields.length > 0) {
            fieldsHtml = fields.map(f => {
                if (f.type === "section" || RESERVED_FIELDS.has(f.id)) return "";
                const val = data[f.id];
                const displayVal = val !== undefined && val !== null ? (typeof val === "boolean" ? (val ? "Yes" : "No") : val) : "--";
                return `
                    <div style="padding: 10px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between;">
                        <span style="color: var(--muted); font-size: 0.9rem;">${f.label || f.id}</span>
                        <strong style="color: var(--ink); font-size: 0.95rem;">${displayVal}</strong>
                    </div>
                `;
            }).join("");
        } else {
            fieldsHtml = Object.entries(data).map(([k, v]) => `
                <div style="padding: 10px; border-bottom: 1px solid var(--border); display: flex; justify-content: space-between;">
                    <span style="color: var(--muted); font-size: 0.9rem;">${k}</span>
                    <strong style="color: var(--ink); font-size: 0.95rem;">${String(v)}</strong>
                </div>
            `).join("");
        }

        const recordedByName = entry.scoutName || entry.username || "Scout";
        const eventDisplay = entry.isPrescout ? "Prescouting Data" : (entry.eventKey || "Shared");

        container.innerHTML = `
            <div style="display: flex; flex-direction: column; gap: 4px;">
                <div style="padding: 10px 0 14px 0; border-bottom: 2px solid var(--border); margin-bottom: 8px;">
                    <div style="font-size: 0.85rem; color: var(--muted);">Recorded by: <strong style="color: var(--ink);">${recordedByName}</strong></div>
                    <div style="font-size: 0.85rem; color: var(--muted);">Event: <strong style="color: var(--ink);">${eventDisplay}</strong></div>
                </div>
                ${fieldsHtml || '<p class="notice">No field data captured for this entry.</p>'}
            </div>
        `;
    }

    // Wire Filters
    const searchInp = document.getElementById("shared-team-search");
    if (searchInp) searchInp.oninput = () => { state.filters.teamQuery = searchInp.value; renderTable(); };

    const typeSel = document.getElementById("shared-type-filter");
    if (typeSel) typeSel.onchange = () => { state.filters.type = typeSel.value; renderTable(); };

    const matchInp = document.getElementById("shared-match-filter");
    if (matchInp) matchInp.oninput = () => { state.filters.matchNumber = matchInp.value; renderTable(); };

    const sortSel = document.getElementById("shared-sort-select");
    if (sortSel) sortSel.onchange = () => { state.filters.sortBy = sortSel.value; renderTable(); };

    const resetBtn = document.getElementById("shared-reset-filters");
    if (resetBtn) resetBtn.onclick = () => {
        state.filters.teamQuery = "";
        state.filters.type = "all";
        state.filters.matchNumber = "";
        state.filters.sortBy = "match-type";
        if (searchInp) searchInp.value = "";
        if (typeSel) typeSel.value = "all";
        if (matchInp) matchInp.value = "";
        if (sortSel) sortSel.value = "match-type";
        renderTable();
    };

    updateMetrics();
    renderTable();
}

// ==========================================================================
// 3. FULL MATCH PREDICTOR IMPLEMENTATION (Matching predictor.html & predictor.js)
// ==========================================================================

function renderPredictorView(target, config, snapshot, live) {
    const rawData = snapshot || live || config || {};
    const pred = rawData.prediction || rawData;
    const settings = rawData.settings || (live && live.settings) || {};

    const isFtc = (window.Obsidianscout && typeof Obsidianscout.getProgram === 'function')
        ? Obsidianscout.getProgram() === "FTC"
        : (settings.program === "FTC");

    const red = pred.redAlliance || {
        teams: (pred.redTeams || []).map(t => typeof t === 'object' ? t : { teamNumber: t, nickname: `Team ${t}`, averageScoutedScore: Math.round((pred.redPredictedScore || 100) / 3), scoutedMatchesCount: 0 }),
        totalScoutedScore: Number(pred.redPredictedScore || 0),
        totalEpa: Number(pred.redEpa || 0),
        totalExp: Number(pred.redExp || 0),
        totalOpr: Number(pred.redOpr || 0)
    };

    const blue = pred.blueAlliance || {
        teams: (pred.blueTeams || []).map(t => typeof t === 'object' ? t : { teamNumber: t, nickname: `Team ${t}`, averageScoutedScore: Math.round((pred.bluePredictedScore || 100) / 3), scoutedMatchesCount: 0 }),
        totalScoutedScore: Number(pred.bluePredictedScore || 0),
        totalEpa: Number(pred.blueEpa || 0),
        totalExp: Number(pred.blueExp || 0),
        totalOpr: Number(pred.blueOpr || 0)
    };

    // Ensure numeric values
    const redScouted = Number(red.totalScoutedScore || pred.redPredictedScore || 0);
    const blueScouted = Number(blue.totalScoutedScore || pred.bluePredictedScore || 0);
    const redEpa = Number(red.totalEpa || 0);
    const blueEpa = Number(blue.totalEpa || 0);
    const redExp = Number(red.totalExp || 0);
    const blueExp = Number(blue.totalExp || 0);
    const redOpr = Number(red.totalOpr || 0);
    const blueOpr = Number(blue.totalOpr || 0);

    const effectiveUseEpa = !isFtc && Boolean(settings.useStatboticsEpa || (redEpa > 0 || blueEpa > 0) || pred.epaPrediction);
    const effectiveUseExp = !isFtc && Boolean(settings.useMatch13Exp || (redExp > 0 || blueExp > 0) || pred.expPrediction);
    const effectiveUseOpr = Boolean(settings.useTbaOpr || (redOpr > 0 || blueOpr > 0) || pred.oprPrediction);

    let selectedSource = config.datasource || (snapshot && snapshot.datasource) || 'all';
    const matchLabel = pred.label || pred.matchKey || config.label || config.matchKey || 'Match Prediction';

    target.innerHTML = `
        <div class="card" style="margin-bottom: 20px;">
            <div class="row justify-between align-center wrap gap-12">
                <div>
                    <h1 style="margin: 0 0 4px 0;">${matchLabel}</h1>
                    <p class="notice" style="margin: 0;">${t('predictor.notice', 'Compare alliances and predict match outcomes using scouted data, EPA, EXP, and OPR.')}</p>
                </div>
                <div class="field" style="margin: 0; min-width: 200px;">
                    <label for="shared-pred-datasource" style="margin-bottom: 4px; font-size: 0.8rem; text-transform: uppercase; color: var(--muted);">${t('predictor.data_source', 'Data Source')}</label>
                    <select id="shared-pred-datasource" class="select-wide">
                        <option value="all" ${selectedSource === 'all' ? 'selected' : ''}>${t('predictor.all_sources', 'All Sources')}</option>
                        <option value="scouted" ${selectedSource === 'scouted' ? 'selected' : ''}>${t('predictor.scouted_data', 'Scouted Data')}</option>
                        ${effectiveUseEpa ? `<option value="epa" ${selectedSource === 'epa' ? 'selected' : ''}>${t('predictor.statbotics_epa', 'Statbotics EPA')}</option>` : ''}
                        ${effectiveUseExp ? `<option value="exp" ${selectedSource === 'exp' ? 'selected' : ''}>${t('alliance-selection.match13_exp', 'Match 13 EXP')}</option>` : ''}
                        ${effectiveUseOpr ? `<option value="opr" ${selectedSource === 'opr' ? 'selected' : ''}>${isFtc ? t('predictor.ftcscout_opr', 'FTC Scout OPR') : t('predictor.tba_opr', 'TBA OPR')}</option>` : ''}
                    </select>
                </div>
            </div>
        </div>

        <div class="predictor-container" style="display: grid; grid-template-columns: 2fr 1fr; gap: 20px;">
            <div class="column gap-20" style="display: flex; flex-direction: column; gap: 20px;">
                <!-- Alliance Comparison -->
                <div class="card">
                    <h2>${t('predictor.alliance_comparison', 'Alliance Comparison')}</h2>
                    <p class="notice">${t('predictor.visual_representation_of_relat', 'Visual representation of relative strengths based on different performance categories.')}</p>
                    
                    <div class="metric-comparison-box mt-16">
                        <!-- Scouted Score Bar -->
                        <div class="comp-bar-container mb-16" id="shared-scouted-comp">
                            <div class="comp-bar-label" style="display: flex; justify-content: space-between; font-weight: 600; margin-bottom: 6px; font-size: 0.9rem;">
                                <span style="color: #f87171;" id="lbl-pred-scouted-red">Red: ${redScouted.toFixed(1)} pts</span>
                                <span style="font-weight: 700;">${t('predictor.scouted_score', 'Scouted Score')}</span>
                                <span style="color: #60a5fa;" id="lbl-pred-scouted-blue">Blue: ${blueScouted.toFixed(1)} pts</span>
                            </div>
                            <div class="comp-bar-track" style="height: 12px; border-radius: 6px; background: var(--surface-2, rgba(0,0,0,0.1)); border: 1px solid var(--border); display: flex; overflow: hidden;">
                                <div id="bar-pred-scouted-red" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #f87171, #ef4444); transition: width 0.6s ease;"></div>
                                <div id="bar-pred-scouted-blue" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #3b82f6, #60a5fa); transition: width 0.6s ease;"></div>
                            </div>
                        </div>

                        <!-- EPA Bar -->
                        <div class="comp-bar-container mb-16 ${effectiveUseEpa ? '' : 'hidden'}" id="shared-epa-comp">
                            <div class="comp-bar-label" style="display: flex; justify-content: space-between; font-weight: 600; margin-bottom: 6px; font-size: 0.9rem;">
                                <span style="color: #f87171;" id="lbl-pred-epa-red">Red: ${redEpa.toFixed(1)}</span>
                                <span style="font-weight: 700;">Statbotics EPA</span>
                                <span style="color: #60a5fa;" id="lbl-pred-epa-blue">Blue: ${blueEpa.toFixed(1)}</span>
                            </div>
                            <div class="comp-bar-track" style="height: 12px; border-radius: 6px; background: var(--surface-2, rgba(0,0,0,0.1)); border: 1px solid var(--border); display: flex; overflow: hidden;">
                                <div id="bar-pred-epa-red" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #f87171, #ef4444); transition: width 0.6s ease;"></div>
                                <div id="bar-pred-epa-blue" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #3b82f6, #60a5fa); transition: width 0.6s ease;"></div>
                            </div>
                        </div>

                        <!-- EXP Bar -->
                        <div class="comp-bar-container mb-16 ${effectiveUseExp ? '' : 'hidden'}" id="shared-exp-comp">
                            <div class="comp-bar-label" style="display: flex; justify-content: space-between; font-weight: 600; margin-bottom: 6px; font-size: 0.9rem;">
                                <span style="color: #f87171;" id="lbl-pred-exp-red">Red: ${redExp.toFixed(1)}</span>
                                <span style="font-weight: 700;">Match 13 EXP</span>
                                <span style="color: #60a5fa;" id="lbl-pred-exp-blue">Blue: ${blueExp.toFixed(1)}</span>
                            </div>
                            <div class="comp-bar-track" style="height: 12px; border-radius: 6px; background: var(--surface-2, rgba(0,0,0,0.1)); border: 1px solid var(--border); display: flex; overflow: hidden;">
                                <div id="bar-pred-exp-red" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #f87171, #ef4444); transition: width 0.6s ease;"></div>
                                <div id="bar-pred-exp-blue" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #3b82f6, #60a5fa); transition: width 0.6s ease;"></div>
                            </div>
                        </div>

                        <!-- OPR Bar -->
                        <div class="comp-bar-container mb-16 ${effectiveUseOpr ? '' : 'hidden'}" id="shared-opr-comp">
                            <div class="comp-bar-label" style="display: flex; justify-content: space-between; font-weight: 600; margin-bottom: 6px; font-size: 0.9rem;">
                                <span style="color: #f87171;" id="lbl-pred-opr-red">Red: ${redOpr.toFixed(1)}</span>
                                <span style="font-weight: 700;">${isFtc ? 'FTC Scout OPR' : 'Event OPR'}</span>
                                <span style="color: #60a5fa;" id="lbl-pred-opr-blue">Blue: ${blueOpr.toFixed(1)}</span>
                            </div>
                            <div class="comp-bar-track" style="height: 12px; border-radius: 6px; background: var(--surface-2, rgba(0,0,0,0.1)); border: 1px solid var(--border); display: flex; overflow: hidden;">
                                <div id="bar-pred-opr-red" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #f87171, #ef4444); transition: width 0.6s ease;"></div>
                                <div id="bar-pred-opr-blue" class="comp-bar-fill" style="height: 100%; background: linear-gradient(90deg, #3b82f6, #60a5fa); transition: width 0.6s ease;"></div>
                            </div>
                        </div>
                    </div>
                </div>

                <!-- Alliance Breakdown Cards -->
                <div class="alliance-cards" style="display: grid; grid-template-columns: 1fr 1fr; gap: 20px;">
                    <!-- Red Alliance -->
                    <div class="alliance-card red-alliance" style="border-radius: 12px; padding: 20px; background: var(--surface); border: 1px solid var(--border); border-top: 4px solid #ef4444; box-shadow: 0 8px 32px 0 rgba(239, 68, 68, 0.1);">
                        <div class="alliance-title" style="font-size: 1.3rem; font-weight: 700; color: #ef4444; display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
                            <span>${t('alliance-selection.red_alliance', 'Red Alliance')}</span>
                            <span class="pill-tag pill-red" id="lbl-red-total-badge" style="font-size: 0.8rem; padding: 2px 8px; border-radius: 4px; font-weight: 700; background: rgba(239, 68, 68, 0.2); color: #ef4444;">${redScouted.toFixed(1)} pts</span>
                        </div>
                        <div class="team-list" id="shared-red-team-list"></div>
                    </div>

                    <!-- Blue Alliance -->
                    <div class="alliance-card blue-alliance" style="border-radius: 12px; padding: 20px; background: var(--surface); border: 1px solid var(--border); border-top: 4px solid #3b82f6; box-shadow: 0 8px 32px 0 rgba(59, 130, 246, 0.1);">
                        <div class="alliance-title" style="font-size: 1.3rem; font-weight: 700; color: #3b82f6; display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px;">
                            <span>${t('alliance-selection.blue_alliance', 'Blue Alliance')}</span>
                            <span class="pill-tag pill-blue" id="lbl-blue-total-badge" style="font-size: 0.8rem; padding: 2px 8px; border-radius: 4px; font-weight: 700; background: rgba(59, 130, 246, 0.2); color: #3b82f6;">${blueScouted.toFixed(1)} pts</span>
                        </div>
                        <div class="team-list" id="shared-blue-team-list"></div>
                    </div>
                </div>
            </div>

            <!-- Winner Spotlights -->
            <div class="column gap-20" style="display: flex; flex-direction: column; gap: 16px;">
                <div class="winner-spotlight card" id="spotlight-scouted-card" style="padding: 20px; text-align: center;">
                    <div class="winner-spotlight-title" style="font-size: 0.8rem; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin-bottom: 6px;">${t('predictor.scouted_prediction', 'Scouted Prediction')}</div>
                    <div class="winner-name" id="spotlight-scouted-winner" style="font-size: 1.6rem; font-weight: 800; margin-bottom: 6px;">-</div>
                    <div class="winner-subtext" id="spotlight-scouted-subtext" style="font-size: 0.85rem; color: var(--muted);">-</div>
                </div>

                <div class="winner-spotlight card ${effectiveUseEpa ? '' : 'hidden'}" id="spotlight-epa-card" style="padding: 20px; text-align: center;">
                    <div class="winner-spotlight-title" style="font-size: 0.8rem; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin-bottom: 6px;">${t('predictor.epa_prediction', 'EPA Prediction')}</div>
                    <div class="winner-name" id="spotlight-epa-winner" style="font-size: 1.6rem; font-weight: 800; margin-bottom: 6px;">-</div>
                    <div class="winner-subtext" id="spotlight-epa-subtext" style="font-size: 0.85rem; color: var(--muted);">-</div>
                </div>

                <div class="winner-spotlight card ${effectiveUseExp ? '' : 'hidden'}" id="spotlight-exp-card" style="padding: 20px; text-align: center;">
                    <div class="winner-spotlight-title" style="font-size: 0.8rem; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin-bottom: 6px;">${t('predictor.exp_prediction', 'EXP Prediction')}</div>
                    <div class="winner-name" id="spotlight-exp-winner" style="font-size: 1.6rem; font-weight: 800; margin-bottom: 6px;">-</div>
                    <div class="winner-subtext" id="spotlight-exp-subtext" style="font-size: 0.85rem; color: var(--muted);">-</div>
                </div>

                <div class="winner-spotlight card ${effectiveUseOpr ? '' : 'hidden'}" id="spotlight-opr-card" style="padding: 20px; text-align: center;">
                    <div class="winner-spotlight-title" style="font-size: 0.8rem; text-transform: uppercase; letter-spacing: 1px; color: var(--muted); margin-bottom: 6px;">${isFtc ? 'FTC Scout OPR Prediction' : 'OPR Prediction'}</div>
                    <div class="winner-name" id="spotlight-opr-winner" style="font-size: 1.6rem; font-weight: 800; margin-bottom: 6px;">-</div>
                    <div class="winner-subtext" id="spotlight-opr-subtext" style="font-size: 0.85rem; color: var(--muted);">-</div>
                </div>
            </div>
        </div>
    `;

    function updateBar(redBarId, blueBarId, redVal, blueVal) {
        const total = redVal + blueVal;
        const redBar = document.getElementById(redBarId);
        const blueBar = document.getElementById(blueBarId);
        if (!redBar || !blueBar) return;

        if (total === 0) {
            redBar.style.width = "50%";
            blueBar.style.width = "50%";
        } else {
            const redPercent = (redVal / total) * 100;
            const bluePercent = (blueVal / total) * 100;
            redBar.style.width = `${redPercent}%`;
            blueBar.style.width = `${bluePercent}%`;
        }
    }

    function renderSpotlight(winnerId, subtextId, redVal, blueVal, label) {
        const nameEl = document.getElementById(winnerId);
        const subEl = document.getElementById(subtextId);
        if (!nameEl || !subEl) return;

        nameEl.className = "winner-name";
        if (redVal === 0 && blueVal === 0) {
            nameEl.textContent = t('predictor.no_data', 'No Data');
            nameEl.style.color = 'var(--muted)';
            subEl.textContent = `Insufficient ${label} entries for predictions.`;
            return;
        }

        const diff = Math.abs(redVal - blueVal);
        if (redVal > blueVal) {
            nameEl.textContent = t('predictor.red_alliance', 'Red Alliance');
            nameEl.style.color = '#ef4444';
            subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
        } else if (blueVal > redVal) {
            nameEl.textContent = t('predictor.blue_alliance', 'Blue Alliance');
            nameEl.style.color = '#3b82f6';
            subEl.textContent = `Predicted to win by ${diff.toFixed(1)} ${label.toLowerCase() === 'scouted' ? 'pts' : 'units'}`;
        } else {
            nameEl.textContent = t('predictor.dead_heat', 'Dead Heat / Draw');
            nameEl.style.color = 'var(--ink)';
            subEl.textContent = `Alliances have identical combined ${label} values.`;
        }
    }

    function renderTeams(listId, teamList) {
        const container = document.getElementById(listId);
        if (!container) return;
        container.innerHTML = "";

        const teams = Array.isArray(teamList) ? teamList : [];
        teams.forEach(t => {
            const tObj = typeof t === 'object' ? t : { teamNumber: t, nickname: `Team ${t}` };
            const tNum = tObj.teamNumber || tObj.number || t;
            const scoreVal = tObj.averageScoutedScore !== null && tObj.averageScoutedScore !== undefined
                ? `${Number(tObj.averageScoutedScore).toFixed(1)} pts`
                : (tObj.scoutedScore !== undefined ? `${Number(tObj.scoutedScore).toFixed(1)} pts` : "No scouted data");

            const row = document.createElement("div");
            row.style.cssText = "display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid var(--border);";

            let pills = `<span style="font-size: 0.75rem; padding: 1px 6px; border-radius: 4px; background: var(--surface-3, rgba(255,255,255,0.06)); color: var(--muted);">${tObj.scoutedMatchesCount || 0} matches</span>`;
            if (tObj.epa !== null && tObj.epa !== undefined && effectiveUseEpa) {
                pills += `<span style="font-size: 0.75rem; padding: 1px 6px; border-radius: 4px; background: var(--surface-3, rgba(255,255,255,0.06)); color: var(--muted);">EPA: ${Number(tObj.epa).toFixed(1)}</span>`;
            }
            if (tObj.exp !== null && tObj.exp !== undefined && effectiveUseExp) {
                pills += `<span style="font-size: 0.75rem; padding: 1px 6px; border-radius: 4px; background: var(--surface-3, rgba(255,255,255,0.06)); color: var(--muted);">EXP: ${Number(tObj.exp).toFixed(1)}</span>`;
            }
            if (tObj.opr !== null && tObj.opr !== undefined && effectiveUseOpr) {
                pills += `<span style="font-size: 0.75rem; padding: 1px 6px; border-radius: 4px; background: var(--surface-3, rgba(255,255,255,0.06)); color: var(--muted);">OPR: ${Number(tObj.opr).toFixed(1)}</span>`;
            }

            row.innerHTML = `
                <div>
                    <div style="display: flex; align-items: center; gap: 8px;">
                        <span class="badge" style="font-weight: 800; font-size: 0.95rem;">${tNum}</span>
                        ${tObj.hasDiscrepancy ? '<span title="Discrepancy Warning" style="color: #eab308;">⚠️</span>' : ''}
                    </div>
                    <span style="font-size: 0.85rem; color: var(--muted); margin-top: 2px; display: block;">${tObj.nickname || ''}</span>
                </div>
                <div style="text-align: right;">
                    <div style="font-weight: 700; font-size: 0.95rem;">${scoreVal}</div>
                    <div style="display: flex; gap: 4px; margin-top: 4px; justify-content: flex-end;">${pills}</div>
                </div>
            `;
            container.appendChild(row);
        });
    }

    function applyPredictorViews() {
        // Bars
        updateBar("bar-pred-scouted-red", "bar-pred-scouted-blue", redScouted, blueScouted);
        renderSpotlight("spotlight-scouted-winner", "spotlight-scouted-subtext", redScouted, blueScouted, "Scouted");

        if (effectiveUseEpa) {
            updateBar("bar-pred-epa-red", "bar-pred-epa-blue", redEpa, blueEpa);
            renderSpotlight("spotlight-epa-winner", "spotlight-epa-subtext", redEpa, blueEpa, "EPA");
        }
        if (effectiveUseExp) {
            updateBar("bar-pred-exp-red", "bar-pred-exp-blue", redExp, blueExp);
            renderSpotlight("spotlight-exp-winner", "spotlight-exp-subtext", redExp, blueExp, "EXP");
        }
        if (effectiveUseOpr) {
            updateBar("bar-pred-opr-red", "bar-pred-opr-blue", redOpr, blueOpr);
            renderSpotlight("spotlight-opr-winner", "spotlight-opr-subtext", redOpr, blueOpr, "OPR");
        }

        renderTeams("shared-red-team-list", red.teams);
        renderTeams("shared-blue-team-list", blue.teams);

        // Visibility based on selected datasource
        const scoutedComp = document.getElementById("shared-scouted-comp");
        const epaComp = document.getElementById("shared-epa-comp");
        const expComp = document.getElementById("shared-exp-comp");
        const oprComp = document.getElementById("shared-opr-comp");

        const scoutedCard = document.getElementById("spotlight-scouted-card");
        const epaCard = document.getElementById("spotlight-epa-card");
        const expCard = document.getElementById("spotlight-exp-card");
        const oprCard = document.getElementById("spotlight-opr-card");

        if (selectedSource === "all") {
            if (scoutedComp) scoutedComp.classList.remove("hidden");
            if (scoutedCard) scoutedCard.classList.remove("hidden");
            if (epaComp) epaComp.classList.toggle("hidden", !effectiveUseEpa);
            if (epaCard) epaCard.classList.toggle("hidden", !effectiveUseEpa);
            if (expComp) expComp.classList.toggle("hidden", !effectiveUseExp);
            if (expCard) expCard.classList.toggle("hidden", !effectiveUseExp);
            if (oprComp) oprComp.classList.toggle("hidden", !effectiveUseOpr);
            if (oprCard) oprCard.classList.toggle("hidden", !effectiveUseOpr);
        } else if (selectedSource === "scouted") {
            if (scoutedComp) scoutedComp.classList.remove("hidden");
            if (scoutedCard) scoutedCard.classList.remove("hidden");
            if (epaComp) epaComp.classList.add("hidden");
            if (epaCard) epaCard.classList.add("hidden");
            if (expComp) expComp.classList.add("hidden");
            if (expCard) expCard.classList.add("hidden");
            if (oprComp) oprComp.classList.add("hidden");
            if (oprCard) oprCard.classList.add("hidden");
        } else if (selectedSource === "epa") {
            if (scoutedComp) scoutedComp.classList.add("hidden");
            if (scoutedCard) scoutedCard.classList.add("hidden");
            if (epaComp) epaComp.classList.remove("hidden");
            if (epaCard) epaCard.classList.remove("hidden");
            if (expComp) expComp.classList.add("hidden");
            if (expCard) expCard.classList.add("hidden");
            if (oprComp) oprComp.classList.add("hidden");
            if (oprCard) oprCard.classList.add("hidden");
        } else if (selectedSource === "exp") {
            if (scoutedComp) scoutedComp.classList.add("hidden");
            if (scoutedCard) scoutedCard.classList.add("hidden");
            if (epaComp) epaComp.classList.add("hidden");
            if (epaCard) epaCard.classList.add("hidden");
            if (expComp) expComp.classList.remove("hidden");
            if (expCard) expCard.classList.remove("hidden");
            if (oprComp) oprComp.classList.add("hidden");
            if (oprCard) oprCard.classList.add("hidden");
        } else if (selectedSource === "opr") {
            if (scoutedComp) scoutedComp.classList.add("hidden");
            if (scoutedCard) scoutedCard.classList.add("hidden");
            if (epaComp) epaComp.classList.add("hidden");
            if (epaCard) epaCard.classList.add("hidden");
            if (expComp) expComp.classList.add("hidden");
            if (expCard) expCard.classList.add("hidden");
            if (oprComp) oprComp.classList.remove("hidden");
            if (oprCard) oprCard.classList.remove("hidden");
        }
    }

    applyPredictorViews();

    const dsSelect = document.getElementById("shared-pred-datasource");
    if (dsSelect) {
        dsSelect.addEventListener("change", (e) => {
            selectedSource = e.target.value;
            applyPredictorViews();
        });
    }
}

// ==========================================================================
// 4. FULL CUSTOM ANALYTICS STUDIO IMPLEMENTATION (Matching custom-analytics)
// ==========================================================================

const BI_PALETTES = {
    obsidian: ["#2563eb", "#38bdf8", "#7c3aed", "#10b981", "#f59e0b", "#ef4444", "#ec4899", "#8b5cf6"],
    emerald: ["#10b981", "#059669", "#34d399", "#065f46", "#047857", "#6ee7b7", "#022c22", "#a7f3d0"],
    sunset: ["#f97316", "#ef4444", "#ec4899", "#f59e0b", "#fbbf24", "#db2777", "#ea580c", "#c2410c"],
    cyberpunk: ["#00f5d4", "#7b2cbf", "#f72585", "#4cc9f0", "#7209b7", "#3a0ca3", "#4361ee", "#4895ef"],
    alliance: ["#2563eb", "#ef4444", "#38bdf8", "#f87171", "#1d4ed8", "#b91c1c"]
};

function computeAggregation(numbers, aggType) {
    if (!numbers || numbers.length === 0) return 0;
    switch (aggType) {
        case "sum":
            return numbers.reduce((a, b) => a + b, 0);
        case "max":
            return Math.max(...numbers);
        case "min":
            return Math.min(...numbers);
        case "count":
            return numbers.length;
        case "median": {
            const sorted = [...numbers].sort((a, b) => a - b);
            const mid = Math.floor(sorted.length / 2);
            return sorted.length % 2 !== 0 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
        }
        case "stdev": {
            const avg = numbers.reduce((a, b) => a + b, 0) / numbers.length;
            const squareDiffs = numbers.map(v => Math.pow(v - avg, 2));
            return Math.sqrt(squareDiffs.reduce((a, b) => a + b, 0) / numbers.length);
        }
        case "p75": {
            const sorted = [...numbers].sort((a, b) => a - b);
            const idx = Math.floor(sorted.length * 0.75);
            return sorted[Math.min(idx, sorted.length - 1)];
        }
        case "avg":
        default:
            return numbers.reduce((a, b) => a + b, 0) / numbers.length;
    }
}

async function renderCustomAnalyticsView(target, config, snapshot, live) {
    const plotly = await ensurePlotly();
    const dataObj = snapshot || live || config || {};

    const reportTitle = config.title || dataObj.title || 'Match Strategy & Performance';
    const reportDesc = config.description || dataObj.description || 'Interactive multi-metric performance dashboard';

    const defaultWidgets = [
        { id: "w_kpi_total", type: "kpi", title: "Event Scoring Average", subtitle: "Mean Total Points per Match", width: "col-3", dimension: "teamNumber", measure: "calc_total_score", aggregation: "avg", palette: "obsidian" },
        { id: "w_kpi_auto", type: "kpi", title: "Autonomous Average", subtitle: "Mean Auto Score", width: "col-3", dimension: "teamNumber", measure: "calc_auto_score", aggregation: "avg", palette: "emerald" },
        { id: "w_kpi_teleop", type: "kpi", title: "Teleop Average", subtitle: "Mean Teleop Score", width: "col-3", dimension: "teamNumber", measure: "calc_teleop_score", aggregation: "avg", palette: "sunset" },
        { id: "w_kpi_matches", type: "kpi", title: "Total Matches Scouted", subtitle: "Scouting Sample Size", width: "col-3", dimension: "teamNumber", measure: "matchNumber", aggregation: "count", palette: "cyberpunk" },
        { id: "w_stacked_scoring", type: "stacked_bar", title: "Points Breakdown by Game Phase", subtitle: "Auto vs Teleop Points per Team", width: "col-8", dimension: "teamNumber", measure: "calc_auto_score", aggregation: "avg", secondaryMeasures: ["calc_teleop_score"], palette: "obsidian", sort: "val_desc", topN: 16 },
        { id: "w_phase_donut", type: "donut", title: "Overall Points Distribution", subtitle: "Auto vs Teleop Proportion", width: "col-4", dimension: "teamNumber", measure: "calc_auto_score", aggregation: "sum", secondaryMeasures: ["calc_teleop_score"], palette: "sunset" },
        { id: "w_scatter_auto_tele", type: "scatter", title: "Auto vs Teleop Correlation", subtitle: "X: Auto Score, Y: Teleop Score", width: "col-6", dimension: "teamNumber", measure: "calc_auto_score", secondaryMeasures: ["calc_teleop_score"], aggregation: "avg", palette: "emerald" },
        { id: "w_box_consistency", type: "box", title: "Match Score Variance & Outliers", subtitle: "Box & Whisker Distribution", width: "col-6", dimension: "teamNumber", measure: "calc_total_score", aggregation: "avg", palette: "cyberpunk" },
        { id: "w_matrix_overview", type: "matrix", title: "Comprehensive Team Performance Matrix", subtitle: "Click columns to sort or filter", width: "col-12", dimension: "teamNumber", measure: "calc_total_score", aggregation: "avg", secondaryMeasures: ["calc_auto_score", "calc_teleop_score"] }
    ];

    const widgets = dataObj.widgets && dataObj.widgets.length > 0 ? dataObj.widgets : (config.widgets && config.widgets.length > 0 ? config.widgets : defaultWidgets);
    const slicersState = dataObj.slicers || config.slicers || { practice: true, quals: true, playoffs: true, includePrescout: true, teamNumbers: [], eventKey: "" };

    // Extract Dataset
    let dataset = dataObj.dataset || {};
    const gameConfig = dataObj.config || config.config || (live && live.config) || { fields: [] };

    let rawEntries = Array.isArray(dataset.matchEntries) && dataset.matchEntries.length > 0
        ? dataset.matchEntries
        : (Array.isArray(dataObj.allEntries) ? dataObj.allEntries : (Array.isArray(dataObj.entries) ? dataObj.entries : (Array.isArray(dataObj.rows) ? dataObj.rows : [])));

    // Ensure calculated scores on entries
    const enrichedEntries = rawEntries.map(e => {
        const d = getEntryData(e);
        const autoPts = Number(e.calc_auto_score !== undefined ? e.calc_auto_score : entryScore(gameConfig, e, "auto"));
        const teleopPts = Number(e.calc_teleop_score !== undefined ? e.calc_teleop_score : entryScore(gameConfig, e, "teleop"));
        const totalPts = Number(e.calc_total_score !== undefined ? e.calc_total_score : (autoPts + teleopPts || entryScore(gameConfig, e, "total")));

        return {
            ...e,
            ...d,
            teamNumber: Number(e.targetTeamNumber || e.teamNumber || d.teamNumber || 0),
            matchNumber: e.matchNumber !== undefined ? e.matchNumber : (d.matchNumber || 0),
            matchKey: e.matchKey || d.matchKey || "",
            eventKey: e.eventKey || d.eventKey || "",
            isPrescout: Boolean(e.isPrescout || d.isPrescout),
            calc_auto_score: autoPts,
            calc_teleop_score: teleopPts,
            calc_total_score: totalPts
        };
    });

    let activeCrossFilterTeam = null;

    function getFilteredEntries() {
        let list = [...enrichedEntries];

        if (slicersState.eventKey) {
            list = list.filter(e => e.eventKey === slicersState.eventKey);
        }

        if (!slicersState.includePrescout) {
            list = list.filter(e => !e.isPrescout);
        }

        list = list.filter(e => {
            const mKey = (e.matchKey || "").toLowerCase();
            const isPractice = mKey.includes("_practice") || mKey.includes("_pr") || mKey.includes("_pm");
            const isPlayoff = mKey.includes("_sf") || mKey.includes("_f") || mKey.includes("_qf") || mKey.includes("_ef");
            const isQual = !isPractice && !isPlayoff;

            if (isPractice && slicersState.practice === false) return false;
            if (isQual && slicersState.quals === false) return false;
            if (isPlayoff && slicersState.playoffs === false) return false;
            return true;
        });

        if (slicersState.teamNumbers && slicersState.teamNumbers.length > 0) {
            const teamSet = new Set(slicersState.teamNumbers.map(Number));
            list = list.filter(e => teamSet.has(Number(e.teamNumber)));
        }

        if (activeCrossFilterTeam !== null) {
            list = list.filter(e => Number(e.teamNumber) === Number(activeCrossFilterTeam));
        }

        return list;
    }

    target.innerHTML = `
        <div class="bi-studio-page">
            <div class="card" style="margin-bottom: 20px;">
                <div class="row justify-between align-center wrap gap-12">
                    <div>
                        <h1 style="margin: 0 0 4px 0;">${reportTitle}</h1>
                        <p class="notice" style="margin: 0;">${reportDesc}</p>
                    </div>
                    <div id="shared-cross-filter-badge" class="badge" style="display: none; background: var(--accent); color: #fff; cursor: pointer;">
                        Filtered: Team <span id="shared-cross-team-text"></span> (Click to Reset)
                    </div>
                </div>
            </div>

            <div id="bi-canvas-grid" style="display: grid; grid-template-columns: repeat(12, 1fr); gap: 20px;"></div>
        </div>
    `;

    const crossBadge = document.getElementById("shared-cross-filter-badge");
    if (crossBadge) {
        crossBadge.addEventListener("click", () => {
            activeCrossFilterTeam = null;
            crossBadge.style.display = "none";
            renderDashboard();
        });
    }

    function renderDashboard() {
        const grid = document.getElementById("bi-canvas-grid");
        if (!grid) return;
        grid.innerHTML = "";

        const entries = getFilteredEntries();

        widgets.forEach((widget, index) => {
            const card = document.createElement("div");
            const widthClass = widget.width || "col-6";
            card.className = `bi-widget-card ${widthClass}`;
            card.style.cssText = `
                background: var(--surface);
                border-radius: var(--radius);
                border: 1px solid var(--border);
                box-shadow: var(--shadow);
                padding: 16px;
                display: flex;
                flex-direction: column;
                min-height: 300px;
                grid-column: span ${widthClass === 'col-3' ? 3 : (widthClass === 'col-4' ? 4 : (widthClass === 'col-8' ? 8 : (widthClass === 'col-12' ? 12 : 6)))};
            `;

            card.innerHTML = `
                <div style="margin-bottom: 12px;">
                    <h3 style="font-size: 15px; font-weight: 700; margin: 0; color: var(--ink);">${widget.title || 'Visual'}</h3>
                    ${widget.subtitle ? `<p style="font-size: 12px; color: var(--muted); margin: 2px 0 0 0;">${widget.subtitle}</p>` : ''}
                </div>
                <div class="bi-widget-body" id="bi-plot-${index}" style="flex: 1; display: flex; flex-direction: column; min-height: 220px;"></div>
            `;

            grid.appendChild(card);
            renderWidgetContent(widget, card.querySelector(`#bi-plot-${index}`), entries);
        });
    }

    function renderWidgetContent(widget, container, entries) {
        if (!container) return;
        const isDark = document.body.classList.contains("theme-dark");
        const colors = BI_PALETTES[widget.palette] || BI_PALETTES.obsidian;

        const layoutTheme = {
            paper_bgcolor: "rgba(0,0,0,0)",
            plot_bgcolor: "rgba(0,0,0,0)",
            font: {
                color: isDark ? "#f8fafc" : "#0f172a",
                family: "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
            },
            margin: { t: 20, r: 20, l: 40, b: 40 },
            autosize: true,
            showlegend: widget.type !== "kpi" && widget.type !== "histogram",
            legend: { orientation: "h", y: -0.2, x: 0 }
        };

        const plotConfig = { responsive: true, displayModeBar: false };

        if (widget.type === "kpi") {
            const measure = widget.measure || "calc_total_score";
            const values = entries.map(e => Number(e[measure]) || 0);
            const val = values.length > 0 ? computeAggregation(values, widget.aggregation || "avg") : 0;
            const formatted = Number.isInteger(val) ? val : val.toFixed(1);

            const allVals = enrichedEntries.map(e => Number(e[measure]) || 0);
            const overallAvg = allVals.length ? computeAggregation(allVals, "avg") : 0;
            const delta = val - overallAvg;
            const deltaFormatted = (delta >= 0 ? "+" : "") + delta.toFixed(1);

            container.innerHTML = `
                <div style="display: flex; flex-direction: column; justify-content: center; align-items: center; text-align: center; height: 100%; padding: 16px 0;">
                    <div style="font-size: 42px; font-weight: 800; color: var(--accent); line-height: 1; margin-bottom: 6px;">${formatted}</div>
                    <div style="font-size: 13px; color: var(--muted); font-weight: 600;">${widget.subtitle || widget.measure || 'Metric'} (${(widget.aggregation || "avg").toUpperCase()})</div>
                    ${overallAvg > 0 ? `
                        <div style="font-size: 12px; font-weight: 700; padding: 2px 8px; border-radius: 10px; margin-top: 8px; background: ${delta >= 0 ? 'rgba(34, 197, 94, 0.15)' : 'rgba(239, 68, 68, 0.15)'}; color: ${delta >= 0 ? '#16a34a' : '#dc2626'};">
                            ${delta >= 0 ? "▲" : "▼"} ${deltaFormatted} vs Event Mean (${overallAvg.toFixed(1)})
                        </div>
                    ` : ""}
                </div>
            `;
            return;
        }

        if (widget.type === "matrix") {
            const dim = widget.dimension || "teamNumber";
            const primaryMeasure = widget.measure || "calc_total_score";
            const secondaryMeasures = widget.secondaryMeasures || ["calc_auto_score", "calc_teleop_score"];
            const allMeasures = [primaryMeasure, ...secondaryMeasures];

            const groups = {};
            entries.forEach(e => {
                const key = e[dim] || "Unknown";
                if (!groups[key]) groups[key] = [];
                groups[key].push(e);
            });

            const rows = Object.entries(groups).map(([k, items]) => {
                const r = { dim: k, rawTeam: Number(k) || 0, count: items.length };
                allMeasures.forEach(m => {
                    r[m] = computeAggregation(items.map(i => Number(i[m]) || 0), widget.aggregation || "avg");
                });
                return r;
            });

            rows.sort((a, b) => (b[primaryMeasure] || 0) - (a[primaryMeasure] || 0));

            container.innerHTML = `
                <div style="width: 100%; height: 100%; max-height: 320px; overflow: auto;">
                    <table class="table" style="width: 100%; font-size: 13px;">
                        <thead>
                            <tr>
                                <th>Team</th>
                                <th>Matches</th>
                                ${allMeasures.map(m => `<th>${m.replace("calc_", "").replace("_", " ").toUpperCase()}</th>`).join("")}
                            </tr>
                        </thead>
                        <tbody>
                            ${rows.map(r => `
                                <tr data-team="${r.rawTeam}" style="cursor: pointer;">
                                    <td><strong>#${r.dim}</strong></td>
                                    <td>${r.count}</td>
                                    ${allMeasures.map(m => `<td><strong>${Number(r[m] || 0).toFixed(1)}</strong></td>`).join("")}
                                </tr>
                            `).join("")}
                        </tbody>
                    </table>
                </div>
            `;

            container.querySelectorAll("tbody tr").forEach(tr => {
                tr.addEventListener("click", () => {
                    const teamNum = Number(tr.dataset.team);
                    if (teamNum) {
                        activeCrossFilterTeam = (activeCrossFilterTeam === teamNum) ? null : teamNum;
                        if (crossBadge) {
                            crossBadge.style.display = activeCrossFilterTeam ? "inline-flex" : "none";
                            document.getElementById("shared-cross-team-text").textContent = activeCrossFilterTeam || "";
                        }
                        renderDashboard();
                    }
                });
            });
            return;
        }

        if (widget.type === "donut") {
            const primaryMeasure = widget.measure || "calc_auto_score";
            const secondaryMeasure = (widget.secondaryMeasures && widget.secondaryMeasures[0]) || "calc_teleop_score";
            const autoSum = computeAggregation(entries.map(e => Number(e[primaryMeasure]) || 0), "sum");
            const teleSum = computeAggregation(entries.map(e => Number(e[secondaryMeasure]) || 0), "sum");

            const trace = {
                labels: ["Auto Points", "Teleop Points"],
                values: [autoSum, teleSum],
                type: "pie",
                hole: 0.5,
                marker: { colors: [colors[0], colors[1]] }
            };

            Plotly.newPlot(container, [trace], layoutTheme, plotConfig);
            return;
        }

        if (widget.type === "scatter") {
            const mX = widget.measure || "calc_auto_score";
            const mY = (widget.secondaryMeasures && widget.secondaryMeasures[0]) || "calc_teleop_score";

            const groups = {};
            entries.forEach(e => {
                const k = e.teamNumber || "Unknown";
                if (!groups[k]) groups[k] = [];
                groups[k].push(e);
            });

            const teamKeys = Object.keys(groups);
            const xs = teamKeys.map(k => computeAggregation(groups[k].map(e => Number(e[mX]) || 0), "avg"));
            const ys = teamKeys.map(k => computeAggregation(groups[k].map(e => Number(e[mY]) || 0), "avg"));

            const trace = {
                x: xs,
                y: ys,
                text: teamKeys.map(k => `Team ${k}`),
                mode: "markers+text",
                textposition: "top center",
                type: "scatter",
                marker: { size: 10, color: colors[0] }
            };

            const layout = {
                ...layoutTheme,
                xaxis: { title: "Auto Points (Avg)" },
                yaxis: { title: "Teleop Points (Avg)" }
            };

            Plotly.newPlot(container, [trace], layout, plotConfig);
            return;
        }

        if (widget.type === "box") {
            const measure = widget.measure || "calc_total_score";
            const topTeams = [...new Set(entries.map(e => e.teamNumber).filter(Boolean))].slice(0, 12);
            const traces = topTeams.map((tNum, idx) => {
                const vals = entries.filter(e => e.teamNumber === tNum).map(e => Number(e[measure]) || 0);
                return {
                    y: vals,
                    name: `Team ${tNum}`,
                    type: "box",
                    marker: { color: colors[idx % colors.length] }
                };
            });

            const layout = { ...layoutTheme, yaxis: { title: "Points Distribution" } };
            Plotly.newPlot(container, traces, layout, plotConfig);
            return;
        }

        // Default: Bar / Stacked Bar
        const dim = widget.dimension || "teamNumber";
        const primaryMeasure = widget.measure || "calc_total_score";
        const secondaryMeasures = widget.secondaryMeasures || [];
        const isStacked = widget.type === "stacked_bar";

        const groups = {};
        entries.forEach(e => {
            const k = e[dim] || "Unknown";
            if (!groups[k]) groups[k] = [];
            groups[k].push(e);
        });

        let groupKeys = Object.keys(groups);
        groupKeys.sort((a, b) => {
            const aVal = computeAggregation(groups[a].map(e => Number(e[primaryMeasure]) || 0), widget.aggregation || "avg");
            const bVal = computeAggregation(groups[b].map(e => Number(e[primaryMeasure]) || 0), widget.aggregation || "avg");
            return bVal - aVal;
        });

        if (widget.topN && widget.topN > 0) {
            groupKeys = groupKeys.slice(0, widget.topN);
        }

        const allMeasures = [primaryMeasure, ...secondaryMeasures];
        const traces = allMeasures.map((m, mIdx) => {
            const yVals = groupKeys.map(k => {
                const vals = groups[k].map(e => Number(e[m]) || 0);
                return Number(computeAggregation(vals, widget.aggregation || "avg").toFixed(2));
            });
            return {
                x: groupKeys.map(k => `Team ${k}`),
                y: yVals,
                name: m.replace("calc_", "").replace("_", " ").toUpperCase(),
                type: "bar",
                marker: { color: colors[mIdx % colors.length] }
            };
        });

        const layout = {
            ...layoutTheme,
            barmode: isStacked ? "stack" : "group",
            xaxis: { title: "Team", tickangle: -45 },
            yaxis: { title: "Score Points" }
        };

        Plotly.newPlot(container, traces, layout, plotConfig);
    }

    renderDashboard();
}
