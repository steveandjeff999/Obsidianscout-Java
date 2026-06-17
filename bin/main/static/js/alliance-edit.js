(function () {
    'use strict';

    const { request, requireAuth, showToast, setUserBadge, setActiveNav,
            adjustNavForRole, wireLogout, initTheme, wireThemeToggle, isAdmin } = window.Obsidianscout;

    let currentUser = null;
    let allianceId = null;
    let alliance = null;
    let isAllianceAdmin = false;

    // Collaboration & Editor State
    let collabWs = null;
    let activeConfigKind = "game";
    let isEditingFromBroadcast = false;
    let currentConfig = { version: 1, title: "ObsidianScout Alliance Form", fields: [], analytics: [] };

    // DOM Elements
    let allianceNameInput, allianceYearInput, allianceEventCodeInput, allianceNotesInput, btnSaveDetails;
    let memberChipsList, inviteFormContainer, formInviteTeam, inviteTeamNumberInput;
    let editor, saveButton, exportButton, importInput;
    let btnVisual, btnRaw, containerVisual, containerRaw;
    let configTitleInput, configVersionInput, btnAddField, visualFieldsList;
    let configModeButtons;
    let allianceActiveToggle, btnImportLocal;

    const configModes = {
        game: {
            kindName: "Game form",
            defaultTitle: "Alliance Scouting Form",
            exportName: "alliance-match-config.json"
        },
        pit: {
            kindName: "Pit form",
            defaultTitle: "Alliance Pit Scouting",
            exportName: "alliance-pit-config.json"
        },
        qual: {
            kindName: "Qualitative form",
            defaultTitle: "Alliance Qualitative Scouting",
            exportName: "alliance-qualitative-config.json"
        }
    };

    function supportsPointsConfig() {
        return activeConfigKind !== "qual";
    }

    // ─────────────────────────────────────────────────────────────────
    // Init
    // ─────────────────────────────────────────────────────────────────

    async function init() {
        initTheme();
        currentUser = await requireAuth();
        if (!currentUser) return;

        setUserBadge(currentUser);
        setActiveNav();
        adjustNavForRole(currentUser);
        wireLogout();
        wireThemeToggle();

        // Parse query parameter id
        const urlParams = new URLSearchParams(window.location.search);
        allianceId = parseInt(urlParams.get('id'));

        if (isNaN(allianceId) || allianceId === null) {
            showToast('Invalid alliance ID', 'error');
            setTimeout(() => window.location.href = '/alliances', 1500);
            return;
        }

        // DOM elements lookup
        allianceNameInput = document.getElementById('alliance-name');
        allianceYearInput = document.getElementById('alliance-year');
        allianceEventCodeInput = document.getElementById('alliance-event-code');
        allianceNotesInput = document.getElementById('alliance-notes');
        btnSaveDetails = document.getElementById('btn-save-details');
        memberChipsList = document.getElementById('member-chips-list');
        inviteFormContainer = document.getElementById('invite-form-container');
        formInviteTeam = document.getElementById('form-invite-team');
        inviteTeamNumberInput = document.getElementById('invite-team-number');
        allianceActiveToggle = document.getElementById('alliance-active-toggle');
        btnImportLocal = document.getElementById('config-import-local');

        editor = document.getElementById("config-editor");
        saveButton = document.getElementById("config-save");
        exportButton = document.getElementById("config-export");
        importInput = document.getElementById("config-import");
        btnVisual = document.getElementById("btn-visual-editor");
        btnRaw = document.getElementById("btn-raw-editor");
        containerVisual = document.getElementById("visual-editor-container");
        containerRaw = document.getElementById("raw-editor-container");
        configTitleInput = document.getElementById("config-title");
        configVersionInput = document.getElementById("config-version");
        btnAddField = document.getElementById("btn-add-field");
        visualFieldsList = document.getElementById("visual-fields-list");
        configModeButtons = document.querySelectorAll("[data-config-kind]");

        await loadAllianceData();
        setupFormListeners();
        setupEditorTabListeners();
        setupRawEditorBroadcast();

        // Connect collaborative WebSocket for the initial game form
        connectCollaboration(allianceId, activeConfigKind);

        initSharedDataSection();
    }

    // ─────────────────────────────────────────────────────────────────
    // Load Alliance Data
    // ─────────────────────────────────────────────────────────────────

    async function loadAllianceData() {
        try {
            alliance = await request(`/api/alliances/${allianceId}`);
            if (!alliance) throw new Error("No alliance found");

            document.getElementById('alliance-page-title').textContent = `Edit Alliance: ${alliance.name}`;
            
            // Populate inputs
            allianceNameInput.value = alliance.name || '';
            allianceYearInput.value = alliance.year || '';
            allianceEventCodeInput.value = alliance.eventCode || '';
            allianceNotesInput.value = alliance.notes || '';

            // Check permissions
            const myMembership = alliance.members.find(m => m.teamNumber === currentUser?.teamNumber);
            isAllianceAdmin = myMembership && myMembership.status === 'ADMIN';

            if (allianceActiveToggle && myMembership) {
                allianceActiveToggle.checked = !!myMembership.active;
            }

            if (!isAllianceAdmin && !isAdmin(currentUser?.role)) {
                // Disable Details
                allianceNameInput.disabled = true;
                allianceYearInput.disabled = true;
                allianceEventCodeInput.disabled = true;
                allianceNotesInput.disabled = true;
                if (btnSaveDetails) btnSaveDetails.style.display = 'none';

                // Hide Invite
                if (inviteFormContainer) inviteFormContainer.style.display = 'none';

                // Lock Config Editor
                if (saveButton) {
                    saveButton.disabled = true;
                    saveButton.textContent = "Alliance Config (ReadOnly)";
                }
                if (btnAddField) btnAddField.style.display = 'none';
                if (configTitleInput) configTitleInput.disabled = true;
                if (configVersionInput) configVersionInput.disabled = true;
                if (importInput) importInput.parentElement.style.display = 'none';
                if (btnImportLocal) btnImportLocal.style.display = 'none';
            }

            renderMembersList();
        } catch (err) {
            console.error('Failed to load alliance:', err);
            showToast('Failed to load alliance data: ' + err.message, 'error');
            setTimeout(() => window.location.href = '/alliances', 2000);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // Render Members
    // ─────────────────────────────────────────────────────────────────

    function renderMembersList() {
        if (!memberChipsList) return;

        const members = alliance.members || [];
        const isUserAppAdmin = isAdmin(currentUser?.role);
        const canManage = isAllianceAdmin || isUserAppAdmin;

        memberChipsList.innerHTML = members.map(m => {
            const cls = m.status.toLowerCase();
            const label = m.status.charAt(0) + m.status.slice(1).toLowerCase();
            const canRemove = canManage && m.teamNumber !== currentUser?.teamNumber;
            
            // Can leave if not the last admin
            const isSelf = m.teamNumber === currentUser?.teamNumber && 
                (m.status !== 'ADMIN' || members.filter(x => x.status === 'ADMIN').length > 1);

            const canPromote = canManage && m.status === 'ACCEPTED';
            const promoteBtn = canPromote ? `<button class="promote-member btn-xs ghost" style="padding:1px 6px; font-size:10px; margin-left:6px; border-radius:4px;" type="button" data-team="${m.teamNumber}" title="Promote to Admin">Make Admin</button>` : '';
            const statusLabel = m.active ? `${label} (Active)` : `${label} (Inactive)`;

            return `<span class="member-chip ${cls}" title="Team ${m.teamNumber} — ${statusLabel}">
                Team ${m.teamNumber}
                <span style="opacity:0.55;font-weight:400;margin-left:2px;">(${statusLabel})</span>
                ${promoteBtn}
                ${(canRemove || isSelf) ? `<button class="remove-member" type="button" data-team="${m.teamNumber}" title="Remove team" aria-label="Remove Team ${m.teamNumber}">×</button>` : ''}
            </span>`;
        }).join('');
    }

    // ─────────────────────────────────────────────────────────────────
    // Actions & Event Listeners
    // ─────────────────────────────────────────────────────────────────

    function setupFormListeners() {
        // Details save
        document.getElementById('form-alliance-details')?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const name = allianceNameInput.value.trim();
            const yearVal = allianceYearInput.value.trim();
            const year = yearVal ? parseInt(yearVal) : null;
            const eventCode = allianceEventCodeInput.value.trim() || null;
            const notes = allianceNotesInput.value.trim() || null;

            if (!name) return;
            btnSaveDetails.disabled = true;

            try {
                await request(`/api/alliances/${allianceId}`, {
                    method: 'PUT',
                    json: { name, year, eventCode, notes }
                });
                showToast('Alliance details updated!', 'success');
                await loadAllianceData();
            } catch (err) {
                showToast('Failed to save details: ' + err.message, 'error');
            } finally {
                btnSaveDetails.disabled = false;
            }
        });

        // Invite team
        formInviteTeam?.addEventListener('submit', async (e) => {
            e.preventDefault();
            const teamNumber = parseInt(inviteTeamNumberInput.value);
            if (!teamNumber || teamNumber <= 0) return;

            const sendBtn = document.getElementById('btn-send-invite');
            sendBtn.disabled = true;

            try {
                await request(`/api/alliances/${allianceId}/invite`, {
                    method: 'POST',
                    json: { partnerTeamNumber: teamNumber }
                });
                showToast(`Invite sent to Team ${teamNumber}!`, 'success');
                inviteTeamNumberInput.value = '';
                await loadAllianceData();
            } catch (err) {
                showToast('Failed to send invite: ' + err.message, 'error');
            } finally {
                sendBtn.disabled = false;
            }
        });

        // Delegate member actions
        document.addEventListener('click', async (e) => {
            const promoteBtn = e.target.closest('.promote-member');
            if (promoteBtn) {
                const teamNumber = parseInt(promoteBtn.dataset.team);
                if (confirm(`Promote Team ${teamNumber} to alliance admin?`)) {
                    try {
                        await request(`/api/alliances/${allianceId}/members/${teamNumber}/promote`, { method: 'POST' });
                        showToast(`Team ${teamNumber} promoted to admin.`, 'success');
                        await loadAllianceData();
                    } catch (err) {
                        showToast('Failed to promote: ' + err.message, 'error');
                    }
                }
                return;
            }

            const removeBtn = e.target.closest('.remove-member');
            if (removeBtn) {
                const teamNumber = parseInt(removeBtn.dataset.team);
                const isSelf = teamNumber === currentUser?.teamNumber;
                const msg = isSelf
                    ? 'Leave this alliance? You will no longer see their scouting data.'
                    : `Remove Team ${teamNumber} from this alliance?`;
                
                if (confirm(msg)) {
                    try {
                        await request(`/api/alliances/${allianceId}/members/${teamNumber}`, { method: 'DELETE' });
                        showToast(isSelf ? 'You left the alliance.' : `Team ${teamNumber} removed.`, 'success');
                        if (isSelf) {
                            window.location.href = '/alliances';
                        } else {
                            await loadAllianceData();
                        }
                    } catch (err) {
                        showToast('Failed: ' + err.message, 'error');
                    }
                }
            }
        });

        // Toggle Active status from edit page
        allianceActiveToggle?.addEventListener('change', async (e) => {
            const active = e.target.checked;
            try {
                await request(`/api/alliances/${allianceId}/toggle-active`, {
                    method: 'POST',
                    json: { active }
                });
                showToast(active ? 'Alliance activated!' : 'Alliance deactivated.', 'success');
                await loadAllianceData();
            } catch (err) {
                showToast('Failed to toggle active status: ' + err.message, 'error');
                e.target.checked = !active;
            }
        });

        // Import Local config
        btnImportLocal?.addEventListener('click', async () => {
            let apiPath = "/api/config";
            if (activeConfigKind === "pit") {
                apiPath = "/api/pit-config";
            } else if (activeConfigKind === "qual") {
                apiPath = "/api/qual-config";
            }

            try {
                const localConfig = await request(apiPath + "?local=true");
                const text = JSON.stringify(localConfig, null, 2);
                if (isValidJson(text)) {
                    editor.value = text;
                    currentConfig = localConfig;
                    if (!currentConfig.fields) currentConfig.fields = [];
                    if (!currentConfig.analytics) currentConfig.analytics = [];

                    configTitleInput.value = currentConfig.title || "";
                    configVersionInput.value = currentConfig.version || 1;

                    renderVisualFields();
                    sendConfigEdit(text);
                    showToast("Imported local team configurations!", "success");
                }
            } catch (err) {
                showToast("Failed to import local config: " + err.message, "error");
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // Config Editors Tabs & Buttons Setup
    // ─────────────────────────────────────────────────────────────────

    function setupEditorTabListeners() {
        // Sub-tab switching (Visual vs Raw JSON)
        if (btnVisual && btnRaw && containerVisual && containerRaw) {
            btnVisual.addEventListener("click", () => {
                const text = editor.value.trim();
                if (!isValidJson(text)) {
                    showToast("Raw JSON is invalid. Fix syntax errors before switching to Visual Editor.", "error");
                    return;
                }
                try {
                    currentConfig = JSON.parse(text);
                    if (!currentConfig.fields) currentConfig.fields = [];
                    if (!currentConfig.analytics) currentConfig.analytics = [];
                    
                    configTitleInput.value = currentConfig.title || "";
                    configVersionInput.value = currentConfig.version || 1;
                    
                    renderVisualFields();
                    
                    btnRaw.classList.remove("active");
                    btnVisual.classList.add("active");
                    containerRaw.classList.add("hidden");
                    containerVisual.classList.remove("hidden");
                } catch (err) {
                    showToast("Failed to parse config properties", "error");
                }
            });
            
            btnRaw.addEventListener("click", () => {
                updateRawFromVisual();
                btnVisual.classList.remove("active");
                btnRaw.classList.add("active");
                containerVisual.classList.add("hidden");
                containerRaw.classList.remove("hidden");
            });
        }

        // Form kind tabs (Game, Pit, Qualitative)
        configModeButtons.forEach((button) => {
            button.addEventListener("click", () => {
                const nextKind = button.dataset.configKind;
                if (!nextKind || nextKind === activeConfigKind || !configModes[nextKind]) return;

                activeConfigKind = nextKind;
                configModeButtons.forEach((btn) => {
                    btn.classList.toggle("active", btn.dataset.configKind === activeConfigKind);
                });

                // Reconnect WebSocket for this config kind
                connectCollaboration(allianceId, activeConfigKind);
            });
        });

        // Config metadata change hooks
        if (configTitleInput) configTitleInput.addEventListener("input", updateRawFromVisual);
        if (configVersionInput) configVersionInput.addEventListener("input", updateRawFromVisual);

        // Add Field
        if (btnAddField) btnAddField.addEventListener("click", addField);

        // Manual Save Configuration (Sends current state over WS)
        saveButton?.addEventListener("click", () => {
            const text = editor.value.trim();
            if (!isValidJson(text)) {
                showToast("Config JSON is invalid", "error");
                return;
            }
            sendConfigEdit(text);
            showToast("Alliance config saved", "success");
        });

        // Export config
        exportButton?.addEventListener("click", () => {
            updateRawFromVisual();
            const blob = new Blob([editor.value], { type: "application/json" });
            const url = URL.createObjectURL(blob);
            const link = document.createElement("a");
            link.href = url;
            link.download = configModes[activeConfigKind].exportName;
            link.click();
            URL.revokeObjectURL(url);
        });

        // Import config
        importInput?.addEventListener("change", () => {
            const file = importInput.files[0];
            if (!file) return;

            const reader = new FileReader();
            reader.onload = () => {
                const text = reader.result;
                if (isValidJson(text)) {
                    editor.value = text;
                    try {
                        currentConfig = JSON.parse(text);
                        if (!currentConfig.fields) currentConfig.fields = [];
                        if (!currentConfig.analytics) currentConfig.analytics = [];
                        
                        configTitleInput.value = currentConfig.title || "";
                        configVersionInput.value = currentConfig.version || 1;
                        
                        renderVisualFields();
                        sendConfigEdit(text);
                        showToast("Config imported and updated", "success");
                    } catch (err) {
                        showToast("Imported JSON structure has errors", "error");
                    }
                } else {
                    showToast("Invalid JSON file", "error");
                }
            };
            reader.readAsText(file);
        });
    }

    function setupRawEditorBroadcast() {
        let rawEditTimer = null;
        editor.addEventListener("input", () => {
            if (isEditingFromBroadcast) return;
            const text = editor.value.trim();
            try {
                JSON.parse(text);
                clearTimeout(rawEditTimer);
                rawEditTimer = setTimeout(() => {
                    sendConfigEdit(text);
                }, 500);
            } catch (e) {}
        });
    }

    // ─────────────────────────────────────────────────────────────────
    // Collaborative Live Socket Connection
    // ─────────────────────────────────────────────────────────────────

    function connectCollaboration(allianceId, kind) {
        if (collabWs) {
            collabWs.close();
            collabWs = null;
        }
        
        const existing = document.getElementById('collaboration-bar');
        if (existing) existing.remove();

        const cardHeader = document.querySelector('main.main-content h1').parentElement;
        const bar = document.createElement('div');
        bar.id = 'collaboration-bar';
        bar.className = 'collaboration-bar';
        bar.innerHTML = `
            <span class="pulse-dot"></span>
            <span class="collab-text">Collaborating live on <strong>${configModes[kind].kindName}</strong></span>
            <div class="collab-editors" id="collab-editors-list"></div>
        `;
        cardHeader.appendChild(bar);

        const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
        const wsUrl = `${protocol}//${window.location.host}/api/alliances/${allianceId}/collaborate/${kind}`;
        
        collabWs = new WebSocket(wsUrl);
        
        collabWs.onmessage = (event) => {
            try {
                const msg = JSON.parse(event.data);
                if (msg.type === 'init') {
                    updateEditorsList(msg.editors);
                    if (msg.configJson) {
                        updateLocalConfig(msg.configJson, false);
                    }
                } else if (msg.type === 'presence') {
                    updateEditorsList(msg.editors);
                } else if (msg.type === 'update') {
                    if (msg.configJson) {
                        updateLocalConfig(msg.configJson, true, msg.editor);
                    }
                }
            } catch (e) {
                console.error("Error parsing WebSocket message", e);
            }
        };
        
        collabWs.onclose = () => {
            const collabBar = document.getElementById('collaboration-bar');
            if (collabBar) collabBar.style.display = 'none';
        };
    }

    function sendConfigEdit(configJson) {
        if (isEditingFromBroadcast) return;
        if (!isAllianceAdmin && !isAdmin(currentUser?.role)) return; // read-only
        if (collabWs && collabWs.readyState === WebSocket.OPEN) {
            collabWs.send(JSON.stringify({
                type: 'edit',
                configJson: configJson
            }));
        }
    }

    function updateEditorsList(editors) {
        const listEl = document.getElementById('collab-editors-list');
        if (!listEl) return;
        
        listEl.innerHTML = (editors || []).map(ed => {
            const initials = ed.username.substring(0, 2).toUpperCase();
            const roleLabel = ed.role === 'SUPERADMIN' ? 'SA' : (ed.role === 'ADMIN' ? 'Admin' : 'Scout');
            return `
                <div class="collab-avatar" title="${ed.username} (Team ${ed.teamNumber} — ${roleLabel})">
                    ${initials}
                </div>
            `;
        }).join('');
    }

    function updateLocalConfig(newJson, isFromBroadcast, editorUser) {
        if (isFromBroadcast) {
            if (document.activeElement === editor) {
                showPendingMergeBanner(newJson, editorUser);
                return;
            }
        }
        
        hidePendingMergeBanner();
        
        isEditingFromBroadcast = true;
        editor.value = newJson;
        try {
            currentConfig = JSON.parse(newJson);
            if (!currentConfig.fields) currentConfig.fields = [];
            if (!currentConfig.analytics) currentConfig.analytics = [];
            
            if (configTitleInput) configTitleInput.value = currentConfig.title || "";
            if (configVersionInput) configVersionInput.value = currentConfig.version || 1;
            
            renderVisualFields();
            
            if (isFromBroadcast && editorUser) {
                showToast(`Forms updated in real-time by ${editorUser}`, "info");
            }
        } catch (e) {
            console.error("Error parsing config from broadcast", e);
        } finally {
            isEditingFromBroadcast = false;
        }
    }

    function showPendingMergeBanner(newJson, editorUser) {
        let banner = document.getElementById('raw-pending-merge-banner');
        if (!banner) {
            banner = document.createElement('div');
            banner.id = 'raw-pending-merge-banner';
            banner.className = 'sharing-notice mb-12';
            banner.style.borderColor = '#e67e22';
            banner.style.background = 'rgba(230, 126, 34, 0.08)';
            containerRaw.insertBefore(banner, containerRaw.firstChild);
        }
        
        banner.innerHTML = `
            <span class="icon">⚠️</span>
            <div style="flex:1;">
                <strong>Conflict Warning:</strong> ${editorUser} has modified this configuration. 
                What would you like to do?
                <div style="margin-top:6px; display:flex; gap:8px;">
                    <button class="btn-xs" id="btn-merge-load" type="button">Load their changes</button>
                    <button class="btn-xs ghost" id="btn-merge-ignore" type="button">Keep my draft</button>
                </div>
            </div>
        `;
        
        document.getElementById('btn-merge-load').onclick = () => {
            updateLocalConfig(newJson, false);
            hidePendingMergeBanner();
        };
        
        document.getElementById('btn-merge-ignore').onclick = () => {
            hidePendingMergeBanner();
            sendConfigEdit(editor.value);
        };
    }
    
    function hidePendingMergeBanner() {
        const banner = document.getElementById('raw-pending-merge-banner');
        if (banner) banner.remove();
    }

    // ─────────────────────────────────────────────────────────────────
    // Visual Editor Logic (DOM Builders)
    // ─────────────────────────────────────────────────────────────────

    function renderVisualFields() {
        if (!visualFieldsList) return;
        
        if (!isAllianceAdmin && !isAdmin(currentUser?.role)) {
            visualFieldsList.classList.add("view-only-editor");
        } else {
            visualFieldsList.classList.remove("view-only-editor");
        }

        visualFieldsList.innerHTML = "";

        const fields = currentConfig.fields || [];
        if (fields.length === 0) {
            const emptyNotice = document.createElement("div");
            emptyNotice.className = "notice";
            emptyNotice.style.textAlign = "center";
            emptyNotice.style.padding = "24px";
            emptyNotice.textContent = "No fields configured. Click '+ Add Field' to start building!";
            visualFieldsList.appendChild(emptyNotice);
            return;
        }

        const hasManualSections = fields.some((field) => field.type === "section");
        if (hasManualSections) {
            fields.forEach((field, index) => {
                const cardNode = createFieldCard(field, index);
                visualFieldsList.appendChild(cardNode);
            });
            return;
        }

        const groups = [
            { key: "auto", title: "Auto" },
            { key: "teleop", title: "Teleop" },
            { key: "endgame", title: "Endgame" },
            { key: "", title: "General" }
        ];

        groups.forEach((group) => {
            const groupFields = fields.filter((field) => {
                const phase = resolveFieldPhase(field);
                if (!group.key) return !phase;
                return phase === group.key;
            });
            if (!groupFields.length) return;

            const header = document.createElement("div");
            header.className = "form-section";
            const headerTitle = document.createElement("h3");
            headerTitle.textContent = group.title;
            header.appendChild(headerTitle);
            visualFieldsList.appendChild(header);

            groupFields.forEach((field) => {
                const fieldIndex = fields.indexOf(field);
                const cardNode = createFieldCard(field, fieldIndex);
                visualFieldsList.appendChild(cardNode);
            });
        });
    }

    function createFieldCard(field, index) {
        const card = document.createElement("div");
        card.className = "field-card";
        
        // Header
        const header = document.createElement("div");
        header.className = "field-card-header";
        
        const title = document.createElement("h4");
        title.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? (Obsidianscout.localize(field.label) || `Field ${index + 1}`) : (field.label || `Field ${index + 1}`);
        
        const controls = document.createElement("div");
        controls.className = "field-card-controls";
        
        const typeBadge = document.createElement("span");
        typeBadge.className = "type-badge";
        typeBadge.textContent = field.type || "text";
        controls.appendChild(typeBadge);
        
        // Move Up
        const btnUp = document.createElement("button");
        btnUp.type = "button";
        btnUp.className = "btn-control-icon";
        btnUp.innerHTML = "▲";
        btnUp.title = "Move Up";
        btnUp.disabled = index === 0;
        btnUp.addEventListener("click", () => moveField(index, -1));
        controls.appendChild(btnUp);
        
        // Move Down
        const btnDown = document.createElement("button");
        btnDown.type = "button";
        btnDown.className = "btn-control-icon";
        btnDown.innerHTML = "▼";
        btnDown.title = "Move Down";
        btnDown.disabled = index === currentConfig.fields.length - 1;
        btnDown.addEventListener("click", () => moveField(index, 1));
        controls.appendChild(btnDown);
        
        // Delete
        const btnDel = document.createElement("button");
        btnDel.type = "button";
        btnDel.className = "btn-control-icon delete";
        btnDel.innerHTML = "🗑️";
        btnDel.title = "Delete Field";
        btnDel.addEventListener("click", () => deleteField(index));
        controls.appendChild(btnDel);
        
        header.appendChild(title);
        header.appendChild(controls);
        card.appendChild(header);
        
        // Body
        const body = document.createElement("div");
        body.className = "field-card-body";
        
        // Label Input
        const divLabel = document.createElement("div");
        divLabel.className = "field";
        const labelTag = document.createElement("label");
        labelTag.textContent = 'Field Label';
        const inputLabel = document.createElement("input");
        inputLabel.type = "text";
        inputLabel.value = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : (field.label || "");
        inputLabel.placeholder = "e.g. Teleop Cycles";
        inputLabel.addEventListener("input", (e) => {
            const lang = (window.Obsidianscout && typeof Obsidianscout.safeGetItem === 'function') ? (Obsidianscout.safeGetItem('obsidianscout:lang') || 'en') : 'en';
            const val = e.target.value;
            if (field && typeof field.label === 'object' && field.label !== null) {
                field.label[lang] = val;
            } else {
                field.label = val;
            }
            title.textContent = val || `Field ${index + 1}`;
            
            // Auto-slugify ID
            const inputId = divId.querySelector("input");
            if (inputId && shouldAutoUpdateId(field, inputId.value)) {
                const base = slugify(e.target.value);
                const autoId = ensureUniqueSlug(base, collectFieldIds(index, field));
                field.id = autoId;
                field._autoId = autoId;
                inputId.value = autoId;
            }
            updateRawFromVisual();
        });
        divLabel.appendChild(labelTag);
        divLabel.appendChild(inputLabel);
        body.appendChild(divLabel);
        
        // ID Input
        const divId = document.createElement("div");
        divId.className = "field";
        const labelId = document.createElement("label");
        labelId.textContent = 'Field ID / Slug';
        const inputId = document.createElement("input");
        inputId.type = "text";
        inputId.value = field.id || "";
        inputId.placeholder = "e.g. teleopCycles";
        inputId.addEventListener("input", (e) => {
            field.id = e.target.value;
            field._autoId = null;
            updateRawFromVisual();
        });
        divId.appendChild(labelId);
        divId.appendChild(inputId);
        body.appendChild(divId);
        
        // Type Select
        const divType = document.createElement("div");
        divType.className = "field";
        const labelType = document.createElement("label");
        labelType.textContent = 'Type';
        const selectType = document.createElement("select");
        const types = ["text", "textarea", "number", "counter", "rating", "checkbox", "select", "section"];
        types.forEach((t) => {
            const opt = document.createElement("option");
            opt.value = t;
            opt.textContent = t.toUpperCase();
            opt.selected = field.type === t;
            selectType.appendChild(opt);
        });
        selectType.addEventListener("change", (e) => {
            field.type = e.target.value;
            if (field.type === "select" && !field.options) {
                field.options = [];
            }
            if ((field.type === "number" || field.type === "counter" || field.type === "rating") && field.min === undefined) {
                field.min = field.type === "rating" ? 1 : 0;
                field.max = field.type === "rating" ? 5 : 10;
                field.step = 1;
            }
            updateRawFromVisual();
            renderVisualFields();
        });
        divType.appendChild(labelType);
        divType.appendChild(selectType);
        body.appendChild(divType);

        // Phase Select
        const divPhase = document.createElement("div");
        divPhase.className = "field";
        const labelPhase = document.createElement("label");
        labelPhase.textContent = 'Phase';
        const selectPhase = document.createElement("select");
        const phaseOptions = [
            { value: "", label: 'General' },
            { value: "auto", label: 'Auto' },
            { value: "teleop", label: 'Teleop' },
            { value: "endgame", label: 'Endgame' }
        ];
        phaseOptions.forEach((phase) => {
            const option = document.createElement("option");
            option.value = phase.value;
            option.textContent = phase.label;
            selectPhase.appendChild(option);
        });
        selectPhase.value = field.phase || resolveFieldPhase(field) || "";
        selectPhase.addEventListener("change", (e) => {
            field.phase = e.target.value || "";
            updateRawFromVisual();
        });
        divPhase.appendChild(labelPhase);
        divPhase.appendChild(selectPhase);
        body.appendChild(divPhase);
        
        // Required Checkbox
        const divReq = document.createElement("div");
        divReq.className = "field";
        const labelReq = document.createElement("label");
        labelReq.textContent = 'Required';
        const labelWrap = document.createElement("label");
        labelWrap.style.display = "flex";
        labelWrap.style.alignItems = "center";
        labelWrap.style.gap = "8px";
        labelWrap.style.cursor = "pointer";
        labelWrap.style.marginTop = "8px";
        const inputReq = document.createElement("input");
        inputReq.type = "checkbox";
        inputReq.checked = !!field.required;
        inputReq.addEventListener("change", (e) => {
            field.required = e.target.checked;
            updateRawFromVisual();
        });
        labelWrap.appendChild(inputReq);
        labelWrap.appendChild(document.createTextNode(' Is Required'));
        divReq.appendChild(labelReq);
        divReq.appendChild(labelWrap);
        body.appendChild(divReq);
        
        if (field.type === "section") {
            divId.style.display = "none";
            divReq.style.display = "none";
            divPhase.style.display = "none";
        } else {
            // Type-specific configs
            if (field.type === "number" || field.type === "counter" || field.type === "rating") {
                const divMin = document.createElement("div");
                divMin.className = "field";
                divMin.innerHTML = `<label>Min Value</label><input type="number" value="${field.min !== undefined ? field.min : 0}" />`;
                divMin.querySelector("input").addEventListener("input", (e) => {
                    field.min = Number(e.target.value);
                    updateRawFromVisual();
                });
                body.appendChild(divMin);

                const divMax = document.createElement("div");
                divMax.className = "field";
                divMax.innerHTML = `<label>Max Value</label><input type="number" value="${field.max !== undefined ? field.max : 10}" />`;
                divMax.querySelector("input").addEventListener("input", (e) => {
                    field.max = Number(e.target.value);
                    updateRawFromVisual();
                });
                body.appendChild(divMax);
            }

            if (supportsPointsConfig() && (field.type === "number" || field.type === "counter" || field.type === "rating" || field.type === "checkbox")) {
                const divPoints = document.createElement("div");
                divPoints.className = "field";
                divPoints.innerHTML = `<label>Points per action</label><input type="number" step="any" value="${field.pointsPer !== undefined ? field.pointsPer : 0}" />`;
                divPoints.querySelector("input").addEventListener("input", (e) => {
                    field.pointsPer = Number(e.target.value);
                    updateRawFromVisual();
                });
                body.appendChild(divPoints);
            }

            if (field.type === "select") {
                const divOpts = document.createElement("div");
                divOpts.className = "field";
                divOpts.style.gridColumn = "1 / -1";
                divOpts.innerHTML = `<label>Select Options</label><div class="select-options-list grid gap-8 mt-6"></div><button type="button" class="btn btn-xs mt-6" style="padding:4px 8px;font-size:11px;">+ Add Option</button>`;
                
                const optsList = divOpts.querySelector(".select-options-list");
                const addOptBtn = divOpts.querySelector("button");

                const renderOptions = () => {
                    optsList.innerHTML = "";
                    (field.options || []).forEach((opt, optIdx) => {
                        const optRow = document.createElement("div");
                        optRow.style.display = "flex";
                        optRow.style.gap = "8px";
                        optRow.style.alignItems = "center";
                        const localizedOptLabel = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(opt.label) : (opt.label || "");
                        optRow.innerHTML = `
                            <input type="text" placeholder="Option Label" value="${localizedOptLabel}" style="flex:1;" />
                            <input type="text" placeholder="Option Value" value="${opt.value || ''}" style="flex:1;" />
                            ${supportsPointsConfig() ? `<input type="number" placeholder="Points" value="${opt.points !== undefined ? opt.points : 0}" style="width:70px;" />` : ''}
                            <button type="button" class="btn-control-icon delete" title="Delete Option">×</button>
                        `;
                        const inputs = optRow.querySelectorAll("input");
                        inputs[0].addEventListener("input", (ev) => {
                            const lang = (window.Obsidianscout && typeof Obsidianscout.safeGetItem === 'function') ? (Obsidianscout.safeGetItem('obsidianscout:lang') || 'en') : 'en';
                            const val = ev.target.value;
                            if (opt && typeof opt.label === 'object' && opt.label !== null) {
                                opt.label[lang] = val;
                            } else {
                                opt.label = val;
                            }
                            if (!opt.value || opt.value === slugify(inputs[0].defaultValue)) {
                                opt.value = slugify(ev.target.value);
                                inputs[1].value = opt.value;
                            }
                            updateRawFromVisual();
                        });
                        inputs[1].addEventListener("input", (ev) => {
                            opt.value = ev.target.value;
                            updateRawFromVisual();
                        });
                        if (supportsPointsConfig() && inputs[2]) {
                            inputs[2].addEventListener("input", (ev) => {
                                opt.points = Number(ev.target.value);
                                updateRawFromVisual();
                            });
                        }
                        optRow.querySelector(".delete").addEventListener("click", () => {
                            field.options.splice(optIdx, 1);
                            renderOptions();
                            updateRawFromVisual();
                        });
                        optsList.appendChild(optRow);
                    });
                };
                renderOptions();

                addOptBtn.addEventListener("click", () => {
                    if (!field.options) field.options = [];
                    field.options.push({ label: "Option " + (field.options.length + 1), value: "option_" + (field.options.length + 1), points: 0 });
                    renderOptions();
                    updateRawFromVisual();
                });
                body.appendChild(divOpts);
            }
        }

        card.appendChild(body);
        
        // Disable controls if read-only
        if (!isAllianceAdmin && !isAdmin(currentUser?.role)) {
            card.querySelectorAll("input, select, textarea, button").forEach(c => c.disabled = true);
        }

        return card;
    }

    function addField() {
        const fields = currentConfig.fields || [];
        const baseSlug = "field_" + (fields.length + 1);
        const uniqueSlug = ensureUniqueSlug(baseSlug, collectFieldIds());
        
        const newField = {
            id: uniqueSlug,
            label: "New Field " + (fields.length + 1),
            type: "counter",
            required: false,
            _autoId: uniqueSlug
        };
        
        fields.push(newField);
        renderVisualFields();
        updateRawFromVisual();

        // Scroll to bottom
        setTimeout(() => {
            const cards = visualFieldsList.querySelectorAll(".field-card");
            if (cards.length) {
                cards[cards.length - 1].scrollIntoView({ behavior: 'smooth' });
                cards[cards.length - 1].querySelector("input")?.focus();
            }
        }, 80);
    }

    function moveField(index, dir) {
        const fields = currentConfig.fields || [];
        const target = index + dir;
        if (target < 0 || target >= fields.length) return;
        const temp = fields[index];
        fields[index] = fields[target];
        fields[target] = temp;
        renderVisualFields();
        updateRawFromVisual();
    }

    function deleteField(index) {
        const fields = currentConfig.fields || [];
        if (confirm(`Delete field "${fields[index].label || fields[index].id}"?`)) {
            fields.splice(index, 1);
            renderVisualFields();
            updateRawFromVisual();
        }
    }

    function updateRawFromVisual() {
        if (isEditingFromBroadcast) return;
        
        const titleVal = configTitleInput.value.trim() || configModes[activeConfigKind].defaultTitle;
        const versionVal = Number(configVersionInput.value) || 1;
        const fields = currentConfig.fields || [];

        const cleanedFields = fields.map((field) => {
            let normalizedLabel = "";
            if (field.label !== undefined && field.label !== null) {
                if (typeof field.label === 'string') {
                    normalizedLabel = field.label.trim();
                } else if (typeof field.label === 'object') {
                    const obj = {};
                    Object.keys(field.label).forEach((k) => {
                        const v = field.label[k];
                        obj[k] = (typeof v === 'string') ? v.trim() : v;
                    });
                    normalizedLabel = obj;
                }
            }

            const cleaned = {
                id: field.id ? field.id.trim() : "",
                label: normalizedLabel,
                type: field.type || "text",
                required: !!field.required
            };
            
            const type = cleaned.type;
            
            if (type === "section") {
                delete cleaned.required;
                return cleaned;
            }

            if (field.phase) {
                cleaned.phase = String(field.phase);
            }
            
            if (type === "number" || type === "counter" || type === "rating") {
                if (field.min !== undefined && field.min !== null && field.min !== "") {
                    cleaned.min = Number(field.min);
                }
                if (field.max !== undefined && field.max !== null && field.max !== "") {
                    cleaned.max = Number(field.max);
                }
                if (field.step !== undefined && field.step !== null && field.step !== "") {
                    cleaned.step = Number(field.step);
                }
            }
            
            if (supportsPointsConfig() && (type === "number" || type === "counter" || type === "rating" || type === "checkbox")) {
                if (field.pointsPer !== undefined && field.pointsPer !== null && field.pointsPer !== "") {
                    cleaned.pointsPer = Number(field.pointsPer);
                }
            }
            
            if (type === "select") {
                cleaned.options = (field.options || []).map((opt) => {
                    let normalizedOptLabel = "";
                    if (opt.label !== undefined && opt.label !== null) {
                        if (typeof opt.label === 'string') {
                            normalizedOptLabel = opt.label.trim();
                        } else if (typeof opt.label === 'object') {
                            const o = {};
                            Object.keys(opt.label).forEach((k) => {
                                const v = opt.label[k];
                                o[k] = (typeof v === 'string') ? v.trim() : v;
                            });
                            normalizedOptLabel = o;
                        }
                    }
                    return {
                        label: normalizedOptLabel,
                        value: opt.value ? opt.value.trim() : "",
                        ...(supportsPointsConfig() ? { points: opt.points !== undefined && opt.points !== null ? Number(opt.points) : 0 } : {})
                    };
                });
            }
            
            return cleaned;
        });

        const cleanedConfig = {
            title: titleVal,
            version: versionVal,
            fields: cleanedFields,
            analytics: currentConfig.analytics || []
        };

        editor.value = JSON.stringify(cleanedConfig, null, 2);
        sendConfigEdit(editor.value);
    }

    // ─────────────────────────────────────────────────────────────────
    // Utility helpers
    // ─────────────────────────────────────────────────────────────────

    function isValidJson(text) {
        try {
            JSON.parse(text);
            return true;
        } catch (error) {
            return false;
        }
    }

    function slugify(text) {
        if (!text) return "";
        return text.toString().toLowerCase().trim()
            .replace(/\s+/g, '-')
            .replace(/[^\w\-]+/g, '')
            .replace(/\-\-+/g, '-');
    }

    function shouldAutoUpdateId(field, currentId) {
        if (!currentId || currentId === "") return true;
        if (field._autoId && field._autoId === currentId) return true;
        return false;
    }

    function collectFieldIds(skipIndex = -1, skipField = null) {
        const fields = currentConfig.fields || [];
        return fields
            .filter((f, idx) => idx !== skipIndex && f !== skipField)
            .map(f => f.id)
            .filter(id => !!id);
    }

    function ensureUniqueSlug(base, existingSlugs) {
        let slug = base;
        let counter = 1;
        const set = new Set(existingSlugs);
        while (set.has(slug)) {
            slug = `${base}_${counter}`;
            counter++;
        }
        return slug;
    }

    function resolveFieldPhase(field) {
        if (field.phase) return field.phase;
        const id = (field.id || "").toLowerCase();
        if (id.startsWith("auto")) return "auto";
        if (id.startsWith("teleop") || id.startsWith("tele")) return "teleop";
        if (id.startsWith("endgame") || id.startsWith("end")) return "endgame";
        return "";
    }

    // Inject View-Only Styles dynamically
    const collabStyle = document.createElement('style');
    collabStyle.textContent = `
        .view-only-editor input,
        .view-only-editor select,
        .view-only-editor textarea,
        .view-only-editor button {
            pointer-events: none !important;
            opacity: 0.6 !important;
        }
        .collaboration-bar {
            display: flex;
            align-items: center;
            gap: 12px;
            background: hsla(210, 100%, 96%, 0.5);
            border: 1px solid hsla(210, 100%, 80%, 0.3);
            border-radius: var(--radius-sm);
            padding: 10px 16px;
            margin-top: 10px;
            font-size: 13px;
            color: var(--muted);
            font-weight: 500;
        }
        body.theme-dark .collaboration-bar {
            background: hsla(210, 30%, 15%, 0.3);
            border-color: hsla(210, 30%, 25%, 0.3);
            color: #a0aec0;
        }
        .pulse-dot {
            width: 8px;
            height: 8px;
            background-color: #2ecc71;
            border-radius: 50%;
            box-shadow: 0 0 0 0 rgba(46, 204, 113, 0.7);
            animation: pulse 1.6s infinite;
        }
        @keyframes pulse {
            0% {
                transform: scale(0.95);
                box-shadow: 0 0 0 0 rgba(46, 204, 113, 0.7);
            }
            70% {
                transform: scale(1);
                box-shadow: 0 0 0 8px rgba(46, 204, 113, 0);
            }
            100% {
                transform: scale(0.95);
                box-shadow: 0 0 0 0 rgba(46, 204, 113, 0);
            }
        }
        .collab-editors {
            display: flex;
            align-items: center;
            gap: 6px;
            margin-left: auto;
        }
        .collab-avatar {
            width: 26px;
            height: 26px;
            border-radius: 50%;
            background-color: var(--accent);
            color: white;
            font-weight: 700;
            font-size: 11px;
            display: flex;
            align-items: center;
            justify-content: center;
            border: 2px solid var(--surface);
            box-shadow: var(--shadow-sm);
            cursor: pointer;
            position: relative;
        }
        .shared-entry-item {
            cursor: pointer;
            padding: 10px 12px;
            border-radius: 6px;
            border: 1px solid rgba(0,0,0,0.08);
            background: var(--surface);
            transition: all 0.2s ease;
        }
        .shared-entry-item:hover {
            border-color: var(--accent);
            background: rgba(0, 0, 0, 0.02);
        }
        .shared-entry-item.active {
            border-color: var(--accent) !important;
            background: rgba(106, 0, 255, 0.06) !important;
            box-shadow: 0 0 0 1px var(--accent);
        }
        body.theme-dark .shared-entry-item:hover {
            background: rgba(255, 255, 255, 0.02);
        }
        body.theme-dark .shared-entry-item.active {
            background: rgba(106, 0, 255, 0.12) !important;
        }
    `;
    document.head.appendChild(collabStyle);

    // Shared Alliance Data State
    let sharedEntries = [];
    let selectedSharedEntry = null;
    let activeDataTab = 'match'; // 'match', 'pit', 'qual'
    let searchFilter = "";
    let sharedConfigs = {};

    function initSharedDataSection() {
        const sharedDataCard = document.getElementById('shared-data-card');
        const sharedEntriesList = document.getElementById('shared-entries-list');
        const sharedSearchInput = document.getElementById('shared-search-input');
        const sharedEntryInspector = document.getElementById('shared-entry-inspector');
        const sharedEntryEmptyState = document.getElementById('shared-entry-empty-state');
        const sharedEntryEditorFormContainer = document.getElementById('shared-entry-editor-form-container');
        const btnDeleteSharedEntry = document.getElementById('btn-delete-shared-entry');
        const formEditSharedEntry = document.getElementById('form-edit-shared-entry');
        const sharedEntryFieldsContainer = document.getElementById('shared-entry-fields-container');
        const btnCancelSharedEntry = document.getElementById('btn-cancel-shared-entry');
        const sharedDataTabs = document.querySelectorAll('#shared-data-tabs .tab');

        if (!sharedDataCard) return;

        // Bind page-level tabs (Manage vs Shared Data)
        const pageTabs = document.querySelectorAll('#alliance-page-tabs .tab');
        const manageTabContent = document.getElementById('alliance-manage-tab-content');
        pageTabs.forEach(tab => {
            tab.addEventListener('click', () => {
                pageTabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                if (tab.dataset.pageTab === 'manage') {
                    manageTabContent.classList.remove('hidden');
                    sharedDataCard.classList.add('hidden');
                } else {
                    manageTabContent.classList.add('hidden');
                    sharedDataCard.classList.remove('hidden');
                    loadSharedData();
                }
            });
        });

        // Bind tabs
        sharedDataTabs.forEach(tab => {
            tab.addEventListener('click', () => {
                sharedDataTabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                activeDataTab = tab.dataset.dataTab;
                selectedSharedEntry = null;
                showEmptyState();
                loadSharedData();
            });
        });

        // Bind search
        sharedSearchInput?.addEventListener('input', (e) => {
            searchFilter = e.target.value.toLowerCase().trim();
            renderSharedEntriesList();
        });

        // Bind Cancel button
        btnCancelSharedEntry?.addEventListener('click', (e) => {
            e.preventDefault();
            selectedSharedEntry = null;
            showEmptyState();
            renderSharedEntriesList();
        });

        // Bind Delete button
        btnDeleteSharedEntry?.addEventListener('click', async (e) => {
            e.preventDefault();
            e.stopPropagation();
            if (!selectedSharedEntry) return;
            const targetType = activeDataTab === 'match' ? 'Match' : (activeDataTab === 'pit' ? 'Pit' : 'Qualitative');
            const targetLabel = selectedSharedEntry.targetTeamNumber ? `for Team ${selectedSharedEntry.targetTeamNumber}` : '';
            if (!confirm(`Are you sure you want to delete this shared ${targetType} entry ${targetLabel}?`)) return;

            try {
                let endpoint = `/api/scouting/${selectedSharedEntry.id}`;
                if (activeDataTab === 'pit') endpoint = `/api/pit-scouting/${selectedSharedEntry.id}`;
                else if (activeDataTab === 'qual') endpoint = `/api/qual-scouting/${selectedSharedEntry.id}`;

                await request(endpoint, { method: 'DELETE' });
                showToast("Entry deleted successfully", "success");
                selectedSharedEntry = null;
                showEmptyState();
                loadSharedData();
            } catch (err) {
                showToast("Failed to delete entry: " + err.message, "error");
            }
        });

        // Bind form submit (Save Changes)
        formEditSharedEntry?.addEventListener('submit', async (e) => {
            e.preventDefault();
            if (!selectedSharedEntry) return;

            const config = sharedConfigs[activeDataTab];
            if (!config) {
                showToast("Configuration not loaded, cannot save changes", "error");
                return;
            }

            const payload = buildSharedPayload(config.fields, formEditSharedEntry);
            if (!payload) return;

            // Preserve eventKey, matchKey, matchNumber, targetTeamNumber
            payload.eventKey = selectedSharedEntry.eventKey;
            payload.targetTeamNumber = selectedSharedEntry.targetTeamNumber;
            if (activeDataTab !== 'pit') {
                payload.matchKey = selectedSharedEntry.matchKey;
                payload.matchNumber = selectedSharedEntry.matchNumber;
            }

            try {
                let endpoint = `/api/scouting/${selectedSharedEntry.id}`;
                if (activeDataTab === 'pit') endpoint = `/api/pit-scouting/${selectedSharedEntry.id}`;
                else if (activeDataTab === 'qual') endpoint = `/api/qual-scouting/${selectedSharedEntry.id}`;

                const updated = await request(endpoint, {
                    method: 'PUT',
                    json: { data: payload }
                });

                showToast("Entry updated successfully", "success");
                
                // update local cached list entry and refresh
                const idx = sharedEntries.findIndex(x => x.id === selectedSharedEntry.id);
                if (idx !== -1) {
                    sharedEntries[idx] = updated;
                }
                selectedSharedEntry = updated;
                inspectEntry(updated);
                renderSharedEntriesList();
            } catch (err) {
                showToast("Failed to save entry: " + err.message, "error");
            }
        });

        // Helper: show empty state
        function showEmptyState() {
            sharedEntryEmptyState.style.display = 'flex';
            sharedEntryEditorFormContainer.classList.add('hidden');
        }

        // Helper: inspect entry
        async function inspectEntry(entry) {
            sharedEntryEmptyState.style.display = 'none';
            sharedEntryEditorFormContainer.classList.remove('hidden');

            document.getElementById('shared-inspector-title').textContent = `Team ${entry.targetTeamNumber}`;
            let metaHtml = `Owner: Team ${entry.ownerTeamNumber} | Date: ${new Date(entry.createdAt).toLocaleString()}`;
            if (activeDataTab !== 'pit') {
                metaHtml = `Match: ${entry.matchNumber || entry.matchKey || '?'} | ` + metaHtml;
            }
            document.getElementById('shared-inspector-meta').innerHTML = metaHtml;

            // Permission check: admin OR user's own team OR SUPERADMIN
            const hasEditPermission = isAllianceAdmin || 
                                      (window.Obsidianscout && window.Obsidianscout.isAdmin && window.Obsidianscout.isAdmin(currentUser?.role)) || 
                                      entry.ownerTeamNumber === currentUser?.teamNumber;

            // Hide/show delete button based on permission
            if (hasEditPermission) {
                btnDeleteSharedEntry.style.display = 'block';
                formEditSharedEntry.querySelector('button[type="submit"]').style.display = 'block';
            } else {
                btnDeleteSharedEntry.style.display = 'none';
                formEditSharedEntry.querySelector('button[type="submit"]').style.display = 'none';
            }

            // Load config and build fields
            let config = sharedConfigs[activeDataTab];
            if (!config) {
                try {
                    let configKind = activeDataTab === 'match' ? 'game' : activeDataTab;
                    config = await request(`/api/alliances/${allianceId}/config/${configKind}`);
                    sharedConfigs[activeDataTab] = config;
                } catch (e) {
                    console.error("Failed to load config for inspection", e);
                    sharedEntryFieldsContainer.innerHTML = `<div class="notice error">Failed to load configuration</div>`;
                    return;
                }
            }

            // Build form fields
            sharedEntryFieldsContainer.innerHTML = "";
            const reserved = new Set(["eventKey", "matchKey", "matchNumber", "targetTeamNumber"]);
            const fields = config.fields || [];

            fields
                .filter(field => !reserved.has(field.id) && field.type !== 'section')
                .forEach(field => {
                    const node = buildSharedField(field);
                    sharedEntryFieldsContainer.appendChild(node);
                });

            // Populate form fields with current values
            applySharedEntryToForm(entry, fields, formEditSharedEntry);

            // Disable all fields if no permission
            const inputs = formEditSharedEntry.querySelectorAll("input, select, textarea, button");
            inputs.forEach(input => {
                if (input.type === 'submit') return;
                input.disabled = !hasEditPermission;
            });
        }

        // Render dynamic field
        function buildSharedField(field) {
            const wrapper = document.createElement("div");
            wrapper.className = "field";

            const label = document.createElement("label");
            label.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;
            label.htmlFor = `shared-field-${field.id}`;

            let input;
            let actualInput = null;
            switch (field.type) {
                case "number":
                    input = document.createElement("input");
                    input.type = "number";
                    applySharedNumberBounds(input, field);
                    break;
                case "counter":
                    ({ wrapper: input, input: actualInput } = buildSharedCounter(field));
                    break;
                case "rating":
                    ({ wrapper: input, input: actualInput } = buildSharedRating(field));
                    break;
                case "select":
                    input = document.createElement("select");
                    const options = field.options || [];
                    options.forEach((option) => {
                        const optionNode = document.createElement("option");
                        if (typeof option === "string") {
                            optionNode.value = option;
                            optionNode.textContent = option;
                        } else {
                            optionNode.value = option.value;
                            optionNode.textContent = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(option.label) : option.label;
                        }
                        input.appendChild(optionNode);
                    });
                    break;
                case "checkbox":
                    input = document.createElement("input");
                    input.type = "checkbox";
                    break;
                case "textarea":
                    input = document.createElement("textarea");
                    break;
                default:
                    input = document.createElement("input");
                    input.type = "text";
                    break;
            }

            const target = actualInput || input;
            if (target instanceof HTMLElement) {
                target.id = `shared-field-${field.id}`;
                target.name = field.id;
                if (field.required) {
                    target.required = true;
                }
            }

            wrapper.appendChild(label);
            wrapper.appendChild(input);
            return wrapper;
        }

        // Counter helper
        function buildSharedCounter(field) {
            const wrapper = document.createElement("div");
            wrapper.className = "counter";
            const minus = document.createElement("button");
            minus.type = "button";
            minus.textContent = "-";

            const input = document.createElement("input");
            input.type = "number";
            input.value = field.min || 0;
            applySharedNumberBounds(input, field);

            const plus = document.createElement("button");
            plus.type = "button";
            plus.textContent = "+";

            minus.addEventListener("click", () => {
                const step = field.step || 1;
                input.value = String(Math.max(Number(input.value || 0) - step, field.min || 0));
                input.dispatchEvent(new Event("input", { bubbles: true }));
            });

            plus.addEventListener("click", () => {
                const step = field.step || 1;
                const max = field.max ?? Number.POSITIVE_INFINITY;
                input.value = String(Math.min(Number(input.value || 0) + step, max));
                input.dispatchEvent(new Event("input", { bubbles: true }));
            });

            wrapper.appendChild(minus);
            wrapper.appendChild(input);
            wrapper.appendChild(plus);
            return { wrapper, input };
        }

        // Rating helper
        function buildSharedRating(field) {
            const wrapper = document.createElement("div");
            wrapper.className = "rating";

            const input = document.createElement("input");
            input.type = "range";
            input.min = field.min || 1;
            input.max = field.max || 5;
            input.step = field.step || 1;
            input.value = input.min;

            const output = document.createElement("output");
            output.textContent = input.value;
            input.addEventListener("input", () => {
                output.textContent = input.value;
            });

            wrapper.appendChild(input);
            wrapper.appendChild(output);
            return { wrapper, input };
        }

        function applySharedNumberBounds(input, field) {
            if (field.min !== null && field.min !== undefined) input.min = field.min;
            if (field.max !== null && field.max !== undefined) input.max = field.max;
            if (field.step !== null && field.step !== undefined) input.step = field.step;
        }

        function applySharedEntryToForm(entry, fields, form) {
            if (!entry || !entry.data) return;
            fields.forEach((field) => {
                if (field.type === "section") return;
                const input = form.querySelector(`[name='${field.id}']`);
                if (!input) return;
                const value = entry.data[field.id];
                if (value === undefined || value === null) return;
                if (field.type === "checkbox") {
                    input.checked = Boolean(value);
                    return;
                }
                input.value = value;
                if (field.type === "rating") {
                    const output = input.parentElement?.querySelector("output");
                    if (output) output.textContent = input.value;
                }
            });
        }

        function buildSharedPayload(fields, form) {
            const payload = {};
            for (const field of fields) {
                if (field.type === 'section') continue;
                const input = form.querySelector(`[name='${field.id}']`);
                if (!input) continue;
                const value = readSharedFieldValue(field, input);
                const label = (window.Obsidianscout && typeof Obsidianscout.localize === 'function') ? Obsidianscout.localize(field.label) : field.label;

                if (field.required && (value === null || value === "")) {
                    showToast(`Missing ${label}`, "error");
                    return null;
                }

                if (value !== null && value !== "") {
                    if (field.type === "number" || field.type === "counter" || field.type === "rating") {
                        const numVal = Number(value);
                        if (field.min !== null && field.min !== undefined && numVal < field.min) {
                            showToast(`${label} must be at least ${field.min}`, "error");
                            return null;
                        }
                        if (field.max !== null && field.max !== undefined && numVal > field.max) {
                            showToast(`${label} must be at most ${field.max}`, "error");
                            return null;
                        }
                    }
                    payload[field.id] = value;
                }
            }
            return payload;
        }

        function readSharedFieldValue(field, input) {
            if (field.type === "checkbox") return input.checked;
            if (field.type === "number" || field.type === "counter" || field.type === "rating") {
                return input.value === "" ? null : Number(input.value);
            }
            return input.value.trim();
        }

        async function loadSharedData() {
            if (!alliance || !alliance.eventKey) {
                sharedEntries = [];
                renderSharedEntriesList();
                return;
            }
            try {
                let endpoint = '/api/scouting?includePrescout=true&all=true';
                if (activeDataTab === 'pit') endpoint = '/api/pit-scouting?includePrescout=true&all=true';
                else if (activeDataTab === 'qual') endpoint = '/api/qual-scouting?includePrescout=true&all=true';

                const data = await request(endpoint);
                sharedEntries = (data || []).filter(e => e.eventKey === alliance.eventKey);
                renderSharedEntriesList();
            } catch (err) {
                console.error('Failed to load shared data:', err);
            }
        }

        function renderSharedEntriesList() {
            sharedEntriesList.innerHTML = "";
            const filtered = sharedEntries.filter(e => {
                if (!searchFilter) return true;
                const targetTeam = String(e.targetTeamNumber || "");
                const ownerTeam = String(e.ownerTeamNumber || "");
                const matchNum = String(e.matchNumber || "");
                return targetTeam.includes(searchFilter) || ownerTeam.includes(searchFilter) || matchNum.includes(searchFilter);
            });

            if (filtered.length === 0) {
                sharedEntriesList.innerHTML = `<div class="notice" style="text-align: center; padding: 12px; grid-column: 1 / -1;">No entries found</div>`;
                return;
            }

            filtered.forEach(entry => {
                const div = document.createElement('div');
                div.className = `shared-entry-item ${selectedSharedEntry && selectedSharedEntry.id === entry.id ? 'active' : ''}`;
                
                let title = `Team ${entry.targetTeamNumber}`;
                if (activeDataTab !== 'pit') {
                    title += ` - Match ${entry.matchNumber || entry.matchKey || '?'}`;
                }
                
                let warnIcon = "";
                if (entry.hasDiscrepancy) {
                    warnIcon = `<span style="color: #eab308; margin-left: 6px; font-weight: bold;" title="Discrepancy detected between partner teams: ${(entry.conflictingTeams || []).join(', ')}">⚠️</span>`;
                }

                div.innerHTML = `
                    <div style="font-weight: 600; font-size: 14px; display: flex; align-center: center;">${title}${warnIcon}</div>
                    <div style="font-size: 11px; color: var(--muted); display: flex; justify-content: space-between; margin-top: 4px;">
                        <span>Owner: Team ${entry.ownerTeamNumber}</span>
                        <span>${new Date(entry.createdAt).toLocaleDateString()}</span>
                    </div>
                `;
                
                div.addEventListener('click', () => {
                    selectedSharedEntry = entry;
                    renderSharedEntriesList();
                    inspectEntry(entry);
                });
                
                sharedEntriesList.appendChild(div);
            });
        }

        // load data initially
        loadSharedData();
    }

    document.addEventListener('DOMContentLoaded', init);
})();
