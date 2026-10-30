/**
 * ObsidianScout - Scouting Assignments Management
 * Full interactive controller for Match Matrix, Qualitative Matrix, Assignment Table, Pit Coverage, and Bulk Wizard.
 */

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    // State Variables
    let settings = {};
    let currentEventKey = "";
    let stopAssignmentsPush = null; // unsubscribes the push listener startPolling() adds
    let events = [];
    let users = [];
    let matches = [];
    let teams = [];
    let assignments = [];
    let pitGroups = [];
    let conflicts = [];
    let pollIntervalId = null;

    // DOM Elements
    const eventSelect = document.getElementById("event-select");
    const refreshBtn = document.getElementById("refresh-btn");
    const newAssignmentBtn = document.getElementById("new-assignment-btn");
    const bulkWizardBtn = document.getElementById("bulk-wizard-btn");
    const reminderSettingsBtn = document.getElementById("reminder-settings-btn");

    // KPI Elements
    const statTotal = document.getElementById("stat-total");
    const statPending = document.getElementById("stat-pending");
    const statProgress = document.getElementById("stat-progress");
    const statCompleted = document.getElementById("stat-completed");
    const statConflicts = document.getElementById("stat-conflicts");

    // Tabs
    const tabBtns = document.querySelectorAll(".asgn-tab-btn");
    const tabContentMatrix = document.getElementById("tab-content-matrix");
    const tabContentQual = document.getElementById("tab-content-qual");
    const tabContentList = document.getElementById("tab-content-list");
    const tabContentPit = document.getElementById("tab-content-pit");

    // Matrix & Pit Containers
    const matrixTbody = document.getElementById("matrix-tbody");
    const qualMatrixTbody = document.getElementById("qual-matrix-tbody");
    const matrixStageFilter = document.getElementById("matrix-stage-filter");
    const qualStageFilter = document.getElementById("qual-stage-filter");
    const pitCoverageGrid = document.getElementById("pit-coverage-grid");
    const pitSearch = document.getElementById("pit-search");

    // List Filters & Actions
    const filterType = document.getElementById("filter-type");
    const filterScouter = document.getElementById("filter-scouter");
    const filterStatus = document.getElementById("filter-status");
    const filterSearch = document.getElementById("filter-search");
    const deleteAllBtn = document.getElementById("delete-all-btn");
    const assignmentsTbody = document.getElementById("assignments-tbody");

    // Assignment Modal Elements
    const assignmentModal = document.getElementById("assignment-modal");
    const assignmentModalClose = document.getElementById("assignment-modal-close");
    const assignmentModalCancel = document.getElementById("assignment-modal-cancel");
    const assignmentModalTitle = document.getElementById("assignment-modal-title");
    const assignmentForm = document.getElementById("assignment-form");
    const assignmentIdInput = document.getElementById("assignment-id");
    const assignmentTypeSelect = document.getElementById("assignment-type");
    const formGroupMatch = document.getElementById("form-group-match");
    const formGroupPit = document.getElementById("form-group-pit");
    const formGroupQual = document.getElementById("form-group-qual");
    const assignmentMatchSelect = document.getElementById("assignment-match");
    const assignmentTeamSelect = document.getElementById("assignment-team");
    const assignmentPitTeamSelect = document.getElementById("assignment-pit-team");
    const assignmentQualMatchSelect = document.getElementById("assignment-qual-match");
    const assignmentQualTargetType = document.getElementById("assignment-qual-target-type");
    const fieldQualTeam = document.getElementById("field-qual-team");
    const assignmentQualTeamSelect = document.getElementById("assignment-qual-team");
    const assignmentScouterSelect = document.getElementById("assignment-scouter");
    const assignmentStatusSelect = document.getElementById("assignment-status");
    const assignmentNotesInput = document.getElementById("assignment-notes");
    const modalConflictBanner = document.getElementById("modal-conflict-banner");
    const modalConflictText = document.getElementById("modal-conflict-text");

    // Bulk Wizard Modal Elements
    const bulkWizardModal = document.getElementById("bulk-wizard-modal");
    const bulkWizardClose = document.getElementById("bulk-wizard-close");
    const bulkWizardCancel = document.getElementById("bulk-wizard-cancel");
    const bulkWizardForm = document.getElementById("bulk-wizard-form");
    const bulkAssignmentType = document.getElementById("bulk-assignment-type");
    const bulkMatchControls = document.getElementById("bulk-match-controls");
    const bulkPitControls = document.getElementById("bulk-pit-controls");
    const bulkPitScope = document.getElementById("bulk-pit-scope");
    const bulkConsecutiveMatches = document.getElementById("bulk-consecutive-matches");
    const bulkCompLevel = document.getElementById("bulk-comp-level");
    const bulkMatchStart = document.getElementById("bulk-match-start");
    const bulkMatchEnd = document.getElementById("bulk-match-end");
    const bulkRangeMode = document.getElementById("bulk-range-mode");
    const bulkMatchRangeField = document.getElementById("bulk-match-range-field");
    const bulkTimeBlockField = document.getElementById("bulk-time-block-field");
    const bulkTimeDay = document.getElementById("bulk-time-day");
    const bulkTimeStart = document.getElementById("bulk-time-start");
    const bulkTimeEnd = document.getElementById("bulk-time-end");
    const bulkTimeSummary = document.getElementById("bulk-time-summary");
    const bulkScoutersContainer = document.getElementById("bulk-scouters-container");
    const bulkSelectAll = document.getElementById("bulk-select-all");
    const bulkDeselectAll = document.getElementById("bulk-deselect-all");
    const bulkOverwrite = document.getElementById("bulk-overwrite");

    // Planned Shifts Modal Elements
    const shiftsList = document.getElementById("shifts-list");
    const shiftForm = document.getElementById("shift-form");
    const shiftFormTitle = document.getElementById("shift-form-title");
    const shiftIdInput = document.getElementById("shift-id");
    const shiftNameInput = document.getElementById("shift-name");
    const shiftDayInput = document.getElementById("shift-day");
    const shiftStartInput = document.getElementById("shift-start");
    const shiftEndInput = document.getElementById("shift-end");
    const shiftStageSelect = document.getElementById("shift-stage");
    const shiftMatchSeats = document.getElementById("shift-match-seats");
    const shiftQualMode = document.getElementById("shift-qual-mode");
    const shiftQualSeats = document.getElementById("shift-qual-seats");
    // Seat picker that is open, and its list (on phones the list lives on <body>)
    let openSeat = null;
    let openPanel = null;
    const phoneQuery = window.matchMedia("(max-width: 600px)");
    const shiftCancelEdit = document.getElementById("shift-cancel-edit");
    let shifts = [];

    // Pit Auto-Assign Modal Elements
    const autoPitBtn = document.getElementById("auto-pit-btn");
    const autoPitHeaderBtn = document.getElementById("auto-pit-header-btn");
    const pitAutoModal = document.getElementById("pit-auto-modal");
    const pitAutoClose = document.getElementById("pit-auto-close");
    const pitAutoCancel = document.getElementById("pit-auto-cancel");
    const pitAutoForm = document.getElementById("pit-auto-form");
    const pitAutoScope = document.getElementById("pit-auto-scope");
    const pitAutoScoutersContainer = document.getElementById("pit-auto-scouters-container");
    const pitAutoSelectAll = document.getElementById("pit-auto-select-all");
    const pitAutoDeselectAll = document.getElementById("pit-auto-deselect-all");
    const pitAutoOverwrite = document.getElementById("pit-auto-overwrite");
    const pitAutoSummary = document.getElementById("pit-auto-summary");
    const pitAutoGroupsContainer = document.getElementById("pit-auto-groups-container");
    const bulkPitGroupsContainer = document.getElementById("bulk-pit-groups-container");

    // Pit Scout Groups Elements
    const pitGroupsBtn = document.getElementById("pit-groups-btn");
    const pitGroupsModal = document.getElementById("pit-groups-modal");
    const pitGroupsForm = document.getElementById("pit-groups-form");
    const pitGroupsList = document.getElementById("pit-groups-list");
    const pitGroupsAdd = document.getElementById("pit-groups-add");
    const assignmentPitGroupSelect = document.getElementById("assignment-pit-group");
    const fieldPitGroup = document.getElementById("field-pit-group");
    const fieldScouterContainer = document.getElementById("field-scouter-container");

    // Conflict Resolver Modal Elements
    const statConflictsChip = document.getElementById("stat-conflicts-chip");
    const conflictResolverModal = document.getElementById("conflict-resolver-modal");
    const conflictResolverClose = document.getElementById("conflict-resolver-close");
    const conflictResolverCancel = document.getElementById("conflict-resolver-cancel");
    const conflictModalTitle = document.getElementById("conflict-modal-title");
    const conflictModalSubtitle = document.getElementById("conflict-modal-subtitle");
    const conflictScoutersList = document.getElementById("conflict-scouters-list");
    const conflictClearAllBtn = document.getElementById("conflict-clear-all-btn");
    const conflictResolveAllBtn = document.getElementById("conflict-resolve-all-btn");

    // Reminder Settings Modal Elements
    const reminderSettingsModal = document.getElementById("reminder-settings-modal");
    const reminderSettingsClose = document.getElementById("reminder-settings-close");
    const reminderSettingsCancel = document.getElementById("reminder-settings-cancel");
    const reminderSettingsForm = document.getElementById("reminder-settings-form");
    const settingReminderMinutes = document.getElementById("setting-reminder-minutes");
    const settingEnablePush = document.getElementById("setting-enable-push");
    const settingEnableEmail = document.getElementById("setting-enable-email");

    // 1. Wire all event listeners IMMEDIATELY so buttons and tabs work unconditionally
    wireEventListeners();

    // 2. Load page data
    await init();

    function normalizeCompLevel(lvl) {
        if (!lvl) return "";
        const s = String(lvl).trim().toLowerCase();
        if (s === "qm" || s === "qual" || s === "qualification") return "qm";
        if (s === "qf" || s === "quarterfinal" || s === "quarterfinals") return "qf";
        if (s === "sf" || s === "semifinal" || s === "semifinals") return "sf";
        if (s === "f" || s === "final" || s === "finals") return "f";
        if (s === "pr" || s === "practice") return "pr";
        return s;
    }

    function extractCompLevel(item) {
        if (!item) return null;
        const lvl = normalizeCompLevel(item.compLevel);
        if (lvl) return lvl;
        const key = String(item.matchKey || "").toLowerCase();
        if (key.includes("_qm") || key.startsWith("qm")) return "qm";
        if (key.includes("_qf") || key.startsWith("qf")) return "qf";
        if (key.includes("_sf") || key.startsWith("sf")) return "sf";
        if (key.includes("_f") || key.startsWith("f")) return "f";
        if (key.includes("_pr") || key.startsWith("pr")) return "pr";
        return null;
    }

    function extractSetNumber(item) {
        if (!item) return null;
        if (item.setNumber !== undefined && item.setNumber !== null && item.setNumber !== "" && !isNaN(Number(item.setNumber))) {
            return Number(item.setNumber);
        }
        const key = String(item.matchKey || "").toLowerCase();
        const m = key.match(/_(?:sf|qf|f)(\d+)m\d+/);
        if (m && m[1] && !isNaN(Number(m[1]))) return Number(m[1]);
        return null;
    }

    function extractMatchNumber(item) {
        if (!item) return null;
        const val = item.matchNumber;
        if (val !== null && val !== undefined && val !== "" && !isNaN(Number(val)) && Number(val) > 0) {
            return Number(val);
        }
        const key = String(item.matchKey || "").trim().toLowerCase();
        if (key) {
            const mPlayoff = key.match(/_(?:sf|qf|f)\d+m(\d+)/);
            if (mPlayoff && mPlayoff[1] && !isNaN(Number(mPlayoff[1]))) return Number(mPlayoff[1]);
            const m = key.match(/\d+$/);
            if (m && m[0] && !isNaN(Number(m[0]))) {
                return Number(m[0]);
            }
        }
        return null;
    }

    function isSameMatch(asgn, match) {
        if (!asgn || !match) return false;
        const aKey = (asgn.matchKey || "").trim().toLowerCase();
        const mKey = (match.matchKey || "").trim().toLowerCase();

        // 1. If both have matchKey, strict equality is required!
        if (aKey && mKey) {
            return aKey === mKey;
        }

        const aNum = extractMatchNumber(asgn);
        const mNum = extractMatchNumber(match);
        const aComp = extractCompLevel(asgn);
        const mComp = extractCompLevel(match);
        const aSet = extractSetNumber(asgn);
        const mSet = extractSetNumber(match);

        // 2. Match number must match
        if (aNum === null || mNum === null || aNum !== mNum) {
            return false;
        }

        // 3. Stage / competition level must match if specified
        if (aComp && mComp && aComp !== mComp) {
            return false;
        }

        // 4. Playoff set numbers (for SF/QF/Finals) must match
        const isPlayoff = aComp === "sf" || aComp === "qf" || aComp === "f" ||
                          mComp === "sf" || mComp === "qf" || mComp === "f";
        if (isPlayoff) {
            if (aSet !== null && mSet !== null && aSet !== mSet) {
                return false;
            }
            if ((aSet !== null && mSet === null) || (aSet === null && mSet !== null)) {
                return false;
            }
        }

        // 5. If one has matchKey and the other has compLevel, verify stage prefix
        if (aKey && !mKey) {
            if (aKey.includes("_pr") && mComp && mComp !== "pr") return false;
            if (aKey.includes("_qm") && mComp && mComp !== "qm") return false;
            if (aKey.includes("_sf") && mComp && mComp !== "sf") return false;
            if (aKey.includes("_qf") && mComp && mComp !== "qf") return false;
            if (aKey.includes("_f") && mComp && mComp !== "f") return false;
        }
        if (!aKey && mKey) {
            if (mKey.includes("_pr") && aComp && aComp !== "pr") return false;
            if (mKey.includes("_qm") && aComp && aComp !== "qm") return false;
            if (mKey.includes("_sf") && aComp && aComp !== "sf") return false;
            if (mKey.includes("_qf") && aComp && aComp !== "qf") return false;
            if (mKey.includes("_f") && aComp && aComp !== "f") return false;
        }

        return true;
    }

    function isMatchForSlot(asgn, match, teamNum) {
        if (!isSameMatch(asgn, match)) return false;
        if (teamNum === undefined || teamNum === null || teamNum === "") return true;
        return Number(asgn.targetTeamNumber) === Number(teamNum);
    }

    function isMatchForQualAlliance(asgn, match, allianceColor) {
        if (!isSameMatch(asgn, match)) return false;
        const asgnAlliance = (asgn.allianceColor || asgn.targetAlliance || "").trim().toLowerCase();
        const target = (allianceColor || "").trim().toLowerCase();
        if (!target) return !asgn.targetTeamNumber;
        return asgnAlliance === target && !asgn.targetTeamNumber;
    }

    function compLevelRank(level) {
        switch ((level || "").toLowerCase()) {
            case "pr": case "practice": return 0;
            case "qm": case "qual": case "qualification": return 1;
            case "qf": case "quarterfinal": return 2;
            case "sf": case "semifinal": return 3;
            case "f": case "finals": return 4;
            default: return 5;
        }
    }

    function getMatchDisplayLabel(m) {
        if (!m) return "";
        if (m.label && !m.label.startsWith("undefined") && !m.label.startsWith("null")) {
            return m.label;
        }
        const key = (m.matchKey || "").toLowerCase();
        const comp = (m.compLevel || "").toLowerCase();
        const num = m.matchNumber;
        const set = m.setNumber;

        if (comp === "qm" || comp === "qual" || key.includes("_qm")) {
            const qmNum = num || key.replace(/.*_qm/i, "");
            return `Qualification Match ${qmNum}`;
        } else if (comp === "sf" || key.includes("_sf")) {
            return `Semifinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "qf" || key.includes("_qf")) {
            return `Quarterfinal ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "f" || key.includes("_f")) {
            return `Finals ${set ? `${set} - ` : ""}Match ${num || 1}`;
        } else if (comp === "pr" || comp === "practice" || key.includes("_pr")) {
            return `Practice Match ${num || 1}`;
        } else if (num) {
            return `Match ${num}`;
        }
        return m.matchKey || "Match";
    }

    async function init() {
        try {
            // Load settings
            const settingsRes = await Obsidianscout.request("/api/settings").catch(() => ({ settings: {} }));
            settings = settingsRes.settings || {};
            currentEventKey = Obsidianscout.resolveEventKey(settings);

            const pitGroupsRes = await Obsidianscout.request("/api/assignments/pit-groups").catch(() => []);
            pitGroups = Array.isArray(pitGroupsRes) ? pitGroupsRes : [];

            // Load events list and deduplicate
            const eventsRes = await Obsidianscout.request(
                settings.year ? `/api/events?year=${settings.year}&cached=1` : "/api/events"
            ).catch(() => []);
            const rawEvents = Array.isArray(eventsRes) ? eventsRes : (eventsRes.events || []);
            const eventMap = new Map();
            rawEvents.forEach(e => {
                const k = (e.eventKey || "").trim();
                if (k && !eventMap.has(k)) {
                    eventMap.set(k, e);
                }
            });
            events = Array.from(eventMap.values());

            // Populate event dropdown
            if (events.length > 0) {
                eventSelect.innerHTML = events.map(e => `
                    <option value="${e.eventKey}" ${e.eventKey === currentEventKey ? "selected" : ""}>
                        ${escapeHtml(e.name || e.eventKey)} (${escapeHtml(e.eventKey)})
                    </option>
                `).join("");

                if (!currentEventKey) {
                    currentEventKey = events[0].eventKey;
                    eventSelect.value = currentEventKey;
                }
            } else if (currentEventKey) {
                eventSelect.innerHTML = `<option value="${currentEventKey}">${escapeHtml(currentEventKey)}</option>`;
            } else {
                eventSelect.innerHTML = `<option value="">No events configured</option>`;
            }

            // Load team users for scouter selection
            await loadUsers();

            // Load event matches, teams, and assignments
            await loadEventData();
        } catch (err) {
            console.error("Assignments init error:", err);
            Obsidianscout.showToast(Obsidianscout.t ? Obsidianscout.t("assignments.init_error", "Failed to load initial assignment data") : "Failed to load initial assignment data", "error");
        }
    }

    async function loadUsers() {
        try {
            // Intra-team and program filtering: Ensure scouters list is always restricted to the active team AND program,
            // even when the logged-in user is a SUPERADMIN.
            const activeTeam = Number((me && me.teamNumber) || (settings && settings.teamNumber) || 0);
            const activeProgram = (me && me.program) || (settings && settings.program) || (typeof Obsidianscout.getProgram === 'function' ? Obsidianscout.getProgram() : (currentEventKey && currentEventKey.toLowerCase().startsWith('ftc') ? 'FTC' : 'FRC'));

            let rawUsers = [];

            // 1. If active team is known, fetch admin users filtered by teamNumber and program
            if (activeTeam > 0) {
                const teamUsersRes = await Obsidianscout.request(`/api/admin/users?teamNumber=${activeTeam}&program=${encodeURIComponent(activeProgram)}&limit=200`).catch(() => null);
                if (teamUsersRes) {
                    rawUsers = Array.isArray(teamUsersRes) ? teamUsersRes : (teamUsersRes.users || []);
                }
            }

            // 2. Fallback to /api/chat/team-members (natively scoped to session's team and program)
            if (!rawUsers || rawUsers.length === 0) {
                const chatUsersRes = await Obsidianscout.request("/api/chat/team-members").catch(() => null);
                if (chatUsersRes) {
                    rawUsers = Array.isArray(chatUsersRes) ? chatUsersRes : (chatUsersRes.users || []);
                }
            }

            // 3. Fallback to /api/admin/users with program filter
            if (!rawUsers || rawUsers.length === 0) {
                const adminUsersRes = await Obsidianscout.request(`/api/admin/users?program=${encodeURIComponent(activeProgram)}&limit=200`).catch(() => []);
                rawUsers = Array.isArray(adminUsersRes) ? adminUsersRes : (adminUsersRes.users || []);
            }

            // Strict filter: Filter strictly to active team and active program
            rawUsers = rawUsers.filter(u => {
                const uTeam = Number(u.teamNumber);
                const uProg = u.program ? String(u.program).toUpperCase() : "";
                const matchesTeam = !activeTeam || !uTeam || uTeam === activeTeam;
                const matchesProg = !activeProgram || !uProg || uProg === activeProgram.toUpperCase();
                return matchesTeam && matchesProg;
            });

            // Deduplicate users by unique ID / username
            const userMap = new Map();
            rawUsers.forEach(u => {
                const uid = String(u.id || u.userId || u.username || "");
                if (uid && !userMap.has(uid)) {
                    userMap.set(uid, u);
                }
            });

            users = Array.from(userMap.values()).sort((a, b) => {
                const nameA = (a.displayName || a.username || "").toLowerCase();
                const nameB = (b.displayName || b.username || "").toLowerCase();
                return nameA.localeCompare(nameB);
            });

            populateScouterOptions();
        } catch (err) {
            console.warn("Could not load users for scouter selection:", err);
            users = [];
        }
    }

    function populateScouterOptions() {
        const scouterUsers = users.filter(u => u.role !== "NONE");
        const optionsHtml = scouterUsers.map(u => {
            const uid = u.id || u.userId;
            const displayName = u.displayName || u.username;
            const handle = (u.username && u.username !== displayName) ? ` (@${u.username})` : "";
            const roleStr = u.role || "SCOUT";
            return `<option value="${uid}">${escapeHtml(displayName)}${escapeHtml(handle)} (${escapeHtml(roleStr)})</option>`;
        }).join("");

        filterScouter.innerHTML = `<option value="">All Scouters (${scouterUsers.length})</option>` + optionsHtml;
        assignmentScouterSelect.innerHTML = `<option value="">Select team member...</option>` + optionsHtml;
        refreshSelectSearch(filterScouter);
        refreshSelectSearch(assignmentScouterSelect);

        // Populate bulk wizard scouter checklist
        bulkScoutersContainer.innerHTML = scouterUsers.map(u => {
            const uid = u.id || u.userId;
            const displayName = u.displayName || u.username;
            return `
                <label style="display:flex; align-items:center; gap:6px; font-size:12px; cursor:pointer; padding:3px 6px; border-radius:4px; background:var(--surface-2, rgba(255,255,255,0.03));">
                    <input type="checkbox" name="bulk_scouter" value="${uid}" checked />
                    <span>${escapeHtml(displayName)}</span>
                </label>
            `;
        }).join("");
        applyListSearch(bulkScoutersContainer);

        // Populate pit auto scouter checklist
        if (pitAutoScoutersContainer) {
            pitAutoScoutersContainer.innerHTML = scouterUsers.map(u => {
                const uid = u.id || u.userId;
                const displayName = u.displayName || u.username;
                return `
                    <label style="display:flex; align-items:center; gap:6px; font-size:12px; cursor:pointer; padding:3px 6px; border-radius:4px; background:var(--surface-2, rgba(255,255,255,0.03));">
                        <input type="checkbox" name="pit_auto_scouter" value="${uid}" checked />
                        <span>${escapeHtml(displayName)}</span>
                    </label>
                `;
            }).join("");

            pitAutoScoutersContainer.querySelectorAll("input[name='pit_auto_scouter']").forEach(cb => {
                cb.addEventListener("change", updatePitAutoSummary);
            });
            applyListSearch(pitAutoScoutersContainer);
            updatePitAutoSummary();
        }

        renderAllPitGroupPickers();
    }

    function updatePitAutoSummary() {
        if (!pitAutoSummary) return;
        const scope = pitAutoScope ? pitAutoScope.value : "unassigned";
        const selectedCheckboxes = pitAutoScoutersContainer ? Array.from(pitAutoScoutersContainer.querySelectorAll("input[name='pit_auto_scouter']:checked")) : [];
        const scouterCount = selectedCheckboxes.length;

        const pitAssignments = assignments.filter(a => a.assignmentType === "PIT");
        const targetTeams = scope === "unassigned"
            ? teams.filter(t => !pitAssignments.some(a => Number(a.targetTeamNumber) === Number(t.teamNumber)))
            : teams;

        const teamCount = targetTeams.length;
        const groups = getCheckedPitGroups(pitAutoGroupsContainer);
        const groupedIds = new Set(groups.flatMap(g => g.memberIds));
        const soloCount = selectedCheckboxes.filter(cb => !groupedIds.has(cb.value)).length;
        const unitCount = groups.length + soloCount;

        if (unitCount === 0) {
            pitAutoSummary.textContent = `Please select at least 1 scouter or group (0 of ${teamCount} teams will be assigned).`;
            pitAutoSummary.style.color = "var(--danger, #ef4444)";
        } else {
            const perUnit = teamCount === 0 ? "0" : (teamCount / unitCount).toFixed(1);
            const parts = [];
            if (groups.length) parts.push(`${groups.length} group${groups.length === 1 ? '' : 's'}`);
            if (soloCount) parts.push(`${soloCount} solo scout${soloCount === 1 ? '' : 's'}`);
            pitAutoSummary.textContent = `Assigning ${teamCount} team${teamCount === 1 ? '' : 's'} across ${parts.join(" + ")} (~${perUnit} teams each).`;
            pitAutoSummary.style.color = "var(--primary)";
        }
    }

    // ==========================================
    // PEOPLE PICKER SEARCH
    // ==========================================
    // Puts a search box above a checklist container; items are its direct <label> children.
    function addListSearch(container, placeholder) {
        if (!container || container._searchInput) return;
        const input = document.createElement("input");
        input.type = "search";
        input.className = "list-search";
        input.placeholder = placeholder || "Search people...";
        input.autocomplete = "off";
        container.parentNode.insertBefore(input, container);
        container._searchInput = input;
        input.addEventListener("input", () => applyListSearch(container));
        // Enter in the box should filter, not submit the modal form
        input.addEventListener("keydown", e => { if (e.key === "Enter") e.preventDefault(); });
    }

    function applyListSearch(container) {
        if (!container || !container._searchInput) return;
        const q = container._searchInput.value.trim().toLowerCase();
        container.querySelectorAll(":scope > label").forEach(el => {
            el.classList.toggle("list-search-hidden", q !== "" && !el.textContent.toLowerCase().includes(q));
        });
    }

    function clearListSearch(container) {
        if (!container || !container._searchInput) return;
        container._searchInput.value = "";
        applyListSearch(container);
    }

    // Checkboxes the search currently shows, so Select All / Deselect All only touch the matches
    function visibleCheckboxes(container) {
        return Array.from(container.querySelectorAll("input[type='checkbox']"))
            .filter(cb => !cb.closest(".list-search-hidden"));
    }

    // Puts a search box above a <select> that narrows its options (the blank placeholder always stays)
    function addSelectSearch(select, placeholder) {
        if (!select || select._searchInput) return;
        const input = document.createElement("input");
        input.type = "search";
        input.className = "list-search";
        input.placeholder = placeholder || "Search people...";
        input.autocomplete = "off";
        select.parentNode.insertBefore(input, select);
        select._searchInput = input;
        input.addEventListener("input", () => applySelectSearch(select));
        input.addEventListener("keydown", e => { if (e.key === "Enter") e.preventDefault(); });
    }

    // Call after replacing a searchable select's options
    function refreshSelectSearch(select) {
        if (!select || !select._searchInput) return;
        select._allOptions = Array.from(select.options).map(o => o.cloneNode(true));
        applySelectSearch(select);
    }

    function applySelectSearch(select) {
        if (!select || !select._allOptions) return;
        const q = select._searchInput.value.trim().toLowerCase();
        const current = select.value;
        const keep = select._allOptions.filter(o =>
            o.value === "" || q === "" || o.textContent.toLowerCase().includes(q)
        );
        select.replaceChildren(...keep.map(o => o.cloneNode(true)));

        // A single match gets picked so the scouter can be chosen by typing alone; a pick the search
        // filtered out is dropped back to the placeholder rather than left showing a non-match
        const matches = keep.filter(o => o.value !== "");
        let next = matches.some(o => o.value === current) ? current : "";
        if (q !== "" && matches.length === 1) next = matches[0].value;
        select.value = next;
        if (next !== current) select.dispatchEvent(new Event("change", { bubbles: true }));
    }

    function clearSelectSearch(select) {
        if (!select || !select._searchInput) return;
        select._searchInput.value = "";
        applySelectSearch(select);
    }

    function setupPeopleSearch() {
        addListSearch(bulkScoutersContainer, "Search scouters...");
        addListSearch(pitAutoScoutersContainer, "Search pit scouters...");
        addListSearch(bulkPitGroupsContainer, "Search groups or members...");
        addListSearch(pitAutoGroupsContainer, "Search groups or members...");
        addSelectSearch(assignmentScouterSelect, "Type a name to find a scouter...");
        addSelectSearch(filterScouter, "Search scouters...");
    }

    // ==========================================
    // PIT SCOUT GROUPS
    // ==========================================
    function getPitGroups() {
        return pitGroups;
    }

    function getUserDisplayName(uid) {
        const u = users.find(usr => (usr.id || usr.userId) === uid);
        return u ? (u.displayName || u.username) : "Unknown";
    }

    // Rows that share a scout group on the same pit team are one unit, not duplicates
    function pitUnitKey(a) {
        return a.scoutGroup ? `group:${String(a.scoutGroup).toLowerCase()}` : `row:${a.id}`;
    }

    function groupPitAssignmentsByUnit(list) {
        const units = new Map();
        list.forEach(a => {
            const key = pitUnitKey(a);
            if (!units.has(key)) units.set(key, []);
            units.get(key).push(a);
        });
        return Array.from(units.values());
    }

    function renderPitGroupChecklist(container, onChange) {
        if (!container) return;
        const groups = getPitGroups();
        if (groups.length === 0) {
            container.innerHTML = `<span style="color:var(--muted); font-size:12px;">No pit groups yet. Use Manage Groups to create some.</span>`;
            return;
        }
        container.innerHTML = groups.map(g => {
            const names = (g.memberIds || []).map(getUserDisplayName).join(", ");
            return `
                <label style="display:flex; align-items:flex-start; gap:6px; font-size:12px; cursor:pointer; padding:4px 6px; border-radius:4px; background:var(--surface-2, rgba(255,255,255,0.03));">
                    <input type="checkbox" name="pit_group" value="${escapeHtml(g.id)}" style="margin-top:2px;" />
                    <span><strong>${escapeHtml(g.name)}</strong><br><span style="color:var(--muted);">${escapeHtml(names || "No members")}</span></span>
                </label>
            `;
        }).join("");
        if (onChange) {
            container.querySelectorAll("input[name='pit_group']").forEach(cb => cb.addEventListener("change", onChange));
        }
        applyListSearch(container);
    }

    function getCheckedPitGroups(container) {
        if (!container) return [];
        const checkedIds = Array.from(container.querySelectorAll("input[name='pit_group']:checked")).map(cb => cb.value);
        return getPitGroups().filter(g => checkedIds.includes(g.id) && (g.memberIds || []).length > 0);
    }

    function renderAllPitGroupPickers() {
        renderPitGroupChecklist(pitAutoGroupsContainer, updatePitAutoSummary);
        renderPitGroupChecklist(bulkPitGroupsContainer);
        if (assignmentPitGroupSelect) {
            const current = assignmentPitGroupSelect.value;
            assignmentPitGroupSelect.innerHTML = `<option value="">Individual scouter</option>` + getPitGroups().map(g =>
                `<option value="${escapeHtml(g.id)}">Group: ${escapeHtml(g.name)} (${(g.memberIds || []).length})</option>`
            ).join("");
            assignmentPitGroupSelect.value = getPitGroups().some(g => g.id === current) ? current : "";
        }
        updatePitAutoSummary();
    }

    function newPitGroupId() {
        return (window.crypto && crypto.randomUUID) ? crypto.randomUUID() : `grp-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
    }

    function renderPitGroupEditorRow(group) {
        const scouterUsers = users.filter(u => u.role !== "NONE");
        const row = document.createElement("div");
        row.className = "pit-group-editor";
        row.dataset.groupId = group.id;
        row.style.cssText = "border:1px solid var(--border-subtle); border-radius:var(--radius-sm, 6px); padding:10px; background:var(--surface-2);";
        row.innerHTML = `
            <div class="row gap-8 items-center mb-6">
                <input type="text" class="pit-group-name" maxlength="64" placeholder="Group name (e.g. Pit Crew A)" value="${escapeHtml(group.name || "")}" style="flex:1;" />
                <button type="button" class="btn ghost btn-sm pit-group-remove" title="Remove group" style="color: var(--danger, #ef4444); padding:6px 8px;">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <polyline points="3 6 5 6 21 6"></polyline>
                        <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                    </svg>
                </button>
            </div>
            <div class="pit-group-members" style="max-height:130px; overflow-y:auto; display:grid; grid-template-columns:repeat(auto-fill, minmax(140px, 1fr)); gap:6px;">
                ${scouterUsers.map(u => {
                    const uid = u.id || u.userId;
                    const checked = (group.memberIds || []).includes(uid) ? "checked" : "";
                    return `
                        <label style="display:flex; align-items:center; gap:6px; font-size:12px; cursor:pointer;">
                            <input type="checkbox" class="pit-group-member" value="${uid}" ${checked} />
                            <span>${escapeHtml(u.displayName || u.username)}</span>
                        </label>
                    `;
                }).join("")}
            </div>
        `;
        row.querySelector(".pit-group-remove").addEventListener("click", () => row.remove());
        pitGroupsList.appendChild(row);
        addListSearch(row.querySelector(".pit-group-members"), "Search team members...");
    }

    function openPitGroupsModal() {
        pitGroupsList.innerHTML = "";
        const groups = getPitGroups();
        if (groups.length === 0) {
            renderPitGroupEditorRow({ id: newPitGroupId(), name: "", memberIds: [] });
        } else {
            groups.forEach(renderPitGroupEditorRow);
        }
        showModal("pit-groups-modal");
    }

    async function savePitGroups(e) {
        e.preventDefault();
        const groups = [];
        const seenNames = new Set();
        for (const row of pitGroupsList.querySelectorAll(".pit-group-editor")) {
            const name = row.querySelector(".pit-group-name").value.trim();
            const memberIds = Array.from(row.querySelectorAll(".pit-group-member:checked")).map(cb => cb.value);
            if (!name && memberIds.length === 0) continue;
            if (!name) {
                Obsidianscout.showToast("Every group needs a name", "error");
                return;
            }
            if (seenNames.has(name.toLowerCase())) {
                Obsidianscout.showToast(`Group name "${name}" is used twice`, "error");
                return;
            }
            if (memberIds.length === 0) {
                Obsidianscout.showToast(`Group "${name}" has no members`, "error");
                return;
            }
            seenNames.add(name.toLowerCase());
            groups.push({ id: row.dataset.groupId, name, memberIds });
        }

        try {
            const saved = await Obsidianscout.request("/api/assignments/pit-groups", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ groups })
            });
            pitGroups = Array.isArray(saved) ? saved : groups;
            renderAllPitGroupPickers();
            Obsidianscout.showToast(`Saved ${pitGroups.length} pit group${pitGroups.length === 1 ? '' : 's'}`, "success");
            hideModal("pit-groups-modal");
        } catch (err) {
            console.error("Save pit groups failed:", err);
            Obsidianscout.showToast(err.message || "Failed to save pit groups", "error");
        }
    }

    // Toggle the manual modal between a single scouter and a whole pit group
    function updatePitGroupModeView() {
        const isPit = assignmentTypeSelect.value === "PIT";
        const usingGroup = isPit && assignmentPitGroupSelect && assignmentPitGroupSelect.value !== "";
        if (fieldPitGroup) fieldPitGroup.classList.toggle("hidden", !isPit || getPitGroups().length === 0);
        if (fieldScouterContainer) fieldScouterContainer.classList.toggle("hidden", usingGroup);
        assignmentScouterSelect.required = !usingGroup;
    }

    async function loadEventData() {
        if (!currentEventKey) {
            renderEmptyState("No active event selected. Please select or sync an event.");
            return;
        }

        try {
            const [matchesRes, teamsRes, assignmentsRes, conflictsRes] = await Promise.all([
                Obsidianscout.request(`/api/matches?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/teams?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/assignments?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => []),
                Obsidianscout.request(`/api/assignments/conflicts?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => [])
            ]);

            // Deduplicate & sort matches
            const rawMatches = Array.isArray(matchesRes) ? matchesRes : (matchesRes.matches || []);
            const matchMap = new Map();
            rawMatches.forEach(m => {
                const key = (m.matchKey || "").trim().toLowerCase();
                if (key && !matchMap.has(key)) {
                    matchMap.set(key, m);
                }
            });
            matches = Array.from(matchMap.values()).sort((a, b) => {
                const rA = compLevelRank(a.compLevel);
                const rB = compLevelRank(b.compLevel);
                if (rA !== rB) return rA - rB;
                const sA = Number(a.setNumber) || 0;
                const sB = Number(b.setNumber) || 0;
                if (sA !== sB) return sA - sB;
                const nA = Number(a.matchNumber) || 0;
                const nB = Number(b.matchNumber) || 0;
                if (nA !== nB) return nA - nB;
                return (a.matchKey || "").localeCompare(b.matchKey || "");
            });

            // Deduplicate & sort teams
            const rawTeams = Array.isArray(teamsRes) ? teamsRes : (teamsRes.teams || []);
            const teamMap = new Map();
            rawTeams.forEach(t => {
                const num = Number(t.teamNumber);
                if (num && !teamMap.has(num)) {
                    teamMap.set(num, t);
                }
            });

            const activeProgram = (me && me.program) || (settings && settings.program) || (typeof Obsidianscout.getProgram === 'function' ? Obsidianscout.getProgram() : (currentEventKey && currentEventKey.toLowerCase().startsWith('ftc') ? 'FTC' : 'FRC'));
            const isFtcEvent = (currentEventKey && currentEventKey.toLowerCase().startsWith('ftc')) || (activeProgram === 'FTC');

            // Ingest teams found in match alliances
            matches.forEach(m => {
                const allMatchTeams = (m.redTeams || []).concat(m.blueTeams || []);
                allMatchTeams.forEach(tKey => {
                    const num = parseTeamNum(tKey);
                    if (num && !teamMap.has(num)) {
                        teamMap.set(num, {
                            teamNumber: num,
                            teamKey: typeof tKey === 'string' && (tKey.toLowerCase().startsWith('frc') || tKey.toLowerCase().startsWith('ftc')) ? tKey : (isFtcEvent ? `ftc${num}` : `frc${num}`),
                            nickname: `Team ${num}`,
                            name: `Team ${num}`
                        });
                    }
                });
            });

            // Ingest teams found in existing assignments
            const rawAssignments = Array.isArray(assignmentsRes) ? assignmentsRes : (assignmentsRes.assignments || []);
            rawAssignments.forEach(a => {
                const num = Number(a.targetTeamNumber);
                if (num && !teamMap.has(num)) {
                    teamMap.set(num, {
                        teamNumber: num,
                        teamKey: isFtcEvent ? `ftc${num}` : `frc${num}`,
                        nickname: a.targetTeamName || `Team ${num}`,
                        name: a.targetTeamName || `Team ${num}`
                    });
                }
            });

            teams = Array.from(teamMap.values()).sort((a, b) => (Number(a.teamNumber) || 0) - (Number(b.teamNumber) || 0));

            // Deduplicate assignments
            const asgnMap = new Map();
            rawAssignments.forEach(a => {
                if (a.id && !asgnMap.has(a.id)) {
                    asgnMap.set(a.id, a);
                }
            });
            assignments = Array.from(asgnMap.values());

            conflicts = Array.isArray(conflictsRes) ? conflictsRes : (conflictsRes.conflicts || []);

            updateStats();
            populateBulkMatchDropdowns();
            updateBulkTypeOptions();
            renderMatrix();
            renderQualitativeMatrix();
            renderAssignmentsList();
            renderPitCoverage();

            startPolling();
        } catch (err) {
            console.error("Failed to load event assignments data:", err);
            Obsidianscout.showToast("Failed to fetch assignment list", "error");
        }
    }

    // Changes arrive as a push over the notification socket; the poll only catches changes made through another
    // cluster node.
    function startPolling() {
        stopPolling();
        pollIntervalId = setInterval(pollAssignments, 20000);
        stopAssignmentsPush = Obsidianscout.onInvalidate(["assignments", "schedule"], pollAssignments);
    }

    function stopPolling() {
        if (pollIntervalId) {
            clearInterval(pollIntervalId);
            pollIntervalId = null;
        }
        if (stopAssignmentsPush) {
            stopAssignmentsPush();
            stopAssignmentsPush = null;
        }
    }

    async function pollAssignments() {
        if (!currentEventKey || document.hidden) return;
        const isModalOpen = document.querySelector(".modal-backdrop.show");
        if (isModalOpen) return; // Don't interrupt user during active modal editing

        try {
            const [assignmentsRes, conflictsRes] = await Promise.all([
                Obsidianscout.request(`/api/assignments?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => null),
                Obsidianscout.request(`/api/assignments/conflicts?eventKey=${encodeURIComponent(currentEventKey)}`).catch(() => null)
            ]);

            if (assignmentsRes) {
                const rawAssignments = Array.isArray(assignmentsRes) ? assignmentsRes : (assignmentsRes.assignments || []);
                const asgnMap = new Map();
                rawAssignments.forEach(a => {
                    if (a.id && !asgnMap.has(a.id)) asgnMap.set(a.id, a);
                });
                assignments = Array.from(asgnMap.values());
            }

            if (conflictsRes) {
                conflicts = Array.isArray(conflictsRes) ? conflictsRes : (conflictsRes.conflicts || []);
            }

            updateStats();
            renderMatrix();
            renderQualitativeMatrix();
            renderAssignmentsList();
            renderPitCoverage();
        } catch (err) {
            console.debug("Silent assignments poll failed:", err);
        }
    }

    function updateStats() {
        statTotal.textContent = assignments.length;
        statPending.textContent = assignments.filter(a => a.status === "PENDING").length;
        statProgress.textContent = assignments.filter(a => a.status === "IN_PROGRESS").length;
        statCompleted.textContent = assignments.filter(a => a.status === "COMPLETED").length;

        const { idsToDelete } = findAssignmentDiscrepancies();
        const conflictCount = idsToDelete.length;
        statConflicts.textContent = conflictCount;

        const pill = document.getElementById("kpi-resolve-pill");
        if (conflictCount > 0) {
            if (statConflictsChip) statConflictsChip.classList.add("has-conflicts");
            if (pill) {
                pill.style.display = "inline-flex";
                pill.textContent = `⚡ Resolve (${conflictCount})`;
            }
        } else {
            if (statConflictsChip) statConflictsChip.classList.remove("has-conflicts");
            if (pill) pill.style.display = "none";
        }
    }

    function renderEmptyState(message) {
        matrixTbody.innerHTML = `<tr><td colspan="8" style="text-align:center; color:var(--muted); padding:32px;">${message}</td></tr>`;
        if (qualMatrixTbody) qualMatrixTbody.innerHTML = `<tr><td colspan="10" style="text-align:center; color:var(--muted); padding:32px;">${message}</td></tr>`;
        assignmentsTbody.innerHTML = `<tr><td colspan="7" style="text-align:center; color:var(--muted); padding:32px;">${message}</td></tr>`;
        pitCoverageGrid.innerHTML = `<p style="color:var(--muted); padding:20px;">${message}</p>`;
    }

    function getFilteredMatches(stage) {
        if (!stage || stage === "all") return matches;
        const filtered = matches.filter(m => {
            const c = extractCompLevel(m);
            if (stage === "qm") return c === "qm" || !c;
            if (stage === "pr") return c === "pr";
            if (stage === "playoffs") return c === "qf" || c === "sf" || c === "f";
            return true;
        });
        return filtered.length > 0 ? filtered : matches;
    }

    function populateBulkMatchDropdowns() {
        if (!bulkMatchStart || !bulkMatchEnd) return;
        const stage = bulkCompLevel ? bulkCompLevel.value : "all";
        const availableMatches = getFilteredMatches(stage);

        if (availableMatches.length === 0) {
            bulkMatchStart.innerHTML = `<option value="">No matches available</option>`;
            bulkMatchEnd.innerHTML = `<option value="">No matches available</option>`;
            return;
        }

        const optionsHtml = availableMatches.map(m => `
            <option value="${escapeHtml(m.matchKey || '')}">${escapeHtml(getMatchDisplayLabel(m))}</option>
        `).join("");

        bulkMatchStart.innerHTML = optionsHtml;
        bulkMatchEnd.innerHTML = optionsHtml;

        // Default to max range (first match to last match)
        bulkMatchStart.value = availableMatches[0].matchKey || "";
        bulkMatchEnd.value = availableMatches[availableMatches.length - 1].matchKey || "";

        populateBulkTimeDays();
    }

    // ==========================================
    // BULK WIZARD: TIME BLOCK (SHIFT) HELPERS
    // ==========================================
    // Returns { day: "YYYY-MM-DD", minutes } for a match in the event's timezone, or null if unscheduled
    function getMatchLocalDayAndMinutes(m) {
        const ms = parseTimestampMs(m.predictedTime || m.scheduledTime);
        if (!ms) return null;
        const timezone = m.eventTimezone || (settings && settings.timezone);
        const options = { year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" };
        let parts;
        try {
            parts = new Intl.DateTimeFormat("en-US", timezone ? { ...options, timeZone: timezone } : options).formatToParts(new Date(ms));
        } catch (_) {
            parts = new Intl.DateTimeFormat("en-US", options).formatToParts(new Date(ms));
        }
        const get = type => (parts.find(p => p.type === type) || {}).value || "0";
        return {
            day: `${get("year")}-${get("month")}-${get("day")}`,
            minutes: (Number(get("hour")) % 24) * 60 + Number(get("minute"))
        };
    }

    function populateBulkTimeDays() {
        if (!bulkTimeDay) return;
        const stage = bulkCompLevel ? bulkCompLevel.value : "all";
        const dayCounts = new Map();
        getFilteredMatches(stage).forEach(m => {
            const local = getMatchLocalDayAndMinutes(m);
            if (local) dayCounts.set(local.day, (dayCounts.get(local.day) || 0) + 1);
        });

        const days = Array.from(dayCounts.keys()).sort();
        if (days.length === 0) {
            bulkTimeDay.innerHTML = `<option value="">No scheduled match times</option>`;
            updateBulkTimeSummary();
            return;
        }

        const previous = bulkTimeDay.value;
        bulkTimeDay.innerHTML = days.map(day => {
            const [y, mo, d] = day.split("-").map(Number);
            const label = new Date(Date.UTC(y, mo - 1, d)).toLocaleDateString([], { weekday: "long", month: "short", day: "numeric", timeZone: "UTC" });
            return `<option value="${day}">${escapeHtml(label)} (${dayCounts.get(day)} matches)</option>`;
        }).join("");
        bulkTimeDay.value = days.includes(previous) ? previous : days[0];
        updateBulkTimeSummary();
    }

    function parseTimeInputMinutes(val) {
        const match = /^(\d{1,2}):(\d{2})/.exec(val || "");
        return match ? Number(match[1]) * 60 + Number(match[2]) : null;
    }

    // Matches (within the selected stage) whose start time falls inside [shift start, shift end)
    function getMatchesInTimeBlock() {
        const day = bulkTimeDay ? bulkTimeDay.value : "";
        const startMin = parseTimeInputMinutes(bulkTimeStart ? bulkTimeStart.value : "");
        const endMin = parseTimeInputMinutes(bulkTimeEnd ? bulkTimeEnd.value : "");
        if (!day || startMin === null || endMin === null || endMin <= startMin) return [];

        const stage = bulkCompLevel ? bulkCompLevel.value : "all";
        return getFilteredMatches(stage).filter(m => {
            const local = getMatchLocalDayAndMinutes(m);
            return local && local.day === day && local.minutes >= startMin && local.minutes < endMin;
        });
    }

    function updateBulkTimeSummary() {
        if (!bulkTimeSummary) return;
        const startMin = parseTimeInputMinutes(bulkTimeStart ? bulkTimeStart.value : "");
        const endMin = parseTimeInputMinutes(bulkTimeEnd ? bulkTimeEnd.value : "");
        if (startMin !== null && endMin !== null && endMin <= startMin) {
            bulkTimeSummary.textContent = "Shift end must be after shift start.";
            return;
        }
        const inBlock = getMatchesInTimeBlock();
        bulkTimeSummary.textContent = inBlock.length === 0
            ? (Obsidianscout.t ? Obsidianscout.t("assignments.bulk_time_none_shifts", "No matches scheduled in this time block. To assign before the schedule is out, use Planned Shifts.") : "No matches scheduled in this time block. To assign before the schedule is out, use Planned Shifts.")
            : `${inBlock.length} match(es): ${getMatchDisplayLabel(inBlock[0])} – ${getMatchDisplayLabel(inBlock[inBlock.length - 1])}`;
    }

    function updateBulkRangeModeView() {
        const isTime = bulkRangeMode && bulkRangeMode.value === "time";
        if (bulkMatchRangeField) bulkMatchRangeField.classList.toggle("hidden", isTime);
        if (bulkTimeBlockField) bulkTimeBlockField.classList.toggle("hidden", !isTime);
        if (isTime) updateBulkTimeSummary();
    }

    function getScoutUsageInMatch(match) {
        const matchAsgns = assignments.filter(a => isSameMatch(a, match) && a.assignedUserId);
        const userStats = new Map();
        matchAsgns.forEach(a => {
            const uid = a.assignedUserId;
            if (!userStats.has(uid)) {
                userStats.set(uid, { matchCount: 0, qualTeamCount: 0, qualAllianceCount: 0, total: 0 });
            }
            const s = userStats.get(uid);
            s.total++;
            if (a.assignmentType === "MATCH") {
                s.matchCount++;
            } else if (a.assignmentType === "QUALITATIVE") {
                if (a.targetTeamNumber) {
                    s.qualTeamCount++;
                } else {
                    s.qualAllianceCount++;
                }
            }
        });

        const isDualMap = new Map();
        userStats.forEach((s, uid) => {
            const isDual = (s.matchCount > 1) ||
                           (s.matchCount >= 1 && (s.qualTeamCount + s.qualAllianceCount) >= 1) ||
                           (s.qualTeamCount > 1) ||
                           (s.qualTeamCount >= 1 && s.qualAllianceCount >= 1);
            isDualMap.set(uid, isDual);
        });
        return isDualMap;
    }

    // ==========================================
    // TAB 1: MATCH SCOUTING MATRIX
    // ==========================================
    function renderMatrix() {
        const stage = matrixStageFilter ? matrixStageFilter.value : "all";
        const targetMatches = getFilteredMatches(stage);

        const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                      ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
        const defaultAllianceSize = isFtc ? 2 : 3;
        const redAllianceCount = Math.max(defaultAllianceSize, ...targetMatches.map(m => (m.redTeams || []).length));
        const blueAllianceCount = Math.max(defaultAllianceSize, ...targetMatches.map(m => (m.blueTeams || []).length));
        const totalCols = 2 + redAllianceCount + blueAllianceCount;

        const tableEl = document.getElementById("matrix-table");
        if (tableEl) {
            const thead = tableEl.querySelector("thead");
            if (thead) {
                thead.innerHTML = `
                    <tr>
                        <th style="width: 120px;" data-i18n="nav.matches">Match</th>
                        <th style="width: 100px;" data-i18n="assignments.th_time">Time</th>
                        <th style="text-align: center;" colspan="${redAllianceCount}">Red Alliance</th>
                        <th style="text-align: center;" colspan="${blueAllianceCount}">Blue Alliance</th>
                    </tr>
                `;
            }
        }

        if (matches.length === 0 || targetMatches.length === 0) {
            matrixTbody.innerHTML = `
                <tr>
                    <td colspan="${totalCols}" style="text-align: center; color: var(--muted); padding: 32px;">
                        No matches found for ${escapeHtml(currentEventKey)}. You can sync matches on the Events page or add an assignment manually.
                    </td>
                </tr>
            `;
            return;
        }

        const matchAssignments = assignments.filter(a => a.assignmentType === "MATCH");

        matrixTbody.innerHTML = targetMatches.map(m => {
            const displayTime = m.predictedTime || m.scheduledTime;
            const timeStr = displayTime ? formatTimeOnly(displayTime, m.eventTimezone || (settings && settings.timezone)) : "--";
            const offsetBadge = renderOffsetBadge(m.scheduleOffsetSeconds, m.scheduledTime, m.predictedTime, m.eventTimezone || (settings && settings.timezone));
            const matchLabel = getMatchDisplayLabel(m);
            const scoutUsage = getScoutUsageInMatch(m);

            // Red slots
            const redSlotsHtml = (m.redTeams || []).map((tKey, idx) => {
                const teamNum = parseTeamNum(tKey);
                return renderSlotCell(m, teamNum, "red", idx + 1, matchAssignments, scoutUsage);
            }).join("");

            // Blue slots
            const blueSlotsHtml = (m.blueTeams || []).map((tKey, idx) => {
                const teamNum = parseTeamNum(tKey);
                return renderSlotCell(m, teamNum, "blue", idx + 1, matchAssignments, scoutUsage);
            }).join("");

            return `
                <tr>
                    <td><strong>${escapeHtml(matchLabel)}</strong></td>
                    <td style="color:var(--muted); font-size:12px; white-space:nowrap;">${timeStr}${offsetBadge}</td>
                    ${redSlotsHtml}
                    ${blueSlotsHtml}
                </tr>
            `;
        }).join("");

        // Wire slot card click handlers
        matrixTbody.querySelectorAll(".slot-card").forEach(card => {
            card.addEventListener("click", () => {
                const aId = card.dataset.assignmentId;
                const mKey = card.dataset.matchKey;
                const mNum = card.dataset.matchNumber;
                const tNum = Number(card.dataset.teamNumber);
                const isConflict = card.dataset.isConflict === "true";

                if (isConflict) {
                    const match = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase()) || { matchKey: mKey, matchNumber: mNum };
                    const matching = assignments.filter(a =>
                        a.assignmentType === "MATCH" &&
                        isMatchForSlot(a, match, tNum)
                    );
                    openConflictResolverModal(matching, {
                        title: `${match ? getMatchDisplayLabel(match) : 'Match'} - Team #${tNum}`,
                        subtitle: `Multiple scouters are assigned to Team #${tNum} in this match. Select which scouter to keep or clear duplicate assignments.`
                    });
                } else if (aId) {
                    openEditAssignmentModal(aId);
                } else {
                    openNewAssignmentModal({
                        type: "MATCH",
                        matchKey: mKey,
                        matchNumber: mNum ? Number(mNum) : null,
                        targetTeamNumber: tNum ? Number(tNum) : null
                    });
                }
            });
        });
    }

    function renderSlotCell(match, teamNum, alliance, pos, matchAssignments, scoutUsage) {
        if (!teamNum) {
            return `<td><div class="slot-card ${alliance}"><span style="color:var(--muted)">Empty</span></div></td>`;
        }

        const matching = matchAssignments.filter(a => isMatchForSlot(a, match, teamNum));

        let cardClass = `slot-card ${alliance}`;
        let scouterHtml = `<span class="slot-scouter-name unassigned">+ Assign</span>`;
        let assignmentId = "";
        const isConflict = matching.length > 1;

        if (isConflict) {
            cardClass += " conflict";
            scouterHtml = `<span class="slot-scouter-name" style="color:#ef4444; font-weight:bold;">${matching.length} Scouts! (Fix)</span>`;
            assignmentId = matching[0].id;
        } else if (matching.length === 1) {
            const asgn = matching[0];
            assignmentId = asgn.id;
            cardClass += " assigned";
            if (asgn.status === "COMPLETED") cardClass += " completed";

            const u = users.find(usr => (usr.id || usr.userId) === asgn.assignedUserId);
            const name = asgn.assignedUserDisplayName || asgn.assignedUserName || (u ? (u.displayName || u.username) : "Scout");

            const isDual = asgn.assignedUserId && scoutUsage && Boolean(scoutUsage.get(asgn.assignedUserId));
            if (isDual) {
                cardClass += " warning-dual";
            }

            const iconSvg = isDual
                ? `<span title="Warning: Scout assigned to multiple targets in this match!" style="color:#f59e0b; margin-right:2px; font-weight:bold; font-size:11px;">⚠️</span>`
                : (asgn.status === "COMPLETED"
                    ? `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="3" style="flex-shrink:0;"><polyline points="20 6 9 17 4 12"></polyline></svg>`
                    : `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0; opacity:0.7;"><circle cx="12" cy="12" r="10"></circle></svg>`);

            scouterHtml = `<span class="slot-scouter-name">${iconSvg}<span>${escapeHtml(name)}${isDual ? ' (Dual)' : ''}</span></span>`;
        }

        return `
            <td>
                <div class="${cardClass}" data-match-key="${match.matchKey}" data-match-number="${match.matchNumber || ''}" data-team-number="${teamNum}" data-assignment-id="${assignmentId}" data-is-conflict="${isConflict ? 'true' : 'false'}">
                    <div class="slot-header">
                        <span class="slot-team-num">#${teamNum}</span>
                        <span style="font-size:10px; opacity:0.7; font-weight:700; text-transform:uppercase;">${alliance[0].toUpperCase()}${pos}</span>
                    </div>
                    ${scouterHtml}
                </div>
            </td>
        `;
    }

    // ==========================================
    // TAB: QUALITATIVE SCOUTING MATRIX
    // ==========================================
    function renderQualitativeMatrix() {
        if (!qualMatrixTbody) return;

        const stage = qualStageFilter ? qualStageFilter.value : "all";
        const targetMatches = getFilteredMatches(stage);

        const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                      ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
        const defaultAllianceSize = isFtc ? 2 : 3;
        const redAllianceCount = Math.max(defaultAllianceSize, ...targetMatches.map(m => (m.redTeams || []).length));
        const blueAllianceCount = Math.max(defaultAllianceSize, ...targetMatches.map(m => (m.blueTeams || []).length));
        const totalCols = 4 + redAllianceCount + blueAllianceCount;

        const qualTableEl = document.getElementById("qual-matrix-table");
        if (qualTableEl) {
            const thead = qualTableEl.querySelector("thead");
            if (thead) {
                thead.innerHTML = `
                    <tr>
                        <th style="width: 120px;" data-i18n="nav.matches">Match</th>
                        <th style="width: 100px;" data-i18n="assignments.th_time">Time</th>
                        <th style="text-align: center; width: 180px;" data-i18n="assignments.red_alliance_qual">Red Alliance</th>
                        <th style="text-align: center; width: 180px;" data-i18n="assignments.blue_alliance_qual">Blue Alliance</th>
                        <th style="text-align: center;" colspan="${redAllianceCount}" data-i18n="assignments.red_teams_qual">Red Teams (Qual)</th>
                        <th style="text-align: center;" colspan="${blueAllianceCount}" data-i18n="assignments.blue_teams_qual">Blue Teams (Qual)</th>
                    </tr>
                `;
            }
        }

        if (matches.length === 0 || targetMatches.length === 0) {
            qualMatrixTbody.innerHTML = `
                <tr>
                    <td colspan="${totalCols}" style="text-align: center; color: var(--muted); padding: 32px;">
                        No matches found for ${escapeHtml(currentEventKey)}.
                    </td>
                </tr>
            `;
            return;
        }

        const qualAssignments = assignments.filter(a => a.assignmentType === "QUALITATIVE");

        qualMatrixTbody.innerHTML = targetMatches.map(m => {
            const displayTime = m.predictedTime || m.scheduledTime;
            const timeStr = displayTime ? formatTimeOnly(displayTime, m.eventTimezone || (settings && settings.timezone)) : "--";
            const offsetBadge = renderOffsetBadge(m.scheduleOffsetSeconds, m.scheduledTime, m.predictedTime, m.eventTimezone || (settings && settings.timezone));
            const matchLabel = getMatchDisplayLabel(m);
            const scoutUsage = getScoutUsageInMatch(m);

            // Red Alliance Qual Slot
            const redAllianceSlotHtml = renderQualAllianceCell(m, "red", qualAssignments, scoutUsage);

            // Blue Alliance Qual Slot
            const blueAllianceSlotHtml = renderQualAllianceCell(m, "blue", qualAssignments, scoutUsage);

            // Red team qual slots
            const redTeamSlotsHtml = (m.redTeams || []).map((tKey, idx) => {
                const teamNum = parseTeamNum(tKey);
                return renderQualTeamCell(m, teamNum, "red", idx + 1, qualAssignments, scoutUsage);
            }).join("");

            // Blue team qual slots
            const blueTeamSlotsHtml = (m.blueTeams || []).map((tKey, idx) => {
                const teamNum = parseTeamNum(tKey);
                return renderQualTeamCell(m, teamNum, "blue", idx + 1, qualAssignments, scoutUsage);
            }).join("");

            return `
                <tr>
                    <td><strong>${escapeHtml(matchLabel)}</strong></td>
                    <td style="color:var(--muted); font-size:12px; white-space:nowrap;">${timeStr}${offsetBadge}</td>
                    ${redAllianceSlotHtml}
                    ${blueAllianceSlotHtml}
                    ${redTeamSlotsHtml}
                    ${blueTeamSlotsHtml}
                </tr>
            `;
        }).join("");

        // Wire slot card click handlers in qualitative matrix
        qualMatrixTbody.querySelectorAll(".slot-card").forEach(card => {
            card.addEventListener("click", () => {
                const aId = card.dataset.assignmentId;
                const mKey = card.dataset.matchKey;
                const mNum = card.dataset.matchNumber;
                const tNum = card.dataset.teamNumber ? Number(card.dataset.teamNumber) : null;
                const alliance = card.dataset.alliance;
                const isConflict = card.dataset.isConflict === "true";

                if (isConflict) {
                    const match = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase()) || { matchKey: mKey, matchNumber: mNum };

                    const matching = qualAssignments.filter(a => {
                        if (alliance && !tNum) {
                            return isMatchForQualAlliance(a, match, alliance);
                        } else if (tNum) {
                            return isMatchForSlot(a, match, tNum) || (alliance ? isMatchForQualAlliance(a, match, alliance) : false);
                        }
                        return isSameMatch(a, match);
                    });
                    openConflictResolverModal(matching, {
                        title: `${match ? getMatchDisplayLabel(match) : 'Match'} - ${alliance ? `${alliance.toUpperCase()} Alliance` : `Team #${tNum}`}`,
                        subtitle: `Multiple scouts are assigned to this qualitative slot or alliance. Select which scout to keep or clear duplicate assignments.`
                    });
                } else if (aId) {
                    openEditAssignmentModal(aId);
                } else if (alliance && !tNum) {
                    openNewAssignmentModal({
                        type: "QUALITATIVE",
                        matchKey: mKey,
                        matchNumber: mNum ? Number(mNum) : null,
                        alliance: alliance
                    });
                } else {
                    openNewAssignmentModal({
                        type: "QUALITATIVE",
                        matchKey: mKey,
                        matchNumber: mNum ? Number(mNum) : null,
                        targetTeamNumber: tNum
                    });
                }
            });
        });
    }

    function renderQualAllianceCell(match, alliance, qualAssignments, scoutUsage) {
        // Alliance-level assignments
        const matchingAlliance = qualAssignments.filter(a => isMatchForQualAlliance(a, match, alliance));

        // Team-level assignments for teams in this alliance
        const teamKeys = alliance.toLowerCase() === "red" ? (match.redTeams || []) : (match.blueTeams || []);
        const teamNums = teamKeys.map(k => parseTeamNum(k)).filter(Boolean);
        const matchingTeams = qualAssignments.filter(a =>
            isSameMatch(a, match) &&
            a.targetTeamNumber &&
            teamNums.includes(Number(a.targetTeamNumber))
        );

        let cardClass = `slot-card ${alliance}`;
        let scouterHtml = `<span class="slot-scouter-name unassigned">+ ${alliance.toUpperCase()} Qual</span>`;
        let assignmentId = "";
        const isConflict = matchingAlliance.length > 1 || (matchingAlliance.length >= 1 && matchingTeams.length > 0);

        if (isConflict) {
            cardClass += " conflict";
            if (matchingAlliance.length > 1) {
                scouterHtml = `<span class="slot-scouter-name" style="color:#ef4444; font-weight:bold;">${matchingAlliance.length} Scouts! (Fix)</span>`;
                assignmentId = matchingAlliance[0].id;
            } else {
                scouterHtml = `<span class="slot-scouter-name" style="color:#ef4444; font-weight:bold;">Conflict! Alliance + ${matchingTeams.length} Team Scout${matchingTeams.length === 1 ? '' : 's'}</span>`;
                assignmentId = matchingAlliance[0] ? matchingAlliance[0].id : matchingTeams[0].id;
            }
        } else if (matchingAlliance.length === 1) {
            const asgn = matchingAlliance[0];
            assignmentId = asgn.id;
            cardClass += " assigned";
            if (asgn.status === "COMPLETED") cardClass += " completed";

            const u = users.find(usr => (usr.id || usr.userId) === asgn.assignedUserId);
            const name = asgn.assignedUserDisplayName || asgn.assignedUserName || (u ? (u.displayName || u.username) : "Scout");

            const isDual = asgn.assignedUserId && scoutUsage && Boolean(scoutUsage.get(asgn.assignedUserId));
            if (isDual) {
                cardClass += " warning-dual";
            }

            const iconSvg = isDual
                ? `<span title="Warning: Scout assigned to multiple targets in this match!" style="color:#f59e0b; margin-right:2px; font-weight:bold; font-size:11px;">⚠️</span>`
                : (asgn.status === "COMPLETED"
                    ? `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="3" style="flex-shrink:0;"><polyline points="20 6 9 17 4 12"></polyline></svg>`
                    : `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0; opacity:0.7;"><circle cx="12" cy="12" r="10"></circle></svg>`);

            const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                          ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
            const allCount = teamNums.length || (isFtc ? 2 : 3);
            scouterHtml = `<span class="slot-scouter-name">${iconSvg}<span>${escapeHtml(name)} (All ${allCount})${isDual ? ' (Dual)' : ''}</span></span>`;
        } else if (matchingTeams.length > 0) {
            cardClass += " covered";
            scouterHtml = `<span class="slot-scouter-name" style="color:var(--muted); font-size:10px;">${matchingTeams.length}/${teamNums.length} Teams Scouted Indiv.</span>`;
        }

        return `
            <td>
                <div class="${cardClass}" data-match-key="${match.matchKey}" data-match-number="${match.matchNumber || ''}" data-alliance="${alliance}" data-assignment-id="${assignmentId}" data-is-conflict="${isConflict ? 'true' : 'false'}">
                    <div class="slot-header">
                        <span class="slot-team-num">${alliance.toUpperCase()} ALLIANCE</span>
                        <span style="font-size:10px; opacity:0.7; font-weight:700; text-transform:uppercase;">QUAL</span>
                    </div>
                    ${scouterHtml}
                </div>
            </td>
        `;
    }

    function renderQualTeamCell(match, teamNum, alliance, pos, qualAssignments, scoutUsage) {
        if (!teamNum) {
            return `<td><div class="slot-card ${alliance}"><span style="color:var(--muted)">Empty</span></div></td>`;
        }

        // Team-specific assignment
        const matchingTeam = qualAssignments.filter(a => isMatchForSlot(a, match, teamNum));

        // Alliance-level assignment for this team's alliance
        const matchingAlliance = qualAssignments.filter(a => isMatchForQualAlliance(a, match, alliance));

        let cardClass = `slot-card ${alliance}`;
        let scouterHtml = `<span class="slot-scouter-name unassigned">+ Qual</span>`;
        let assignmentId = "";
        const isConflict = matchingTeam.length > 1 || (matchingTeam.length >= 1 && matchingAlliance.length >= 1);

        if (isConflict) {
            cardClass += " conflict";
            if (matchingTeam.length > 1) {
                scouterHtml = `<span class="slot-scouter-name" style="color:#ef4444; font-weight:bold;">${matchingTeam.length} Scouts! (Fix)</span>`;
                assignmentId = matchingTeam[0].id;
            } else {
                scouterHtml = `<span class="slot-scouter-name" style="color:#ef4444; font-weight:bold;">Conflict! Team + Alliance Scout</span>`;
                assignmentId = matchingTeam[0].id;
            }
        } else if (matchingTeam.length === 1) {
            const asgn = matchingTeam[0];
            assignmentId = asgn.id;
            cardClass += " assigned";
            if (asgn.status === "COMPLETED") cardClass += " completed";

            const u = users.find(usr => (usr.id || usr.userId) === asgn.assignedUserId);
            const name = asgn.assignedUserDisplayName || asgn.assignedUserName || (u ? (u.displayName || u.username) : "Scout");

            const isDual = asgn.assignedUserId && scoutUsage && Boolean(scoutUsage.get(asgn.assignedUserId));
            if (isDual) {
                cardClass += " warning-dual";
            }

            const iconSvg = isDual
                ? `<span title="Warning: Scout assigned to multiple targets in this match!" style="color:#f59e0b; margin-right:2px; font-weight:bold; font-size:11px;">⚠️</span>`
                : (asgn.status === "COMPLETED"
                    ? `<svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#10b981" stroke-width="3" style="flex-shrink:0;"><polyline points="20 6 9 17 4 12"></polyline></svg>`
                    : `<svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="flex-shrink:0; opacity:0.7;"><circle cx="12" cy="12" r="10"></circle></svg>`);

            scouterHtml = `<span class="slot-scouter-name">${iconSvg}<span>${escapeHtml(name)}${isDual ? ' (Dual)' : ''}</span></span>`;
        } else if (matchingAlliance.length === 1) {
            // Covered by alliance scout! Grey out / mark covered
            const allianceAsgn = matchingAlliance[0];
            assignmentId = allianceAsgn.id;
            cardClass += " covered";
            if (allianceAsgn.status === "COMPLETED") cardClass += " completed";

            const u = users.find(usr => (usr.id || usr.userId) === allianceAsgn.assignedUserId);
            const name = allianceAsgn.assignedUserDisplayName || allianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "Scout");

            scouterHtml = `<span class="slot-scouter-name" style="color:var(--muted); font-size:11px;" title="Covered under ${alliance.toUpperCase()} Alliance qualitative assignment">Covered: @${escapeHtml(name)}</span>`;
        }

        return `
            <td>
                <div class="${cardClass}" data-match-key="${match.matchKey}" data-match-number="${match.matchNumber || ''}" data-team-number="${teamNum}" data-alliance="${alliance}" data-assignment-id="${assignmentId}" data-is-conflict="${isConflict ? 'true' : 'false'}">
                    <div class="slot-header">
                        <span class="slot-team-num">#${teamNum}</span>
                        <span style="font-size:10px; opacity:0.7; font-weight:700; text-transform:uppercase;">${alliance[0].toUpperCase()}${pos}</span>
                    </div>
                    ${scouterHtml}
                </div>
            </td>
        `;
    }

    // ==========================================
    // TAB: ASSIGNMENTS LIST VIEW
    // ==========================================
    function getFilteredAssignmentsList() {
        const typeVal = filterType ? filterType.value : "";
        const scouterVal = filterScouter ? filterScouter.value : "";
        const statusVal = filterStatus ? filterStatus.value : "";
        const searchVal = filterSearch ? filterSearch.value.trim().toLowerCase() : "";

        return assignments.filter(a => {
            if (typeVal && a.assignmentType !== typeVal) return false;
            if (scouterVal && a.assignedUserId !== scouterVal) return false;
            if (statusVal && a.status !== statusVal) return false;
            if (searchVal) {
                const targetText = `${a.matchKey || ''} ${a.matchNumber || ''} ${a.targetTeamNumber || ''} ${a.assignedUserName || ''} ${a.assignedUserDisplayName || ''} ${a.targetAlliance || ''} ${a.notes || ''}`.toLowerCase();
                if (!targetText.includes(searchVal)) return false;
            }
            return true;
        });
    }

    function renderAssignmentsList() {
        const filtered = getFilteredAssignmentsList();

        if (deleteAllBtn) {
            const isFiltered = Boolean(
                (filterType && filterType.value) ||
                (filterScouter && filterScouter.value) ||
                (filterStatus && filterStatus.value) ||
                (filterSearch && filterSearch.value.trim())
            );
            const labelText = isFiltered ? `Delete Filtered (${filtered.length})` : `Delete All (${assignments.length})`;
            deleteAllBtn.innerHTML = `
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <polyline points="3 6 5 6 21 6"></polyline>
                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                </svg>
                <span>${escapeHtml(labelText)}</span>
            `;
            deleteAllBtn.title = isFiltered
                ? `Delete only the ${filtered.length} currently filtered assignments`
                : `Delete all assignments for this event`;
        }

        if (filtered.length === 0) {
            assignmentsTbody.innerHTML = `
                <tr>
                    <td colspan="7" style="text-align: center; color: var(--muted); padding: 32px;">
                        No assignments found matching filters. Click "+ New Assignment" or "Bulk Match Wizard" to add some!
                    </td>
                </tr>
            `;
            return;
        }

        assignmentsTbody.innerHTML = filtered.map(a => {
            const user = users.find(u => (u.id || u.userId) === a.assignedUserId);
            const userName = a.assignedUserDisplayName || a.assignedUserName || (user ? (user.displayName || user.username) : "Unassigned");
            const userInitial = userName ? userName[0].toUpperCase() : "?";

            // Type badge
            let typeBadge = "";
            if (a.assignmentType === "MATCH") {
                typeBadge = `<span class="asgn-type-badge match"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="22" y1="12" x2="18" y2="12"></line><line x1="6" y1="12" x2="2" y2="12"></line><line x1="12" y1="6" x2="12" y2="2"></line><line x1="12" y1="22" x2="12" y2="18"></line></svg>Match</span>`;
            } else if (a.assignmentType === "PIT") {
                typeBadge = `<span class="asgn-type-badge pit"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.77-3.77a6 6 0 0 1-7.94 7.94l-6.91 6.91a2.12 2.12 0 0 1-3-3l6.91-6.91a6 6 0 0 1 7.94-7.94l-3.76 3.76z"/></svg>Pit</span>`;
            } else if (a.assignmentType === "QUALITATIVE") {
                typeBadge = `<span class="asgn-type-badge qual"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><circle cx="12" cy="12" r="3"/><line x1="22" y1="12" x2="18" y2="12"/><line x1="6" y1="12" x2="2" y2="12"/><line x1="12" y1="6" x2="12" y2="2"/><line x1="12" y1="22" x2="12" y2="18"/></svg>Qual</span>`;
            }

            // Target description
            let targetDesc = "";
            if (a.assignmentType === "MATCH") {
                const mNum = getMatchDisplayLabel(a) || (a.matchNumber ? `Match ${a.matchNumber}` : (a.matchKey || "Match"));
                targetDesc = `<strong>${escapeHtml(mNum)}</strong> &bull; Team <span class="badge secondary">#${a.targetTeamNumber}</span>`;
            } else if (a.assignmentType === "PIT") {
                const teamObj = teams.find(t => Number(t.teamNumber) === Number(a.targetTeamNumber));
                const nick = teamObj && teamObj.nickname ? ` <small style="color:var(--muted)">(${escapeHtml(teamObj.nickname)})</small>` : "";
                targetDesc = `Pit &bull; Team <strong>#${a.targetTeamNumber}</strong>${nick}`;
            } else if (a.assignmentType === "QUALITATIVE") {
                const mNum = getMatchDisplayLabel(a) || (a.matchNumber ? `Match ${a.matchNumber}` : (a.matchKey || "Match"));
                const alliance = a.allianceColor || a.targetAlliance;
                const targetText = alliance ? `${alliance.toUpperCase()} ALLIANCE` : (a.targetTeamNumber ? `Team #${a.targetTeamNumber}` : "Single Target");
                targetDesc = `Qual &bull; <strong>${escapeHtml(mNum)}</strong> (${targetText})`;
            }

            // Time
            const displayTime = a.predictedTime || a.scheduledTime;
            const timeStr = displayTime ? formatDateTime(displayTime, a.eventTimezone || (settings && settings.timezone)) : '<span style="color:var(--muted)">Not scheduled</span>';
            const offsetBadge = renderOffsetBadge(a.scheduleOffsetSeconds, a.scheduledTime, a.predictedTime, a.eventTimezone || (settings && settings.timezone));

            // Status badge
            let statusBadge = "";
            if (a.status === "COMPLETED") {
                statusBadge = `<span class="asgn-status-badge completed"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><polyline points="20 6 9 17 4 12"></polyline></svg>Done</span>`;
            } else if (a.status === "IN_PROGRESS") {
                statusBadge = `<span class="asgn-status-badge in-progress"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg>In Progress</span>`;
            } else if (a.status === "MISSED") {
                statusBadge = `<span class="asgn-status-badge" style="background:rgba(239,68,68,0.12); color:#ef4444; border:1px solid rgba(239,68,68,0.35);">Missed</span>`;
            } else {
                statusBadge = `<span class="asgn-status-badge pending"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/></svg>Pending</span>`;
            }

            // Conflicts & Persistent Dual-Assignment Detection
            const isConflicted = conflicts.some(c => (c.assignmentIds && c.assignmentIds.includes(a.id)) || (c.assignmentId === a.id));
            const otherMatchAssignments = (a.assignmentType === "MATCH" || a.assignmentType === "QUALITATIVE") && a.assignedUserId
                ? assignments.filter(other => other.id !== a.id && other.assignedUserId === a.assignedUserId && isSameMatch(other, a))
                : [];

            const isBothAlliancesQualPair = a.assignmentType === "QUALITATIVE" && !a.targetTeamNumber &&
                otherMatchAssignments.length === 1 &&
                otherMatchAssignments[0].assignmentType === "QUALITATIVE" &&
                !otherMatchAssignments[0].targetTeamNumber &&
                ((a.allianceColor || a.targetAlliance || "").toLowerCase() !== (otherMatchAssignments[0].allianceColor || otherMatchAssignments[0].targetAlliance || "").toLowerCase());

            const isDual = otherMatchAssignments.length > 0 && !isBothAlliancesQualPair;

            let conflictCell = `<span style="color:var(--muted); font-size:12px;">None</span>`;
            if (isConflicted) {
                conflictCell = `<span class="badge" style="background:rgba(239,68,68,0.15); color:#ef4444; border:1px solid rgba(239,68,68,0.4); cursor:pointer;" title="Click to resolve"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="vertical-align:-1px; margin-right:3px;"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>Slot Conflict</span>`;
            } else if (isDual) {
                conflictCell = `<span class="badge" style="background:rgba(245,158,11,0.15); color:#f59e0b; border:1px solid rgba(245,158,11,0.4);" title="Scout assigned to multiple slots in this match (Match & Qual / Dual Station)"><svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" style="vertical-align:-1px; margin-right:3px;"><path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"></path><line x1="12" y1="9" x2="12" y2="13"></line><line x1="12" y1="17" x2="12.01" y2="17"></line></svg>Dual Scout (${otherMatchAssignments.length + 1})</span>`;
            }

            return `
                <tr data-id="${a.id}">
                    <td>${typeBadge}</td>
                    <td>${targetDesc}</td>
                    <td>
                        <div class="scouter-avatar-tag">
                            <span class="avatar-initial">${userInitial}</span>
                            <span>${escapeHtml(userName)}</span>
                        </div>
                        ${a.notes ? `<div style="color:var(--muted); font-size:11px; margin-top:2px;">${escapeHtml(a.notes)}</div>` : ''}
                    </td>
                    <td style="font-size:12px; white-space:nowrap;">${timeStr}${offsetBadge}</td>
                    <td>${statusBadge}</td>
                    <td>${conflictCell}</td>
                    <td>
                        <div class="row gap-4">
                            <button class="btn ghost btn-icon-square btn-remind" data-id="${a.id}" title="Send Reminder Now" type="button" style="color:var(--primary);">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <line x1="22" y1="2" x2="11" y2="13"></line>
                                    <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
                                </svg>
                            </button>
                            <button class="btn ghost btn-icon-square btn-edit" data-id="${a.id}" title="Edit Assignment" type="button">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <path d="M12 20h9"></path>
                                    <path d="M16.5 3.5a2.121 2.121 0 0 1 3 3L7 19l-4 1 1-4L16.5 3.5z"></path>
                                </svg>
                            </button>
                            <button class="btn ghost btn-icon-square btn-delete" data-id="${a.id}" title="Delete Assignment" type="button" style="color:var(--danger, #ef4444);">
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                    <polyline points="3 6 5 6 21 6"></polyline>
                                    <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
                                </svg>
                            </button>
                        </div>
                    </td>
                </tr>
            `;
        }).join("");

        // Wire actions
        assignmentsTbody.querySelectorAll(".btn-remind").forEach(btn => {
            btn.addEventListener("click", () => sendManualReminder(btn.dataset.id));
        });
        assignmentsTbody.querySelectorAll(".btn-edit").forEach(btn => {
            btn.addEventListener("click", () => openEditAssignmentModal(btn.dataset.id));
        });
        assignmentsTbody.querySelectorAll(".btn-delete").forEach(btn => {
            btn.addEventListener("click", () => deleteAssignment(btn.dataset.id));
        });
    }

    // ==========================================
    // TAB 3: PIT SCOUTING COVERAGE
    // ==========================================
    function renderPitCoverage() {
        if (teams.length === 0) {
            pitCoverageGrid.innerHTML = `
                <p style="color: var(--muted); padding: 20px;">No teams synced for this event. Sync teams on the Events page.</p>
            `;
            return;
        }

        const pitAssignments = assignments.filter(a => a.assignmentType === "PIT");
        const search = (pitSearch.value || "").trim().toLowerCase();

        const filteredTeams = teams.filter(t => {
            if (!search) return true;
            return String(t.teamNumber).includes(search) ||
                (t.nickname || "").toLowerCase().includes(search) ||
                (t.name || "").toLowerCase().includes(search);
        });

        pitCoverageGrid.innerHTML = filteredTeams.map(t => {
            const matching = pitAssignments.filter(a => Number(a.targetTeamNumber) === Number(t.teamNumber));
            const units = groupPitAssignmentsByUnit(matching);
            let cardClass = "pit-team-card";
            let statusHtml = "";
            let membersHtml = "";
            let asgnId = "";
            const isConflict = units.length > 1;

            if (isConflict) {
                cardClass += " conflict";
                asgnId = matching[0].id;
                statusHtml = `<span class="badge" style="background:#ef4444; color:#fff; cursor:pointer;" title="Click to resolve conflict">${units.length} Assignees (Fix)</span>`;
            } else if (units.length === 1) {
                const unit = units[0];
                const asgn = unit[0];
                asgnId = asgn.id;
                if (unit.some(a => a.status === "COMPLETED")) {
                    cardClass += " scouted";
                    statusHtml = `<span class="status-badge complete"><svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" style="vertical-align:-1px; margin-right:2px;"><polyline points="20 6 9 17 4 12"></polyline></svg>Scouted</span>`;
                } else if (asgn.scoutGroup) {
                    statusHtml = `<span class="badge" style="background:var(--primary); color:#fff;">${escapeHtml(asgn.scoutGroup)}</span>`;
                } else {
                    const name = asgn.assignedUserDisplayName || asgn.assignedUserName || getUserDisplayName(asgn.assignedUserId);
                    statusHtml = `<span class="badge" style="background:var(--primary); color:#fff;">${escapeHtml(name)}</span>`;
                }
                if (asgn.scoutGroup) {
                    const names = unit.map(a => a.assignedUserDisplayName || a.assignedUsername || getUserDisplayName(a.assignedUserId));
                    membersHtml = `<div style="color:var(--muted); font-size:11px; margin-top:2px;">${escapeHtml(names.join(", "))}</div>`;
                }
            } else {
                statusHtml = `<span style="color:var(--warning); font-size:12px; font-weight:600;">Unassigned</span>`;
            }

            const displayName = t.nickname || (t.name && t.name !== `Team ${t.teamNumber}` ? t.name : "");

            return `
                <div class="${cardClass}">
                    <div class="row justify-between items-center">
                        <strong style="font-size:15px;">Team ${t.teamNumber}</strong>
                        ${statusHtml}
                    </div>
                    ${displayName ? `<div style="color:var(--muted); font-size:12px; white-space:nowrap; overflow:hidden; text-overflow:ellipsis;">${escapeHtml(displayName)}</div>` : ''}
                    ${membersHtml}
                    <div class="mt-4">
                        <button class="btn ghost btn-sm btn-pit-assign" data-team="${t.teamNumber}" data-assignment-id="${asgnId}" data-is-conflict="${isConflict ? 'true' : 'false'}" type="button" style="width:100%; font-size:12px; padding:6px 10px; justify-content:center;">
                            ${isConflict ? `Fix Conflict (${units.length} Assignees)` : (asgnId ? (units[0][0].scoutGroup ? 'Edit Group Assignment' : 'Edit Assignment') : '+ Assign Scouter / Group')}
                        </button>
                    </div>
                </div>
            `;
        }).join("");

        pitCoverageGrid.querySelectorAll(".btn-pit-assign").forEach(btn => {
            btn.addEventListener("click", () => {
                const aId = btn.dataset.assignmentId;
                const teamNum = Number(btn.dataset.team);
                const isConflict = btn.dataset.isConflict === "true";

                if (isConflict) {
                    const matching = assignments.filter(a => a.assignmentType === "PIT" && Number(a.targetTeamNumber) === teamNum);
                    openConflictResolverModal(matching, {
                        title: `Pit Scouting - Team #${teamNum}`,
                        subtitle: `Multiple scouts are assigned to Team #${teamNum} for pit scouting. Select which scout to keep or clear duplicate assignments.`
                    });
                } else if (aId) {
                    openEditAssignmentModal(aId);
                } else {
                    openNewAssignmentModal({
                        type: "PIT",
                        targetTeamNumber: teamNum
                    });
                }
            });
        });
    }

    // ==========================================
    // MODAL: CREATE / EDIT ASSIGNMENT
    // ==========================================
    function openNewAssignmentModal(defaults = {}) {
        assignmentForm.reset();
        clearSelectSearch(assignmentScouterSelect);
        assignmentIdInput.value = "";
        assignmentModalTitle.textContent = Obsidianscout.t ? Obsidianscout.t("assignments.create_title", "Create Assignment") : "Create Assignment";
        document.getElementById("field-status-container").classList.add("hidden");
        modalConflictBanner.classList.add("hidden");

        const type = defaults.type || "MATCH";
        assignmentTypeSelect.value = type;
        updateModalTypeView(type);

        populateModalMatchDropdowns();
        populateModalPitTeams();

        if (type === "MATCH") {
            if (defaults.matchKey) {
                assignmentMatchSelect.value = defaults.matchKey;
            }
            populateModalMatchTeams();
            if (defaults.targetTeamNumber) {
                assignmentTeamSelect.value = String(defaults.targetTeamNumber);
            }
        } else if (type === "PIT") {
            if (defaults.targetTeamNumber) {
                assignmentPitTeamSelect.value = String(defaults.targetTeamNumber);
            }
        } else if (type === "QUALITATIVE") {
            if (defaults.matchKey) {
                assignmentQualMatchSelect.value = defaults.matchKey;
            }
            updateQualTargetScopeOptions();
            if (defaults.alliance) {
                assignmentQualTargetType.value = defaults.alliance.toLowerCase();
                fieldQualTeam.classList.add("hidden");
            } else if (defaults.targetTeamNumber) {
                assignmentQualTargetType.value = "team";
                fieldQualTeam.classList.remove("hidden");
                populateModalQualTeams();
                assignmentQualTeamSelect.value = String(defaults.targetTeamNumber);
            } else {
                assignmentQualTargetType.value = "team";
                fieldQualTeam.classList.remove("hidden");
                populateModalQualTeams();
            }
        }

        checkLiveModalConflict();
        showModal("assignment-modal");
    }

    function openEditAssignmentModal(id) {
        const asgn = assignments.find(a => a.id === id);
        if (!asgn) return;

        assignmentForm.reset();
        clearSelectSearch(assignmentScouterSelect);
        assignmentIdInput.value = asgn.id;
        assignmentModalTitle.textContent = Obsidianscout.t ? Obsidianscout.t("assignments.edit_title", "Edit Assignment") : "Edit Assignment";
        document.getElementById("field-status-container").classList.remove("hidden");
        modalConflictBanner.classList.add("hidden");

        assignmentTypeSelect.value = asgn.assignmentType;
        updateModalTypeView(asgn.assignmentType);

        populateModalMatchDropdowns();
        populateModalPitTeams();

        if (asgn.assignmentType === "MATCH") {
            if (asgn.matchKey) assignmentMatchSelect.value = asgn.matchKey;
            populateModalMatchTeams();
            if (asgn.targetTeamNumber) assignmentTeamSelect.value = String(asgn.targetTeamNumber);
        } else if (asgn.assignmentType === "PIT") {
            if (asgn.targetTeamNumber) assignmentPitTeamSelect.value = String(asgn.targetTeamNumber);
            const group = asgn.scoutGroup ? getPitGroups().find(g => g.name.toLowerCase() === String(asgn.scoutGroup).toLowerCase()) : null;
            if (assignmentPitGroupSelect) assignmentPitGroupSelect.value = group ? group.id : "";
            updatePitGroupModeView();
        } else if (asgn.assignmentType === "QUALITATIVE") {
            if (asgn.matchKey) assignmentQualMatchSelect.value = asgn.matchKey;
            updateQualTargetScopeOptions();
            const alliance = (asgn.allianceColor || asgn.targetAlliance || "").toLowerCase();
            if (alliance === "red" || alliance === "blue") {
                assignmentQualTargetType.value = alliance;
                fieldQualTeam.classList.add("hidden");
            } else {
                assignmentQualTargetType.value = "team";
                fieldQualTeam.classList.remove("hidden");
                populateModalQualTeams();
                if (asgn.targetTeamNumber) assignmentQualTeamSelect.value = String(asgn.targetTeamNumber);
            }
        }

        assignmentScouterSelect.value = asgn.assignedUserId || "";
        assignmentStatusSelect.value = asgn.status || "PENDING";
        assignmentNotesInput.value = asgn.notes || "";

        checkLiveModalConflict();
        showModal("assignment-modal");
    }

    function updateModalTypeView(type) {
        formGroupMatch.classList.toggle("hidden", type !== "MATCH");
        formGroupPit.classList.toggle("hidden", type !== "PIT");
        formGroupQual.classList.toggle("hidden", type !== "QUALITATIVE");
        updatePitGroupModeView();
    }

    function populateModalMatchDropdowns() {
        const options = matches.map(m => `
            <option value="${m.matchKey}">${escapeHtml(getMatchDisplayLabel(m))}</option>
        `).join("");

        assignmentMatchSelect.innerHTML = `<option value="">Select match...</option>` + options;
        assignmentQualMatchSelect.innerHTML = `<option value="">Select match...</option>` + options;
    }

    function populateModalMatchTeams() {
        const mKey = assignmentMatchSelect.value;
        const selectedMatch = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase());
        if (!selectedMatch) {
            assignmentTeamSelect.innerHTML = `<option value="">Select a match first...</option>`;
            return;
        }

        const matchTeams = [];
        const seenTeams = new Set();
        (selectedMatch.redTeams || []).forEach((k, idx) => {
            const num = parseTeamNum(k);
            if (num && !seenTeams.has(num)) {
                seenTeams.add(num);
                matchTeams.push({ teamNumber: num, alliance: "Red", pos: idx + 1 });
            }
        });
        (selectedMatch.blueTeams || []).forEach((k, idx) => {
            const num = parseTeamNum(k);
            if (num && !seenTeams.has(num)) {
                seenTeams.add(num);
                matchTeams.push({ teamNumber: num, alliance: "Blue", pos: idx + 1 });
            }
        });

        const currentAsgnId = assignmentIdInput.value;
        const matchAsgns = assignments.filter(a => a.assignmentType === "MATCH" && isSameMatch(a, selectedMatch) && a.id !== currentAsgnId);

        assignmentTeamSelect.innerHTML = `<option value="">Select team...</option>` + matchTeams.map(t => {
            const existing = matchAsgns.find(a => Number(a.targetTeamNumber) === t.teamNumber);
            const userObj = existing ? users.find(u => (u.id || u.userId) === existing.assignedUserId) : null;
            const existingName = existing ? (existing.assignedUserDisplayName || existing.assignedUserName || (userObj ? (userObj.displayName || userObj.username) : 'scout')) : '';
            const annotation = existing ? ` (Assigned: @${existingName})` : ` (Unassigned)`;
            return `<option value="${t.teamNumber}">${t.alliance} ${t.pos} - Team ${t.teamNumber}${annotation}</option>`;
        }).join("");
    }

    function populateModalPitTeams() {
        const currentAsgnId = assignmentIdInput.value;
        const pitAsgns = assignments.filter(a => a.assignmentType === "PIT" && a.id !== currentAsgnId);

        assignmentPitTeamSelect.innerHTML = `<option value="">Select team...</option>` + teams.map(t => {
            const existing = pitAsgns.find(a => Number(a.targetTeamNumber) === Number(t.teamNumber));
            const userObj = existing ? users.find(u => (u.id || u.userId) === existing.assignedUserId) : null;
            const existingName = existing ? (existing.assignedUserDisplayName || existing.assignedUserName || (userObj ? (userObj.displayName || userObj.username) : 'scout')) : '';
            const annotation = existing ? ` (Assigned: @${existingName})` : ` (Unassigned)`;
            return `<option value="${t.teamNumber}">Team ${t.teamNumber}${t.nickname ? ` - ${escapeHtml(t.nickname)}` : ''}${annotation}</option>`;
        }).join("");
    }

    function updateQualTargetScopeOptions() {
        const mKey = assignmentQualMatchSelect.value;
        const currentAsgnId = assignmentIdInput.value;
        const selectedMatch = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase());

        const currentVal = assignmentQualTargetType.value || "team";
        const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                      ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
        const defaultAllianceSize = isFtc ? 2 : 3;

        if (!selectedMatch) {
            assignmentQualTargetType.innerHTML = `
                <option value="team">Single Team</option>
                <option value="red">Red Alliance (${defaultAllianceSize} teams)</option>
                <option value="blue">Blue Alliance (${defaultAllianceSize} teams)</option>
                <option value="both">Both Alliances (${defaultAllianceSize * 2} teams)</option>
            `;
            assignmentQualTargetType.value = currentVal;
            return;
        }

        const matchQualAsgns = assignments.filter(a =>
            a.assignmentType === "QUALITATIVE" &&
            isSameMatch(a, selectedMatch) &&
            a.id !== currentAsgnId
        );

        const redTeamNums = (selectedMatch.redTeams || []).map(k => parseTeamNum(k)).filter(Boolean);
        const blueTeamNums = (selectedMatch.blueTeams || []).map(k => parseTeamNum(k)).filter(Boolean);
        const redCount = redTeamNums.length || defaultAllianceSize;
        const blueCount = blueTeamNums.length || defaultAllianceSize;
        const totalCount = redCount + blueCount;

        const redAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "red"));
        const blueAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "blue"));
        const redTeamAsgns = matchQualAsgns.filter(a => a.targetTeamNumber && redTeamNums.includes(Number(a.targetTeamNumber)));
        const blueTeamAsgns = matchQualAsgns.filter(a => a.targetTeamNumber && blueTeamNums.includes(Number(a.targetTeamNumber)));

        let redLabel = `Red Alliance (${redCount} teams)`;
        if (redTeamAsgns.length > 0) {
            redLabel += ` ⚠️ (${redTeamAsgns.length} team(s) assigned)`;
        } else if (redAllianceAsgn) {
            const u = users.find(usr => (usr.id || usr.userId) === redAllianceAsgn.assignedUserId);
            const name = redAllianceAsgn.assignedUserDisplayName || redAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "scout");
            redLabel += ` (Assigned: @${name})`;
        }

        let blueLabel = `Blue Alliance (${blueCount} teams)`;
        if (blueTeamAsgns.length > 0) {
            blueLabel += ` ⚠️ (${blueTeamAsgns.length} team(s) assigned)`;
        } else if (blueAllianceAsgn) {
            const u = users.find(usr => (usr.id || usr.userId) === blueAllianceAsgn.assignedUserId);
            const name = blueAllianceAsgn.assignedUserDisplayName || blueAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "scout");
            blueLabel += ` (Assigned: @${name})`;
        }

        let bothLabel = `Both Alliances (${totalCount} teams)`;
        if (redAllianceAsgn || blueAllianceAsgn || redTeamAsgns.length > 0 || blueTeamAsgns.length > 0) {
            bothLabel += ` ⚠️ (Some slots assigned)`;
        }

        assignmentQualTargetType.innerHTML = `
            <option value="team">Single Team</option>
            <option value="red">${escapeHtml(redLabel)}</option>
            <option value="blue">${escapeHtml(blueLabel)}</option>
            <option value="both">${escapeHtml(bothLabel)}</option>
        `;
        assignmentQualTargetType.value = currentVal;
    }

    function populateModalQualTeams() {
        const mKey = assignmentQualMatchSelect.value;
        const currentAsgnId = assignmentIdInput.value;
        const selectedMatch = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase());
        if (!selectedMatch) {
            assignmentQualTeamSelect.innerHTML = `<option value="">Select match first...</option>`;
            return;
        }

        const matchQualAsgns = assignments.filter(a =>
            a.assignmentType === "QUALITATIVE" &&
            isSameMatch(a, selectedMatch) &&
            a.id !== currentAsgnId
        );

        const redAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "red"));
        const blueAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "blue"));

        const matchTeams = [];
        (selectedMatch.redTeams || []).forEach((k, idx) => {
            const num = parseTeamNum(k);
            if (num && !matchTeams.some(t => t.teamNumber === num)) {
                matchTeams.push({ teamNumber: num, alliance: "Red", pos: idx + 1 });
            }
        });
        (selectedMatch.blueTeams || []).forEach((k, idx) => {
            const num = parseTeamNum(k);
            if (num && !matchTeams.some(t => t.teamNumber === num)) {
                matchTeams.push({ teamNumber: num, alliance: "Blue", pos: idx + 1 });
            }
        });

        assignmentQualTeamSelect.innerHTML = `<option value="">Select team...</option>` + matchTeams.map(t => {
            const teamObj = teams.find(tm => tm.teamNumber === t.teamNumber);
            const nick = teamObj && teamObj.nickname ? ` (${escapeHtml(teamObj.nickname)})` : "";

            const existingIndiv = matchQualAsgns.find(a => Number(a.targetTeamNumber) === t.teamNumber);
            let annotation = "";

            if (existingIndiv) {
                const u = users.find(usr => (usr.id || usr.userId) === existingIndiv.assignedUserId);
                const name = existingIndiv.assignedUserDisplayName || existingIndiv.assignedUserName || (u ? (u.displayName || u.username) : "scout");
                annotation = ` (Assigned: @${name})`;
            } else if (t.alliance === "Red" && redAllianceAsgn) {
                const u = users.find(usr => (usr.id || usr.userId) === redAllianceAsgn.assignedUserId);
                const name = redAllianceAsgn.assignedUserDisplayName || redAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "scout");
                annotation = ` (Covered by Red Alliance @${name})`;
            } else if (t.alliance === "Blue" && blueAllianceAsgn) {
                const u = users.find(usr => (usr.id || usr.userId) === blueAllianceAsgn.assignedUserId);
                const name = blueAllianceAsgn.assignedUserDisplayName || blueAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "scout");
                annotation = ` (Covered by Blue Alliance @${name})`;
            } else {
                annotation = " (Unassigned)";
            }

            return `<option value="${t.teamNumber}">${t.alliance} ${t.pos} - Team ${t.teamNumber}${nick}${annotation}</option>`;
        }).join("");
    }

    // Real-time conflict warning banner inside modal
    function checkLiveModalConflict() {
        const type = assignmentTypeSelect.value;
        const currentId = assignmentIdInput.value;
        let conflictMsg = "";

        if (type === "MATCH") {
            const mKey = assignmentMatchSelect.value;
            const tNum = Number(assignmentTeamSelect.value);
            const selectedMatch = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase()) || { matchKey: mKey };
            if (mKey && tNum) {
                const duplicate = assignments.find(a =>
                    a.assignmentType === "MATCH" &&
                    isMatchForSlot(a, selectedMatch, tNum) &&
                    a.id !== currentId
                );
                if (duplicate) {
                    const u = users.find(usr => (usr.id || usr.userId) === duplicate.assignedUserId);
                    const name = duplicate.assignedUserDisplayName || duplicate.assignedUserName || (u ? (u.displayName || u.username) : "another scout");
                    conflictMsg = `Warning: Team ${tNum} in this match is already assigned to @${name}!`;
                }
            }
        } else if (type === "PIT") {
            const tNum = Number(assignmentPitTeamSelect.value);
            if (tNum) {
                const editing = currentId ? assignments.find(a => a.id === currentId) : null;
                const editingUnit = editing ? pitUnitKey(editing) : null;
                const chosenGroup = assignmentPitGroupSelect ? getPitGroups().find(g => g.id === assignmentPitGroupSelect.value) : null;
                const chosenUnit = chosenGroup ? `group:${chosenGroup.name.toLowerCase()}` : null;
                const duplicate = assignments.find(a =>
                    a.assignmentType === "PIT" &&
                    Number(a.targetTeamNumber) === tNum &&
                    a.id !== currentId &&
                    pitUnitKey(a) !== editingUnit &&
                    pitUnitKey(a) !== chosenUnit
                );
                if (duplicate) {
                    const u = users.find(usr => (usr.id || usr.userId) === duplicate.assignedUserId);
                    const name = duplicate.assignedUserDisplayName || duplicate.assignedUserName || (u ? (u.displayName || u.username) : "another scout");
                    conflictMsg = `Warning: Team ${tNum} is already assigned for Pit Scouting to @${name}!`;
                }
            }
        } else if (type === "QUALITATIVE") {
            const mKey = assignmentQualMatchSelect.value;
            const targetScope = assignmentQualTargetType.value;
            const selectedMatch = matches.find(m => (m.matchKey || "").toLowerCase() === (mKey || "").toLowerCase());

            if (selectedMatch) {
                const matchQualAsgns = assignments.filter(a =>
                    a.assignmentType === "QUALITATIVE" &&
                    isSameMatch(a, selectedMatch) &&
                    a.id !== currentId
                );

                const redTeamNums = (selectedMatch.redTeams || []).map(k => parseTeamNum(k)).filter(Boolean);
                const blueTeamNums = (selectedMatch.blueTeams || []).map(k => parseTeamNum(k)).filter(Boolean);

                const redAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "red"));
                const blueAllianceAsgn = matchQualAsgns.find(a => isMatchForQualAlliance(a, selectedMatch, "blue"));
                const redTeamAsgns = matchQualAsgns.filter(a => a.targetTeamNumber && redTeamNums.includes(Number(a.targetTeamNumber)));
                const blueTeamAsgns = matchQualAsgns.filter(a => a.targetTeamNumber && blueTeamNums.includes(Number(a.targetTeamNumber)));

                if (targetScope === "red") {
                    if (redAllianceAsgn) {
                        const u = users.find(usr => (usr.id || usr.userId) === redAllianceAsgn.assignedUserId);
                        const name = redAllianceAsgn.assignedUserDisplayName || redAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "another scout");
                        conflictMsg = `Warning: Red Alliance is already assigned to @${name}!`;
                    } else if (redTeamAsgns.length > 0) {
                        conflictMsg = `Warning: ${redTeamAsgns.length} individual team(s) on Red Alliance are already assigned!`;
                    }
                } else if (targetScope === "blue") {
                    if (blueAllianceAsgn) {
                        const u = users.find(usr => (usr.id || usr.userId) === blueAllianceAsgn.assignedUserId);
                        const name = blueAllianceAsgn.assignedUserDisplayName || blueAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "another scout");
                        conflictMsg = `Warning: Blue Alliance is already assigned to @${name}!`;
                    } else if (blueTeamAsgns.length > 0) {
                        conflictMsg = `Warning: ${blueTeamAsgns.length} individual team(s) on Blue Alliance are already assigned!`;
                    }
                } else if (targetScope === "both") {
                    if (redAllianceAsgn || blueAllianceAsgn || redTeamAsgns.length > 0 || blueTeamAsgns.length > 0) {
                        conflictMsg = `Warning: One or more alliances/teams in this match already have assigned qualitative scouts!`;
                    }
                } else if (targetScope === "team") {
                    const tNum = Number(assignmentQualTeamSelect.value);
                    if (tNum) {
                        const existingIndiv = matchQualAsgns.find(a => Number(a.targetTeamNumber) === tNum);
                        if (existingIndiv) {
                            const u = users.find(usr => (usr.id || usr.userId) === existingIndiv.assignedUserId);
                            const name = existingIndiv.assignedUserDisplayName || existingIndiv.assignedUserName || (u ? (u.displayName || u.username) : "another scout");
                            conflictMsg = `Warning: Team ${tNum} is already individually assigned to @${name}!`;
                        } else if (redTeamNums.includes(tNum) && redAllianceAsgn) {
                            const u = users.find(usr => (usr.id || usr.userId) === redAllianceAsgn.assignedUserId);
                            const name = redAllianceAsgn.assignedUserDisplayName || redAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "alliance scout");
                            conflictMsg = `Warning: Team ${tNum} is already covered under Red Alliance assignment (@${name})!`;
                        } else if (blueTeamNums.includes(tNum) && blueAllianceAsgn) {
                            const u = users.find(usr => (usr.id || usr.userId) === blueAllianceAsgn.assignedUserId);
                            const name = blueAllianceAsgn.assignedUserDisplayName || blueAllianceAsgn.assignedUserName || (u ? (u.displayName || u.username) : "alliance scout");
                            conflictMsg = `Warning: Team ${tNum} is already covered under Blue Alliance assignment (@${name})!`;
                        }
                    }
                }
            }
        }

        if (conflictMsg) {
            modalConflictText.textContent = conflictMsg;
            modalConflictBanner.classList.remove("hidden");
        } else {
            modalConflictBanner.classList.add("hidden");
        }
    }

    // Assigning a group (or editing a grouped row) replaces the whole unit on that pit team
    async function savePitGroupAssignment(pitGroup, editingRow, scouterId, notes) {
        const targetTeamNumber = Number(assignmentPitTeamSelect.value) || null;
        if (!targetTeamNumber) {
            Obsidianscout.showToast("Please select a team to pit scout", "error");
            return;
        }
        if (!pitGroup && !scouterId) {
            Obsidianscout.showToast("Please select a scouter or a pit group", "error");
            return;
        }

        const replaceUnits = new Set();
        if (editingRow) replaceUnits.add(pitUnitKey(editingRow));
        if (pitGroup) replaceUnits.add(`group:${pitGroup.name.toLowerCase()}`);
        const toReplace = assignments.filter(a =>
            a.assignmentType === "PIT" &&
            (Number(a.targetTeamNumber) === targetTeamNumber || (editingRow && Number(a.targetTeamNumber) === Number(editingRow.targetTeamNumber))) &&
            replaceUnits.has(pitUnitKey(a))
        );

        const team = teams.find(t => Number(t.teamNumber) === targetTeamNumber);
        const nickname = team ? (team.nickname || team.name) : "";
        const baseNote = notes || (nickname ? `Pit Scouting - ${nickname}` : "Pit Scouting");
        const items = (pitGroup ? pitGroup.memberIds : [scouterId]).map(uid => ({
            assignedUserId: uid,
            assignmentType: "PIT",
            targetTeamNumber,
            scoutGroup: pitGroup ? pitGroup.name : null,
            notes: pitGroup ? `${baseNote} (Group: ${pitGroup.name})` : baseNote
        }));

        try {
            await Promise.all(toReplace.map(a =>
                Obsidianscout.request(`/api/assignments/${encodeURIComponent(a.id)}`, { method: "DELETE" })
            ));
            await Obsidianscout.request("/api/assignments/bulk", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ eventKey: currentEventKey, assignments: items })
            });
            Obsidianscout.showToast(pitGroup
                ? `Assigned group "${pitGroup.name}" (${items.length} scouts) to Team ${targetTeamNumber}`
                : "Pit assignment saved", "success");
            hideModal("assignment-modal");
            await loadEventData();
        } catch (err) {
            console.error("Save pit group assignment failed:", err);
            Obsidianscout.showToast(err.message || "Failed to save pit group assignment", "error");
            await loadEventData();
        }
    }

    // Save single assignment
    async function saveAssignment(e) {
        e.preventDefault();

        const id = assignmentIdInput.value;
        const type = assignmentTypeSelect.value;
        const scouterId = assignmentScouterSelect.value;
        const notes = assignmentNotesInput.value.trim() || null;
        const status = id ? assignmentStatusSelect.value : "PENDING";

        const pitGroup = type === "PIT" && assignmentPitGroupSelect
            ? getPitGroups().find(g => g.id === assignmentPitGroupSelect.value)
            : null;
        const editingRow = id ? assignments.find(a => a.id === id) : null;
        if (type === "PIT" && (pitGroup || (editingRow && editingRow.scoutGroup))) {
            await savePitGroupAssignment(pitGroup, editingRow, scouterId, notes);
            return;
        }

        if (!scouterId) {
            Obsidianscout.showToast("Please select a scouter", "error");
            return;
        }

        let matchKey = null;
        let matchNumber = null;
        let compLevel = null;
        let targetTeamNumber = null;
        let targetAlliance = null;
        let scheduledTime = null;

        if (type === "MATCH") {
            matchKey = assignmentMatchSelect.value || null;
            if (!matchKey) {
                Obsidianscout.showToast("Please select a match", "error");
                return;
            }
            targetTeamNumber = Number(assignmentTeamSelect.value) || null;
            if (!targetTeamNumber) {
                Obsidianscout.showToast("Please select a team in the match", "error");
                return;
            }
            const m = matches.find(match => match.matchKey === matchKey);
            if (m) {
                matchNumber = m.matchNumber ? Number(m.matchNumber) : null;
                compLevel = m.compLevel || "qm";
                scheduledTime = m.scheduledTime || null;
            }
        } else if (type === "PIT") {
            targetTeamNumber = Number(assignmentPitTeamSelect.value) || null;
            if (!targetTeamNumber) {
                Obsidianscout.showToast("Please select a team to pit scout", "error");
                return;
            }
        } else if (type === "QUALITATIVE") {
            matchKey = assignmentQualMatchSelect.value || null;
            if (!matchKey) {
                Obsidianscout.showToast("Please select a match", "error");
                return;
            }
            const scope = assignmentQualTargetType.value;
            const m = matches.find(match => match.matchKey === matchKey);
            if (m) {
                matchNumber = m.matchNumber ? Number(m.matchNumber) : null;
                compLevel = m.compLevel || "qm";
                scheduledTime = m.scheduledTime || null;
            }

            if (scope === "team") {
                targetTeamNumber = Number(assignmentQualTeamSelect.value) || null;
                if (!targetTeamNumber) {
                    Obsidianscout.showToast("Please select a team", "error");
                    return;
                }
            } else if (scope === "both") {
                // Bulk create for both RED and BLUE alliances
                try {
                    const bulkItems = [
                        {
                            assignedUserId: scouterId,
                            assignmentType: "QUALITATIVE",
                            matchKey,
                            matchNumber,
                            compLevel: compLevel || "qm",
                            allianceColor: "RED",
                            targetAlliance: "RED",
                            scheduledTime,
                            notes: notes ? `${notes} (Red Alliance)` : "Qualitative scouting for RED alliance"
                        },
                        {
                            assignedUserId: scouterId,
                            assignmentType: "QUALITATIVE",
                            matchKey,
                            matchNumber,
                            compLevel: compLevel || "qm",
                            allianceColor: "BLUE",
                            targetAlliance: "BLUE",
                            scheduledTime,
                            notes: notes ? `${notes} (Blue Alliance)` : "Qualitative scouting for BLUE alliance"
                        }
                    ];

                    await Obsidianscout.request("/api/assignments/bulk", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            eventKey: currentEventKey,
                            assignments: bulkItems
                        })
                    });

                    Obsidianscout.showToast("Created qualitative assignments for both alliances!", "success");
                    hideModal("assignment-modal");
                    await loadEventData();
                    return;
                } catch (err) {
                    console.error("Save both alliances qualitative failed:", err);
                    Obsidianscout.showToast(err.message || "Failed to save assignments", "error");
                    return;
                }
            } else {
                targetAlliance = scope.toUpperCase();
            }
        }

        const payload = {
            eventKey: currentEventKey,
            assignmentType: type,
            assignedUserId: scouterId,
            matchKey,
            matchNumber,
            compLevel,
            targetTeamNumber,
            allianceColor: targetAlliance ? targetAlliance.toUpperCase() : null,
            targetAlliance: targetAlliance ? targetAlliance.toUpperCase() : null,
            scheduledTime,
            status,
            notes
        };

        try {
            if (id) {
                await Obsidianscout.request(`/api/assignments/${encodeURIComponent(id)}`, {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
                Obsidianscout.showToast("Assignment updated successfully", "success");
            } else {
                await Obsidianscout.request("/api/assignments", {
                    method: "POST",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(payload)
                });
                Obsidianscout.showToast("Assignment created successfully", "success");
            }

            hideModal("assignment-modal");
            await loadEventData();
        } catch (err) {
            console.error("Save assignment failed:", err);
            Obsidianscout.showToast(err.message || "Failed to save assignment", "error");
        }
    }

    async function deleteAssignment(id) {
        if (!confirm("Are you sure you want to delete this assignment?")) return;

        try {
            await Obsidianscout.request(`/api/assignments/${encodeURIComponent(id)}`, {
                method: "DELETE"
            });
            Obsidianscout.showToast("Assignment deleted", "success");
            await loadEventData();
        } catch (err) {
            console.error("Delete assignment failed:", err);
            Obsidianscout.showToast("Failed to delete assignment", "error");
        }
    }

    async function sendManualReminder(id) {
        try {
            const res = await Obsidianscout.request(`/api/assignments/${encodeURIComponent(id)}/remind`, {
                method: "POST"
            });
            Obsidianscout.showToast(res.message || "Reminder sent!", "success");
        } catch (err) {
            console.error("Send reminder failed:", err);
            Obsidianscout.showToast(err.message || "Failed to dispatch reminder", "error");
        }
    }

    // ==========================================
    // CONFLICT RESOLVER / MULTI-SCOUT QUICK FIX
    // ==========================================
    let currentConflictedAssignments = [];

    function openConflictResolverModal(conflictedList, slotInfo = {}) {
        currentConflictedAssignments = conflictedList || [];
        if (currentConflictedAssignments.length === 0) {
            Obsidianscout.showToast("No conflicts to resolve in this slot", "info");
            return;
        }

        conflictModalTitle.textContent = slotInfo.title || "Resolve Assignment Conflict";
        conflictModalSubtitle.textContent = slotInfo.subtitle || "Multiple scouters are assigned to this slot. Choose which scouter to keep or delete duplicates.";

        conflictScoutersList.innerHTML = currentConflictedAssignments.map((a) => {
            const u = users.find(usr => (usr.id || usr.userId) === a.assignedUserId);
            const name = a.assignedUserDisplayName || a.assignedUserName || (u ? (u.displayName || u.username) : "Scout");
            const role = (u && u.role) ? u.role : "";
            const status = a.status || "PENDING";
            const targetInfo = a.assignmentType === "QUALITATIVE"
                ? (a.targetAlliance ? `${a.targetAlliance.toUpperCase()} Alliance` : `Team #${a.targetTeamNumber}`)
                : (a.targetTeamNumber ? `Team #${a.targetTeamNumber}` : "");

            return `
                <div style="display:flex; justify-content:space-between; align-items:center; padding:10px 14px; background:var(--surface-2); border:1px solid var(--border-subtle); border-radius:6px;">
                    <div>
                        <div style="font-weight:600; font-size:13px; display:flex; align-items:center; gap:6px;">
                            <span>${escapeHtml(name)}</span>
                            ${role ? `<span class="badge secondary" style="font-size:10px;">${escapeHtml(role)}</span>` : ''}
                            <span class="status-badge" style="font-size:10px;">${escapeHtml(status)}</span>
                            ${targetInfo ? `<span class="badge" style="font-size:10px; background:var(--surface);">${escapeHtml(targetInfo)}</span>` : ''}
                        </div>
                        ${a.notes ? `<div style="font-size:11px; color:var(--muted); margin-top:2px;">Note: ${escapeHtml(a.notes)}</div>` : ''}
                    </div>
                    <button type="button" class="btn btn-sm btn-keep-scout" data-keep-id="${a.id}" style="font-size:12px; padding:4px 10px;">
                        Keep This Scout
                    </button>
                </div>
            `;
        }).join("");

        conflictScoutersList.querySelectorAll(".btn-keep-scout").forEach(btn => {
            btn.addEventListener("click", () => keepSingleAssignmentInConflict(btn.dataset.keepId));
        });

        showModal("conflict-resolver-modal");
    }

    async function keepSingleAssignmentInConflict(keepId) {
        const toDelete = currentConflictedAssignments.filter(a => a.id !== keepId);
        if (toDelete.length === 0) {
            hideModal("conflict-resolver-modal");
            return;
        }

        try {
            await Promise.all(toDelete.map(a =>
                Obsidianscout.request(`/api/assignments/${encodeURIComponent(a.id)}`, { method: "DELETE" })
            ));
            Obsidianscout.showToast("Conflict resolved! Kept selected scouter.", "success");
            hideModal("conflict-resolver-modal");
            await loadEventData();
        } catch (err) {
            console.error("Failed to resolve conflict:", err);
            Obsidianscout.showToast("Failed to remove duplicate assignments", "error");
        }
    }

    async function clearAllInConflictSlot() {
        if (!currentConflictedAssignments || currentConflictedAssignments.length === 0) return;
        if (!confirm(`Delete all ${currentConflictedAssignments.length} assignments in this slot?`)) return;

        try {
            await Promise.all(currentConflictedAssignments.map(a =>
                Obsidianscout.request(`/api/assignments/${encodeURIComponent(a.id)}`, { method: "DELETE" })
            ));
            Obsidianscout.showToast("Cleared all assignments in slot.", "success");
            hideModal("conflict-resolver-modal");
            await loadEventData();
        } catch (err) {
            console.error("Failed to clear slot:", err);
            Obsidianscout.showToast("Failed to delete assignments", "error");
        }
    }

    function findAssignmentDiscrepancies() {
        const toDeleteIds = new Set();
        const conflictDetails = {
            slotDuplicates: [],
            dualScouterOverlaps: [],
            redundantQual: []
        };

        // 1. Same-slot duplicate assignments
        const slotMap = new Map();
        assignments.forEach(a => {
            let slotKey = "";
            const lvl = (a.compLevel || "qm").toLowerCase();
            const mKey = (a.matchKey || `${lvl}_${a.matchNumber || ''}`).toLowerCase();
            if (a.assignmentType === "MATCH") {
                slotKey = `MATCH:${mKey}:${a.targetTeamNumber}`;
            } else if (a.assignmentType === "PIT") {
                slotKey = `PIT:${a.targetTeamNumber}`;
            } else if (a.assignmentType === "QUALITATIVE") {
                const alliance = (a.allianceColor || a.targetAlliance || "").toLowerCase();
                const isTeamLevel = a.targetTeamNumber !== null && a.targetTeamNumber !== undefined && String(a.targetTeamNumber).trim() !== "";
                slotKey = `QUAL:${mKey}:${isTeamLevel ? `team_${a.targetTeamNumber}` : `alliance_${alliance}`}`;
            }
            if (slotKey) {
                if (!slotMap.has(slotKey)) slotMap.set(slotKey, []);
                slotMap.get(slotKey).push(a);
            }
        });

        slotMap.forEach((list, slotKey) => {
            if (list.length > 1) {
                // Priority: Keep COMPLETED, then IN_PROGRESS, then with notes, then oldest
                const sorted = [...list].sort((x, y) => {
                    const score = (item) => (item.status === "COMPLETED" ? 3 : (item.status === "IN_PROGRESS" ? 2 : 1));
                    if (score(x) !== score(y)) return score(y) - score(x);
                    if (Boolean(x.notes) !== Boolean(y.notes)) return x.notes ? -1 : 1;
                    return 0;
                });
                const keep = sorted[0];
                const duplicates = keep.assignmentType === "PIT"
                    ? sorted.filter(d => pitUnitKey(d) !== pitUnitKey(keep))
                    : sorted.slice(1);
                duplicates.forEach(d => {
                    toDeleteIds.add(d.id);
                    conflictDetails.slotDuplicates.push({ kept: keep, deleted: d, slotKey });
                });
            }
        });

        // 2. Redundant qualitative scouting in the same match:
        // Alliance-level assignment + individual team assignments on the same alliance
        matches.forEach(match => {
            const matchQual = assignments.filter(a =>
                !toDeleteIds.has(a.id) &&
                a.assignmentType === "QUALITATIVE" &&
                isSameMatch(a, match)
            );

            ["red", "blue"].forEach(alliance => {
                const allianceAsgn = matchQual.find(a => (a.allianceColor || a.targetAlliance || "").toLowerCase() === alliance);
                if (!allianceAsgn) return;

                const teamKeys = alliance === "red" ? (match.redTeams || []) : (match.blueTeams || []);
                const teamNums = teamKeys.map(k => parseTeamNum(k)).filter(Boolean);
                const teamAsgns = matchQual.filter(a => a.targetTeamNumber && teamNums.includes(Number(a.targetTeamNumber)));

                if (teamAsgns.length > 0) {
                    const completedTeam = teamAsgns.find(t => t.status === "COMPLETED");
                    if (completedTeam && allianceAsgn.status !== "COMPLETED") {
                        toDeleteIds.add(allianceAsgn.id);
                        conflictDetails.redundantQual.push({ kept: completedTeam, deleted: allianceAsgn });
                    } else {
                        teamAsgns.forEach(t => {
                            if (!toDeleteIds.has(t.id)) {
                                toDeleteIds.add(t.id);
                                conflictDetails.redundantQual.push({ kept: allianceAsgn, deleted: t });
                            }
                        });
                    }
                }
            });
        });

        // 3. Dual-scouter match overlaps:
        // A single user assigned multiple times in the same match
        matches.forEach(match => {
            const activeMatchAsgns = assignments.filter(a => !toDeleteIds.has(a.id) && isSameMatch(a, match) && a.assignedUserId);
            const userGroups = new Map();
            activeMatchAsgns.forEach(a => {
                if (!userGroups.has(a.assignedUserId)) userGroups.set(a.assignedUserId, []);
                userGroups.get(a.assignedUserId).push(a);
            });

            userGroups.forEach((userAsgns) => {
                // Allow user to scout both RED and BLUE alliance in qualitative if assigned as entire alliance pair
                const isBothAlliancesQualPair = userAsgns.length === 2 &&
                    userAsgns.every(a => a.assignmentType === "QUALITATIVE" && !a.targetTeamNumber) &&
                    ((userAsgns[0].allianceColor || userAsgns[0].targetAlliance || "").toLowerCase() !== (userAsgns[1].allianceColor || userAsgns[1].targetAlliance || "").toLowerCase());

                if (userAsgns.length > 1 && !isBothAlliancesQualPair) {
                    const sorted = [...userAsgns].sort((x, y) => {
                        if ((x.status === "COMPLETED") !== (y.status === "COMPLETED")) return x.status === "COMPLETED" ? -1 : 1;
                        if ((x.assignmentType === "MATCH") !== (y.assignmentType === "MATCH")) return x.assignmentType === "MATCH" ? -1 : 1;
                        if ((x.status === "IN_PROGRESS") !== (y.status === "IN_PROGRESS")) return x.status === "IN_PROGRESS" ? -1 : 1;
                        return 0;
                    });
                    const keep = sorted[0];
                    const extras = sorted.slice(1);
                    extras.forEach(extra => {
                        toDeleteIds.add(extra.id);
                        conflictDetails.dualScouterOverlaps.push({ kept: keep, deleted: extra, match });
                    });
                }
            });
        });

        return {
            idsToDelete: Array.from(toDeleteIds),
            details: conflictDetails
        };
    }

    async function autoResolveAllConflicts() {
        if (!currentEventKey) {
            Obsidianscout.showToast("No active event selected", "error");
            return;
        }

        const confirmMsg = "Auto-resolve will analyze all assignments on the server for duplicate slot assignments, qual alliance/team redundancies, and dual-scouter overlaps in the same match, and safely clear invalid entries. Proceed?";
        if (!confirm(confirmMsg)) return;

        try {
            const res = await Obsidianscout.request("/api/assignments/auto-resolve-conflicts", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ eventKey: currentEventKey })
            });

            const deletedCount = res.deletedCount || 0;
            if (deletedCount === 0) {
                Obsidianscout.showToast("No assignment conflicts or discrepancies found!", "info");
            } else {
                Obsidianscout.showToast(`Auto-resolved ${deletedCount} conflict item(s)!`, "success");
            }
            hideModal("conflict-resolver-modal");
            await loadEventData();
        } catch (err) {
            console.error("Failed to auto-resolve conflicts:", err);
            Obsidianscout.showToast(err.message || "Failed to auto-resolve all conflicts", "error");
        }
    }

    // ==========================================
    // AUTO-ASSIGN PIT SCOUTERS
    // ==========================================
    async function generatePitAutoAssignments(e) {
        e.preventDefault();

        const scope = pitAutoScope ? pitAutoScope.value : "unassigned";
        const overwrite = pitAutoOverwrite ? pitAutoOverwrite.checked : false;

        const selectedScouterCheckboxes = pitAutoScoutersContainer ? Array.from(pitAutoScoutersContainer.querySelectorAll("input[name='pit_auto_scouter']:checked")) : [];
        const pitGroups = getCheckedPitGroups(pitAutoGroupsContainer);
        // Group members are always part of the pool, even if their own checkbox is unchecked
        const scouterIds = Array.from(new Set([
            ...selectedScouterCheckboxes.map(cb => cb.value),
            ...pitGroups.flatMap(g => g.memberIds)
        ]));

        if (scouterIds.length === 0) {
            Obsidianscout.showToast("Please select at least one pit scouter or group", "error");
            return;
        }

        try {
            const res = await Obsidianscout.request("/api/assignments/auto-generate", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    eventKey: currentEventKey,
                    assignmentType: "PIT_AUTO",
                    scouterUserIds: scouterIds,
                    pitGroups: pitGroups.map(g => ({ name: g.name, memberIds: g.memberIds })),
                    overwrite: overwrite,
                    pitScope: scope
                })
            });

            Obsidianscout.showToast(pitGroups.length
                ? `Created ${res.createdCount || 0} pit assignments using ${pitGroups.length} group${pitGroups.length === 1 ? '' : 's'}!`
                : `Successfully assigned ${res.createdCount || 0} teams across ${scouterIds.length} pit scouters!`, "success");
            hideModal("pit-auto-modal");
            await loadEventData();
        } catch (err) {
            console.error("Pit auto-assignment failed:", err);
            Obsidianscout.showToast(err.message || "Failed to generate pit auto assignments", "error");
        }
    }

    // ==========================================
    // BULK MATCH WIZARD
    // ==========================================
    function updateBulkTypeOptions() {
        if (!bulkAssignmentType) return;
        const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                      ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
        const redCount = isFtc ? 2 : 3;
        const blueCount = isFtc ? 2 : 3;
        const totalCount = redCount + blueCount;

        const currentVal = bulkAssignmentType.value || "MATCH";
        bulkAssignmentType.innerHTML = `
            <option value="MATCH">Match Scouting (${totalCount} Robot Stations: Red 1-${redCount}, Blue 1-${blueCount})</option>
            <option value="QUALITATIVE_BOTH">Qualitative Scouting (Both Alliances: Red & Blue)</option>
            <option value="QUALITATIVE_RED">Qualitative Scouting (Red Alliance Only)</option>
            <option value="QUALITATIVE_BLUE">Qualitative Scouting (Blue Alliance Only)</option>
            <option value="QUALITATIVE_TEAMS">Qualitative Scouting (Individual Match Teams: ${totalCount} Teams)</option>
            <option value="PIT_AUTO">Pit Scouting (Auto-Distribute Attending Teams)</option>
        `;
        bulkAssignmentType.value = currentVal;
    }

    async function generateBulkAssignments(e) {
        e.preventDefault();

        const overwrite = bulkOverwrite ? bulkOverwrite.checked : false;
        const bulkType = bulkAssignmentType ? bulkAssignmentType.value : "MATCH";

        const selectedScouterCheckboxes = Array.from(bulkScoutersContainer.querySelectorAll("input[name='bulk_scouter']:checked"));
        const pitGroups = bulkType === "PIT_AUTO" ? getCheckedPitGroups(bulkPitGroupsContainer) : [];
        const scouterIds = Array.from(new Set([
            ...selectedScouterCheckboxes.map(cb => cb.value),
            ...pitGroups.flatMap(g => g.memberIds)
        ]));

        if (scouterIds.length === 0) {
            Obsidianscout.showToast("Please select at least one scouter to assign", "error");
            return;
        }

        const startMatchKey = bulkMatchStart ? bulkMatchStart.value : "";
        const endMatchKey = bulkMatchEnd ? bulkMatchEnd.value : "";
        const consecutiveMatches = Math.max(1, Number(bulkConsecutiveMatches ? bulkConsecutiveMatches.value : 5) || 5);
        const stageFilter = bulkCompLevel ? bulkCompLevel.value : "all";
        const pitScope = bulkPitScope ? bulkPitScope.value : "unassigned";

        let matchKeys = null;
        if (bulkType !== "PIT_AUTO" && bulkRangeMode && bulkRangeMode.value === "time") {
            matchKeys = getMatchesInTimeBlock().map(m => m.matchKey).filter(Boolean);
            if (matchKeys.length === 0) {
                Obsidianscout.showToast("No matches are scheduled in the selected time block", "error");
                return;
            }
        }

        try {
            const res = await Obsidianscout.request("/api/assignments/auto-generate", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    eventKey: currentEventKey,
                    assignmentType: bulkType,
                    scouterUserIds: scouterIds,
                    stageFilter: stageFilter,
                    startMatchKey: startMatchKey,
                    endMatchKey: endMatchKey,
                    matchKeys: matchKeys,
                    consecutiveMatches: consecutiveMatches,
                    overwrite: overwrite,
                    pitScope: pitScope,
                    pitGroups: pitGroups.map(g => ({ name: g.name, memberIds: g.memberIds }))
                })
            });

            const createdCount = res.createdCount || 0;
            const unavoidableOverlapCount = res.unavoidableOverlapCount || 0;
            const continuityBreaksCount = res.continuityBreaksCount || 0;

            if (createdCount === 0) {
                Obsidianscout.showToast("All slots in this range are already assigned (or no matches found). Check 'Overwrite' to replace.", "info");
                return;
            }

            if (unavoidableOverlapCount > 0) {
                Obsidianscout.showToast(
                    `Created ${createdCount} assignments. ⚠️ Warning: ${unavoidableOverlapCount} slot(s) have scouts dual-assigned to match & qual scouting due to limited scout pool!`,
                    "warning"
                );
            } else if (continuityBreaksCount > 0) {
                Obsidianscout.showToast(
                    `Created ${createdCount} assignments! Shifted rotation ${continuityBreaksCount} time(s) to avoid dual-assigning scouts to both match and qualitative scouting in the same match.`,
                    "success"
                );
            } else {
                Obsidianscout.showToast(`Successfully created ${createdCount} assignments with 0 overlaps!`, "success");
            }

            hideModal("bulk-wizard-modal");
            await loadEventData();
        } catch (err) {
            console.error("Bulk assignment failed:", err);
            Obsidianscout.showToast(err.message || "Failed to generate bulk assignments", "error");
        }
    }

    // ==========================================
    // REMINDER SETTINGS
    // ==========================================
    // ==========================================
    // PLANNED SHIFTS (assign by time before the schedule is out)
    // ==========================================
    // Translated text with {placeholders} filled in
    function shiftText(key, fallback, vars) {
        let text = (Obsidianscout.t ? Obsidianscout.t(key, fallback) : fallback) || fallback;
        Object.entries(vars || {}).forEach(([name, value]) => { text = text.split(`{${name}}`).join(String(value)); });
        return text;
    }

    function shiftStageLabel(stage) {
        const fallbacks = { all: "All stages", qm: "Quals", pr: "Practice", playoffs: "Playoffs" };
        return fallbacks[stage] ? shiftText(`assignments.shift_stage_short_${stage}`, fallbacks[stage]) : stage;
    }

    // Robot stations in the order the server fills them: red 1..n, then blue 1..n
    function getShiftStationLabels() {
        const isFtc = (currentEventKey && currentEventKey.toLowerCase().startsWith("ftc")) ||
                      ((me && me.program === "FTC") || (settings && settings.program === "FTC"));
        const perAlliance = isFtc ? 2 : 3;
        const labels = [];
        for (let i = 1; i <= perAlliance; i++) labels.push({ text: shiftText("assignments.shift_seat_red", "Red {n}", { n: i }), alliance: "red" });
        for (let i = 1; i <= perAlliance; i++) labels.push({ text: shiftText("assignments.shift_seat_blue", "Blue {n}", { n: i }), alliance: "blue" });
        return labels;
    }

    function getQualSeatLabels(mode) {
        return mode === "ALLIANCE"
            ? [
                { text: shiftText("assignments.shift_seat_red_alliance", "Red Alliance"), alliance: "red" },
                { text: shiftText("assignments.shift_seat_blue_alliance", "Blue Alliance"), alliance: "blue" }
            ]
            : getShiftStationLabels();
    }

    // The event's first and last day (YYYY-MM-DD), when the event list knows them
    function getEventDayRange() {
        const ev = events.find(e => e.eventKey === currentEventKey);
        const day = val => (typeof val === "string" && /^\d{4}-\d{2}-\d{2}/.test(val)) ? val.slice(0, 10) : "";
        return { start: ev ? day(ev.startDate) : "", end: ev ? day(ev.endDate) : "" };
    }

    function formatShiftDay(day) {
        const [y, mo, d] = String(day || "").split("-").map(Number);
        if (!y || !mo || !d) return day || "";
        return new Date(Date.UTC(y, mo - 1, d)).toLocaleDateString([], { weekday: "short", month: "short", day: "numeric", timeZone: "UTC" });
    }

    function formatShiftTime(hhmm) {
        const mins = parseTimeInputMinutes(hhmm);
        if (mins === null) return hhmm || "";
        return new Date(2000, 0, 1, Math.floor(mins / 60), mins % 60).toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
    }

    // Each seat is a button that opens a searchable list of the team's scouts. A custom picker rather than
    // <datalist>, which many phone browsers never open.
    function getShiftScoutChoices() {
        return users.filter(u => u.role !== "NONE").map(u => ({
            id: String(u.id || u.userId),
            name: u.displayName || u.username,
            username: u.username || ""
        }));
    }

    const SEAT_CHEVRON = `<svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><polyline points="6 9 12 15 18 9"></polyline></svg>`;

    function setSeatValue(seat, id) {
        const choice = getShiftScoutChoices().find(c => c.id === id);
        seat.dataset.value = choice ? choice.id : "";
        const text = seat.querySelector(".seat-picker-text");
        text.textContent = choice ? choice.name : shiftText("assignments.shift_nobody", "Nobody");
        text.classList.toggle("empty", !choice);
    }

    function renderSeatSelects(container, labels, selectedIds) {
        closeSeatPicker();
        container.innerHTML = labels.map((label, i) => `
            <div class="shift-seat" data-seat="${i}" data-label="${escapeHtml(label.text)}">
                <span class="shift-seat-label ${label.alliance}">${escapeHtml(label.text)}</span>
                <div class="seat-picker">
                    <button type="button" class="seat-picker-trigger" aria-haspopup="listbox" aria-expanded="false">
                        <span class="seat-picker-text"></span>${SEAT_CHEVRON}
                    </button>
                </div>
            </div>
        `).join("");
        container.querySelectorAll(".shift-seat").forEach((seat, i) => {
            setSeatValue(seat, String((selectedIds && selectedIds[i]) || ""));
            seat.querySelector(".seat-picker-trigger").addEventListener("click", () => toggleSeatPicker(seat));
        });
    }

    // Seat picks in order; blank means the seat stays open
    function readSeatSelects(container) {
        return Array.from(container.querySelectorAll(".shift-seat")).map(seat => seat.dataset.value || "");
    }

    // Desktop: a dropdown under the seat, or above it when there's no room below. Phones: a bottom sheet (CSS).
    function positionSeatPicker() {
        if (!openSeat || !openPanel) return;
        if (phoneQuery.matches) {
            openPanel.style.top = openPanel.style.left = openPanel.style.width = "";
            return;
        }
        const rect = openSeat.querySelector(".seat-picker-trigger").getBoundingClientRect();
        const width = Math.max(rect.width, 240);
        const left = Math.min(rect.left, window.innerWidth - width - 8);
        const height = openPanel.offsetHeight;
        const below = window.innerHeight - rect.bottom - 8;
        const top = below >= height || rect.top < height ? rect.bottom + 4 : rect.top - height - 4;
        openPanel.style.width = `${width}px`;
        openPanel.style.left = `${Math.max(8, left)}px`;
        openPanel.style.top = `${Math.max(8, top)}px`;
    }

    function closeSeatPicker() {
        if (!openSeat) return;
        if (openPanel) openPanel.remove();
        openPanel = null;
        const trigger = openSeat.querySelector(".seat-picker-trigger");
        trigger.setAttribute("aria-expanded", "false");
        document.body.classList.remove("seat-picker-sheet-open");
        openSeat = null;
    }

    function toggleSeatPicker(seat) {
        if (openSeat === seat) {
            closeSeatPicker();
            return;
        }
        closeSeatPicker();
        openSeat = seat;

        // Who already sits where, so the list can show it
        const seatedAt = new Map();
        shiftForm.querySelectorAll(".shift-seat").forEach(other => {
            if (other !== seat && other.dataset.value) seatedAt.set(other.dataset.value, other.dataset.label);
        });

        const panel = document.createElement("div");
        panel.className = "seat-picker-panel";
        panel.setAttribute("role", "dialog");
        panel.innerHTML = `
            <div class="seat-picker-sheet-head">
                <strong>${escapeHtml(seat.dataset.label)}</strong>
                <button type="button" class="seat-picker-close" aria-label="${escapeHtml(shiftText("assignments.close", "Close"))}">&times;</button>
            </div>
            <input type="search" class="seat-picker-search" autocomplete="off"
                   placeholder="${escapeHtml(shiftText("assignments.person_search_hint", "Search by name or username..."))}" />
            <div class="seat-picker-list" role="listbox"></div>
        `;
        // The list lives on the page itself, not in the modal: the modal's transform would pin a fixed-position
        // list to the modal, and its scroll area would clip a dropdown near the bottom of the form
        document.body.appendChild(panel);
        openPanel = panel;
        positionSeatPicker();
        seat.querySelector(".seat-picker-trigger").setAttribute("aria-expanded", "true");
        document.body.classList.add("seat-picker-sheet-open");

        const search = panel.querySelector(".seat-picker-search");
        const list = panel.querySelector(".seat-picker-list");
        let active = 0;

        const renderList = () => {
            const q = search.value.trim().toLowerCase();
            const matches = getShiftScoutChoices().filter(c =>
                !q || c.name.toLowerCase().includes(q) || c.username.toLowerCase().includes(q));
            const options = [{ id: "", name: shiftText("assignments.shift_nobody", "Nobody"), username: "" }, ...matches];
            active = Math.min(active, options.length - 1);
            list.innerHTML = options.map((c, i) => {
                const where = seatedAt.get(c.id);
                const handle = c.username && c.username !== c.name ? `@${c.username}` : "";
                return `
                    <button type="button" role="option" class="seat-picker-option${c.id === (seat.dataset.value || "") ? " selected" : ""}${i === active ? " active" : ""}${c.id ? "" : " nobody"}"
                            data-id="${escapeHtml(c.id)}" aria-selected="${c.id === (seat.dataset.value || "")}">
                        <span class="seat-picker-name">${escapeHtml(c.name)}</span>
                        ${handle ? `<span class="seat-picker-handle">${escapeHtml(handle)}</span>` : ""}
                        ${where ? `<span class="seat-picker-taken">${escapeHtml(where)}</span>` : ""}
                    </button>
                `;
            }).join("") + (matches.length === 0 && q
                ? `<div class="seat-picker-none">${escapeHtml(shiftText("assignments.person_search_none", "No one matches that search."))}</div>`
                : "");
        };

        const pick = id => {
            setSeatValue(seat, id);
            closeSeatPicker();
            seat.querySelector(".seat-picker-trigger").focus();
        };

        list.addEventListener("click", e => {
            const option = e.target.closest(".seat-picker-option");
            if (option) pick(option.dataset.id);
        });
        search.addEventListener("input", () => { active = 0; renderList(); });
        search.addEventListener("keydown", e => {
            const options = list.querySelectorAll(".seat-picker-option");
            if (e.key === "ArrowDown" || e.key === "ArrowUp") {
                e.preventDefault();
                active = (active + (e.key === "ArrowDown" ? 1 : -1) + options.length) % options.length;
                renderList();
                const current = list.querySelector(".seat-picker-option.active");
                if (current) current.scrollIntoView({ block: "nearest" });
            } else if (e.key === "Enter") {
                // Enter picks the highlighted scout, it shouldn't submit the shift
                e.preventDefault();
                if (options[active]) pick(options[active].dataset.id);
            } else if (e.key === "Escape") {
                e.preventDefault();
                e.stopPropagation();
                closeSeatPicker();
                seat.querySelector(".seat-picker-trigger").focus();
            }
        });
        panel.querySelector(".seat-picker-close").addEventListener("click", closeSeatPicker);

        renderList();
        positionSeatPicker();
        // Phones get the list as a bottom sheet; opening the keyboard right away would hide most of it
        if (!phoneQuery.matches) search.focus();
    }

    function renderQualSeats(selectedIds) {
        renderSeatSelects(shiftQualSeats, getQualSeatLabels(shiftQualMode.value), selectedIds);
    }

    function resetShiftForm() {
        const range = getEventDayRange();
        shiftIdInput.value = "";
        shiftNameInput.value = "";
        shiftDayInput.min = range.start;
        shiftDayInput.max = range.end;
        shiftDayInput.value = range.start;
        shiftStartInput.value = "08:00";
        shiftEndInput.value = "12:00";
        shiftStageSelect.value = "all";
        shiftQualMode.value = "TEAMS";
        shiftFormTitle.textContent = shiftText("assignments.shift_add_title", "Add a Shift");
        shiftCancelEdit.classList.add("hidden");
        renderSeatSelects(shiftMatchSeats, getShiftStationLabels(), []);
        renderQualSeats([]);
    }

    function describeSeats(labels, ids, names) {
        return labels.map((label, i) => ids[i] ? `${label.text}: ${names[ids[i]] || "?"}` : null).filter(Boolean).join(", ");
    }

    function renderShiftsList() {
        if (shifts.length === 0) {
            shiftsList.innerHTML = `<p style="margin:0; font-size:13px; color:var(--muted);">${escapeHtml(shiftText("assignments.shifts_empty", "No shifts planned for this event yet."))}</p>`;
            return;
        }
        shiftsList.innerHTML = shifts.map(view => {
            const s = view.shift || {};
            const names = view.scouterNames || {};
            const matchSeats = describeSeats(getShiftStationLabels(), s.matchScouterIds || [], names);
            const qualSeats = describeSeats(getQualSeatLabels(s.qualMode), s.qualScouterIds || [], names);
            const status = view.matchCount > 0
                ? `<span style="color:var(--primary); font-weight:600;">${escapeHtml(shiftText("assignments.shift_in_window", "{count} match(es) in window", { count: view.matchCount }))}</span>`
                : `<span style="color:var(--muted);">${escapeHtml(shiftText("assignments.shift_waiting", "Waiting for the match schedule"))}</span>`;
            const applied = s.appliedCount > 0 ? ` · ${shiftText("assignments.shift_applied", "{count} assignment(s) created", { count: s.appliedCount })}` : "";
            const when = `${formatShiftDay(s.day)} · ${formatShiftTime(s.startTime)} – ${formatShiftTime(s.endTime)} · ${shiftStageLabel(s.stageFilter)}`;
            return `
                <div class="row justify-between items-center gap-12" style="padding:10px 12px; border:1px solid var(--border-subtle); border-radius:var(--radius-sm, 6px);">
                    <div style="min-width:0; flex:1;">
                        <div style="font-weight:700; font-size:13px;">${escapeHtml(s.name || when)}</div>
                        ${s.name ? `<div style="font-size:12px; margin-top:2px;">${escapeHtml(when)}</div>` : ""}
                        ${matchSeats ? `<div style="font-size:12px; color:var(--muted); margin-top:2px; overflow-wrap:anywhere;"><strong>${escapeHtml(shiftText("assignments.shift_list_match", "Match"))}</strong> · ${escapeHtml(matchSeats)}</div>` : ""}
                        ${qualSeats ? `<div style="font-size:12px; color:var(--muted); margin-top:2px; overflow-wrap:anywhere;"><strong>${escapeHtml(shiftText("assignments.shift_list_qual", "Qualitative"))}</strong> · ${escapeHtml(qualSeats)}</div>` : ""}
                        <div style="font-size:12px; margin-top:2px;">${status}${escapeHtml(applied)}</div>
                    </div>
                    <div class="row gap-8">
                        <button type="button" class="btn ghost btn-sm" data-shift-edit="${escapeHtml(s.id)}" style="font-size:12px; padding:4px 10px;">${escapeHtml(shiftText("assignments.shift_edit", "Edit"))}</button>
                        <button type="button" class="btn ghost btn-sm" data-shift-delete="${escapeHtml(s.id)}" style="font-size:12px; padding:4px 10px; color:var(--danger, #ef4444);">${escapeHtml(shiftText("assignments.shift_delete", "Delete"))}</button>
                    </div>
                </div>
            `;
        }).join("");
        shiftsList.querySelectorAll("[data-shift-edit]").forEach(btn => btn.addEventListener("click", () => editShift(btn.dataset.shiftEdit)));
        shiftsList.querySelectorAll("[data-shift-delete]").forEach(btn => btn.addEventListener("click", () => deleteShift(btn.dataset.shiftDelete)));
    }

    async function loadShifts() {
        if (!currentEventKey) {
            shifts = [];
            renderShiftsList();
            return;
        }
        try {
            const res = await Obsidianscout.request(`/api/assignments/shifts?eventKey=${encodeURIComponent(currentEventKey)}`);
            shifts = (res && res.shifts) || [];
        } catch (err) {
            console.error("Load shifts failed:", err);
            shifts = [];
        }
        renderShiftsList();
    }

    async function openShiftsModal() {
        if (!currentEventKey) {
            Obsidianscout.showToast(shiftText("assignments.shift_select_event", "Select an event first"), "error");
            return;
        }
        resetShiftForm();
        showModal("shifts-modal");
        await loadShifts();
    }

    function editShift(id) {
        const view = shifts.find(v => v.shift && v.shift.id === id);
        if (!view) return;
        const s = view.shift;
        shiftIdInput.value = s.id;
        shiftNameInput.value = s.name || "";
        shiftDayInput.min = "";
        shiftDayInput.max = "";
        shiftDayInput.value = s.day || "";
        shiftStartInput.value = s.startTime || "";
        shiftEndInput.value = s.endTime || "";
        shiftStageSelect.value = s.stageFilter || "all";
        shiftQualMode.value = s.qualMode === "ALLIANCE" ? "ALLIANCE" : "TEAMS";
        shiftFormTitle.textContent = shiftText("assignments.shift_edit_title", "Edit Shift");
        shiftCancelEdit.classList.remove("hidden");
        renderSeatSelects(shiftMatchSeats, getShiftStationLabels(), s.matchScouterIds || []);
        renderQualSeats(s.qualScouterIds || []);
        shiftForm.scrollIntoView({ behavior: "smooth", block: "nearest" });
    }

    async function saveShift(e) {
        e.preventDefault();
        const saveBtn = document.getElementById("shift-save");
        try {
            await submitShift();
        } finally {
            if (saveBtn && Obsidianscout.setButtonLoading) Obsidianscout.setButtonLoading(saveBtn, false);
        }
    }

    async function submitShift() {
        const matchSeats = readSeatSelects(shiftMatchSeats);
        const qualSeats = readSeatSelects(shiftQualSeats);
        const picked = [...matchSeats, ...qualSeats].filter(Boolean);
        if (picked.length === 0) {
            Obsidianscout.showToast(shiftText("assignments.shift_need_seat", "Pick a scout for at least one seat"), "error");
            return;
        }
        if (new Set(picked).size !== picked.length) {
            Obsidianscout.showToast(shiftText("assignments.shift_duplicate", "Each scout can only have one seat in a shift"), "error");
            return;
        }
        const startMin = parseTimeInputMinutes(shiftStartInput.value);
        const endMin = parseTimeInputMinutes(shiftEndInput.value);
        if (startMin === null || endMin === null || endMin <= startMin) {
            Obsidianscout.showToast(shiftText("assignments.shift_bad_range", "Shift end must be after shift start"), "error");
            return;
        }
        try {
            const res = await Obsidianscout.request("/api/assignments/shifts", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({
                    id: shiftIdInput.value || null,
                    eventKey: currentEventKey,
                    name: shiftNameInput.value,
                    day: shiftDayInput.value,
                    startTime: shiftStartInput.value,
                    endTime: shiftEndInput.value,
                    stageFilter: shiftStageSelect.value,
                    matchScouterIds: matchSeats,
                    qualMode: shiftQualMode.value,
                    qualScouterIds: qualSeats
                })
            });
            shifts = res.shifts || [];
            renderShiftsList();
            resetShiftForm();

            const created = res.createdCount || 0;
            if (created > 0) {
                Obsidianscout.showToast(shiftText("assignments.shift_saved_created", "Shift saved. Created {count} assignment(s) from matches already synced.", { count: created }), "success");
                await loadEventData();
            } else {
                Obsidianscout.showToast(shiftText("assignments.shift_saved_waiting", "Shift saved. Scouts will be assigned to its matches when the schedule syncs."), "success");
            }
        } catch (err) {
            console.error("Save shift failed:", err);
            Obsidianscout.showToast(err.message || shiftText("assignments.shift_save_failed", "Failed to save shift"), "error");
        }
    }

    async function deleteShift(id) {
        if (!confirm(shiftText("assignments.shift_delete_confirm", "Delete this shift? Assignments it already created will stay."))) return;
        try {
            await Obsidianscout.request(`/api/assignments/shifts/${encodeURIComponent(id)}`, { method: "DELETE" });
            if (shiftIdInput.value === id) resetShiftForm();
            Obsidianscout.showToast(shiftText("assignments.shift_deleted", "Shift deleted"), "success");
            await loadShifts();
        } catch (err) {
            console.error("Delete shift failed:", err);
            Obsidianscout.showToast(err.message || shiftText("assignments.shift_delete_failed", "Failed to delete shift"), "error");
        }
    }

    function openReminderSettingsModal() {
        settingReminderMinutes.value = String(settings.assignmentReminderMinutes || 10);
        settingEnablePush.checked = settings.enableAssignmentPushReminders !== false;
        settingEnableEmail.checked = settings.enableAssignmentEmailReminders !== false;
        showModal("reminder-settings-modal");
    }

    async function saveReminderSettings(e) {
        e.preventDefault();

        const minutes = Number(settingReminderMinutes.value);
        const pushEnabled = settingEnablePush.checked;
        const emailEnabled = settingEnableEmail.checked;

        try {
            const updated = {
                ...settings,
                assignmentReminderMinutes: minutes,
                enableAssignmentPushReminders: pushEnabled,
                enableAssignmentEmailReminders: emailEnabled
            };

            await Obsidianscout.request("/api/settings", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ settings: updated })
            });

            settings = updated;
            Obsidianscout.showToast("Reminder settings saved", "success");
            hideModal("reminder-settings-modal");
        } catch (err) {
            console.error("Save settings failed:", err);
            Obsidianscout.showToast("Failed to save settings", "error");
        }
    }

    // Modal Helpers
    function showModal(idOrEl) {
        const el = typeof idOrEl === "string" ? document.getElementById(idOrEl) : idOrEl;
        if (el) {
            el.classList.add("show");
            el.classList.remove("hidden");
        }
    }

    function hideModal(idOrEl) {
        const el = typeof idOrEl === "string" ? document.getElementById(idOrEl) : idOrEl;
        if (el) {
            el.classList.remove("show");
        }
    }

    // ==========================================
    // EVENT LISTENERS WIRE-UP
    // ==========================================
    function wireEventListeners() {
        setupPeopleSearch();
        // Event filter dropdown change
        eventSelect.addEventListener("change", async () => {
            currentEventKey = eventSelect.value;
            await loadEventData();
        });

        // Top-right refresh button
        refreshBtn.addEventListener("click", async () => {
            await loadEventData();
            Obsidianscout.showToast(Obsidianscout.t ? Obsidianscout.t("assignments.updated", "Assignments updated") : "Assignments updated", "info");
        });

        // KPI Conflicts Chip - quick auto resolve
        if (statConflictsChip) {
            statConflictsChip.addEventListener("click", () => {
                autoResolveAllConflicts();
            });
        }

        // Tab Navigation
        tabBtns.forEach(btn => {
            btn.addEventListener("click", () => {
                const tab = btn.dataset.tab;
                tabBtns.forEach(b => b.classList.toggle("active", b === btn));
                tabContentMatrix.classList.toggle("hidden", tab !== "matrix");
                if (tabContentQual) tabContentQual.classList.toggle("hidden", tab !== "qual");
                tabContentList.classList.toggle("hidden", tab !== "list");
                tabContentPit.classList.toggle("hidden", tab !== "pit");
            });
        });

        // Matrix Stage Filters
        if (matrixStageFilter) matrixStageFilter.addEventListener("change", renderMatrix);
        if (qualStageFilter) qualStageFilter.addEventListener("change", renderQualitativeMatrix);

        // List Filters
        filterType.addEventListener("change", renderAssignmentsList);
        filterScouter.addEventListener("change", renderAssignmentsList);
        filterStatus.addEventListener("change", renderAssignmentsList);
        filterSearch.addEventListener("input", renderAssignmentsList);
        pitSearch.addEventListener("input", renderPitCoverage);

        // Delete All / Filtered Button
        if (deleteAllBtn) {
            deleteAllBtn.addEventListener("click", async () => {
                if (!currentEventKey) {
                    Obsidianscout.showToast("No active event selected", "error");
                    return;
                }
                const filtered = getFilteredAssignmentsList();
                if (filtered.length === 0) {
                    Obsidianscout.showToast("No assignments match the current filter view to delete", "info");
                    return;
                }

                const isFiltered = Boolean(
                    (filterType && filterType.value) ||
                    (filterScouter && filterScouter.value) ||
                    (filterStatus && filterStatus.value) ||
                    (filterSearch && filterSearch.value.trim())
                );

                if (isFiltered) {
                    const confirmed = confirm(`Are you sure you want to delete ONLY the ${filtered.length} filtered assignment(s)? (Total in event: ${assignments.length}).\n\nThis will NOT delete assignments hidden by filters.`);
                    if (!confirmed) return;

                    try {
                        await Promise.all(filtered.map(a =>
                            Obsidianscout.request(`/api/assignments/${encodeURIComponent(a.id)}`, { method: "DELETE" })
                        ));
                        Obsidianscout.showToast(`Deleted ${filtered.length} filtered assignment(s)`, "success");
                        await loadEventData();
                    } catch (err) {
                        console.error("Delete filtered assignments failed:", err);
                        Obsidianscout.showToast(err.message || "Failed to delete filtered assignments", "error");
                    }
                } else {
                    const confirmed = confirm(`Are you sure you want to delete ALL ${assignments.length} assignments for event ${currentEventKey}? This action cannot be undone.`);
                    if (!confirmed) return;

                    try {
                        const res = await Obsidianscout.request(`/api/assignments?eventKey=${encodeURIComponent(currentEventKey)}`, {
                            method: "DELETE"
                        });
                        Obsidianscout.showToast(res.message || `Deleted ${res.deletedCount || 0} assignments`, "success");
                        await loadEventData();
                    } catch (err) {
                        console.error("Delete all assignments failed:", err);
                        Obsidianscout.showToast(err.message || "Failed to delete all assignments", "error");
                    }
                }
            });
        }

        // Bulk stage filter
        if (bulkCompLevel) {
            bulkCompLevel.addEventListener("change", populateBulkMatchDropdowns);
        }

        // Pit scout groups
        if (pitGroupsBtn) pitGroupsBtn.addEventListener("click", openPitGroupsModal);
        document.querySelectorAll(".btn-manage-pit-groups").forEach(btn => btn.addEventListener("click", openPitGroupsModal));
        if (pitGroupsAdd) pitGroupsAdd.addEventListener("click", () => renderPitGroupEditorRow({ id: newPitGroupId(), name: "", memberIds: [] }));
        if (pitGroupsForm) pitGroupsForm.addEventListener("submit", savePitGroups);
        ["pit-groups-close", "pit-groups-cancel"].forEach(btnId => {
            const btn = document.getElementById(btnId);
            if (btn) btn.addEventListener("click", () => hideModal("pit-groups-modal"));
        });
        if (assignmentPitGroupSelect) {
            assignmentPitGroupSelect.addEventListener("change", () => {
                updatePitGroupModeView();
                checkLiveModalConflict();
            });
        }

        // Bulk range mode (match range vs time block)
        if (bulkRangeMode) bulkRangeMode.addEventListener("change", updateBulkRangeModeView);
        [bulkTimeDay, bulkTimeStart, bulkTimeEnd].forEach(el => {
            if (el) el.addEventListener("input", updateBulkTimeSummary);
            if (el) el.addEventListener("change", updateBulkTimeSummary);
        });

        // Planned shifts
        const shiftsBtn = document.getElementById("shifts-btn");
        if (shiftsBtn) shiftsBtn.addEventListener("click", openShiftsModal);
        if (shiftForm) shiftForm.addEventListener("submit", saveShift);
        if (shiftCancelEdit) shiftCancelEdit.addEventListener("click", resetShiftForm);
        ["shifts-close", "shifts-done"].forEach(btnId => {
            const btn = document.getElementById(btnId);
            if (btn) btn.addEventListener("click", () => {
                closeSeatPicker();
                hideModal("shifts-modal");
            });
        });
        // Tapping anywhere outside the open seat picker closes it
        document.addEventListener("pointerdown", e => {
            if (!openSeat) return;
            if (openPanel && openPanel.contains(e.target)) return;
            if (openSeat.querySelector(".seat-picker-trigger").contains(e.target)) return;
            closeSeatPicker();
        });
        // Keep an open dropdown attached to its seat while the modal scrolls or the window resizes
        window.addEventListener("scroll", positionSeatPicker, true);
        window.addEventListener("resize", positionSeatPicker);
        // Switching per-robot / per-alliance keeps the picks that still have a seat
        if (shiftQualMode) shiftQualMode.addEventListener("change", () => renderQualSeats(readSeatSelects(shiftQualSeats)));

        // Modals Open
        newAssignmentBtn.addEventListener("click", () => openNewAssignmentModal());
        bulkWizardBtn.addEventListener("click", () => {
            populateBulkMatchDropdowns();
            updateBulkRangeModeView();
            clearListSearch(bulkScoutersContainer);
            clearListSearch(bulkPitGroupsContainer);
            showModal("bulk-wizard-modal");
        });
        if (autoPitBtn) {
            autoPitBtn.addEventListener("click", () => {
                updatePitAutoSummary();
                showModal("pit-auto-modal");
            });
        }
        if (autoPitHeaderBtn) {
            autoPitHeaderBtn.addEventListener("click", () => {
                updatePitAutoSummary();
                showModal("pit-auto-modal");
            });
        }
        reminderSettingsBtn.addEventListener("click", openReminderSettingsModal);

        // Bulk Assignment Type Change (Match vs Pit Controls Toggle)
        if (bulkAssignmentType) {
            bulkAssignmentType.addEventListener("change", () => {
                const isPit = bulkAssignmentType.value === "PIT_AUTO";
                if (bulkMatchControls) bulkMatchControls.classList.toggle("hidden", isPit);
                if (bulkPitControls) bulkPitControls.classList.toggle("hidden", !isPit);
                const notice = document.getElementById("bulk-wizard-notice");
                if (notice) {
                    notice.textContent = isPit
                        ? "Quickly distribute registered event teams evenly among your selected pit scouters."
                        : "Quickly assign team scouters across matches or pit teams. Select available scouters and they will be distributed across targets.";
                }
            });
        }

        // Bulk Wizard Quick Buttons
        bulkSelectAll.addEventListener("click", () => {
            visibleCheckboxes(bulkScoutersContainer).forEach(cb => cb.checked = true);
        });
        bulkDeselectAll.addEventListener("click", () => {
            visibleCheckboxes(bulkScoutersContainer).forEach(cb => cb.checked = false);
        });

        // Pit Auto Quick Buttons & Scope Change
        if (pitAutoSelectAll) {
            pitAutoSelectAll.addEventListener("click", () => {
                if (pitAutoScoutersContainer) {
                    visibleCheckboxes(pitAutoScoutersContainer).forEach(cb => cb.checked = true);
                    updatePitAutoSummary();
                }
            });
        }
        if (pitAutoDeselectAll) {
            pitAutoDeselectAll.addEventListener("click", () => {
                if (pitAutoScoutersContainer) {
                    visibleCheckboxes(pitAutoScoutersContainer).forEach(cb => cb.checked = false);
                    updatePitAutoSummary();
                }
            });
        }
        if (pitAutoScope) {
            pitAutoScope.addEventListener("change", updatePitAutoSummary);
        }

        // Modal Close Buttons
        assignmentModalClose.addEventListener("click", () => hideModal("assignment-modal"));
        assignmentModalCancel.addEventListener("click", () => hideModal("assignment-modal"));
        bulkWizardClose.addEventListener("click", () => hideModal("bulk-wizard-modal"));
        bulkWizardCancel.addEventListener("click", () => hideModal("bulk-wizard-modal"));
        if (pitAutoClose) pitAutoClose.addEventListener("click", () => hideModal("pit-auto-modal"));
        if (pitAutoCancel) pitAutoCancel.addEventListener("click", () => hideModal("pit-auto-modal"));
        if (conflictResolverClose) conflictResolverClose.addEventListener("click", () => hideModal("conflict-resolver-modal"));
        if (conflictResolverCancel) conflictResolverCancel.addEventListener("click", () => hideModal("conflict-resolver-modal"));
        if (conflictClearAllBtn) conflictClearAllBtn.addEventListener("click", clearAllInConflictSlot);
        if (conflictResolveAllBtn) conflictResolveAllBtn.addEventListener("click", autoResolveAllConflicts);
        reminderSettingsClose.addEventListener("click", () => hideModal("reminder-settings-modal"));
        reminderSettingsCancel.addEventListener("click", () => hideModal("reminder-settings-modal"));

        // Close on backdrop click
        [assignmentModal, bulkWizardModal, pitAutoModal, pitGroupsModal, conflictResolverModal, reminderSettingsModal].forEach(m => {
            if (m) {
                m.addEventListener("click", (e) => {
                    if (e.target === m) hideModal(m);
                });
            }
        });

        // Form change & conflict detection triggers
        assignmentTypeSelect.addEventListener("change", () => {
            updateModalTypeView(assignmentTypeSelect.value);
            if (assignmentTypeSelect.value === "QUALITATIVE") {
                updateQualTargetScopeOptions();
                if (assignmentQualTargetType.value === "team") populateModalQualTeams();
            }
            checkLiveModalConflict();
        });
        assignmentMatchSelect.addEventListener("change", () => {
            populateModalMatchTeams();
            checkLiveModalConflict();
        });
        assignmentTeamSelect.addEventListener("change", checkLiveModalConflict);
        assignmentPitTeamSelect.addEventListener("change", checkLiveModalConflict);
        assignmentQualMatchSelect.addEventListener("change", () => {
            updateQualTargetScopeOptions();
            if (assignmentQualTargetType.value === "team") populateModalQualTeams();
            checkLiveModalConflict();
        });
        assignmentQualTargetType.addEventListener("change", () => {
            const isTeam = assignmentQualTargetType.value === "team";
            fieldQualTeam.classList.toggle("hidden", !isTeam);
            if (isTeam) populateModalQualTeams();
            checkLiveModalConflict();
        });
        assignmentQualTeamSelect.addEventListener("change", checkLiveModalConflict);
        assignmentScouterSelect.addEventListener("change", checkLiveModalConflict);

        // Form submissions
        assignmentForm.addEventListener("submit", saveAssignment);
        bulkWizardForm.addEventListener("submit", generateBulkAssignments);
        if (pitAutoForm) pitAutoForm.addEventListener("submit", generatePitAutoAssignments);
        reminderSettingsForm.addEventListener("submit", saveReminderSettings);

        // Interactive Conflicts KPI Chip
        if (statConflictsChip) {
            statConflictsChip.addEventListener("click", autoResolveAllConflicts);
            statConflictsChip.addEventListener("keydown", (e) => {
                if (e.key === "Enter" || e.key === " ") {
                    e.preventDefault();
                    autoResolveAllConflicts();
                }
            });
        }
    }

    // ==========================================
    // UTILITIES
    // ==========================================
    function parseTeamNum(key) {
        if (!key) return null;
        const cleaned = String(key).replace(/^(frc|ftc)/i, '').trim();
        const num = parseInt(cleaned, 10);
        return isNaN(num) ? null : num;
    }

    function parseTimestampMs(timeVal) {
        if (timeVal === null || timeVal === undefined || timeVal === "") return null;
        if (typeof timeVal === "number") {
            if (isNaN(timeVal) || timeVal <= 0) return null;
            return timeVal < 1e11 ? timeVal * 1000 : timeVal;
        }
        if (typeof timeVal === "string") {
            const trimmed = timeVal.trim();
            if (!trimmed) return null;
            if (/^\d+(\.\d+)?$/.test(trimmed)) {
                const num = Number(trimmed);
                if (isNaN(num) || num <= 0) return null;
                return num < 1e11 ? num * 1000 : num;
            }
            const parsed = Date.parse(trimmed);
            if (!isNaN(parsed) && parsed > 0) {
                return parsed;
            }
        }
        if (timeVal instanceof Date) {
            const t = timeVal.getTime();
            return isNaN(t) ? null : t;
        }
        return null;
    }

    function formatDateTime(isoString, timezone) {
        const ms = parseTimestampMs(isoString);
        if (!ms) return "";
        try {
            const d = new Date(ms);
            const options = { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' };
            if (timezone) {
                try {
                    return new Intl.DateTimeFormat([], { ...options, timeZone: timezone }).format(d);
                } catch (_) {}
            }
            return d.toLocaleDateString([], { month: 'short', day: 'numeric' }) + " " + d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        } catch {
            return String(isoString);
        }
    }

    function formatTimeOnly(isoString, timezone) {
        const ms = parseTimestampMs(isoString);
        if (!ms) return "";
        try {
            const d = new Date(ms);
            const options = { hour: '2-digit', minute: '2-digit' };
            if (timezone) {
                try {
                    return new Intl.DateTimeFormat([], { ...options, timeZone: timezone }).format(d);
                } catch (_) {}
            }
            return d.toLocaleTimeString([], options);
        } catch {
            return String(isoString);
        }
    }

    function renderOffsetBadge(offsetSeconds, scheduledTime, predictedTime, timezone) {
        const offset = Number(offsetSeconds || 0);
        if (!offset || !scheduledTime || !predictedTime || predictedTime === scheduledTime) return "";
        const offsetMinutes = Math.round(offset / 60);
        if (Math.abs(offsetMinutes) < 1) return "";
        const isLate = offsetMinutes > 0;
        const sign = isLate ? "+" : "";
        const origLocalStr = formatTimeOnly(scheduledTime, timezone);
        const estLocalStr = formatTimeOnly(predictedTime, timezone);
        const statusText = isLate ? `${offsetMinutes}m behind schedule` : `${Math.abs(offsetMinutes)}m ahead of schedule`;
        const tooltip = `Est: ${estLocalStr} (${statusText} \u2022 Sched: ${origLocalStr})`;
        return ` <span class="schedule-offset-badge ${isLate ? 'behind' : 'ahead'}" data-tooltip="${escapeHtml(tooltip)}" aria-label="${escapeHtml(statusText)}">${sign}${offsetMinutes}m</span>`;
    }

    function escapeHtml(str) {
        if (!str) return "";
        return String(str)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#039;");
    }
});
