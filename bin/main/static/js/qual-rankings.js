function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

let currentUser = null;
let state = {
    settings: null,
    config: null,
    fields: [],
    rankingMetrics: [],
    entriesRaw: [],
    entries: [],
    teams: [],
    events: [],
    selectedEvent: "all",
    selectedMetric: "all_metrics", // default to showing all columns
    activeSortMetric: "__composite__",
    aggregateMode: "avg",
    selectedRoles: [], // Active robot role filters
    sortDir: "desc"
};

let originalCardHTML = "";
let card = null;

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }
    currentUser = me;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    card = document.querySelector(".card");
    originalCardHTML = card.innerHTML;

    await initQualRankingsPage();
});

async function initQualRankingsPage() {
    Obsidianscout.showLoadingSpinner(card, "Loading qualitative rankings...");

    try {
        const settingsResponse = await Obsidianscout.request("/api/settings");
        state.settings = settingsResponse.settings;
        state.selectedEvent = Obsidianscout.resolveEventKey(state.settings);

        const [config, entries, events] = await Promise.all([
            Obsidianscout.request("/api/qual-config"),
            Obsidianscout.request("/api/qual-scouting?includePrescout=true"),
            Obsidianscout.request(`/api/events?year=${state.settings.year}&cached=1`)
        ]);

        card.innerHTML = originalCardHTML;

        // Wire up sorting clicks on static / dynamic headers via delegation
        const headerRow = document.getElementById("table-headers");
        if (headerRow) {
            headerRow.addEventListener("click", (e) => {
                const th = e.target.closest("th");
                if (!th) return;
                
                if (th.cellIndex === 0) {
                    if (state.activeSortMetric === "__composite__") {
                        state.sortDir = state.sortDir === "desc" ? "asc" : "desc";
                    } else {
                        state.activeSortMetric = "__composite__";
                        state.sortDir = "desc";
                    }
                    renderTable();
                } else if (th.dataset.metricId) {
                    const metricId = th.dataset.metricId;
                    if (state.activeSortMetric === metricId) {
                        state.sortDir = state.sortDir === "desc" ? "asc" : "desc";
                    } else {
                        state.activeSortMetric = metricId;
                        state.sortDir = "desc";
                    }
                    if (state.selectedMetric !== "all_metrics") {
                        state.selectedMetric = metricId;
                        document.getElementById("metric-select").value = metricId;
                    }
                    renderTable();
                }
            });
        }

        state.config = config;
        state.fields = normalizeFields(config.fields || []);
        state.rankingMetrics = buildRankingMetrics(state.fields);
        state.entriesRaw = Array.isArray(entries) ? entries : [];

        // Show/hide robot role filter panel
        const roleFilterCard = document.getElementById("role-filter-card");
        if (roleFilterCard) {
            if (config.enableRobotRoleCollection) {
                roleFilterCard.classList.remove("hidden");
                setupRoleFilters();
            } else {
                roleFilterCard.classList.add("hidden");
            }
        }

        // Populate event filter select dropdown
        const eventFilter = document.getElementById("event-filter");
        eventFilter.innerHTML = "";
        
        // Add "All Events"
        const optAll = document.createElement("option");
        optAll.value = "all";
        optAll.textContent = t('qual_data.all_events', "All events");
        if (state.selectedEvent === "all") optAll.selected = true;
        eventFilter.appendChild(optAll);

        events.forEach(e => {
            const opt = document.createElement("option");
            opt.value = e.eventKey;
            opt.textContent = `${e.name} (${e.eventKey})`;
            if (e.eventKey === state.selectedEvent) {
                opt.selected = true;
            }
            eventFilter.appendChild(opt);
        });

        // Add Prescout Option
        const optPrescout = document.createElement("option");
        optPrescout.value = "prescout";
        optPrescout.textContent = "Prescouting Data";
        if (state.selectedEvent === "prescout") optPrescout.selected = true;
        eventFilter.appendChild(optPrescout);

        eventFilter.addEventListener("change", async () => {
            state.selectedEvent = eventFilter.value;
            await loadTeamsAndEntries();
        });

        // Build Metric Selector
        const metricSelect = document.getElementById("metric-select");
        metricSelect.innerHTML = "";
        
        // Add "All Metrics" (side-by-side view)
        const optAllMetrics = document.createElement("option");
        optAllMetrics.value = "all_metrics";
        optAllMetrics.textContent = t('qual_rankings.metric.all', "All Metrics");
        if (state.selectedMetric === "all_metrics") optAllMetrics.selected = true;
        metricSelect.appendChild(optAllMetrics);

        state.rankingMetrics.forEach(m => {
            const opt = document.createElement("option");
            opt.value = m.id;
            opt.textContent = m.label;
            if (state.selectedMetric === m.id) opt.selected = true;
            metricSelect.appendChild(opt);
        });

        metricSelect.addEventListener("change", () => {
            state.selectedMetric = metricSelect.value;
            if (state.selectedMetric !== "all_metrics") {
                state.activeSortMetric = state.selectedMetric;
            } else {
                state.activeSortMetric = "__composite__";
            }
            renderTable();
        });

        // Aggregation mode selector
        const aggregateSelect = document.getElementById("aggregate-select");
        aggregateSelect.addEventListener("change", () => {
            state.aggregateMode = aggregateSelect.value;
            renderTable();
        });

        await loadTeamsAndEntries();

    } catch (error) {
        console.error("Failed to load qual rankings page data:", error);
        Obsidianscout.showRetryButton(card, "Failed to load rankings data: " + error.message, initQualRankingsPage);
    }
}

