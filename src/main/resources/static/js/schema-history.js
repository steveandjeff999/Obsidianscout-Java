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
        document.getElementById("history-container").classList.add("hidden");
        return;
    }

    const urlParams = new URLSearchParams(window.location.search);
    let activeKind = urlParams.get("kind") || "game";
    if (!["game", "pit", "qual"].includes(activeKind)) {
        activeKind = "game";
    }

    const kindButtons = document.querySelectorAll("[data-kind]");
    const revisionsList = document.getElementById("revisions-list");
    const btnGotoMigration = document.getElementById("btn-goto-migration");

    const inspectModal = document.getElementById("inspect-modal");
    const inspectTitle = document.getElementById("inspect-title");
    const inspectMetaBar = document.getElementById("inspect-meta-bar");
    const inspectContent = document.getElementById("inspect-content");
    const btnCloseInspect = document.getElementById("btn-close-inspect");
    const btnDismissInspect = document.getElementById("btn-dismiss-inspect");
    const btnRestoreFromModal = document.getElementById("btn-restore-from-modal");

    let currentInspectedRevision = null;

    function updateKindTabs() {
        kindButtons.forEach(btn => {
            btn.classList.toggle("active", btn.dataset.kind === activeKind);
        });
        if (btnGotoMigration) {
            btnGotoMigration.href = `/config-migration?kind=${activeKind}`;
        }
    }

    kindButtons.forEach(btn => {
        btn.addEventListener("click", async () => {
            const nextKind = btn.dataset.kind;
            if (nextKind === activeKind) return;
            activeKind = nextKind;
            updateKindTabs();
            const newUrl = new URL(window.location);
            newUrl.searchParams.set("kind", activeKind);
            window.history.replaceState({}, "", newUrl);
            await loadRevisions();
        });
    });

    updateKindTabs();

    async function loadRevisions() {
        try {
            revisionsList.innerHTML = `<div style="text-align: center; color: var(--muted); padding: 32px;">Loading revisions...</div>`;
            const revisions = await Obsidianscout.request(`/api/config-history?kind=${activeKind}`);

            if (!revisions || revisions.length === 0) {
                revisionsList.innerHTML = `
                    <div class="card soft" style="text-align: center; padding: 40px 20px;">
                        <div style="font-size: 32px; margin-bottom: 8px;">📜</div>
                        <h3 style="margin: 0 0 6px 0;">No Historical Snapshots Recorded Yet</h3>
                        <p class="notice" style="max-width: 460px; margin: 0 auto 16px auto;">
                            Revisions are automatically captured whenever you save changes to your scouting form in Admin Settings.
                        </p>
                        <a href="/admin-settings" class="btn secondary" style="text-decoration: none;">Go to Form Editor</a>
                    </div>
                `;
                return;
            }

            renderRevisions(revisions);
        } catch (err) {
            console.error("Failed to load revisions:", err);
            revisionsList.innerHTML = `<div class="notice" style="color: #ef4444; padding: 20px; text-align: center;">Failed to load revisions: ${err.message}</div>`;
        }
    }

    function renderRevisions(revisions) {
        revisionsList.innerHTML = "";

        revisions.forEach((rev, idx) => {
            const card = document.createElement("div");
            card.className = "revision-card";

            const dateStr = rev.createdAt ? new Date(rev.createdAt).toLocaleString() : "Unknown date";
            const isLatest = idx === 0;

            card.innerHTML = `
                <div style="flex: 1; min-width: 260px;">
                    <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap;">
                        <span class="revision-version-badge">v${rev.version}</span>
                        <strong>${rev.title || "Scouting Form"}</strong>
                        ${isLatest ? '<span class="matched-key-badge" style="font-size: 11px; padding: 2px 6px;">Latest Active</span>' : ''}
                    </div>
                    <div class="revision-meta">
                        <span>📅 ${dateStr}</span>
                        <span>👤 Saved by <strong>${rev.savedByUsername || 'admin'}</strong></span>
                        <span>📊 ${rev.fieldCount} fields</span>
                    </div>
                    <div class="revision-summary">
                        ${rev.changeSummary ? `<em>Summary:</em> ${rev.changeSummary}` : '<em>No change description</em>'}
                    </div>
                </div>
                <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap;">
                    <button class="btn secondary btn-inspect" type="button" style="padding: 6px 14px; font-size: 13px;">Inspect Fields</button>
                    <button class="btn ghost btn-restore" type="button" style="padding: 6px 14px; font-size: 13px;" ${isLatest ? 'disabled title="Currently active version"' : ''}>Restore</button>
                </div>
            `;

            card.querySelector(".btn-inspect").addEventListener("click", () => openInspectModal(rev.id));
            
            const btnRestore = card.querySelector(".btn-restore");
            if (btnRestore && !isLatest) {
                btnRestore.addEventListener("click", () => restoreRevision(rev.id, rev.version));
            }

            revisionsList.appendChild(card);
        });
    }

    async function openInspectModal(id) {
        try {
            inspectContent.textContent = "Loading configuration...";
            inspectModal.classList.add("show");
            
            const detail = await Obsidianscout.request(`/api/config-history/${id}`);
            currentInspectedRevision = detail;

            inspectTitle.textContent = `Revision v${detail.version} - ${detail.title}`;
            const dateStr = detail.createdAt ? new Date(detail.createdAt).toLocaleString() : "";
            inspectMetaBar.innerHTML = `Saved on <strong>${dateStr}</strong> by <strong>${detail.savedByUsername || 'admin'}</strong> • ${detail.configKind.toUpperCase()} Form`;

            let parsedObj;
            try {
                parsedObj = JSON.parse(detail.configJson);
            } catch (e) {
                parsedObj = detail.configJson;
            }
            inspectContent.textContent = JSON.stringify(parsedObj, null, 2);

            btnRestoreFromModal.onclick = () => {
                closeInspectModal();
                restoreRevision(detail.id, detail.version);
            };
        } catch (err) {
            console.error("Failed to fetch revision detail:", err);
            inspectContent.textContent = "Failed to load: " + err.message;
        }
    }

    function closeInspectModal() {
        inspectModal.classList.remove("show");
        currentInspectedRevision = null;
    }

    btnCloseInspect.addEventListener("click", closeInspectModal);
    btnDismissInspect.addEventListener("click", closeInspectModal);

    async function restoreRevision(id, version) {
        if (!confirm(`Are you sure you want to restore schema version v${version}?\n\nThis will update your active ${activeKind.toUpperCase()} form configuration to match this historical version.`)) {
            return;
        }

        try {
            const res = await Obsidianscout.request(`/api/config-history/${id}/restore`, {
                method: "POST"
            });

            Obsidianscout.showToast(`Restored version v${version} successfully!`, "success");
            await loadRevisions();

            if (res.hasFieldChanges && res.entryCount > 0) {
                if (confirm(`Schema restored! You have ${res.entryCount} existing records that may have schema differences with this restored version.\n\nWould you like to open the Data Migration page now?`)) {
                    window.location.href = `/config-migration?kind=${activeKind}`;
                }
            }
        } catch (err) {
            console.error("Restore failed:", err);
            Obsidianscout.showToast(err.message || "Failed to restore revision", "error");
        }
    }

    await loadRevisions();
});
