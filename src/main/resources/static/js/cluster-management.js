(function () {
    console.log("[ClusterManagementJS] Initialized.");

    let selectedNodeIp = "all";
    let pendingActionTargetIp = "all";
    let autoRefreshInterval = null;
    let currentNodes = [];

    document.addEventListener("DOMContentLoaded", async () => {
        try {
            if (window.Obsidianscout) {
                Obsidianscout.initTheme();
                const me = await Obsidianscout.requireAuth();
                if (!me) return;

                Obsidianscout.setUserBadge(me);
                Obsidianscout.setActiveNav();
                Obsidianscout.adjustNavForRole(me);
                Obsidianscout.wireLogout();
                Obsidianscout.wireThemeToggle();

                if (!Obsidianscout.isAdmin(me.role)) {
                    document.getElementById("admin-locked")?.classList.remove("hidden");
                    document.getElementById("admin-panel")?.classList.add("hidden");
                    return;
                }
            }

            document.getElementById("admin-locked")?.classList.add("hidden");
            document.getElementById("admin-panel")?.classList.remove("hidden");

            initClusterManagement();
        } catch (e) {
            console.error("[ClusterManagement] Page initialization error:", e);
            document.getElementById("admin-locked")?.classList.add("hidden");
            document.getElementById("admin-panel")?.classList.remove("hidden");
            initClusterManagement();
        }
    });

    async function apiRequest(endpoint, options = {}) {
        if (window.Obsidianscout && typeof Obsidianscout.request === "function") {
            return await Obsidianscout.request(endpoint, options);
        }
        const res = await fetch(endpoint, options);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        return await res.json();
    }

    function showToastMsg(msg, type = "info") {
        if (window.Obsidianscout && typeof Obsidianscout.showToast === "function") {
            Obsidianscout.showToast(msg, type);
        } else {
            console.log(`[Toast ${type}] ${msg}`);
        }
    }

    function initClusterManagement() {
        bindEvents();
        loadClusterNodes();
    }

    function bindEvents() {
        document.getElementById("btn-refresh-nodes")?.addEventListener("click", () => {
            loadClusterNodes();
        });

        document.getElementById("btn-fetch-logs")?.addEventListener("click", () => {
            fetchNodeLogs();
        });

        document.getElementById("log-search")?.addEventListener("input", () => {
            fetchNodeLogs();
        });

        document.getElementById("log-limit-select")?.addEventListener("change", () => {
            fetchNodeLogs();
        });

        const autoToggle = document.getElementById("auto-refresh-toggle");
        autoToggle?.addEventListener("change", (e) => {
            if (e.target.checked) {
                startAutoRefresh();
            } else {
                stopAutoRefresh();
            }
        });

        // Cluster-wide Action Button Handlers
        document.getElementById("btn-cluster-logs")?.addEventListener("click", () => {
            selectedNodeIp = "all";
            updateSelectedNodeLabels();
            fetchNodeLogs();
        });

        document.getElementById("btn-cluster-reinstall")?.addEventListener("click", () => {
            openReinstallModal("all");
        });

        document.getElementById("btn-cluster-reboot")?.addEventListener("click", () => {
            openRebootModal("all");
        });

        // Modal Action Button Handlers
        document.getElementById("modal-reboot-cancel")?.addEventListener("click", closeRebootModal);
        document.getElementById("modal-reboot-cancel-x")?.addEventListener("click", closeRebootModal);
        document.getElementById("modal-reboot-confirm")?.addEventListener("click", executeRebootNode);

        document.getElementById("modal-reinstall-cancel")?.addEventListener("click", closeReinstallModal);
        document.getElementById("modal-reinstall-cancel-x")?.addEventListener("click", closeReinstallModal);
        document.getElementById("modal-reinstall-confirm")?.addEventListener("click", executeForceReinstallNode);

        startAutoRefresh();
    }

    function startAutoRefresh() {
        stopAutoRefresh();
        autoRefreshInterval = setInterval(() => {
            if (selectedNodeIp) {
                fetchNodeLogs(true);
            }
        }, 3500);
    }

    function stopAutoRefresh() {
        if (autoRefreshInterval) {
            clearInterval(autoRefreshInterval);
            autoRefreshInterval = null;
        }
    }

    async function loadClusterNodes() {
        const container = document.getElementById("server-list-container");
        if (!container) return;

        try {
            const resp = await apiRequest("/api/admin/cluster/nodes");
            currentNodes = resp.nodes || [];
            const localIp = resp.localNodeIp || "127.0.0.1";

            renderServerList(currentNodes, localIp);
            fetchNodeLogs();
        } catch (e) {
            console.error("[ClusterManagement] Failed to fetch cluster nodes:", e);
            currentNodes = [
                { nodeId: "local", ip: "127.0.0.1", dbPort: 26257, appPort: 8080, isLocal: true, status: "online", role: "Local Gateway Server Node" }
            ];
            renderServerList(currentNodes, "127.0.0.1");
            fetchNodeLogs();
        }
    }

    function renderServerList(nodes, localIp) {
        const container = document.getElementById("server-list-container");
        if (!container) return;

        if (nodes.length === 0) {
            container.innerHTML = `<div style="color: var(--text-muted); padding: 12px;">No cluster servers discovered.</div>`;
            return;
        }

        let html = "";
        nodes.forEach(node => {
            const isSelected = (node.ip === selectedNodeIp);
            const statusClass = (node.status || "offline").toLowerCase();
            const badgeClass = node.isLocal ? "local" : "remote";
            const badgeText = node.isLocal ? "Local Server" : "Peer Server";

            html += `
                <div class="server-item ${isSelected ? "active-selected" : ""}" data-ip="${escapeHtml(node.ip)}">
                    <div class="server-info-col">
                        <span class="status-dot ${statusClass}" title="Status: ${escapeHtml(node.status)}"></span>
                        <div>
                            <div class="server-ip-title">
                                ${escapeHtml(node.ip)}
                                <span class="node-badge ${badgeClass}">${badgeText}</span>
                            </div>
                            <div style="font-size: 12px; color: var(--text-muted, #94a3b8); margin-top: 2px;">
                                App Port: <strong>${node.appPort}</strong> | Cockroach DB Port: <strong>${node.dbPort}</strong> | Role: ${escapeHtml(node.role || "Gateway")}
                            </div>
                        </div>
                    </div>
                    <div class="server-actions-col">
                        <button class="btn-action logs btn-server-logs" data-ip="${escapeHtml(node.ip)}" type="button">🔍 View Logs</button>
                        <button class="btn-action reinstall btn-server-reinstall" data-ip="${escapeHtml(node.ip)}" type="button">🔄 Force Reinstall</button>
                        <button class="btn-action reboot btn-server-reboot" data-ip="${escapeHtml(node.ip)}" type="button">⚠️ Reboot</button>
                    </div>
                </div>
            `;
        });

        container.innerHTML = html;

        // Wire event listeners on server action buttons
        container.querySelectorAll(".btn-server-logs").forEach(btn => {
            btn.addEventListener("click", (e) => {
                e.stopPropagation();
                const ip = btn.getAttribute("data-ip");
                if (ip) {
                    selectedNodeIp = ip;
                    updateSelectedNodeLabels();
                    fetchNodeLogs();
                }
            });
        });

        container.querySelectorAll(".btn-server-reinstall").forEach(btn => {
            btn.addEventListener("click", (e) => {
                e.stopPropagation();
                const ip = btn.getAttribute("data-ip");
                if (ip) {
                    openReinstallModal(ip);
                }
            });
        });

        container.querySelectorAll(".btn-server-reboot").forEach(btn => {
            btn.addEventListener("click", (e) => {
                e.stopPropagation();
                const ip = btn.getAttribute("data-ip");
                if (ip) {
                    openRebootModal(ip);
                }
            });
        });

        updateSelectedNodeLabels();
    }

    function updateSelectedNodeLabels() {
        const titleEl = document.getElementById("selected-node-title");

        if (selectedNodeIp === "all") {
            if (titleEl) titleEl.textContent = `Server Log Console (Entire Cluster - ${currentNodes.length} Nodes)`;
        } else {
            const targetNode = currentNodes.find(n => n.ip === selectedNodeIp);
            const nodeName = targetNode ? (targetNode.isLocal ? `Local (${selectedNodeIp})` : `Peer (${selectedNodeIp})`) : selectedNodeIp;
            if (titleEl) titleEl.textContent = `Server Log Console (Node ${nodeName})`;
        }

        // Highlight active server item row
        document.querySelectorAll(".server-item").forEach(item => {
            if (item.getAttribute("data-ip") === selectedNodeIp) {
                item.classList.add("active-selected");
            } else {
                item.classList.remove("active-selected");
            }
        });
    }

    async function fetchNodeLogs(isBackgroundAutoRefresh = false) {
        const countBadge = document.getElementById("log-count-badge");
        const searchVal = (document.getElementById("log-search")?.value || "").trim();
        const limitVal = document.getElementById("log-limit-select")?.value || "500";

        try {
            const queryParams = new URLSearchParams({ limit: limitVal });
            if (searchVal) queryParams.set("filter", searchVal);

            let endpoint = "";
            if (selectedNodeIp === "all") {
                endpoint = `/api/admin/cluster/logs-all?${queryParams.toString()}`;
            } else {
                endpoint = `/api/admin/cluster/nodes/${encodeURIComponent(selectedNodeIp)}/logs?${queryParams.toString()}`;
            }

            const resp = await apiRequest(endpoint);
            const logs = resp.logs || [];

            if (countBadge) {
                countBadge.textContent = `${logs.length} / 1,000 entries (capped)`;
            }

            renderLogsInTerminal(logs);
        } catch (e) {
            console.error("[ClusterManagement] Log fetch failed:", e);
            const terminal = document.getElementById("log-terminal");
            if (!isBackgroundAutoRefresh && terminal) {
                terminal.innerHTML = `<div style="color: #ef4444; padding: 20px;">Failed to fetch logs: ${escapeHtml(e.message || "Network timeout")}</div>`;
            }
        }
    }

    function renderLogsInTerminal(logs) {
        const terminal = document.getElementById("log-terminal");
        if (!terminal) return;

        if (logs.length === 0) {
            terminal.innerHTML = `<div style="color: #64748b; text-align: center; padding-top: 40px;">No log entries found.</div>`;
            return;
        }

        const isScrolledToBottom = (terminal.scrollHeight - terminal.clientHeight <= terminal.scrollTop + 60);

        let html = "";
        logs.forEach(log => {
            const time = escapeHtml(log.timestamp || "");
            const lvl = escapeHtml(log.level || "INFO").toUpperCase();
            const msg = escapeHtml(log.message || "");
            const logger = escapeHtml(log.logger ? `[${log.logger}] ` : "");

            html += `<div class="log-line">
                <span class="log-time">${time}</span>
                <span class="log-level ${lvl}">${lvl}</span>
                <span class="log-msg">${logger}${msg}</span>
            </div>`;
        });

        terminal.innerHTML = html;

        if (isScrolledToBottom) {
            terminal.scrollTop = terminal.scrollHeight;
        }
    }

    // Modal Confirmation Controls using Obsidianscout native modal class .show
    function openRebootModal(targetIp) {
        pendingActionTargetIp = targetIp || selectedNodeIp || "all";
        const modal = document.getElementById("reboot-modal");
        const modalText = document.getElementById("modal-reboot-text");

        if (pendingActionTargetIp === "all") {
            if (modalText) {
                modalText.innerHTML = `Are you sure you want to reboot <strong>ALL ${currentNodes.length} servers in the CockroachDB cluster</strong>?<br><br>This will send reboot signals to every server node on the cluster.`;
            }
        } else {
            if (modalText) {
                modalText.innerHTML = `Are you sure you want to reboot server node <strong style="font-family: monospace; color: #ef4444;">${escapeHtml(pendingActionTargetIp)}</strong>?<br><br>This will terminate the node process and initiate a server reboot sequence.`;
            }
        }

        if (modal) modal.classList.add("show");
    }

    function closeRebootModal() {
        const modal = document.getElementById("reboot-modal");
        if (modal) modal.classList.remove("show");
    }

    async function executeRebootNode() {
        const target = pendingActionTargetIp || "all";
        closeRebootModal();

        try {
            if (target === "all") {
                showToastMsg("Dispatching reboot command to ENTIRE cluster...", "info");
                const resp = await apiRequest("/api/admin/cluster/reboot-all", { method: "POST" });
                showToastMsg(resp.message || "Cluster reboot sequence triggered.", resp.success ? "success" : "error");
            } else {
                showToastMsg(`Initiating reboot for server node ${target}...`, "info");
                const resp = await apiRequest(`/api/admin/cluster/nodes/${encodeURIComponent(target)}/reboot`, { method: "POST" });
                showToastMsg(resp.message || "Reboot command dispatched successfully.", resp.success ? "success" : "error");
            }
            setTimeout(() => loadClusterNodes(), 2500);
        } catch (e) {
            showToastMsg(`Reboot request failed: ${e.message}`, "error");
        }
    }

    function openReinstallModal(targetIp) {
        pendingActionTargetIp = targetIp || selectedNodeIp || "all";
        const modal = document.getElementById("reinstall-modal");
        const modalText = document.getElementById("modal-reinstall-text");

        if (pendingActionTargetIp === "all") {
            if (modalText) {
                modalText.innerHTML = `Are you sure you want to force reinstall and update <strong>ALL ${currentNodes.length} servers in the CockroachDB cluster</strong>?<br><br>This will trigger CockroachDB binary re-verification and pull release updates on every node.`;
            }
        } else {
            if (modalText) {
                modalText.innerHTML = `Are you sure you want to force reinstall/update server node <strong style="font-family: monospace; color: #f59e0b;">${escapeHtml(pendingActionTargetIp)}</strong>?<br><br>This will trigger binary verification, re-download CockroachDB binaries if corrupted, and pull release updates on this node.`;
            }
        }

        if (modal) modal.classList.add("show");
    }

    function closeReinstallModal() {
        const modal = document.getElementById("reinstall-modal");
        if (modal) modal.classList.remove("show");
    }

    async function executeForceReinstallNode() {
        const target = pendingActionTargetIp || "all";
        closeReinstallModal();

        try {
            if (target === "all") {
                showToastMsg("Dispatching force reinstall/update to ENTIRE cluster...", "info");
                const resp = await apiRequest("/api/admin/cluster/reinstall-update-all", { method: "POST" });
                showToastMsg(resp.message || "Cluster force reinstall triggered.", resp.success ? "success" : "error");
            } else {
                showToastMsg(`Triggering force reinstall/update for server node ${target}...`, "info");
                const resp = await apiRequest(`/api/admin/cluster/nodes/${encodeURIComponent(target)}/reinstall-update`, { method: "POST" });
                showToastMsg(resp.message || "Force reinstall sequence started.", resp.success ? "success" : "error");
            }
            setTimeout(() => fetchNodeLogs(), 2000);
        } catch (e) {
            showToastMsg(`Force reinstall request failed: ${e.message}`, "error");
        }
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
})();