function setupRoleFilters() {
    const buttons = document.querySelectorAll(".btn-role-filter");
    buttons.forEach(btn => {
        btn.addEventListener("click", () => {
            const role = btn.dataset.role;
            if (role === "all") {
                state.selectedRoles = [];
            } else {
                const idx = state.selectedRoles.indexOf(role);
                if (idx > -1) {
                    state.selectedRoles.splice(idx, 1);
                } else {
                    state.selectedRoles.push(role);
                }
            }
            
            // Update active states
            buttons.forEach(b => {
                const r = b.dataset.role;
                if (role === "all") {
                    b.classList.toggle("active", r === "all");
                } else {
                    if (r === "all") {
                        b.classList.toggle("active", state.selectedRoles.length === 0);
                    } else {
                        b.classList.toggle("active", state.selectedRoles.includes(r));
                    }
                }
            });

            renderTable();
        });
    });
}

async function loadTeamsAndEntries() {
    try {
        let teamsUrl = "/api/teams";
        if (state.selectedEvent && state.selectedEvent !== "all" && state.selectedEvent !== "prescout") {
            teamsUrl += `?eventKey=${state.selectedEvent}`;
        }
        const teams = await Obsidianscout.request(teamsUrl);
        state.teams = Array.isArray(teams) ? teams : [];

        // filter entries raw based on current event selection
        state.entries = state.entriesRaw.filter(e => {
            if (state.selectedEvent === "prescout") {
                return e.isPrescout === true;
            }
            if (state.selectedEvent !== "all") {
                return e.eventKey === state.selectedEvent;
            }
            return true;
        });

        renderTable();
    } catch (error) {
        console.error("Failed to load teams:", error);
        Obsidianscout.showToast("Failed to load teams for event", "error");
    }
}

