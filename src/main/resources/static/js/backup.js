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
    // The server builds the export in the background and keeps it in parts; the page waits for it, then fetches one
    // part per request and saves the joined file. A single long download would be cut off: Cloudflare gives up after
    // 100 seconds without a reply, and the server cancels a response that streams for a minute.
    const exportProgressBox = document.getElementById("export-progress");
    const exportProgressBar = document.getElementById("export-progress-bar");
    const exportProgressText = document.getElementById("export-progress-text");
    const PENDING_EXPORT_KEY = "obsidianscout.backupExport";
    const EXPORT_POLL_MS = 2000;
    const EXPORT_PART_ATTEMPTS = 5;
    const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

    function showExportProgress(percent, text) {
        exportProgressBox.classList.remove("hidden");
        if (percent === null) exportProgressBar.removeAttribute("value");
        else exportProgressBar.value = Math.max(0, Math.min(100, percent));
        exportProgressText.textContent = text;
    }

    function hideExportProgress() {
        exportProgressBox.classList.add("hidden");
        exportProgressBar.value = 0;
        exportProgressText.textContent = "";
    }

    function setPendingExport(exportId) {
        try {
            if (exportId) localStorage.setItem(PENDING_EXPORT_KEY, exportId);
            else localStorage.removeItem(PENDING_EXPORT_KEY);
        } catch (e) {}
    }

    function getPendingExport() {
        try { return localStorage.getItem(PENDING_EXPORT_KEY); } catch (e) { return null; }
    }

    function exportRetryable(err) {
        const status = err && err.status;
        return !status || status === 408 || status === 429 || status >= 500;
    }

    async function waitForExport(exportId) {
        const started = Date.now();
        let failures = 0;
        while (true) {
            let status;
            try {
                status = await Obsidianscout.request(`/api/admin/export/jobs/${exportId}`, { noCache: true, skipEntryStore: true });
                failures = 0;
            } catch (err) {
                if (!exportRetryable(err) || ++failures >= 15) throw err;
                await pause(EXPORT_POLL_MS * 2);
                continue;
            }
            if (status.state === "done") return status;
            if (status.state === "failed") {
                const err = new Error(status.error || "Export failed");
                err.status = 422;
                throw err;
            }
            const seconds = Math.round((Date.now() - started) / 1000);
            showExportProgress(null, `Building export... ${seconds}s. Large backups with many photos can take several minutes.`);
            await pause(EXPORT_POLL_MS);
        }
    }

    // fetch() directly: the shared request helper reads replies as text, and these parts are binary.
    async function fetchExportPart(exportId, index) {
        for (let attempt = 1; ; attempt++) {
            try {
                const response = await fetch(`/api/admin/export/jobs/${exportId}/parts/${index}`, {
                    credentials: "same-origin",
                    cache: "no-store",
                    headers: { "X-Requested-With": "XMLHttpRequest" }
                });
                if (!response.ok) {
                    let message = `Download failed (HTTP ${response.status})`;
                    try { message = (await response.json()).error || message; } catch (e) {}
                    const err = new Error(message);
                    err.status = response.status;
                    throw err;
                }
                return await response.blob();
            } catch (err) {
                if (attempt >= EXPORT_PART_ATTEMPTS || !exportRetryable(err)) throw err;
                await pause(1000 * attempt * attempt);
            }
        }
    }

    async function downloadExport(exportId) {
        const status = await waitForExport(exportId);
        const parts = [];
        for (let i = 0; i < status.totalChunks; i++) {
            parts.push(await fetchExportPart(exportId, i));
            const percent = Math.round(((i + 1) / status.totalChunks) * 100);
            showExportProgress(percent, `Downloading ${status.fileName}: part ${i + 1} of ${status.totalChunks} (${percent}%)`);
        }
        const file = new Blob(parts, { type: status.contentType || "application/octet-stream" });
        if (file.size !== status.totalBytes) {
            throw new Error(`The download is incomplete (${file.size} of ${status.totalBytes} bytes). Try the export again.`);
        }
        const url = URL.createObjectURL(file);
        const a = document.createElement("a");
        a.href = url;
        a.download = status.fileName;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        // Give the browser time to start saving before the object URL goes away.
        setTimeout(() => URL.revokeObjectURL(url), 60000);
        // Saved: free the parts on the server now rather than when they expire.
        Obsidianscout.request(`/api/admin/export/jobs/${exportId}`, { method: "DELETE" }).catch(() => {});
    }

    async function runExport(exportId) {
        Obsidianscout.setButtonLoading(btnExport, true, "Exporting...");
        setPendingExport(exportId);
        try {
            await downloadExport(exportId);
            Obsidianscout.showToast("Export downloaded", "success");
        } catch (err) {
            console.error(err);
            if (err && err.status !== 404) Obsidianscout.showToast(err.message || "Export failed", "error");
        } finally {
            setPendingExport(null);
            hideExportProgress();
            Obsidianscout.setButtonLoading(btnExport, false);
        }
    }

    btnExport.addEventListener("click", async () => {
        const type = exportTypeSelect.value;
        const format = exportFormatSelect.value;
        const scope = isSuperAdmin ? (document.getElementById("export-scope")?.value || "team") : "team";
        Obsidianscout.setButtonLoading(btnExport, true, "Exporting...");
        showExportProgress(null, "Starting export...");
        let started;
        try {
            started = await Obsidianscout.request("/api/admin/export/jobs", { method: "POST", json: { type, format, scope } });
        } catch (err) {
            console.error(err);
            Obsidianscout.showToast(err.message || "Export failed", "error");
            hideExportProgress();
            Obsidianscout.setButtonLoading(btnExport, false);
            return;
        }
        await runExport(started.exportId);
    });

    // An export started before this page was reloaded: finish downloading it.
    const pendingExport = getPendingExport();
    if (pendingExport) runExport(pendingExport);

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
            fileInfo.textContent = `Selected file: ${file.name} (${formatSize(file.size)})`;
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
    // The file goes up in parts and is imported in the background while the page polls for the result, so no single
    // request is large or slow: Cloudflare refuses bodies over 100 MB and gives up on a reply after 100 seconds.
    const progressBox = document.getElementById("import-progress");
    const progressBar = document.getElementById("import-progress-bar");
    const progressText = document.getElementById("import-progress-text");
    const PENDING_KEY = "obsidianscout.backupImport";
    const PART_ATTEMPTS = 5;
    const POLL_MS = 2000;
    const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

    function showProgress(percent, text) {
        progressBox.classList.remove("hidden");
        if (percent === null) progressBar.removeAttribute("value");
        else progressBar.value = Math.max(0, Math.min(100, percent));
        progressText.textContent = text;
    }

    function hideProgress() {
        progressBox.classList.add("hidden");
        progressBar.value = 0;
        progressText.textContent = "";
    }

    function rememberPending(uploadId, fileName) {
        try { localStorage.setItem(PENDING_KEY, JSON.stringify({ uploadId, fileName })); } catch (e) {}
    }

    function forgetPending() {
        try { localStorage.removeItem(PENDING_KEY); } catch (e) {}
    }

    function readPending() {
        try { return JSON.parse(localStorage.getItem(PENDING_KEY) || "null"); } catch (e) { return null; }
    }

    function formatSize(bytes) {
        if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
        return `${(bytes / 1024).toFixed(1)} KB`;
    }

    // Network drops, timeouts and server hiccups are retried; a refusal (bad part, upload gone) is not.
    function isRetryable(err) {
        const status = err && err.status;
        return !status || status === 408 || status === 429 || status >= 500;
    }

    async function sendPart(uploadId, index, blob) {
        for (let attempt = 1; ; attempt++) {
            try {
                await Obsidianscout.request(`/api/admin/import/uploads/${uploadId}/chunks/${index}`, {
                    method: "PUT",
                    body: blob,
                    headers: { "Content-Type": "application/octet-stream" },
                    timeoutMs: 120000
                });
                return;
            } catch (err) {
                if (attempt >= PART_ATTEMPTS || !isRetryable(err)) throw err;
                await sleep(1000 * attempt * attempt);
            }
        }
    }

    async function waitForImport(uploadId, fileName) {
        const started = Date.now();
        let failures = 0;
        while (true) {
            let status;
            try {
                status = await Obsidianscout.request(`/api/admin/import/uploads/${uploadId}`, { noCache: true, skipEntryStore: true });
                failures = 0;
            } catch (err) {
                // The import keeps running on the server; a few failed checks in a row are worth riding out.
                if (!isRetryable(err) || ++failures >= 15) throw err;
                await sleep(POLL_MS * 2);
                continue;
            }
            if (status.state === "done") return status.report;
            if (status.state === "failed") {
                const err = new Error(status.error || "Import failed");
                err.status = 422;
                throw err;
            }
            const seconds = Math.round((Date.now() - started) / 1000);
            showProgress(null, `Importing ${fileName}... ${seconds}s. Large backups can take several minutes; you can leave this page and come back.`);
            await sleep(POLL_MS);
        }
    }

    async function uploadAndImport(file, scope) {
        showProgress(0, `Preparing upload of ${file.name} (${formatSize(file.size)})...`);
        const upload = await Obsidianscout.request("/api/admin/import/uploads", {
            method: "POST",
            json: { fileName: file.name, totalBytes: file.size, scope }
        });
        const { uploadId, chunkSize, totalChunks } = upload;
        try {
            for (let i = 0; i < totalChunks; i++) {
                const blob = file.slice(i * chunkSize, Math.min(file.size, (i + 1) * chunkSize));
                await sendPart(uploadId, i, blob);
                const percent = Math.round(((i + 1) / totalChunks) * 100);
                showProgress(percent, `Uploading ${file.name}: part ${i + 1} of ${totalChunks} (${percent}%)`);
            }
            await Obsidianscout.request(`/api/admin/import/uploads/${uploadId}/complete`, { method: "POST", json: {} });
        } catch (err) {
            // Free the parts already stored; the server would otherwise keep them for a couple of hours.
            Obsidianscout.request(`/api/admin/import/uploads/${uploadId}`, { method: "DELETE" }).catch(() => {});
            throw err;
        }
        rememberPending(uploadId, file.name);
        try {
            return await waitForImport(uploadId, file.name);
        } finally {
            forgetPending();
        }
    }

    btnImport.addEventListener("click", async () => {
        const file = fileInput.files[0];
        if (!file) return;

        Obsidianscout.setButtonLoading(btnImport, true, "Uploading & Importing...");
        reportCard.classList.add("hidden");

        const scope = isSuperAdmin ? (document.getElementById("import-scope")?.value || "team") : "team";

        try {
            const report = await uploadAndImport(file, scope);
            displayReport(report);
            Obsidianscout.showToast("Data imported successfully", "success");
        } catch (err) {
            console.error(err);
            Obsidianscout.showToast(err.message || "Import failed", "error");
        } finally {
            hideProgress();
            Obsidianscout.setButtonLoading(btnImport, false);
            fileInput.value = "";
            handleFileSelected();
        }
    });

    // An import started before this page was reloaded or reopened: pick up its result.
    (async () => {
        const pending = readPending();
        if (!pending || !pending.uploadId) return;
        Obsidianscout.setButtonLoading(btnImport, true, "Importing...");
        try {
            const report = await waitForImport(pending.uploadId, pending.fileName || "backup");
            displayReport(report);
            Obsidianscout.showToast("Data imported successfully", "success");
        } catch (err) {
            if (err && err.status !== 404) Obsidianscout.showToast(err.message || "Import failed", "error");
        } finally {
            forgetPending();
            hideProgress();
            Obsidianscout.setButtonLoading(btnImport, false);
            handleFileSelected();
        }
    })();

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
});

