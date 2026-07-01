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
        btnExport.disabled = true;
        btnExport.textContent = "Generating export...";

        const url = `/api/admin/export?type=${type}&format=${format}`;
        
        // Trigger download
        const a = document.createElement("a");
        a.href = url;
        // The header from Ktor controls attachment naming, but this is a nice fallback
        a.download = `team_${me.teamNumber}_backup_${type}.${format === "obsidiandb" ? "obsidiandb" : "zip"}`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);

        setTimeout(() => {
            btnExport.disabled = false;
            btnExport.textContent = "Export and Download";
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

        btnImport.disabled = true;
        btnImport.textContent = "Uploading & Importing...";
        reportCard.classList.add("hidden");

        const formData = new FormData();
        formData.append("file", file);

        try {
            const response = await fetch("/api/admin/import", {
                method: "POST",
                body: formData,
                credentials: "same-origin"
            });

            if (!response.ok) {
                const text = await response.text();
                let errMsg = "Import failed";
                try {
                    const errObj = JSON.parse(text);
                    if (errObj && errObj.error) errMsg = errObj.error;
                } catch(e) {}
                throw new Error(errMsg);
            }

            const report = await response.json();
            displayReport(report);
            Obsidianscout.showToast("Data imported successfully", "success");
        } catch (err) {
            console.error(err);
            Obsidianscout.showToast(err.message || "Import failed", "error");
        } finally {
            btnImport.disabled = false;
            btnImport.textContent = "Import Data";
            fileInput.value = "";
            handleFileSelected();
        }
    });

    // 4. Render Import Report
    function displayReport(report) {
        reportSummaryText.textContent = `Summary: ${report.message || "Success"}. Imported type: ${report.type === "entire" ? "Entire Database" : "Scouting Data Only"}.`;
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
