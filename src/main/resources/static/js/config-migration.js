document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const isUserAdmin = Obsidianscout.isAdmin(me.role);
    if (!isUserAdmin) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("migration-container").classList.add("hidden");
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    let activeKind = urlParams.get("kind") || "game";
    if (!["game", "pit", "qual"].includes(activeKind)) {
        activeKind = "game";
    }

    let schemaStatus = null;
    let previewSamples = [];
    let currentSampleIndex = 0;

    // DOM Elements
    const kindButtons = document.querySelectorAll("[data-kind]");
    const statTotalEntries = document.getElementById("stat-total-entries");
    const statUnmatchedFields = document.getElementById("stat-unmatched-fields");
    const statNewFields = document.getElementById("stat-new-fields");
    const statConfigVersion = document.getElementById("stat-config-version");
    const mappingTableBody = document.getElementById("mapping-table-body");
    const noLegacyBanner = document.getElementById("no-legacy-fields-banner");
    const newFieldsCard = document.getElementById("new-fields-card");
    const newFieldsList = document.getElementById("new-fields-list");
    const previewBefore = document.getElementById("preview-before");
    const previewAfter = document.getElementById("preview-after");
    const btnPrevSample = document.getElementById("btn-prev-sample");
    const btnNextSample = document.getElementById("btn-next-sample");
    const sampleIndexLabel = document.getElementById("sample-index-label");
    const btnExecute = document.getElementById("btn-execute-migration");
    const btnRefreshPreview = document.getElementById("btn-refresh-preview");
    const btnAutoMatch = document.getElementById("btn-auto-match");

    // Kind Tab Switching
    function updateKindTabs() {
        kindButtons.forEach(btn => {
            btn.classList.toggle("active", btn.dataset.kind === activeKind);
        });
    }

    kindButtons.forEach(btn => {
        btn.addEventListener("click", async () => {
            const nextKind = btn.dataset.kind;
            if (nextKind === activeKind) return;
            activeKind = nextKind;
            updateKindTabs();
            // Update URL without reload
            const newUrl = new URL(window.location);
            newUrl.searchParams.set("kind", activeKind);
            window.history.replaceState({}, "", newUrl);
            await loadSchemaStatus();
        });
    });

    updateKindTabs();

    async function loadSchemaStatus() {
        try {
            Obsidianscout.showLoadingSpinner(mappingTableBody, "Loading schema details...");
            schemaStatus = await Obsidianscout.request(`/api/config-migration/status?kind=${activeKind}`);
            renderStatusOverview();
            renderMappingTable();
            renderNewFieldsBackfill();
            await fetchPreview();
        } catch (err) {
            console.error("Failed to load schema status:", err);
            Obsidianscout.showToast(err.message || "Failed to load migration status", "error");
        }
    }

    function renderStatusOverview() {
        if (!schemaStatus) return;
        statTotalEntries.textContent = schemaStatus.entryCount;
        statUnmatchedFields.textContent = schemaStatus.unmatchedDataKeys.length;
        statNewFields.textContent = schemaStatus.newConfigKeys.length;
        statConfigVersion.textContent = `v${schemaStatus.configVersion}`;
    }

    function renderMappingTable() {
        if (!schemaStatus) return;
        mappingTableBody.innerHTML = "";

        const allDataKeys = schemaStatus.dataKeys || [];
        const configFields = schemaStatus.configFields || [];

        if (allDataKeys.length === 0) {
            mappingTableBody.innerHTML = `<tr><td colspan="4" style="text-align: center; color: var(--muted); padding: 24px;">No existing scouting entries found for this category.</td></tr>`;
            noLegacyBanner.classList.add("hidden");
            return;
        }

        if (schemaStatus.unmatchedDataKeys.length === 0) {
            noLegacyBanner.classList.remove("hidden");
        } else {
            noLegacyBanner.classList.add("hidden");
        }

        allDataKeys.forEach(key => {
            const isMatched = !schemaStatus.unmatchedDataKeys.includes(key);
            const matchingField = configFields.find(f => f.id === key);

            const tr = document.createElement("tr");
            tr.dataset.oldKey = key;

            // Column 1: Legacy Key Badge
            const tdKey = document.createElement("td");
            const badge = document.createElement("span");
            badge.className = isMatched ? "matched-key-badge" : "legacy-key-badge";
            badge.textContent = key;
            tdKey.appendChild(badge);
            tr.appendChild(tdKey);

            // Column 2: Action Select
            const tdAction = document.createElement("td");
            const actionSelect = document.createElement("select");
            actionSelect.className = "input mapping-action-select";
            actionSelect.style.width = "100%";
            actionSelect.style.padding = "6px 8px";
            actionSelect.innerHTML = `
                <option value="map" ${isMatched ? "selected" : ""}>Map to Field</option>
                <option value="keep">Keep as-is</option>
                <option value="delete">Delete from records</option>
            `;
            tdAction.appendChild(actionSelect);
            tr.appendChild(tdAction);

            // Column 3: Target Field Select
            const tdTarget = document.createElement("td");
            const targetSelect = document.createElement("select");
            targetSelect.className = "input mapping-target-select";
            targetSelect.style.width = "100%";
            targetSelect.style.padding = "6px 8px";

            let targetOptionsHtml = `<option value="">-- Choose New Field --</option>`;
            configFields.forEach(f => {
                const isSelected = f.id === key;
                targetOptionsHtml += `<option value="${f.id}" ${isSelected ? "selected" : ""}>${f.label} (${f.id}) [${f.type}]</option>`;
            });
            targetSelect.innerHTML = targetOptionsHtml;
            tdTarget.appendChild(targetSelect);
            tr.appendChild(tdTarget);

            // Column 4: Status Badge
            const tdStatus = document.createElement("td");
            const statusSpan = document.createElement("span");
            statusSpan.className = "mapping-status-label";
            if (isMatched) {
                statusSpan.innerHTML = `<span style="color: #22c55e; font-size: 13px; font-weight: 500;">✓ Active Key</span>`;
            } else {
                statusSpan.innerHTML = `<span style="color: #f59e0b; font-size: 13px; font-weight: 500;">⚠️ Legacy Key</span>`;
            }
            tdStatus.appendChild(statusSpan);
            tr.appendChild(tdStatus);

            // Event Listeners for Dynamic Feedback
            actionSelect.addEventListener("change", () => {
                if (actionSelect.value === "delete") {
                    targetSelect.disabled = true;
                    statusSpan.innerHTML = `<span style="color: #ef4444; font-size: 13px;">Will be removed</span>`;
                } else if (actionSelect.value === "keep") {
                    targetSelect.disabled = true;
                    statusSpan.innerHTML = `<span style="color: var(--muted); font-size: 13px;">Preserve raw key</span>`;
                } else {
                    targetSelect.disabled = false;
                    const val = targetSelect.value;
                    if (val) {
                        statusSpan.innerHTML = `<span style="color: #3b82f6; font-size: 13px;">Maps to ${val}</span>`;
                    } else {
                        statusSpan.innerHTML = `<span style="color: #f59e0b; font-size: 13px;">Select target</span>`;
                    }
                }
                fetchPreview();
            });

            targetSelect.addEventListener("change", () => {
                const val = targetSelect.value;
                if (val) {
                    statusSpan.innerHTML = `<span style="color: #3b82f6; font-size: 13px;">Maps to ${val}</span>`;
                } else {
                    statusSpan.innerHTML = `<span style="color: #f59e0b; font-size: 13px;">Select target</span>`;
                }
                fetchPreview();
            });

            mappingTableBody.appendChild(tr);
        });
    }

    function renderNewFieldsBackfill() {
        if (!schemaStatus) return;
        newFieldsList.innerHTML = "";

        const newKeys = schemaStatus.newConfigKeys || [];
        const configFields = schemaStatus.configFields || [];

        if (newKeys.length === 0) {
            newFieldsCard.classList.add("hidden");
            return;
        }

        newFieldsCard.classList.remove("hidden");

        newKeys.forEach(key => {
            const field = configFields.find(f => f.id === key);
            if (!field) return;

            const div = document.createElement("div");
            div.className = "field";

            const label = document.createElement("label");
            label.textContent = `${field.label} (${field.id})`;
            div.appendChild(label);

            let inputEl;
            if (field.type === "checkbox") {
                inputEl = document.createElement("select");
                inputEl.innerHTML = `
                    <option value="">(None / Omit)</option>
                    <option value="false">false (Unchecked)</option>
                    <option value="true">true (Checked)</option>
                `;
            } else if (field.type === "counter" || field.type === "number" || field.type === "rating") {
                inputEl = document.createElement("input");
                inputEl.type = "number";
                inputEl.placeholder = "e.g. 0";
            } else if (field.options && field.options.length > 0) {
                inputEl = document.createElement("select");
                let opts = `<option value="">(None / Omit)</option>`;
                field.options.forEach(opt => {
                    opts += `<option value="${opt.value}">${opt.label} (${opt.value})</option>`;
                });
                inputEl.innerHTML = opts;
            } else {
                inputEl = document.createElement("input");
                inputEl.type = "text";
                inputEl.placeholder = "Default value string...";
            }

            inputEl.id = `default-val-${key}`;
            inputEl.dataset.fieldKey = key;
            inputEl.dataset.fieldType = field.type;
            inputEl.className = "input";
            inputEl.addEventListener("input", fetchPreview);
            inputEl.addEventListener("change", fetchPreview);

            div.appendChild(inputEl);
            newFieldsList.appendChild(div);
        });
    }

    function buildMigrationPayload() {
        const rows = mappingTableBody.querySelectorAll("tr[data-old-key]");
        const mappings = [];

        rows.forEach(tr => {
            const oldKey = tr.dataset.oldKey;
            const actionSelect = tr.querySelector(".mapping-action-select");
            const targetSelect = tr.querySelector(".mapping-target-select");

            const action = actionSelect ? actionSelect.value : "keep";
            const newKey = (action === "map" && targetSelect) ? targetSelect.value.trim() : null;

            mappings.push({
                oldKey: oldKey,
                newKey: newKey || null,
                action: action
            });
        });

        const defaultValues = {};
        const defaultInputs = newFieldsList.querySelectorAll("[data-field-key]");
        defaultInputs.forEach(input => {
            const key = input.dataset.fieldKey;
            const type = input.dataset.fieldType;
            const rawVal = input.value;

            if (rawVal !== "" && rawVal !== undefined) {
                if (type === "checkbox") {
                    defaultValues[key] = rawVal === "true";
                } else if (type === "counter" || type === "number" || type === "rating") {
                    defaultValues[key] = Number(rawVal) || 0;
                } else {
                    defaultValues[key] = rawVal;
                }
            }
        });

        return {
            configKind: activeKind,
            mappings: mappings,
            defaultValues: defaultValues
        };
    }

    async function fetchPreview() {
        const payload = buildMigrationPayload();
        try {
            const res = await Obsidianscout.request("/api/config-migration/preview", {
                method: "POST",
                json: payload
            });

            previewSamples = res.sampleEntries || [];
            currentSampleIndex = 0;
            updatePreviewUI();
        } catch (err) {
            console.warn("Failed to fetch migration preview:", err);
            previewBefore.textContent = "Error generating preview";
            previewAfter.textContent = err.message || "Preview failed";
        }
    }

    function updatePreviewUI() {
        if (previewSamples.length === 0) {
            previewBefore.textContent = "No existing records available to preview";
            previewAfter.textContent = "No existing records available to preview";
            btnPrevSample.disabled = true;
            btnNextSample.disabled = true;
            sampleIndexLabel.textContent = "0 samples";
            return;
        }

        btnPrevSample.disabled = currentSampleIndex <= 0;
        btnNextSample.disabled = currentSampleIndex >= previewSamples.length - 1;
        sampleIndexLabel.textContent = `Sample ${currentSampleIndex + 1} of ${previewSamples.length}`;

        const currentSample = previewSamples[currentSampleIndex];
        previewBefore.textContent = JSON.stringify(currentSample.before, null, 2);
        previewAfter.textContent = JSON.stringify(currentSample.after, null, 2);
    }

    btnPrevSample.addEventListener("click", () => {
        if (currentSampleIndex > 0) {
            currentSampleIndex--;
            updatePreviewUI();
        }
    });

    btnNextSample.addEventListener("click", () => {
        if (currentSampleIndex < previewSamples.length - 1) {
            currentSampleIndex++;
            updatePreviewUI();
        }
    });

    btnRefreshPreview.addEventListener("click", fetchPreview);

    // Auto-match similar fields
    btnAutoMatch.addEventListener("click", () => {
        if (!schemaStatus) return;
        const configFields = schemaStatus.configFields || [];
        const rows = mappingTableBody.querySelectorAll("tr[data-old-key]");

        let matchedCount = 0;
        rows.forEach(tr => {
            const oldKey = tr.dataset.oldKey;
            const targetSelect = tr.querySelector(".mapping-target-select");
            const actionSelect = tr.querySelector(".mapping-action-select");
            if (!targetSelect || !actionSelect) return;

            // If already matched, skip
            if (targetSelect.value) return;

            const normalizedOld = oldKey.toLowerCase().replace(/[^a-z0-9]/g, "");

            // Look for closest match
            const found = configFields.find(f => {
                const normId = f.id.toLowerCase().replace(/[^a-z0-9]/g, "");
                const normLabel = f.label.toLowerCase().replace(/[^a-z0-9]/g, "");
                return normId === normalizedOld || normLabel === normalizedOld || normId.includes(normalizedOld) || normalizedOld.includes(normId);
            });

            if (found) {
                targetSelect.value = found.id;
                actionSelect.value = "map";
                targetSelect.disabled = false;
                const statusSpan = tr.querySelector(".mapping-status-label");
                if (statusSpan) {
                    statusSpan.innerHTML = `<span style="color: #3b82f6; font-size: 13px;">Auto-matched to ${found.id}</span>`;
                }
                matchedCount++;
            }
        });

        if (matchedCount > 0) {
            Obsidianscout.showToast(`Auto-matched ${matchedCount} fields based on names.`, "success");
            fetchPreview();
        } else {
            Obsidianscout.showToast("No additional automatic field matches found.", "info");
        }
    });

    // Execute Migration
    btnExecute.addEventListener("click", async () => {
        const payload = buildMigrationPayload();
        const total = schemaStatus ? schemaStatus.entryCount : 0;

        if (total === 0) {
            Obsidianscout.showToast("No records available to migrate.", "warning");
            return;
        }

        const confirmMsg = `Are you sure you want to execute data migration on ${total} ${activeKind.toUpperCase()} scouting records?\n\nThis will rewrite record fields according to your mapping matrix. This action cannot be automatically undone.`;
        if (!confirm(confirmMsg)) {
            return;
        }

        try {
            Obsidianscout.setButtonLoading(btnExecute, true, "Migrating...");
            const res = await Obsidianscout.request("/api/config-migration/apply", {
                method: "POST",
                json: payload,
                button: btnExecute
            });

            Obsidianscout.showToast(res.message || "Migration completed successfully!", "success");
            alert(`Migration Complete!\n\n${res.message || `Successfully migrated ${res.count} records.`}`);
            await loadSchemaStatus();
        } catch (err) {
            console.error("Migration execution failed:", err);
            Obsidianscout.showToast(err.message || "Migration failed", "error");
        } finally {
            Obsidianscout.setButtonLoading(btnExecute, false);
        }
    });

    // Initial load
    await loadSchemaStatus();
});
