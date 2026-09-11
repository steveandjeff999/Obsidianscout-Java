document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) {
        return;
    }

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const isUserAdmin = Obsidianscout.isAdmin(me.role);
    if (!isUserAdmin) {
        document.getElementById("admin-locked").classList.remove("hidden");
        document.getElementById("admin-panel").classList.add("hidden");
        return;
    }

    const isSuperAdmin = me.role === "SUPERADMIN";
    if (isSuperAdmin) {
        document.getElementById("field-export-scope")?.classList.remove("hidden");
        document.getElementById("field-import-scope")?.classList.remove("hidden");
    }

    const exportTypeSelect = document.getElementById("export-type");
    const exportFormatSelect = document.getElementById("export-format");
    const btnExport = document.getElementById("btn-export");

    const dropZone = document.getElementById("drop-zone");
    const fileInput = document.getElementById("import-file");
    const fileInfo = document.getElementById("file-info");
    const btnImport = document.getElementById("btn-import");

    const reportCard = document.getElementById("import-report-card");
    const reportSummaryText = document.getElementById("report-summary-text");
    const reportTableBody = document.getElementById("report-table-body");

    // 1. Export Action
    btnExport.addEventListener("click", () => {
        const type = exportTypeSelect.value;
        const format = exportFormatSelect.value;
        const scope = isSuperAdmin ? (document.getElementById("export-scope")?.value || "team") : "team";
        Obsidianscout.setButtonLoading(btnExport, true, "Generating export...");

        const url = `/api/admin/export?type=${type}&format=${format}&scope=${scope}`;
        
        // Trigger download
        const a = document.createElement("a");
        a.href = url;
        const filename = scope === "global" ? `global_backup_${type}.${format === "obsidiandb" ? "obsidiandb" : "zip"}` : `team_${me.teamNumber}_backup_${type}.${format === "obsidiandb" ? "obsidiandb" : "zip"}`;
        a.download = filename;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        setTimeout(() => {
            Obsidianscout.setButtonLoading(btnExport, false);
            Obsidianscout.showToast("Export download started", "success");
        }, 1500);
    });

    // 2. Import Drag & Drop
    dropZone.addEventListener("click", (e) => {
        if (e.target !== fileInput) {
            fileInput.click();
        }
    });

    dropZone.addEventListener("dragover", (e) => {
        e.preventDefault();
        dropZone.classList.add("dragover");
    });

    dropZone.addEventListener("dragleave", () => {
        dropZone.classList.remove("dragover");
    });

    dropZone.addEventListener("drop", (e) => {
        e.preventDefault();
        dropZone.classList.remove("dragover");
        if (e.dataTransfer.files.length > 0) {
            fileInput.files = e.dataTransfer.files;
            handleFileSelected();
        }
    });

    fileInput.addEventListener("change", () => {
        handleFileSelected();
    });

    function handleFileSelected() {
        const file = fileInput.files[0];
        if (file) {
            fileInfo.textContent = `Selected file: ${file.name} (${(file.size / 1024).toFixed(1)} KB)`;
            fileInfo.classList.remove("hidden");
            btnImport.disabled = false;
            btnImport.classList.remove("secondary");
        } else {
            fileInfo.classList.add("hidden");
            btnImport.disabled = true;
            btnImport.classList.add("secondary");
        }
    }

    // 3. Import Action
    btnImport.addEventListener("click", async () => {
        const file = fileInput.files[0];
        if (!file) return;

        Obsidianscout.setButtonLoading(btnImport, true, "Uploading & Importing...");
        reportCard.classList.add("hidden");

        const formData = new FormData();
        formData.append("file", file);

        const scope = isSuperAdmin ? (document.getElementById("import-scope")?.value || "team") : "team";

        try {
            const report = await Obsidianscout.request(`/api/admin/import?scope=${scope}`, {
                method: "POST",
                body: formData
            });

            displayReport(report);
            Obsidianscout.showToast("Data imported successfully", "success");
        } catch (err) {
            console.error(err);
            Obsidianscout.showToast(err.message || "Import failed", "error");
        } finally {
            Obsidianscout.setButtonLoading(btnImport, false);
            fileInput.value = "";
            handleFileSelected();
        }
    });

    // 4. Render Import Report
    function displayReport(report) {
        const scopeName = report.scope === "global" ? "Global Scope" : "Team Scope";
        reportSummaryText.textContent = `Summary: ${report.message || "Success"}. Imported type: ${report.type === "entire" ? "Entire Database" : "Scouting Data Only"} (${scopeName}).`;
        reportTableBody.innerHTML = "";

        const items = [];

        if (report.type === "entire") {
            items.push({ name: "Users", imported: report.usersImported, skipped: report.usersSkipped, action: "Created" });
            items.push({ name: "Form Configs", imported: report.configsImported, skipped: report.configsUpdated, action: "Updated" });
            items.push({ name: "App Settings", imported: report.settingsImported, skipped: report.settingsUpdated, action: "Updated" });
            items.push({ name: "Alliances", imported: report.alliancesImported, skipped: report.alliancesSkipped, action: "Created" });
            items.push({ name: "Banners", imported: report.bannersImported, skipped: report.bannersSkipped, action: "Created" });
            items.push({ name: "Chat Messages", imported: report.chatsImported, skipped: report.chatsSkipped, action: "Created" });
        }

        items.push({ name: "Scouting Entries", imported: report.scoutingEntriesImported, skipped: report.scoutingEntriesSkipped, action: "Created" });
        items.push({ name: "Pit Scouting Entries", imported: report.pitEntriesImported, skipped: report.pitEntriesSkipped, action: "Created" });
        items.push({ name: "Qualitative Scouting Entries", imported: report.qualEntriesImported, skipped: report.qualEntriesSkipped, action: "Created" });

        if (report.scope === "global") {
            items.push({ name: "API Events (TBA)", imported: report.apiEventsImported, skipped: report.apiEventsSkipped, action: "Created/Updated" });
            items.push({ name: "API Teams (TBA)", imported: report.apiTeamsImported, skipped: report.apiTeamsSkipped, action: "Created/Updated" });
            items.push({ name: "API Matches (TBA)", imported: report.apiMatchesImported, skipped: report.apiMatchesSkipped, action: "Created/Updated" });
            items.push({ name: "EPA/OPR History Cache", imported: report.epaOprHistoryCacheImported, skipped: report.epaOprHistoryCacheSkipped, action: "Created/Updated" });
            items.push({ name: "Alliance Selections", imported: report.allianceSelectionsImported, skipped: report.allianceSelectionsSkipped, action: "Created/Updated" });
            items.push({ name: "Push Subscriptions", imported: report.pushSubscriptionsImported, skipped: report.pushSubscriptionsSkipped, action: "Created" });
            items.push({ name: "User Chat Last Reads", imported: report.chatLastReadsImported, skipped: report.chatLastReadsSkipped, action: "Created/Updated" });
            items.push({ name: "Password Reset Tokens", imported: report.passwordResetTokensImported, skipped: report.passwordResetTokensSkipped, action: "Created" });
        }

        items.forEach(item => {
            const tr = document.createElement("tr");
            
            const tdName = document.createElement("td");
            tdName.textContent = item.name;
            tdName.style.fontWeight = "600";
            
            const tdImported = document.createElement("td");
            tdImported.textContent = item.imported;
            tdImported.style.color = item.imported > 0 ? "var(--accent)" : "inherit";
            
            const tdSkipped = document.createElement("td");
            tdSkipped.textContent = item.skipped;
            tdSkipped.style.color = item.skipped > 0 ? "var(--muted)" : "inherit";
            
            const tdStatus = document.createElement("td");
            if (item.imported > 0) {
                tdStatus.innerHTML = `<span class="badge" style="background: rgba(40, 167, 69, 0.1); color: #28a745;">${item.action}</span>`;
            } else if (item.skipped > 0) {
                tdStatus.innerHTML = `<span class="badge" style="background: rgba(108, 117, 125, 0.1); color: #6c757d;">No Change</span>`;
            } else {
                tdStatus.innerHTML = `<span class="badge ghost">-</span>`;
            }

            tr.appendChild(tdName);
            tr.appendChild(tdImported);
            tr.appendChild(tdSkipped);
            tr.appendChild(tdStatus);

            reportTableBody.appendChild(tr);
        });

        reportCard.classList.remove("hidden");
    }

    // =========================================================================
    // Superadmin Automated Database Snapshots & Entire Database Recovery
    // =========================================================================
    if (isSuperAdmin) {
        const autoBackupSection = document.getElementById("auto-backup-section");
        autoBackupSection?.classList.remove("hidden");

        const autoBackupBadge = document.getElementById("auto-backup-badge");
        const chkAutoBackup = document.getElementById("chk-auto-backup-enabled");
        const inputRetention = document.getElementById("input-retention-days");
        const scheduleInfo = document.getElementById("snapshot-schedule-info");
        const btnSaveConfig = document.getElementById("btn-save-backup-config");
        const btnCreateSnapshot = document.getElementById("btn-create-manual-snapshot");
        const btnRefreshSnapshots = document.getElementById("btn-refresh-snapshots");
        const snapshotsTbody = document.getElementById("snapshots-table-body");
        const clusterTbody = document.getElementById("cluster-backup-nodes-tbody");

        // Restore upload elements
        const restoreDropZone = document.getElementById("restore-drop-zone");
        const restoreFileInput = document.getElementById("restore-file-input");
        const restoreFileInfo = document.getElementById("restore-file-info");
        const btnExecuteUploadRestore = document.getElementById("btn-execute-upload-restore");

        // Restore confirm modal elements
        const restoreModal = document.getElementById("restore-confirm-modal");
        const targetSnapshotLabel = document.getElementById("restore-target-snapshot-name");
        const inputRestoreConfirmText = document.getElementById("input-restore-confirm-text");
        const btnConfirmExecuteRestore = document.getElementById("btn-confirm-execute-restore");
        const btnCancelRestore = document.getElementById("btn-cancel-restore");
        const btnCloseRestoreModal = document.getElementById("btn-close-restore-modal");

        let pendingRestoreType = null; // "named" | "upload"
        let pendingRestoreFileName = null;
        let pendingRestoreFile = null;

        function formatBytes(bytes) {
            if (!bytes || bytes === 0) return "0 B";
            const k = 1024;
            const sizes = ["B", "KB", "MB", "GB"];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + " " + sizes[i];
        }

        async function loadSnapshotsStatus() {
            try {
                const status = await Obsidianscout.request("/api/admin/snapshots");
                renderLocalStatus(status);
            } catch (err) {
                console.error("Failed to load snapshot status:", err);
                Obsidianscout.showToast("Failed to load snapshots status: " + err.message, "error");
            }

            try {
                const cluster = await Obsidianscout.request("/api/admin/cluster/auto-backup");
                renderClusterStatus(cluster);
            } catch (err) {
                console.warn("Failed to load cluster auto-backup status:", err);
            }
        }

        function renderLocalStatus(status) {
            if (!status) return;

            if (chkAutoBackup) chkAutoBackup.checked = !!status.enabled;
            if (inputRetention) inputRetention.value = status.retentionDays || 30;

            if (autoBackupBadge) {
                if (status.enabled) {
                    autoBackupBadge.textContent = "Daily Active (02:54 UTC)";
                    autoBackupBadge.style.background = "rgba(16, 185, 129, 0.2)";
                    autoBackupBadge.style.color = "#34d399";
                } else {
                    autoBackupBadge.textContent = "Auto-Backup Disabled";
                    autoBackupBadge.style.background = "rgba(100, 116, 139, 0.2)";
                    autoBackupBadge.style.color = "#94a3b8";
                }
            }

            if (scheduleInfo) {
                if (status.enabled && status.nextScheduledRunUtc) {
                    scheduleInfo.textContent = `Next Run: ${status.nextScheduledRunUtc} UTC`;
                } else if (!status.enabled) {
                    scheduleInfo.textContent = "Auto-backup disabled on this server.";
                } else {
                    scheduleInfo.textContent = "Scheduled daily at 02:54 UTC.";
                }
            }

            if (snapshotsTbody) {
                snapshotsTbody.innerHTML = "";
                const snapshots = status.snapshots || [];
                if (snapshots.length === 0) {
                    snapshotsTbody.innerHTML = `<tr><td colspan="5" style="text-align: center; color: var(--muted); padding: 16px;">No local SQLite snapshots created yet.</td></tr>`;
                    return;
                }

                snapshots.forEach(s => {
                    const tr = document.createElement("tr");

                    const tdName = document.createElement("td");
                    tdName.innerHTML = `<strong style="font-family: monospace;">${s.fileName}</strong>`;

                    const tdCreated = document.createElement("td");
                    tdCreated.textContent = s.createdAtUtc || "--";

                    const tdSize = document.createElement("td");
                    tdSize.textContent = formatBytes(s.sizeBytes);

                    const tdTrigger = document.createElement("td");
                    if (s.isAutoBackup) {
                        tdTrigger.innerHTML = `<span class="badge" style="background: rgba(59, 130, 246, 0.15); color: #60a5fa;">Auto (02:54 UTC)</span>`;
                    } else {
                        tdTrigger.innerHTML = `<span class="badge ghost">Manual</span>`;
                    }

                    const tdActions = document.createElement("td");
                    tdActions.style.textAlign = "right";

                    // Download button
                    const btnDownload = document.createElement("a");
                    btnDownload.href = `/api/admin/snapshots/download/${encodeURIComponent(s.fileName)}`;
                    btnDownload.className = "btn ghost";
                    btnDownload.style.padding = "4px 8px";
                    btnDownload.style.fontSize = "12px";
                    btnDownload.style.marginRight = "6px";
                    btnDownload.textContent = "⬇️ Download";
                    btnDownload.download = s.fileName;

                    // Restore button
                    const btnRestore = document.createElement("button");
                    btnRestore.type = "button";
                    btnRestore.className = "btn danger";
                    btnRestore.style.padding = "4px 8px";
                    btnRestore.style.fontSize = "12px";
                    btnRestore.style.marginRight = "6px";
                    btnRestore.textContent = "🔄 Restore";
                    btnRestore.addEventListener("click", () => {
                        openRestoreModal("named", s.fileName);
                    });

                    // Delete button
                    const btnDelete = document.createElement("button");
                    btnDelete.type = "button";
                    btnDelete.className = "btn ghost";
                    btnDelete.style.padding = "4px 8px";
                    btnDelete.style.fontSize = "12px";
                    btnDelete.style.color = "var(--danger)";
                    btnDelete.textContent = "🗑️";
                    btnDelete.title = "Delete Snapshot";
                    btnDelete.addEventListener("click", async () => {
                        if (confirm(`Delete snapshot file "${s.fileName}"?`)) {
                            try {
                                await Obsidianscout.request(`/api/admin/snapshots/${encodeURIComponent(s.fileName)}`, {
                                    method: "DELETE"
                                });
                                Obsidianscout.showToast("Snapshot deleted", "success");
                                loadSnapshotsStatus();
                            } catch (e) {
                                Obsidianscout.showToast("Failed to delete snapshot: " + e.message, "error");
                            }
                        }
                    });

                    tdActions.appendChild(btnDownload);
                    tdActions.appendChild(btnRestore);
                    tdActions.appendChild(btnDelete);

                    tr.appendChild(tdName);
                    tr.appendChild(tdCreated);
                    tr.appendChild(tdSize);
                    tr.appendChild(tdTrigger);
                    tr.appendChild(tdActions);

                    snapshotsTbody.appendChild(tr);
                });
            }
        }

        function renderClusterStatus(cluster) {
            if (!cluster || !clusterTbody) return;
            const nodes = cluster.nodes || [];
            clusterTbody.innerHTML = "";

            if (nodes.length === 0) {
                clusterTbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--muted); padding: 16px;">No cluster nodes detected.</td></tr>`;
                return;
            }

            nodes.forEach(n => {
                const tr = document.createElement("tr");

                const tdNode = document.createElement("td");
                tdNode.innerHTML = `<strong>${n.nodeIp}</strong> ${n.isLocal ? '<span class="badge ghost" style="font-size: 10px; margin-left: 4px;">Local</span>' : ''}`;

                const tdDaily = document.createElement("td");
                const toggle = document.createElement("input");
                toggle.type = "checkbox";
                toggle.checked = !!n.enabled;
                toggle.style.cursor = "pointer";
                toggle.addEventListener("change", async () => {
                    const wantEnabled = toggle.checked;
                    try {
                        await Obsidianscout.request("/api/admin/cluster/auto-backup/toggle", {
                            method: "POST",
                            body: JSON.stringify({ targetIp: n.nodeIp, enabled: wantEnabled })
                        });
                        Obsidianscout.showToast(`Auto-backup ${wantEnabled ? "enabled" : "disabled"} on ${n.nodeIp}`, "success");
                        loadSnapshotsStatus();
                    } catch (e) {
                        Obsidianscout.showToast("Failed to toggle auto-backup: " + e.message, "error");
                        toggle.checked = !wantEnabled;
                    }
                });
                const label = document.createElement("label");
                label.style.display = "inline-flex";
                label.style.alignItems = "center";
                label.style.gap = "6px";
                label.style.cursor = "pointer";
                label.appendChild(toggle);
                label.appendChild(document.createTextNode(n.enabled ? "02:54 UTC" : "Disabled"));
                tdDaily.appendChild(label);

                const tdRetention = document.createElement("td");
                tdRetention.textContent = `${n.retentionDays || 30} days`;

                const tdCount = document.createElement("td");
                tdCount.textContent = n.snapshotsCount ?? (n.snapshots ? n.snapshots.length : 0);

                const tdStorage = document.createElement("td");
                tdStorage.textContent = formatBytes(n.totalSnapshotsSizeBytes || 0);

                const tdLast = document.createElement("td");
                tdLast.textContent = n.lastBackupTimeUtc || "--";

                const tdActions = document.createElement("td");
                tdActions.style.textAlign = "right";

                const btnNodeSnap = document.createElement("button");
                btnNodeSnap.type = "button";
                btnNodeSnap.className = "btn secondary";
                btnNodeSnap.style.padding = "4px 8px";
                btnNodeSnap.style.fontSize = "11px";
                btnNodeSnap.textContent = "📸 Snapshot";
                btnNodeSnap.addEventListener("click", async () => {
                    Obsidianscout.setButtonLoading(btnNodeSnap, true, "Saving...");
                    try {
                        const res = await Obsidianscout.request("/api/admin/cluster/auto-backup/create", {
                            method: "POST",
                            body: JSON.stringify({ targetIp: n.nodeIp })
                        });
                        Obsidianscout.showToast(res.message || `Snapshot created on ${n.nodeIp}`, res.success ? "success" : "error");
                        loadSnapshotsStatus();
                    } catch (e) {
                        Obsidianscout.showToast("Failed to trigger snapshot: " + e.message, "error");
                    } finally {
                        Obsidianscout.setButtonLoading(btnNodeSnap, false);
                    }
                });

                tdActions.appendChild(btnNodeSnap);

                tr.appendChild(tdNode);
                tr.appendChild(tdDaily);
                tr.appendChild(tdRetention);
                tr.appendChild(tdCount);
                tr.appendChild(tdStorage);
                tr.appendChild(tdLast);
                tr.appendChild(tdActions);

                clusterTbody.appendChild(tr);
            });
        }

        // Save local configuration
        btnSaveConfig?.addEventListener("click", async () => {
            const enabled = chkAutoBackup.checked;
            const retentionDays = parseInt(inputRetention.value, 10) || 30;

            Obsidianscout.setButtonLoading(btnSaveConfig, true, "Saving...");
            try {
                const res = await Obsidianscout.request("/api/admin/snapshots/config", {
                    method: "PUT",
                    body: JSON.stringify({
                        enabled: enabled,
                        retentionDays: retentionDays
                    })
                });
                renderLocalStatus(res);
                Obsidianscout.showToast("Auto-backup settings saved", "success");
                loadSnapshotsStatus();
            } catch (err) {
                Obsidianscout.showToast("Failed to save settings: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(btnSaveConfig, false);
            }
        });

        // Trigger manual snapshot on this node
        btnCreateSnapshot?.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(btnCreateSnapshot, true, "Creating snapshot...");
            try {
                const snapshot = await Obsidianscout.request("/api/admin/snapshots/create", {
                    method: "POST"
                });
                Obsidianscout.showToast(`Snapshot created: ${snapshot.fileName}`, "success");
                loadSnapshotsStatus();
            } catch (err) {
                Obsidianscout.showToast("Failed to create snapshot: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(btnCreateSnapshot, false);
            }
        });

        btnRefreshSnapshots?.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(btnRefreshSnapshots, true, "Refreshing...");
            await loadSnapshotsStatus();
            Obsidianscout.setButtonLoading(btnRefreshSnapshots, false);
            Obsidianscout.showToast("Snapshots refreshed", "info");
        });

        // Restore file drag & drop
        restoreDropZone?.addEventListener("click", (e) => {
            if (e.target !== restoreFileInput) {
                restoreFileInput.click();
            }
        });

        restoreDropZone?.addEventListener("dragover", (e) => {
            e.preventDefault();
            restoreDropZone.classList.add("dragover");
        });

        restoreDropZone?.addEventListener("dragleave", () => {
            restoreDropZone.classList.remove("dragover");
        });

        restoreDropZone?.addEventListener("drop", (e) => {
            e.preventDefault();
            restoreDropZone.classList.remove("dragover");
            if (e.dataTransfer.files.length > 0) {
                restoreFileInput.files = e.dataTransfer.files;
                handleRestoreFileSelected();
            }
        });

        restoreFileInput?.addEventListener("change", () => {
            handleRestoreFileSelected();
        });

        function handleRestoreFileSelected() {
            const file = restoreFileInput.files[0];
            if (file) {
                restoreFileInfo.textContent = `Selected snapshot: ${file.name} (${formatBytes(file.size)})`;
                restoreFileInfo.classList.remove("hidden");
                btnExecuteUploadRestore.disabled = false;
            } else {
                restoreFileInfo.classList.add("hidden");
                btnExecuteUploadRestore.disabled = true;
            }
        }

        btnExecuteUploadRestore?.addEventListener("click", () => {
            const file = restoreFileInput.files[0];
            if (!file) return;
            openRestoreModal("upload", file.name, file);
        });

        // Confirmation Modal Logic
        function openRestoreModal(type, targetName, file = null) {
            pendingRestoreType = type;
            pendingRestoreFileName = targetName;
            pendingRestoreFile = file;

            if (targetSnapshotLabel) targetSnapshotLabel.textContent = targetName;
            if (inputRestoreConfirmText) inputRestoreConfirmText.value = "";
            if (btnConfirmExecuteRestore) btnConfirmExecuteRestore.disabled = true;

            restoreModal?.classList.add("show");
            setTimeout(() => inputRestoreConfirmText?.focus(), 150);
        }

        function closeRestoreModal() {
            restoreModal?.classList.remove("show");
            pendingRestoreType = null;
            pendingRestoreFileName = null;
            pendingRestoreFile = null;
        }

        inputRestoreConfirmText?.addEventListener("input", () => {
            const val = inputRestoreConfirmText.value.trim().toUpperCase();
            btnConfirmExecuteRestore.disabled = val !== "RESTORE DATABASE";
        });

        btnCancelRestore?.addEventListener("click", closeRestoreModal);
        btnCloseRestoreModal?.addEventListener("click", closeRestoreModal);

        btnConfirmExecuteRestore?.addEventListener("click", async () => {
            if (inputRestoreConfirmText.value.trim().toUpperCase() !== "RESTORE DATABASE") return;

            Obsidianscout.setButtonLoading(btnConfirmExecuteRestore, true, "Restoring database...");
            try {
                let report;
                if (pendingRestoreType === "named") {
                    report = await Obsidianscout.request(`/api/admin/snapshots/restore/${encodeURIComponent(pendingRestoreFileName)}`, {
                        method: "POST"
                    });
                } else if (pendingRestoreType === "upload" && pendingRestoreFile) {
                    const formData = new FormData();
                    formData.append("file", pendingRestoreFile);
                    report = await Obsidianscout.request("/api/admin/snapshots/restore-upload", {
                        method: "POST",
                        body: formData
                    });
                }

                closeRestoreModal();
                if (report) {
                    Obsidianscout.showToast(`Database restored successfully! ${report.totalRowsRestored} records restored.`, "success");
                    alert(`Database restoration complete!\n\nSource: ${report.sourceFile}\nDuration: ${report.durationMs}ms\nTotal Records Restored: ${report.totalRowsRestored}\nSeed Superadmin Preserved: ${report.superadminPreserved ? 'Yes' : 'No'}\n\nThe page will now refresh.`);
                    window.location.reload();
                }
            } catch (err) {
                console.error("Restoration failed:", err);
                Obsidianscout.showToast("Database restoration failed: " + err.message, "error");
                alert("Database restoration failed: " + err.message);
            } finally {
                Obsidianscout.setButtonLoading(btnConfirmExecuteRestore, false);
            }
        });

        // Initial load of snapshots
        loadSnapshotsStatus();
    }
});