function renderTable() {
    const table = document.getElementById("qual-rankings-table");
    const headerRow = document.getElementById("table-headers");
    const body = table.querySelector("tbody");
    body.innerHTML = "";

    // Update Rank header styling & indicator
    const rankHeader = headerRow.children[0];
    if (rankHeader) {
        rankHeader.style.cursor = "pointer";
        if (state.activeSortMetric === "__composite__") {
            rankHeader.style.fontWeight = "bold";
            const indicator = state.sortDir === "asc" ? "▲" : "▼";
            rankHeader.innerHTML = `Rank <span class="sort-indicator">${indicator}</span>`;
        } else {
            rankHeader.style.fontWeight = "normal";
            rankHeader.innerHTML = "Rank";
        }
    }

    // Clear previous dynamic headers
    const defaultHeadersCount = 2; // Rank, TEAM
    while (headerRow.children.length > defaultHeadersCount) {
        headerRow.lastElementChild.remove();
    }

    // Build dynamic headers based on selectedMetric
    let headersToRender = [];
    if (state.selectedMetric === "all_metrics") {
        headersToRender = state.rankingMetrics;
    } else {
        const found = state.rankingMetrics.find(m => m.id === state.selectedMetric);
        if (found) {
            headersToRender = [found];
        }
    }

    headersToRender.forEach(m => {
        const th = document.createElement("th");
        th.style.cursor = "pointer";
        th.dataset.metricId = m.id;
        th.textContent = m.label;
        if (state.activeSortMetric === m.id) {
            th.style.fontWeight = "bold";
            const indicator = state.sortDir === "asc" ? "▲" : "▼";
            th.innerHTML += ` <span class="sort-indicator">${indicator}</span>`;
        }
        headerRow.appendChild(th);
    });

    // Add APPEARANCES & ROLES headers if enabled in config
    const showRoleCollection = !!(state.config && state.config.enableRobotRoleCollection);
    if (showRoleCollection) {
        const thAppearances = document.createElement("th");
        thAppearances.textContent = "APPEARANCES";
        headerRow.appendChild(thAppearances);

        const thRoles = document.createElement("th");
        thRoles.textContent = "ROLES";
        headerRow.appendChild(thRoles);
    }

    if (state.teams.length === 0) {
        body.innerHTML = `<tr><td colspan="${defaultHeadersCount + headersToRender.length + (showRoleCollection ? 2 : 0)}" style="text-align: center; color: var(--muted); padding: 24px;">No teams found.</td></tr>`;
        return;
    }

    // Precalculate scores and roles per team
    const teamScores = state.teams.map(team => {
        const teamEntries = state.entries.filter(e => Number(e.targetTeamNumber) === Number(team.teamNumber));
        const scores = {};
        
        state.rankingMetrics.forEach(m => {
            const values = teamEntries
                .map(entry => metricValue(m, entry))
                .filter(val => val !== null && val !== undefined && Number.isFinite(val));
            const agg = aggregateMetric(values, state.aggregateMode);
            scores[m.id] = Number.isFinite(agg) ? agg : null;
        });

        // Collect distinct roles
        const rolesSet = new Set();
        teamEntries.forEach(e => {
            if (e.data && Array.isArray(e.data.robotRoles)) {
                e.data.robotRoles.forEach(r => rolesSet.add(r));
            }
        });

        return {
            team,
            scores,
            entriesCount: teamEntries.length,
            roles: Array.from(rolesSet)
        };
    });

    // Apply role filters if any are active
    let filteredScores = teamScores;
    if (showRoleCollection && state.selectedRoles.length > 0) {
        filteredScores = teamScores.filter(rowObj => {
            return state.selectedRoles.some(r => rowObj.roles.includes(r));
        });
    }

    // Update Team Count Badge next to header title
    const countBadge = document.getElementById("rankings-team-count-badge");
    if (countBadge) {
        countBadge.textContent = `${filteredScores.length} teams`;
    }

    // Sort by activeSortMetric, placing nulls/missing values at the bottom in both directions
    filteredScores.sort((a, b) => {
        const valA = a.scores[state.activeSortMetric];
        const valB = b.scores[state.activeSortMetric];
        const hasA = valA !== null && valA !== undefined && valA !== -999999 && valA !== Number.NEGATIVE_INFINITY;
        const hasB = valB !== null && valB !== undefined && valB !== -999999 && valB !== Number.NEGATIVE_INFINITY;

        if (hasA && !hasB) return -1;
        if (!hasA && hasB) return 1;
        if (!hasA && !hasB) return a.team.teamNumber - b.team.teamNumber;

        if (state.sortDir === "asc") {
            if (valA !== valB) return valA - valB;
        } else {
            if (valB !== valA) return valB - valA;
        }
        return a.team.teamNumber - b.team.teamNumber;
    });

    // Populate rows
    filteredScores.forEach((rowObj, index) => {
        const row = document.createElement("tr");
        const displayNum = Obsidianscout.formatTeam(rowObj.team.teamKey, rowObj.team.teamNumber);
        
        // Render Rank Badge (e.g. #1, #2, #3, etc.)
        let rankClass = "rank-default";
        if (index === 0) rankClass = "rank-1";
        else if (index === 1) rankClass = "rank-2";
        else if (index === 2) rankClass = "rank-3";
        const rankBadge = `<span class="rank-badge ${rankClass}">#${index + 1}</span>`;

        // Render Dynamic cells (with rating progress bar if numeric)
        let scoreCells = "";
        headersToRender.forEach(m => {
            const scoreVal = rowObj.scores[m.id];
            if (scoreVal !== null && scoreVal !== -999999 && scoreVal !== Number.NEGATIVE_INFINITY) {
                // If this is overallRating or a field with max configured, we draw a progress bar
                const maxVal = m.field?.max || 5;
                const pct = Math.min((scoreVal / maxVal) * 100, 100);
                scoreCells += `
                    <td>
                        <div class="qual-rating-wrapper">
                            <span style="font-weight:600;">${scoreVal.toFixed(1)}</span>
                            <div class="qual-progress-bar-container">
                                <div class="qual-progress-bar" style="width: ${pct}%"></div>
                            </div>
                        </div>
                    </td>
                `;
            } else {
                scoreCells += `<td>—</td>`;
            }
        });

        // Append appearances & roles tags
        let roleCells = "";
        if (showRoleCollection) {
            const appText = `${rowObj.entriesCount} times`;
            roleCells += `<td><span class="appearance-badge">${appText}</span></td>`;

            let roleBadges = "";
            rowObj.roles.forEach(role => {
                const cleanRole = role.toLowerCase().replace(/[^a-z0-9]/g, "");
                roleBadges += `<span class="role-badge role-${cleanRole}">${role}</span>`;
            });
            if (rowObj.roles.length === 0) {
                roleBadges = `<span style="color: var(--muted); font-size:11px; font-style:italic;">None</span>`;
            }
            roleCells += `<td><div class="row wrap gap-4">${roleBadges}</div></td>`;
        }

        row.innerHTML = `
            <td>${rankBadge}</td>
            <td>
                <div style="font-weight: 700; font-size: 13px;">
                    <a href="/team?teamNumber=${rowObj.team.teamNumber}&eventKey=${state.selectedEvent}" class="team-profile-link" style="color: var(--text);">Team ${rowObj.team.teamNumber}</a>
                </div>
            </td>
            ${scoreCells}
            ${roleCells}
        `;
        body.appendChild(row);
    });
}

