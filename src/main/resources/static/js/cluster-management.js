(function () {
    console.log("[ClusterManagementJS] Initialized.");

    let selectedNodeIp = "all";
    let pendingActionTargetIp = "all";
    let pendingConfigTargetIp = "local";
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

                if (!Obsidianscout.isSuperAdmin(me.role)) {
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
        loadNodeAlertEnrollment();
        bindNodeAlertEvents();
        loadLoadBalancerStatus();
        bindLoadBalancerEvents();
    }

    async function loadNodeAlertEnrollment() {
        const alertsCard = document.getElementById("node-alerts-card");
        const toggle = document.getElementById("node-alerts-enroll-toggle");
        if (!alertsCard || !toggle) return;

        try {
            const data = await apiRequest("/api/admin/cluster/notifications/enrollment");
            if (data && data.success !== undefined) {
                alertsCard.classList.remove("hidden");
                toggle.checked = !!data.enrolled;
            }
        } catch (e) {
            // Endpoint returns 403 Forbidden if not superadmin
            alertsCard.classList.add("hidden");
        }
    }

    function bindNodeAlertEvents() {
        const toggle = document.getElementById("node-alerts-enroll-toggle");
        const testBtn = document.getElementById("btn-test-node-alert");
        const statusMsg = document.getElementById("node-alerts-status-msg");

        toggle?.addEventListener("change", async (e) => {
            const enrolled = e.target.checked;
            try {
                const res = await apiRequest("/api/admin/cluster/notifications/enrollment", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({ enrolled: enrolled })
                });
                try { localStorage.removeItem("cache:/api/auth/me"); } catch (e) {}
                showToastMsg(res.message || (enrolled ? "Enrolled in node alerts" : "Unsubscribed from node alerts"), "success");
            } catch (err) {
                showToastMsg("Failed to update node alert enrollment: " + err.message, "error");
                toggle.checked = !enrolled;
            }
        });

        testBtn?.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(testBtn, true, "Dispatching alert...");
            if (statusMsg) {
                statusMsg.style.display = "block";
                statusMsg.style.color = "#cbd5e1";
                statusMsg.textContent = "Dispatching test node down alert (FCM & Email)...";
            }
            try {
                const res = await apiRequest("/api/admin/cluster/notifications/test", { method: "POST" });
                if (statusMsg) {
                    statusMsg.style.color = res.success ? "#4ade80" : "#f87171";
                    statusMsg.textContent = res.message;
                }
                showToastMsg(res.message, res.success ? "success" : "error");
            } catch (err) {
                if (statusMsg) {
                    statusMsg.style.color = "#f87171";
                    statusMsg.textContent = "Failed to dispatch test alert: " + err.message;
                }
                showToastMsg("Failed to dispatch test alert: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(testBtn, false);
            }
        });
    }

    async function loadLoadBalancerStatus() {
        const card = document.getElementById("load-balancer-card");
        const toggle = document.getElementById("lb-enable-toggle");
        const pill = document.getElementById("lb-status-pill");
        const container = document.getElementById("lb-metrics-container");
        if (!card) return;

        try {
            const status = await apiRequest("/api/admin/cluster/load-balancer/status");
            card.classList.remove("hidden");
            if (toggle) toggle.checked = !!status.enabled;

            if (pill) {
                if (!status.enabled) {
                    pill.textContent = "Disabled";
                    pill.style.background = "rgba(100, 116, 139, 0.2)";
                    pill.style.color = "#94a3b8";
                    pill.style.border = "1px solid rgba(100, 116, 139, 0.3)";
                } else if (status.isForwardingActive) {
                    pill.textContent = `Forwarding Active -> ${status.bestNodeIp}`;
                    pill.style.background = "rgba(16, 185, 129, 0.2)";
                    pill.style.color = "#34d399";
                    pill.style.border = "1px solid rgba(16, 185, 129, 0.4)";
                } else {
                    pill.textContent = "Active (Serving Locally)";
                    pill.style.background = "rgba(59, 130, 246, 0.2)";
                    pill.style.color = "#60a5fa";
                    pill.style.border = "1px solid rgba(59, 130, 246, 0.4)";
                }
            }

            if (container) {
                const local = status.localNode || {};
                const localScore = (local.score * 100).toFixed(1);
                const localHeap = `${local.availableHeapMb || 0} MB free / ${local.maxHeapMb || 0} MB`;
                const localCpu = `${Math.round((local.cpuLoad || 0) * 100)}%`;
                const peers = status.peerNodes || [];

                let peersHtml = "";
                if (peers.length === 0) {
                    peersHtml = `<div style="color: #94a3b8; font-size: 12px;">No active remote peer nodes discovered on the cluster.</div>`;
                } else {
                    peersHtml = peers.map(p => {
                        const scorePct = (p.score * 100).toFixed(1);
                        const isWinner = (p.ip === status.bestNodeIp);
                        const winnerBadge = isWinner ? `<span class="badge" style="background: rgba(16, 185, 129, 0.2); color: #34d399; font-size: 10px; margin-left: 4px;">Top Route</span>` : "";
                        return `
                            <div style="background: rgba(0, 0, 0, 0.2); padding: 8px 10px; border-radius: 6px; margin-bottom: 6px; border: 1px solid ${isWinner ? 'rgba(16, 185, 129, 0.4)' : 'rgba(255, 255, 255, 0.05)'};">
                                <div style="display: flex; justify-content: space-between; align-items: center;">
                                    <span style="font-family: monospace; font-weight: 600; font-size: 13px; color: ${isWinner ? '#34d399' : '#f1f5f9'};">${escapeHtml(p.ip)}${winnerBadge}</span>
                                    <span style="font-size: 12px; font-weight: 700; color: #38bdf8;">${scorePct}%</span>
                                </div>
                                <div style="font-size: 11px; color: #94a3b8; margin-top: 2px; display: flex; gap: 10px;">
                                    <span>Latency: <strong>${p.latencyMs}ms</strong></span>
                                    <span>Free Heap: <strong>${p.availableHeapMb}MB</strong></span>
                                    <span>CPU: <strong>${Math.round((p.cpuLoad || 0) * 100)}%</strong></span>
                                </div>
                            </div>
                        `;
                    }).join("");
                }

                container.innerHTML = `
                    <div style="background: rgba(0, 0, 0, 0.25); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 12px;">
                        <div style="font-size: 11px; text-transform: uppercase; color: #94a3b8; font-weight: 600;">Forwarded Requests</div>
                        <div style="font-size: 22px; font-weight: 700; color: #34d399; margin-top: 4px;">${status.forwardedCount || 0}</div>
                        <div style="font-size: 11px; color: #64748b; margin-top: 2px;">Check every ${status.probeIntervalSeconds}s</div>
                    </div>
                    <div style="background: rgba(0, 0, 0, 0.25); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 12px;">
                        <div style="font-size: 11px; text-transform: uppercase; color: #94a3b8; font-weight: 600;">Selected Target</div>
                        <div style="font-size: 15px; font-weight: 700; font-family: monospace; color: #60a5fa; margin-top: 6px;">${escapeHtml(status.bestNodeIp || "local")}</div>
                        <div style="font-size: 11px; color: #64748b; margin-top: 4px;">Local Preference Margin: +${status.localPreferenceMargin}</div>
                    </div>
                    <div style="background: rgba(0, 0, 0, 0.25); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 12px;">
                        <div style="font-size: 11px; text-transform: uppercase; color: #94a3b8; font-weight: 600;">Local Node Capacity</div>
                        <div style="font-size: 18px; font-weight: 700; color: #38bdf8; margin-top: 4px;">Score: ${localScore}%</div>
                        <div style="font-size: 11px; color: #94a3b8; margin-top: 2px;">${localHeap} (CPU: ${localCpu})</div>
                    </div>
                    <div style="background: rgba(0, 0, 0, 0.25); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 12px; grid-column: 1 / -1;">
                        <div style="font-size: 12px; text-transform: uppercase; color: #94a3b8; font-weight: 600; margin-bottom: 8px;">Network Peer Load & Latency Ranking</div>
                        ${peersHtml}
                    </div>
                `;
            }

            // Render 30-minute activity history
            const summaryPill = document.getElementById("lb-30m-summary-pill");
            const activityList = document.getElementById("lb-activity-list");
            const stats = status.recentStats || {};
            const fwd30m = stats.totalForwarded30m || 0;
            const loc30m = stats.totalLocalServed30m || 0;
            const ratioPct = Math.round((stats.forwardedRatio30m || 0) * 100);

            if (summaryPill) {
                summaryPill.textContent = `${fwd30m} forwarded / ${loc30m} local (${ratioPct}% offloaded)`;
            }

            if (activityList) {
                const history = (status.activityHistory || []).slice().reverse();
                if (history.length === 0) {
                    activityList.innerHTML = `<div style="color: #64748b; text-align: center; padding: 16px;">No load balancing activity recorded yet in the last 30 minutes.</div>`;
                } else {
                    activityList.innerHTML = history.map(item => {
                        const timeStr = new Date(item.timestampEpochMs).toLocaleTimeString();
                        const isFwd = item.isForwarded;
                        const badgeColor = isFwd ? '#34d399' : '#60a5fa';
                        const badgeBg = isFwd ? 'rgba(16, 185, 129, 0.15)' : 'rgba(59, 130, 246, 0.15)';
                        const badgeBorder = isFwd ? 'rgba(16, 185, 129, 0.3)' : 'rgba(59, 130, 246, 0.3)';
                        const badgeText = isFwd ? `Forwarded -> ${escapeHtml(item.targetIp)}` : 'Served Locally';

                        return `
                            <div style="display: flex; justify-content: space-between; align-items: center; padding: 6px 10px; border-bottom: 1px solid rgba(255, 255, 255, 0.04); gap: 10px; flex-wrap: wrap;">
                                <div style="display: flex; align-items: center; gap: 8px;">
                                    <span style="color: #64748b; font-size: 11px;">[${timeStr}]</span>
                                    <span style="background: ${badgeBg}; color: ${badgeColor}; border: 1px solid ${badgeBorder}; padding: 2px 6px; border-radius: 4px; font-size: 11px; font-weight: 600;">
                                        ${badgeText}
                                    </span>
                                    <span style="color: #cbd5e1; font-size: 11px;">${escapeHtml(item.note || "")}</span>
                                </div>
                                <div style="font-size: 11px; color: #94a3b8; display: flex; gap: 10px;">
                                    <span>+${item.requestsForwarded} fwd / +${item.requestsServedLocally} loc</span>
                                    <span>Free Heap: ${item.localHeapFreeMb}MB</span>
                                    <span>CPU: ${item.localCpuPercent}%</span>
                                </div>
                            </div>
                        `;
                    }).join("");
                }
            }

            // Also check and update overload stress test status
            loadStressTestStatus();
        } catch (e) {
            // Non-superadmins will get 403, hide the card
            card.classList.add("hidden");
        }
    }

    let stressPollInterval = null;

    async function loadStressTestStatus() {
        const pill = document.getElementById("lb-stress-status-pill");
        const startBtn = document.getElementById("btn-lb-stress-start");
        const stopBtn = document.getElementById("btn-lb-stress-stop");
        if (!pill) return;

        try {
            const status = await apiRequest("/api/admin/cluster/stress/status");
            if (status.isRunning) {
                pill.textContent = `Overload Active (${status.remainingSeconds}s remaining)`;
                pill.style.background = "rgba(239, 68, 68, 0.25)";
                pill.style.color = "#f87171";
                pill.style.border = "1px solid rgba(239, 68, 68, 0.5)";
                if (startBtn) startBtn.classList.add("hidden");
                if (stopBtn) stopBtn.classList.remove("hidden");

                if (!stressPollInterval) {
                    stressPollInterval = setInterval(() => {
                        loadStressTestStatus();
                        loadLoadBalancerStatus();
                    }, 2000);
                }
            } else {
                pill.textContent = "Idle";
                pill.style.background = "rgba(100, 116, 139, 0.2)";
                pill.style.color = "#94a3b8";
                pill.style.border = "1px solid rgba(100, 116, 139, 0.3)";
                if (startBtn) startBtn.classList.remove("hidden");
                if (stopBtn) stopBtn.classList.add("hidden");

                if (stressPollInterval) {
                    clearInterval(stressPollInterval);
                    stressPollInterval = null;
                }
            }
        } catch (_e) {
            // non-superadmin or network error
        }
    }

    function bindLoadBalancerEvents() {
        const toggle = document.getElementById("lb-enable-toggle");
        const btnConfig = document.getElementById("btn-lb-configure");
        const btnRefresh = document.getElementById("btn-lb-refresh");
        const btnStressStart = document.getElementById("btn-lb-stress-start");
        const btnStressStop = document.getElementById("btn-lb-stress-stop");

        toggle?.addEventListener("change", async (e) => {
            const enabled = e.target.checked;
            try {
                const currentSettings = await apiRequest("/api/admin/cluster/load-balancer/settings");
                currentSettings.enabled = enabled;
                await apiRequest("/api/admin/cluster/load-balancer/settings", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify(currentSettings)
                });
                showToastMsg(enabled ? "Cluster load balancing enabled." : "Cluster load balancing disabled.", "success");
                loadLoadBalancerStatus();
            } catch (err) {
                showToastMsg("Failed to update load balancer state: " + err.message, "error");
                toggle.checked = !enabled;
            }
        });

        btnConfig?.addEventListener("click", () => {
            openLoadBalancerModal();
        });

        btnRefresh?.addEventListener("click", () => {
            loadLoadBalancerStatus();
            loadStressTestStatus();
            showToastMsg("Load balancer status refreshed.", "info");
        });

        btnStressStart?.addEventListener("click", async () => {
            if (!confirm("Start simulated server overload test for up to 60 seconds? This safely spikes local CPU and heap to verify traffic forwarding to peers.")) {
                return;
            }
            try {
                Obsidianscout.setButtonLoading(btnStressStart, true, "Starting...");
                const res = await apiRequest("/api/admin/cluster/stress/start", { method: "POST" });
                showToastMsg(res.message || "Simulated overload started.", "warning");
                loadStressTestStatus();
                setTimeout(loadLoadBalancerStatus, 1500);
            } catch (err) {
                showToastMsg("Failed to start overload simulation: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(btnStressStart, false);
            }
        });

        btnStressStop?.addEventListener("click", async () => {
            try {
                Obsidianscout.setButtonLoading(btnStressStop, true, "Stopping...");
                const res = await apiRequest("/api/admin/cluster/stress/stop", { method: "POST" });
                showToastMsg(res.message || "Simulated overload stopped.", "info");
                loadStressTestStatus();
                setTimeout(loadLoadBalancerStatus, 1000);
            } catch (err) {
                showToastMsg("Failed to stop overload simulation: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(btnStressStop, false);
            }
        });

        document.getElementById("modal-lb-cancel")?.addEventListener("click", closeLoadBalancerModal);
        document.getElementById("modal-lb-cancel-x")?.addEventListener("click", closeLoadBalancerModal);
        document.getElementById("modal-lb-save")?.addEventListener("click", saveLoadBalancerSettings);
    }

    async function openLoadBalancerModal() {
        const modal = document.getElementById("load-balancer-modal");
        if (!modal) return;

        try {
            const settings = await apiRequest("/api/admin/cluster/load-balancer/settings");
            const marginInput = document.getElementById("lb-input-margin");
            const latencyInput = document.getElementById("lb-input-latency");
            const probeInput = document.getElementById("lb-input-probe");
            const timeoutInput = document.getElementById("lb-input-timeout");
            const excludedInput = document.getElementById("lb-input-excluded");

            if (marginInput) marginInput.value = settings.localPreferenceMargin ?? 0.10;
            if (latencyInput) latencyInput.value = settings.maxExpectedLatencyMs ?? 150.0;
            if (probeInput) probeInput.value = settings.probeIntervalSeconds ?? 15;
            if (timeoutInput) timeoutInput.value = settings.forwardTimeoutSeconds ?? 30;
            if (excludedInput) excludedInput.value = (settings.excludedPathPrefixes || []).join(", ");

            modal.classList.add("show");
        } catch (e) {
            showToastMsg("Failed to load load balancer settings: " + e.message, "error");
        }
    }

    function closeLoadBalancerModal() {
        const modal = document.getElementById("load-balancer-modal");
        if (modal) modal.classList.remove("show");
    }

    async function saveLoadBalancerSettings() {
        const saveBtn = document.getElementById("modal-lb-save");
        const marginInput = document.getElementById("lb-input-margin");
        const latencyInput = document.getElementById("lb-input-latency");
        const probeInput = document.getElementById("lb-input-probe");
        const timeoutInput = document.getElementById("lb-input-timeout");
        const excludedInput = document.getElementById("lb-input-excluded");
        const toggle = document.getElementById("lb-enable-toggle");

        const margin = parseFloat(marginInput?.value) || 0.10;
        const latency = parseFloat(latencyInput?.value) || 150.0;
        const probe = parseInt(probeInput?.value) || 15;
        const timeout = parseInt(timeoutInput?.value) || 30;
        const excluded = (excludedInput?.value || "")
            .split(",")
            .map(s => s.trim())
            .filter(s => s.length > 0);
        const enabled = toggle?.checked ?? false;

        const payload = {
            enabled: enabled,
            probeIntervalSeconds: probe,
            forwardTimeoutSeconds: timeout,
            localPreferenceMargin: margin,
            maxExpectedLatencyMs: latency,
            excludedPathPrefixes: excluded.length > 0 ? excluded : ["/api/admin", "/api/cluster", "/cluster-management"]
        };

        Obsidianscout.setButtonLoading(saveBtn, true, "Saving...");
        try {
            await apiRequest("/api/admin/cluster/load-balancer/settings", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            showToastMsg("Cluster load balancer configuration saved successfully.", "success");
            closeLoadBalancerModal();
            loadLoadBalancerStatus();
        } catch (e) {
            showToastMsg("Failed to save load balancer settings: " + e.message, "error");
        } finally {
            Obsidianscout.setButtonLoading(saveBtn, false);
        }
    }

    function bindEvents() {
        window.addEventListener("obsidianscout:languagechange", () => {
            loadClusterNodes();
        });

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
            fetchNodeLogs();
        });

        const btnClusterKeys = document.getElementById("btn-cluster-keys");
        btnClusterKeys?.addEventListener("click", async () => {
            const confirmMsg = window.Obsidianscout && typeof Obsidianscout.t === "function"
                ? Obsidianscout.t("cluster.confirm_regen_keys", "Are you sure you want to regenerate all cluster keys (Session Secret & VAPID keys)?\n\nRotating session keys will require active users across all nodes to sign in again.")
                : "Are you sure you want to regenerate all cluster keys (Session Secret & VAPID keys)?\n\nRotating session keys will require active users across all nodes to sign in again.";
            if (!confirm(confirmMsg)) {
                return;
            }
            Obsidianscout.setButtonLoading(btnClusterKeys, true, "Regenerating...");
            try {
                const res = await apiRequest("/api/admin/cluster/regenerate-keys", { method: "POST" });
                if (res.success) {
                    showToastMsg(res.message || "Cluster keys regenerated successfully!", "success");
                } else {
                    showToastMsg("Failed to regenerate cluster keys: " + (res.message || "Unknown error"), "error");
                }
            } catch (err) {
                showToastMsg("Error regenerating cluster keys: " + err.message, "error");
            } finally {
                Obsidianscout.setButtonLoading(btnClusterKeys, false);
            }
        });

        document.getElementById("btn-cluster-reinstall")?.addEventListener("click", () => {
            openReinstallModal("all");
        });

        document.getElementById("btn-cluster-reboot")?.addEventListener("click", () => {
            openRebootModal("all");
        });

        // App Config Modal Handlers
        document.getElementById("modal-config-cancel")?.addEventListener("click", closeAppConfigModal);
        document.getElementById("modal-config-cancel-x")?.addEventListener("click", closeAppConfigModal);
        document.getElementById("modal-config-save")?.addEventListener("click", saveAppConfig);

        // Reboot & Reinstall Modal Handlers
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
            if (document.hidden) return;
            if (selectedNodeIp) {
                fetchNodeLogs(true);
            }
        }, 5000);
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

        const t = (key, fallback) => (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;

        if (nodes.length === 0) {
            container.innerHTML = `<div style="color: var(--text-muted); padding: 12px;">${t("cluster.no_servers", "No cluster servers discovered.")}</div>`;
            return;
        }

        let html = "";
        nodes.forEach(node => {
            const isSelected = (node.ip === selectedNodeIp);
            const statusClass = (node.status || "offline").toLowerCase();
            const badgeClass = node.isLocal ? "local" : "remote";
            const badgeText = node.isLocal ? t("cluster.badge_local", "Local Node") : t("cluster.badge_remote", "Remote Node");
            const versionStr = node.serverVersion ? (node.serverVersion.startsWith("v") ? node.serverVersion : `v${node.serverVersion}`) : "vUnknown";

            const rawMode = (node.executionMode || "Unknown").trim();
            let modeBadgeHtml = "";
            if (rawMode.toLowerCase() === "native") {
                modeBadgeHtml = `<span class="node-badge" style="background: rgba(168, 85, 247, 0.2); color: #c084fc; border: 1px solid rgba(168, 85, 247, 0.4);" title="Execution Mode: GraalVM Native Binary Executable">⚡ Native</span>`;
            } else if (rawMode.toLowerCase() === "jar") {
                modeBadgeHtml = `<span class="node-badge" style="background: rgba(245, 158, 11, 0.2); color: #fbbf24; border: 1px solid rgba(245, 158, 11, 0.4);" title="Execution Mode: JVM Fat-JAR">☕ Jar</span>`;
            } else {
                modeBadgeHtml = `<span class="node-badge" style="background: rgba(156, 163, 175, 0.2); color: #9ca3af; border: 1px solid rgba(156, 163, 175, 0.4);">${escapeHtml(rawMode)}</span>`;
            }

            const modeLabelColor = rawMode.toLowerCase() === "native" ? "#c084fc" : (rawMode.toLowerCase() === "jar" ? "#fbbf24" : "#9ca3af");

            html += `
                <div class="server-item ${isSelected ? "active-selected" : ""}" data-ip="${escapeHtml(node.ip)}">
                    <div class="server-info-col">
                        <span class="status-dot ${statusClass}" title="Status: ${escapeHtml(node.status)}"></span>
                        <div>
                            <div class="server-ip-title">
                                ${escapeHtml(node.ip)}
                                <span class="node-badge ${badgeClass}">${badgeText}</span>
                                <span class="node-badge" style="background: rgba(59, 130, 246, 0.15); color: #38bdf8; border: 1px solid rgba(56, 189, 248, 0.3);">${escapeHtml(versionStr)}</span>
                                ${modeBadgeHtml}
                            </div>
                            <div style="font-size: 12px; color: var(--text-muted, #94a3b8); margin-top: 2px;">
                                Server Version: <strong style="color: #38bdf8;">${escapeHtml(versionStr)}</strong> | Mode: <strong style="color: ${modeLabelColor};">${escapeHtml(rawMode)}</strong> | App Port: <strong>${node.appPort}</strong> | Cockroach DB Port: <strong>${node.dbPort}</strong> | Role: ${escapeHtml(node.role || "Gateway")}
                            </div>
                        </div>
                    </div>
                    <div class="server-actions-col">
                        <button class="btn-action logs btn-server-logs" data-ip="${escapeHtml(node.ip)}" type="button">🔍 View Logs</button>
                        <button class="btn-action config btn-server-config" data-ip="${escapeHtml(node.ip)}" type="button">⚙️ Edit app-config.json</button>
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

        container.querySelectorAll(".btn-server-config").forEach(btn => {
            btn.addEventListener("click", (e) => {
                e.stopPropagation();
                const ip = btn.getAttribute("data-ip");
                if (ip) {
                    openAppConfigModal(ip);
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

    // App Config Modal Controls
    async function openAppConfigModal(targetIp) {
        pendingConfigTargetIp = targetIp;
        const modal = document.getElementById("app-config-modal");
        const subtitle = document.getElementById("modal-config-subtitle");
        const editor = document.getElementById("app-config-editor");
        const errEl = document.getElementById("modal-config-error");

        if (subtitle) {
            subtitle.innerHTML = `Editing <strong>config/app-config.json</strong> for target server node: <strong style="font-family: monospace; color: #c084fc;">${escapeHtml(targetIp)}</strong>`;
        }

        if (errEl) errEl.style.display = "none";
        if (editor) editor.value = "Loading configuration...";

        if (modal) modal.classList.add("show");

        try {
            const resp = await apiRequest(`/api/admin/cluster/nodes/${encodeURIComponent(targetIp)}/app-config`);
            if (editor) {
                editor.value = resp.rawJson || JSON.stringify(resp.config, null, 4);
            }
        } catch (e) {
            if (editor) editor.value = `// Failed to load app-config.json from server ${targetIp}\n// Error: ${e.message}`;
            showToastMsg(`Failed to load app-config.json from ${targetIp}: ${e.message}`, "error");
        }
    }

    function closeAppConfigModal() {
        const modal = document.getElementById("app-config-modal");
        if (modal) modal.classList.remove("show");
    }

    async function saveAppConfig() {
        const editor = document.getElementById("app-config-editor");
        const errEl = document.getElementById("modal-config-error");
        if (!editor) return;

        const rawJson = editor.value;

        // Local JSON validation
        try {
            JSON.parse(rawJson);
            if (errEl) errEl.style.display = "none";
        } catch (e) {
            if (errEl) {
                errEl.textContent = `JSON Syntax Error: ${e.message}`;
                errEl.style.display = "block";
            }
            showToastMsg("Invalid JSON format. Please fix syntax errors before saving.", "error");
            return;
        }

        try {
            showToastMsg(`Saving app-config.json to server ${pendingConfigTargetIp}...`, "info");
            const endpoint = `/api/admin/cluster/nodes/${encodeURIComponent(pendingConfigTargetIp)}/app-config`;
            
            const resp = await apiRequest(endpoint, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: rawJson
            });

            if (resp.success) {
                showToastMsg(resp.message || `app-config.json saved successfully for node ${pendingConfigTargetIp}.`, "success");
                closeAppConfigModal();
            } else {
                if (errEl) {
                    errEl.textContent = resp.message || "Failed to save configuration.";
                    errEl.style.display = "block";
                }
                showToastMsg(`Failed to save config: ${resp.message}`, "error");
            }
        } catch (e) {
            if (errEl) {
                errEl.textContent = `Server Error: ${e.message}`;
                errEl.style.display = "block";
            }
            showToastMsg(`Save request failed: ${e.message}`, "error");
        }
    }

    // Reboot & Reinstall Modal Controls
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