// Calculations & Helpers copied from qual-data.js
function normalizeFields(fields) {
    return fields.filter(field => field.type !== "section" && !["eventKey", "matchKey", "matchNumber", "targetTeamNumber"].includes(field.id));
}

function buildRankingMetrics(fields) {
    const numericFields = fields.filter(field => ["rating", "number", "counter", "checkbox", "select"].includes(field.type));
    const metrics = [
        {
            id: "__composite__",
            label: "Composite average (all numeric fields)",
            source: "computed",
            fields: numericFields
        }
    ];

    numericFields.forEach(field => {
        metrics.push({
            id: field.id,
            label: localizeLabel(field.label),
            source: "field",
            field
        });
    });

    return metrics;
}

function localizeLabel(value) {
    if (value === null || value === undefined) return '';
    if (typeof value === 'object') {
        const lang = localStorage.getItem('obsidianscout:lang') || 'en';
        if (value[lang]) return value[lang];
        if (value.en) return value.en;
        const keys = Object.keys(value);
        if (keys.length) return value[keys[0]];
        return '';
    }
    return String(value);
}

function metricValue(metric, entry) {
    if (!metric || !entry) {
        return null;
    }
    if (metric.id === "__composite__") {
        const values = metric.fields
            .map(field => fieldNumericValue(field, entry.data ? entry.data[field.id] : undefined))
            .filter(value => value !== null && Number.isFinite(value));
        return values.length ? average(values) : null;
    }
    const value = entry.data ? entry.data[metric.id] : undefined;
    return fieldNumericValue(metric.field, value);
}

function fieldNumericValue(field, rawValue) {
    if (rawValue === null || rawValue === undefined || rawValue === "") {
        return null;
    }
    if (!field) {
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    if (field.type === "checkbox") {
        return rawValue ? 1 : 0;
    }
    if (field.type === "select") {
        const options = Array.isArray(field.options) ? field.options : [];
        const index = options.findIndex(option => {
            if (typeof option === "string") {
                return option === rawValue;
            }
            return option.value === rawValue || option.label === rawValue;
        });
        if (index >= 0) {
            return index + 1;
        }
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    if (field.type === "number" || field.type === "counter" || field.type === "rating") {
        const asNumber = Number(rawValue);
        return Number.isFinite(asNumber) ? asNumber : null;
    }
    const asNumber = Number(rawValue);
    return Number.isFinite(asNumber) ? asNumber : null;
}

function aggregateMetric(values, mode) {
    if (!values.length) {
        return Number.NEGATIVE_INFINITY;
    }
    switch (mode) {
        case "median": {
            const sorted = values.slice().sort((a, b) => a - b);
            const mid = Math.floor(sorted.length / 2);
            if (sorted.length % 2 === 0) {
                return (sorted[mid - 1] + sorted[mid]) / 2;
            }
            return sorted[mid];
        }
        case "latest":
            return values[values.length - 1];
        case "max":
            return Math.max(...values);
        case "min":
            return Math.min(...values);
        case "avg":
        default:
            return average(values);
    }
}

function average(values) {
    if (!values.length) {
        return Number.NaN;
    }
    return values.reduce((sum, value) => sum + value, 0) / values.length;
}
