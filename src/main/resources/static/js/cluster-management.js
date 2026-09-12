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
        loadServerErrorAlertSettings();
        bindServerErrorAlertEvents();
        loadLoadBalancerStatus();
        bindLoadBalancerEvents();
        loadQuorumFallbackStatus();
        bindQuorumFallbackEvents();
        loadSnapshotsStatus();
        bindSnapshotEvents();
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
                try {
                    localStorage.removeItem("cache:/api/auth/me");
                    localStorage.removeItem("etag:/api/auth/me");
                } catch (e) {}
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

    async function loadServerErrorAlertSettings() {
        const card = document.getElementById("server-error-alerts-card");
        const toggle = document.getElementById("server-error-alerts-toggle");
        const badge = document.getElementById("error-alerts-status-badge");
        const info = document.getElementById("server-error-alerts-info");
        const recipients = document.getElementById("server-error-alerts-recipients");
        if (!card) return;

        try {
            const data = await apiRequest("/api/admin/cluster/error-alerts");
            if (data && data.success) {
                card.classList.remove("hidden");
                const isEnabled = !!(data.emailServerErrors ?? data.enabled ?? data.settings?.emailServerErrors ?? data.settings?.enabled);
                if (toggle) toggle.checked = isEnabled;
                if (badge) {
                    if (isEnabled) {
                        badge.textContent = "Active";
                        badge.style.background = "rgba(239, 68, 68, 0.2)";
                        badge.style.color = "#f87171";
                    } else {
                        badge.textContent = "Disabled";
                        badge.style.background = "rgba(100, 116, 139, 0.2)";
                        badge.style.color = "#94a3b8";
                    }
                }
                if (info && recipients) {
                    info.style.display = "block";
                    const recList = Array.isArray(data.enrolledSuperadmins) && data.enrolledSuperadmins.length > 0
                        ? data.enrolledSuperadmins.join(", ")
                        : (Array.isArray(data.recipients) && data.recipients.length > 0
                            ? data.recipients.join(", ")
                            : "None (superadmins must enable node alerts / have valid email addresses)");
                    const count = (data.enrolledSuperadmins || data.recipients || []).length;
                    recipients.textContent = `Mailing List (${count}): ${recList}`;
                }
            }
        } catch (e) {
            card.classList.add("hidden");
        }
    }

    function bindServerErrorAlertEvents() {
        const toggle = document.getElementById("server-error-alerts-toggle");
        const testBtn = document.getElementById("btn-test-error-alert");
        const badge = document.getElementById("error-alerts-status-badge");
        const statusMsg = document.getElementById("server-error-alerts-status-msg");

        toggle?.addEventListener("change", async (e) => {
            const enabled = e.target.checked;
            try {
                const res = await apiRequest("/api/admin/cluster/error-alerts", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        emailServerErrors: enabled,
                        enabled: enabled
                    })
                });
                if (badge) {
                    if (enabled) {
                        badge.textContent = "Active";
                        badge.style.background = "rgba(239, 68, 68, 0.2)";
                        badge.style.color = "#f87171";
                    } else {
                        badge.textContent = "Disabled";
                        badge.style.background = "rgba(100, 116, 139, 0.2)";
                        badge.style.color = "#94a3b8";
                    }
                }
                showToastMsg(res.message || (enabled ? "Cluster error alerting enabled" : "Cluster error alerting disabled"), "success");
            } catch (err) {
                showToastMsg("Failed to update error alert settings: " + err.message, "error");
                toggle.checked = !enabled;
            }
        });

        testBtn?.addEventListener("click", async () => {
            Obsidianscout.setButtonLoading(testBtn, true, "Sending test alert...");
            if (statusMsg) {
                statusMsg.style.display = "block";
                statusMsg.style.color = "#cbd5e1";
                statusMsg.textContent = "Sending test server code error alert to mailing list...";
            }
            try {
                const res = await apiRequest("/api/admin/cluster/error-alerts/test", { method: "POST" });
                if (statusMsg) {
                    statusMsg.style.color = res.success ? "#4ade80" : "#f87171";
                    statusMsg.textContent = res.message;
                }
                showToastMsg(res.message, res.success ? "success" : "error");
            } catch (err) {
                if (statusMsg) {
                    statusMsg.style.color = "#f87171";
                    statusMsg.textContent = "Failed to send test alert: " + err.message;
                }
                showToastMsg("Failed to send test alert: " + err.message, "error");
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
            if (status.isRunning && status.remainingSeconds > 0) {
                pill.textContent = `Overload Active (${status.remainingSeconds}s remaining)`;
                pill.style.background = "rgba(239, 68, 68, 0.25)";
                pill.style.color = "#f87171";
                pill.style.border = "1px solid rgba(239, 68, 68, 0.5)";
                if (startBtn) startBtn.classList.add("hidden");
                if (stopBtn) stopBtn.classList.remove("hidden");

                if (!stressPollInterval) {
                    stressPollInterval = setInterval(() => {
                        loadStressTestStatus();
                    }, 1000);
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
                    loadLoadBalancerStatus();
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
            loadSnapshotsStatus();
        } catch (e) {
            console.error("[ClusterManagement] Failed to fetch cluster nodes:", e);
            currentNodes = [
                { nodeId: "local", ip: "127.0.0.1", dbPort: 26257, appPort: 8080, isLocal: true, status: "online", role: "Local Gateway Server Node" }
            ];
            renderServerList(currentNodes, "127.0.0.1");
            fetchNodeLogs();
            loadSnapshotsStatus();
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

    async function loadQuorumFallbackStatus() {
        const card = document.getElementById("quorum-fallback-card");
        const tbody = document.getElementById("qf-nodes-tbody");
        const globalPill = document.getElementById("qf-global-status-pill");
        if (!card || !tbody) return;

        try {
            const data = await apiRequest("/api/admin/cluster/quorum-fallback");
            if (data && Array.isArray(data)) {
                card.classList.remove("hidden");
                renderQuorumFallbackNodes(data, tbody, globalPill);
            }
        } catch (e) {
            console.warn("[ClusterManagement] Quorum fallback status error:", e);
            card.classList.add("hidden");
        }
    }

    function renderQuorumFallbackNodes(nodes, tbody, globalPill) {
        tbody.innerHTML = "";
        let anyActive = false;
        let allEnabled = true;

        if (nodes.length === 0) {
            tbody.innerHTML = `<tr><td colspan="6" style="padding: 16px; text-align: center; color: #64748b;">No cluster nodes found.</td></tr>`;
            return;
        }

        nodes.forEach(node => {
            if (node.isActiveServingReads) anyActive = true;
            if (!node.enabled) allEnabled = false;

            const tr = document.createElement("tr");
            tr.style.borderBottom = "1px solid rgba(255, 255, 255, 0.06)";

            // 1. Server Node IP
            const nodeTd = document.createElement("td");
            nodeTd.style.padding = "10px";
            nodeTd.innerHTML = `
                <div style="font-family: monospace; font-weight: 700; color: #f8fafc; display: flex; align-items: center; gap: 6px;">
                    ${escapeHtml(node.nodeIp)}
                    <span class="node-badge ${node.isLocal ? "local" : "remote"}">${node.isLocal ? "Local" : "Remote"}</span>
                </div>
            `;

            // 2. Status Badge
            const statusTd = document.createElement("td");
            statusTd.style.padding = "10px";
            let statusColor = "#94a3b8";
            let statusBg = "rgba(148, 163, 184, 0.15)";
            if (node.isActiveServingReads) {
                statusColor = "#f59e0b";
                statusBg = "rgba(245, 158, 11, 0.2)";
            } else if (node.enabled && node.isAvailable) {
                statusColor = "#10b981";
                statusBg = "rgba(16, 185, 129, 0.2)";
            }
            statusTd.innerHTML = `<span class="badge" style="background: ${statusBg}; color: ${statusColor}; font-size: 11px;">${escapeHtml(node.status)}</span>`;

            // 3. Snapshot DB Size
            const sizeTd = document.createElement("td");
            sizeTd.style.padding = "10px";
            sizeTd.style.fontFamily = "monospace";
            sizeTd.style.color = node.databaseSizeBytes > 0 ? "#e2e8f0" : "#64748b";
            sizeTd.textContent = formatBytes(node.databaseSizeBytes);

            // 4. Available Disk Space
            const diskTd = document.createElement("td");
            diskTd.style.padding = "10px";
            if (node.totalDiskSpaceBytes > 0) {
                const freeFormatted = formatBytes(node.freeDiskSpaceBytes);
                const totalFormatted = formatBytes(node.totalDiskSpaceBytes);
                const percentFree = Math.round((node.freeDiskSpaceBytes / node.totalDiskSpaceBytes) * 100);
                diskTd.innerHTML = `
                    <div style="font-size: 12px; color: #cbd5e1;">${freeFormatted} free / ${totalFormatted}</div>
                    <div style="font-size: 11px; color: ${percentFree < 15 ? "#ef4444" : "#94a3b8"};">${percentFree}% disk available</div>
                `;
            } else {
                diskTd.innerHTML = `<span style="color: #64748b;">Unknown</span>`;
            }

            // 5. Last Synced
            const syncTd = document.createElement("td");
            syncTd.style.padding = "10px";
            syncTd.style.fontSize = "12px";
            syncTd.style.color = "#94a3b8";
            if (node.lastSyncTimestamp) {
                try {
                    const date = new Date(node.lastSyncTimestamp);
                    syncTd.textContent = date.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit", second: "2-digit" });
                } catch (_) {
                    syncTd.textContent = node.lastSyncTimestamp;
                }
            } else {
                syncTd.textContent = "Never";
            }

            // 6. Actions
            const actionsTd = document.createElement("td");
            actionsTd.style.padding = "10px";
            actionsTd.style.textAlign = "right";

            const toggleLabel = document.createElement("label");
            toggleLabel.style.display = "inline-flex";
            toggleLabel.style.alignItems = "center";
            toggleLabel.style.gap = "6px";
            toggleLabel.style.marginRight = "10px";
            toggleLabel.style.fontSize = "12px";
            toggleLabel.style.cursor = "pointer";
            toggleLabel.style.color = "#cbd5e1";

            const toggleInput = document.createElement("input");
            toggleInput.type = "checkbox";
            toggleInput.checked = !!node.enabled;
            toggleInput.style.cursor = "pointer";
            toggleInput.addEventListener("change", async () => {
                const wantEnabled = toggleInput.checked;
                await toggleQuorumFallbackOnNode(node.nodeIp, wantEnabled);
            });

            toggleLabel.appendChild(toggleInput);
            toggleLabel.appendChild(document.createTextNode(node.enabled ? "Enabled" : "Disabled"));

            const configBtn = document.createElement("button");
            configBtn.className = "btn-action config";
            configBtn.style.padding = "4px 8px";
            configBtn.style.fontSize = "11px";
            configBtn.style.marginRight = "6px";
            configBtn.textContent = "⚙️ Configure";
            configBtn.addEventListener("click", () => {
                openConfigureQuorumFallbackModal(node);
            });

            const inspectBtn = document.createElement("button");
            inspectBtn.className = "btn-action logs";
            inspectBtn.style.padding = "4px 8px";
            inspectBtn.style.fontSize = "11px";
            inspectBtn.style.marginRight = "6px";
            inspectBtn.textContent = "🔍 Inspect DB";
            inspectBtn.disabled = !node.enabled && node.databaseSizeBytes === 0;
            inspectBtn.addEventListener("click", () => {
                openInspectQuorumFallbackModal(node.nodeIp);
            });

            const syncBtn = document.createElement("button");
            syncBtn.className = "btn-action logs";
            syncBtn.style.padding = "4px 8px";
            syncBtn.style.fontSize = "11px";
            syncBtn.style.marginRight = "6px";
            syncBtn.textContent = "Sync Now";
            syncBtn.disabled = !node.enabled;
            syncBtn.addEventListener("click", async () => {
                await syncQuorumFallbackOnNode(node.nodeIp, syncBtn);
            });

            const purgeBtn = document.createElement("button");
            purgeBtn.className = "btn-action reinstall";
            purgeBtn.style.padding = "4px 8px";
            purgeBtn.style.fontSize = "11px";
            purgeBtn.textContent = "Purge Storage";
            purgeBtn.addEventListener("click", async () => {
                if (confirm(`Purge local fallback SQLite database on node ${node.nodeIp} to reclaim storage space?`)) {
                    await purgeQuorumFallbackOnNode(node.nodeIp, purgeBtn);
                }
            });

            actionsTd.appendChild(toggleLabel);
            actionsTd.appendChild(configBtn);
            actionsTd.appendChild(inspectBtn);
            actionsTd.appendChild(syncBtn);
            actionsTd.appendChild(purgeBtn);

            tr.appendChild(nodeTd);
            tr.appendChild(statusTd);
            tr.appendChild(sizeTd);
            tr.appendChild(diskTd);
            tr.appendChild(syncTd);
            tr.appendChild(actionsTd);

            tbody.appendChild(tr);
        });

        if (globalPill) {
            if (anyActive) {
                globalPill.style.background = "rgba(239, 68, 68, 0.2)";
                globalPill.style.color = "#f87171";
                globalPill.textContent = "⚠️ Active (Quorum Lost / Offline Reads)";
            } else if (allEnabled) {
                globalPill.style.background = "rgba(16, 185, 129, 0.2)";
                globalPill.style.color = "#34d399";
                globalPill.textContent = "Active Mirroring (All Nodes Ready)";
            } else {
                globalPill.style.background = "rgba(245, 158, 11, 0.2)";
                globalPill.style.color = "#fbbf24";
                globalPill.textContent = "Partial Mirroring";
            }
        }
    }

    let currentConfiguringNodeIp = null;

    function bindQuorumFallbackEvents() {
        const refreshBtn = document.getElementById("btn-qf-refresh");
        refreshBtn?.addEventListener("click", async () => {
            refreshBtn.disabled = true;
            refreshBtn.textContent = "Refreshing...";
            try {
                await loadQuorumFallbackStatus();
                showToastMsg("Quorum fallback status refreshed.", "info");
            } finally {
                refreshBtn.disabled = false;
                refreshBtn.textContent = "Refresh Fallback Status";
            }
        });

        document.getElementById("modal-inspect-close-x")?.addEventListener("click", closeInspectQuorumFallbackModal);
        document.getElementById("modal-inspect-close-btn")?.addEventListener("click", closeInspectQuorumFallbackModal);
        document.getElementById("quorum-inspect-modal")?.addEventListener("click", (e) => {
            if (e.target.id === "quorum-inspect-modal") closeInspectQuorumFallbackModal();
        });

        document.getElementById("modal-qf-config-close-x")?.addEventListener("click", closeConfigureQuorumFallbackModal);
        document.getElementById("modal-qf-config-cancel")?.addEventListener("click", closeConfigureQuorumFallbackModal);
        document.getElementById("quorum-config-modal")?.addEventListener("click", (e) => {
            if (e.target.id === "quorum-config-modal") closeConfigureQuorumFallbackModal();
        });
        document.getElementById("modal-qf-config-save")?.addEventListener("click", saveQuorumFallbackConfig);

        // Toggle selective checkboxes visibility
        const modeEverything = document.getElementById("qf-mode-everything");
        const modeSelective = document.getElementById("qf-mode-selective");
        const selectiveOpts = document.getElementById("qf-selective-options");

        const updateModeUI = () => {
            if (selectiveOpts) {
                selectiveOpts.style.opacity = modeEverything?.checked ? "0.4" : "1";
                selectiveOpts.style.pointerEvents = modeEverything?.checked ? "none" : "auto";
            }
        };

        modeEverything?.addEventListener("change", updateModeUI);
        modeSelective?.addEventListener("change", updateModeUI);
    }

    function openConfigureQuorumFallbackModal(node) {
        const modal = document.getElementById("quorum-config-modal");
        const nodePill = document.getElementById("qf-config-node-pill");
        if (!modal) return;

        currentConfiguringNodeIp = node.nodeIp;
        if (nodePill) nodePill.textContent = `Node: ${node.nodeIp}`;

        const cfg = node.config || {};
        const isMirrorAll = !!cfg.mirror_all_data;

        const radioEverything = document.getElementById("qf-mode-everything");
        const radioSelective = document.getElementById("qf-mode-selective");
        if (isMirrorAll) {
            if (radioEverything) radioEverything.checked = true;
        } else {
            if (radioSelective) radioSelective.checked = true;
        }

        const chkUsers = document.getElementById("qf-chk-users");
        if (chkUsers) chkUsers.checked = cfg.mirror_users !== false;

        const chkScouting = document.getElementById("qf-chk-scouting");
        if (chkScouting) chkScouting.checked = cfg.mirror_scouting !== false;

        const chkApi = document.getElementById("qf-chk-api-data");
        if (chkApi) chkApi.checked = cfg.mirror_api_data !== false;

        const chkAnalytics = document.getElementById("qf-chk-analytics");
        if (chkAnalytics) chkAnalytics.checked = cfg.mirror_custom_analytics !== false;

        const chkConfigs = document.getElementById("qf-chk-configs");
        if (chkConfigs) chkConfigs.checked = true;

        const chkAlliances = document.getElementById("qf-chk-alliances");
        if (chkAlliances) chkAlliances.checked = cfg.mirror_alliances !== false;

        const chkChat = document.getElementById("qf-chk-chat");
        if (chkChat) chkChat.checked = cfg.mirror_chat !== false;

        const chkSecrets = document.getElementById("qf-chk-secrets");
        if (chkSecrets) chkSecrets.checked = cfg.mirror_notifications_secrets !== false;

        const selRetention = document.getElementById("qf-select-retention");
        if (selRetention) selRetention.value = String(cfg.scouting_retention_days || 7);

        const inputInterval = document.getElementById("qf-input-interval");
        if (inputInterval) inputInterval.value = String(cfg.sync_interval_seconds || 30);

        const inputSqlite = document.getElementById("qf-input-sqlite-file");
        if (inputSqlite) inputSqlite.value = cfg.sqlite_file || "data/quorum_fallback.db";

        const selectiveOpts = document.getElementById("qf-selective-options");
        if (selectiveOpts) {
            selectiveOpts.style.opacity = isMirrorAll ? "0.4" : "1";
            selectiveOpts.style.pointerEvents = isMirrorAll ? "none" : "auto";
        }

        modal.classList.add("show");
    }

    function closeConfigureQuorumFallbackModal() {
        const modal = document.getElementById("quorum-config-modal");
        if (modal) modal.classList.remove("show");
        currentConfiguringNodeIp = null;
    }

    async function saveQuorumFallbackConfig() {
        if (!currentConfiguringNodeIp) return;

        const saveBtn = document.getElementById("modal-qf-config-save");
        const radioEverything = document.getElementById("qf-mode-everything");
        const isMirrorAll = radioEverything?.checked ?? false;

        const payload = {
            targetIp: currentConfiguringNodeIp,
            mirrorAllData: isMirrorAll,
            mirrorUsers: document.getElementById("qf-chk-users")?.checked ?? true,
            mirrorScouting: document.getElementById("qf-chk-scouting")?.checked ?? true,
            mirrorApiData: document.getElementById("qf-chk-api-data")?.checked ?? true,
            mirrorCustomAnalytics: document.getElementById("qf-chk-analytics")?.checked ?? true,
            mirrorConfigs: true,
            mirrorAlliances: document.getElementById("qf-chk-alliances")?.checked ?? true,
            mirrorChat: document.getElementById("qf-chk-chat")?.checked ?? true,
            mirrorNotificationsSecrets: document.getElementById("qf-chk-secrets")?.checked ?? true,
            scoutingRetentionDays: parseInt(document.getElementById("qf-select-retention")?.value || "7", 10),
            syncIntervalSeconds: parseInt(document.getElementById("qf-input-interval")?.value || "30", 10),
            sqliteFile: document.getElementById("qf-input-sqlite-file")?.value?.trim() || "data/quorum_fallback.db"
        };

        if (saveBtn) {
            saveBtn.disabled = true;
            saveBtn.textContent = "Saving...";
        }

        try {
            const resp = await apiRequest("/api/admin/cluster/quorum-fallback/config", {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify(payload)
            });
            showToastMsg(resp.message || "Quorum fallback configuration saved.", resp.success ? "success" : "error");
            closeConfigureQuorumFallbackModal();
            setTimeout(() => loadQuorumFallbackStatus(), 1000);
        } catch (e) {
            showToastMsg("Failed to save configuration: " + e.message, "error");
        } finally {
            if (saveBtn) {
                saveBtn.disabled = false;
                saveBtn.textContent = "Save Node Configuration";
            }
        }
    }

    async function openInspectQuorumFallbackModal(nodeIp) {
        const modal = document.getElementById("quorum-inspect-modal");
        const nodePill = document.getElementById("inspect-node-pill");
        const modalBody = document.getElementById("inspect-modal-body");

        if (!modal || !modalBody) return;

        if (nodePill) nodePill.textContent = `Node: ${nodeIp}`;
        modalBody.innerHTML = `<div style="text-align: center; color: #94a3b8; padding: 40px;"><div style="font-size: 24px; margin-bottom: 8px;">⏳</div>Loading remote SQLite snapshot for <strong>${escapeHtml(nodeIp)}</strong>...</div>`;
        modal.classList.add("show");

        try {
            const data = await apiRequest(`/api/admin/cluster/quorum-fallback/inspect?targetIp=${encodeURIComponent(nodeIp)}`);
            renderInspectData(data, modalBody);
        } catch (err) {
            modalBody.innerHTML = `<div style="color: #f87171; padding: 20px; text-align: center;">Failed to inspect fallback database on ${escapeHtml(nodeIp)}: ${escapeHtml(err.message)}</div>`;
        }
    }

    function closeInspectQuorumFallbackModal() {
        const modal = document.getElementById("quorum-inspect-modal");
        if (modal) modal.classList.remove("show");
    }

    function renderInspectData(data, container) {
        const tableCounts = data.tableCounts || {};
        const activeEvents = data.activeEvents || [];

        let eventsHtml = "";
        if (activeEvents.length > 0) {
            eventsHtml = `
                <div style="overflow-x: auto; margin-top: 8px;">
                    <table style="width: 100%; border-collapse: collapse; font-size: 12px;">
                        <thead>
                            <tr style="border-bottom: 1px solid rgba(255,255,255,0.1); color: #94a3b8; text-align: left;">
                                <th style="padding: 6px 8px;">Event Key</th>
                                <th style="padding: 6px 8px;">Name</th>
                                <th style="padding: 6px 8px;">Dates</th>
                                <th style="padding: 6px 8px; text-align: center;">Matches</th>
                                <th style="padding: 6px 8px; text-align: center;">Teams</th>
                                <th style="padding: 6px 8px; text-align: center;">Match Scouting</th>
                                <th style="padding: 6px 8px; text-align: center;">Pit Scouting</th>
                                <th style="padding: 6px 8px; text-align: center;">Qual Notes</th>
                            </tr>
                        </thead>
                        <tbody>
                            ${activeEvents.map(ev => `
                                <tr style="border-bottom: 1px solid rgba(255,255,255,0.05);">
                                    <td style="padding: 6px 8px; font-weight: 600; color: #fbbf24;">${escapeHtml(ev.eventKey)}</td>
                                    <td style="padding: 6px 8px; color: #f1f5f9;">${escapeHtml(ev.name)}</td>
                                    <td style="padding: 6px 8px; color: #94a3b8;">${escapeHtml(ev.startDate || '')} ${ev.endDate ? '→ ' + escapeHtml(ev.endDate) : ''}</td>
                                    <td style="padding: 6px 8px; text-align: center; color: #38bdf8;">${ev.matchCount}</td>
                                    <td style="padding: 6px 8px; text-align: center; color: #38bdf8;">${ev.teamCount}</td>
                                    <td style="padding: 6px 8px; text-align: center; color: #34d399; font-weight: 600;">${ev.matchScoutingCount}</td>
                                    <td style="padding: 6px 8px; text-align: center; color: #34d399;">${ev.pitScoutingCount}</td>
                                    <td style="padding: 6px 8px; text-align: center; color: #34d399;">${ev.qualScoutingCount}</td>
                                </tr>
                            `).join('')}
                        </tbody>
                    </table>
                </div>
            `;
        } else {
            eventsHtml = `<div style="color: #64748b; font-size: 13px; padding: 12px 0;">No active events within ±1 week are currently stored in this snapshot.</div>`;
        }

        const tableEntries = Object.entries(tableCounts).sort(([a], [b]) => a.localeCompare(b));
        const tablesGridHtml = tableEntries.map(([tbl, count]) => `
            <div style="background: rgba(15, 23, 42, 0.6); border: 1px solid rgba(255,255,255,0.08); border-radius: 6px; padding: 8px 12px; display: flex; justify-content: space-between; align-items: center;">
                <span style="font-size: 12px; color: #cbd5e1; font-family: monospace;">${escapeHtml(tbl)}</span>
                <span style="font-size: 12px; font-weight: 700; color: ${count > 0 ? '#38bdf8' : '#64748b'};">${count.toLocaleString()}</span>
            </div>
        `).join('');

        container.innerHTML = `
            <!-- Top Status Grid -->
            <div style="display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px; margin-bottom: 20px;">
                <div style="background: rgba(245, 158, 11, 0.08); border: 1px solid rgba(245, 158, 11, 0.2); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 11px; color: #fbbf24; text-transform: uppercase; font-weight: 700;">Mirror Status</div>
                    <div style="font-size: 15px; font-weight: 700; color: #fef3c7; margin-top: 4px;">${escapeHtml(data.status)}</div>
                </div>
                <div style="background: rgba(56, 189, 248, 0.08); border: 1px solid rgba(56, 189, 248, 0.2); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 11px; color: #38bdf8; text-transform: uppercase; font-weight: 700;">Snapshot File Size</div>
                    <div style="font-size: 15px; font-weight: 700; color: #f0f9ff; margin-top: 4px;">${formatBytes(data.databaseSizeBytes)}</div>
                </div>
                <div style="background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.2); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 11px; color: #34d399; text-transform: uppercase; font-weight: 700;">Available Free Disk</div>
                    <div style="font-size: 15px; font-weight: 700; color: #ecfdf5; margin-top: 4px;">${formatBytes(data.freeDiskSpaceBytes)} / ${formatBytes(data.totalDiskSpaceBytes)}</div>
                </div>
                <div style="background: rgba(168, 85, 247, 0.08); border: 1px solid rgba(168, 85, 247, 0.2); border-radius: 8px; padding: 12px;">
                    <div style="font-size: 11px; color: #c084fc; text-transform: uppercase; font-weight: 700;">Last Synced</div>
                    <div style="font-size: 13px; font-weight: 600; color: #faf5ff; margin-top: 4px;">${data.lastSyncTimestamp ? new Date(data.lastSyncTimestamp).toLocaleString() : 'Never'}</div>
                </div>
            </div>

            <!-- Mirrored Events (±1 Week Window) -->
            <div style="margin-bottom: 20px; background: rgba(0, 0, 0, 0.2); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 14px;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                    <h3 style="font-size: 14px; color: #fbbf24; margin: 0;">📅 Mirrored Events (±1 Week Competition Window)</h3>
                    <span class="badge" style="background: rgba(245, 158, 11, 0.2); color: #fbbf24; font-size: 11px;">${activeEvents.length} events</span>
                </div>
                <p style="font-size: 12px; color: #94a3b8; margin: 0 0 10px 0;">
                    All API event data, match schedules, team rosters, and competition scouting entries within ±7 days of today are fully mirrored.
                </p>
                ${eventsHtml}
            </div>

            <!-- Mirrored Database Tables & Row Counts -->
            <div style="background: rgba(0, 0, 0, 0.2); border: 1px solid rgba(255, 255, 255, 0.08); border-radius: 8px; padding: 14px;">
                <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px;">
                    <h3 style="font-size: 14px; color: #38bdf8; margin: 0;">🗄️ SQLite Table Records Breakdown</h3>
                    <span class="badge" style="background: rgba(56, 189, 248, 0.2); color: #38bdf8; font-size: 11px;">${tableEntries.length} tables</span>
                </div>
                <div style="display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 8px; margin-top: 10px;">
                    ${tablesGridHtml}
                </div>
            </div>
        `;
    }

    async function toggleQuorumFallbackOnNode(nodeIp, enabled) {
        try {
            showToastMsg(`${enabled ? "Enabling" : "Disabling"} Quorum Fallback on ${nodeIp}...`, "info");
            const resp = await apiRequest("/api/admin/cluster/quorum-fallback/toggle", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ targetIp: nodeIp, enabled: enabled })
            });
            showToastMsg(resp.message || "Updated quorum fallback status.", resp.success ? "success" : "error");
            setTimeout(() => loadQuorumFallbackStatus(), 1000);
        } catch (e) {
            showToastMsg("Failed to toggle fallback: " + e.message, "error");
            setTimeout(() => loadQuorumFallbackStatus(), 500);
        }
    }

    async function syncQuorumFallbackOnNode(nodeIp, btn) {
        if (btn) btn.disabled = true;
        try {
            showToastMsg(`Triggering immediate sync to SQLite mirror on ${nodeIp}...`, "info");
            const resp = await apiRequest("/api/admin/cluster/quorum-fallback/sync", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ targetIp: nodeIp })
            });
            showToastMsg(resp.message || "Sync finished.", resp.success ? "success" : "error");
            setTimeout(() => loadQuorumFallbackStatus(), 1000);
        } catch (e) {
            showToastMsg("Sync failed: " + e.message, "error");
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    async function purgeQuorumFallbackOnNode(nodeIp, btn) {
        if (btn) btn.disabled = true;
        try {
            showToastMsg(`Purging local fallback storage on ${nodeIp}...`, "info");
            const resp = await apiRequest("/api/admin/cluster/quorum-fallback/purge", {
                method: "POST",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ targetIp: nodeIp })
            });
            showToastMsg(resp.message || "Storage purged.", resp.success ? "success" : "error");
            setTimeout(() => loadQuorumFallbackStatus(), 1000);
        } catch (e) {
            showToastMsg("Purge failed: " + e.message, "error");
        } finally {
            if (btn) btn.disabled = false;
        }
    }

    // =========================================================================
    // Superadmin Automated Database Snapshots & System Disaster Recovery
    // =========================================================================
    let pendingRestoreType = null; // "named" | "upload"
    let pendingRestoreFileName = null;
    let pendingRestoreFile = null;

    async function loadSnapshotsStatus() {
        const section = document.getElementById("auto-backup-section");
        if (!section) return;

        try {
            const status = await apiRequest("/api/admin/snapshots");
            section.classList.remove("hidden");
            renderLocalSnapshotStatus(status);
        } catch (err) {
            // Not superadmin or error
            console.warn("[ClusterManagement] Snapshot status load error:", err);
            section.classList.add("hidden");
            return;
        }

        try {
            const cluster = await apiRequest("/api/admin/cluster/auto-backup");
            renderClusterSnapshotStatus(cluster);
        } catch (err) {
            console.warn("[ClusterManagement] Failed to load cluster auto-backup status:", err);
        }
    }

    function renderLocalSnapshotStatus(status) {
        if (!status) return;

        const chkAutoBackup = document.getElementById("chk-auto-backup-enabled");
        const inputRetention = document.getElementById("input-retention-days");
        const autoBackupBadge = document.getElementById("auto-backup-badge");
        const scheduleInfo = document.getElementById("snapshot-schedule-info");
        const snapshotsTbody = document.getElementById("snapshots-table-body");

        if (chkAutoBackup) chkAutoBackup.checked = !!status.enabled;
        if (inputRetention) inputRetention.value = status.retentionDays || 30;

        if (autoBackupBadge) {
            if (status.enabled) {
                autoBackupBadge.textContent = "Daily Active (02:54 UTC)";
                autoBackupBadge.style.background = "rgba(16, 185, 129, 0.2)";
                autoBackupBadge.style.color = "var(--success)";
            } else {
                autoBackupBadge.textContent = "Auto-Backup Disabled";
                autoBackupBadge.style.background = "rgba(100, 116, 139, 0.2)";
                autoBackupBadge.style.color = "var(--muted)";
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
                tdName.innerHTML = `<strong style="font-family: monospace; color: var(--ink);">${escapeHtml(s.fileName)}</strong>`;

                const tdCreated = document.createElement("td");
                tdCreated.textContent = s.createdAtUtc || "--";

                const tdSize = document.createElement("td");
                tdSize.textContent = formatBytes(s.sizeBytes);

                const tdTrigger = document.createElement("td");
                if (s.isAutoBackup) {
                    tdTrigger.innerHTML = `<span class="badge" style="background: rgba(59, 130, 246, 0.15); color: var(--accent);">Auto (02:54 UTC)</span>`;
                } else {
                    tdTrigger.innerHTML = `<span class="badge ghost">Manual</span>`;
                }

                const tdActions = document.createElement("td");
                tdActions.style.textAlign = "right";

                // Download link
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
                            await apiRequest(`/api/admin/snapshots/${encodeURIComponent(s.fileName)}`, {
                                method: "DELETE"
                            });
                            showToastMsg("Snapshot deleted", "success");
                            loadSnapshotsStatus();
                        } catch (e) {
                            showToastMsg("Failed to delete snapshot: " + e.message, "error");
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

    function renderClusterSnapshotStatus(cluster) {
        const clusterTbody = document.getElementById("cluster-backup-nodes-tbody");
        if (!clusterTbody) return;

        // Backend returns List<AutoBackupNodeStatusDto> directly (JSON Array) or an object with .nodes
        let nodes = Array.isArray(cluster) ? cluster : (cluster && Array.isArray(cluster.nodes) ? cluster.nodes : []);

        // If cluster probe returned empty or failed to detect peers, merge with currentNodes so all servers are visible
        if (nodes.length === 0 && Array.isArray(currentNodes) && currentNodes.length > 0) {
            nodes = currentNodes.map(cn => ({
                nodeIp: cn.ip,
                isLocal: !!cn.isLocal,
                enabled: false,
                retentionDays: 30,
                snapshotsCount: 0,
                totalSnapshotsSizeBytes: 0,
                lastBackupTimeUtc: null,
                isAvailable: cn.status === "online"
            }));
        } else if (Array.isArray(currentNodes) && currentNodes.length > 0) {
            // Ensure any node in currentNodes that wasn't in the cluster response is still present
            const presentIps = new Set(nodes.map(n => n.nodeIp));
            currentNodes.forEach(cn => {
                if (!presentIps.has(cn.ip)) {
                    nodes.push({
                        nodeIp: cn.ip,
                        isLocal: !!cn.isLocal,
                        enabled: false,
                        retentionDays: 30,
                        snapshotsCount: 0,
                        totalSnapshotsSizeBytes: 0,
                        lastBackupTimeUtc: null,
                        isAvailable: cn.status === "online"
                    });
                }
            });
        }

        clusterTbody.innerHTML = "";

        if (nodes.length === 0) {
            clusterTbody.innerHTML = `<tr><td colspan="7" style="text-align: center; color: var(--muted); padding: 16px;">No cluster nodes detected.</td></tr>`;
            return;
        }

        nodes.forEach(n => {
            const tr = document.createElement("tr");

            const tdNode = document.createElement("td");
            tdNode.innerHTML = `<strong>${escapeHtml(n.nodeIp)}</strong> ${n.isLocal ? '<span class="badge ghost" style="font-size: 10px; margin-left: 4px;">Local</span>' : ''}`;

            const tdDaily = document.createElement("td");
            const toggle = document.createElement("input");
            toggle.type = "checkbox";
            toggle.checked = !!n.enabled;
            toggle.style.cursor = "pointer";
            toggle.addEventListener("change", async () => {
                const wantEnabled = toggle.checked;
                try {
                    await apiRequest("/api/admin/cluster/auto-backup/toggle", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ targetIp: n.nodeIp, enabled: wantEnabled })
                    });
                    showToastMsg(`Auto-backup ${wantEnabled ? "enabled" : "disabled"} on ${n.nodeIp}`, "success");
                    loadSnapshotsStatus();
                } catch (e) {
                    showToastMsg("Failed to toggle auto-backup: " + e.message, "error");
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
                if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                    Obsidianscout.setButtonLoading(btnNodeSnap, true, "Saving...");
                }
                try {
                    const res = await apiRequest("/api/admin/cluster/auto-backup/create", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({ targetIp: n.nodeIp })
                    });
                    showToastMsg(res.message || `Snapshot created on ${n.nodeIp}`, res.success ? "success" : "error");
                    loadSnapshotsStatus();
                } catch (e) {
                    showToastMsg("Failed to trigger snapshot: " + e.message, "error");
                } finally {
                    if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                        Obsidianscout.setButtonLoading(btnNodeSnap, false);
                    }
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

    function bindSnapshotEvents() {
        const btnSaveConfig = document.getElementById("btn-save-backup-config");
        const btnCreateSnapshot = document.getElementById("btn-create-manual-snapshot");
        const btnRefreshSnapshots = document.getElementById("btn-refresh-snapshots");
        const chkAutoBackup = document.getElementById("chk-auto-backup-enabled");
        const inputRetention = document.getElementById("input-retention-days");

        // Restore upload elements
        const restoreDropZone = document.getElementById("restore-drop-zone");
        const restoreFileInput = document.getElementById("restore-file-input");
        const restoreFileInfo = document.getElementById("restore-file-info");
        const btnExecuteUploadRestore = document.getElementById("btn-execute-upload-restore");

        // Restore confirm modal elements
        const inputRestoreConfirmText = document.getElementById("input-restore-confirm-text");
        const btnConfirmExecuteRestore = document.getElementById("btn-confirm-execute-restore");
        const btnCancelRestore = document.getElementById("btn-cancel-restore");
        const btnCloseRestoreModal = document.getElementById("btn-close-restore-modal");

        // Save local configuration
        btnSaveConfig?.addEventListener("click", async () => {
            const enabled = chkAutoBackup.checked;
            const retentionDays = parseInt(inputRetention.value, 10) || 30;

            if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                Obsidianscout.setButtonLoading(btnSaveConfig, true, "Saving...");
            }
            try {
                const res = await apiRequest("/api/admin/snapshots/config", {
                    method: "PUT",
                    headers: { "Content-Type": "application/json" },
                    body: JSON.stringify({
                        enabled: enabled,
                        retentionDays: retentionDays
                    })
                });
                renderLocalSnapshotStatus(res);
                showToastMsg("Auto-backup settings saved", "success");
                loadSnapshotsStatus();
            } catch (err) {
                showToastMsg("Failed to save settings: " + err.message, "error");
            } finally {
                if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                    Obsidianscout.setButtonLoading(btnSaveConfig, false);
                }
            }
        });

        // Trigger manual snapshot on this node
        btnCreateSnapshot?.addEventListener("click", async () => {
            if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                Obsidianscout.setButtonLoading(btnCreateSnapshot, true, "Creating snapshot...");
            }
            try {
                const snapshot = await apiRequest("/api/admin/snapshots/create", {
                    method: "POST"
                });
                showToastMsg(`Snapshot created: ${snapshot.fileName}`, "success");
                loadSnapshotsStatus();
            } catch (err) {
                showToastMsg("Failed to create snapshot: " + err.message, "error");
            } finally {
                if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                    Obsidianscout.setButtonLoading(btnCreateSnapshot, false);
                }
            }
        });

        btnRefreshSnapshots?.addEventListener("click", async () => {
            if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                Obsidianscout.setButtonLoading(btnRefreshSnapshots, true, "Refreshing...");
            }
            await loadSnapshotsStatus();
            if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                Obsidianscout.setButtonLoading(btnRefreshSnapshots, false);
            }
            showToastMsg("Snapshots refreshed", "info");
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
        inputRestoreConfirmText?.addEventListener("input", () => {
            const val = inputRestoreConfirmText.value.trim().toUpperCase();
            btnConfirmExecuteRestore.disabled = val !== "RESTORE DATABASE";
        });

        btnCancelRestore?.addEventListener("click", closeRestoreModal);
        btnCloseRestoreModal?.addEventListener("click", closeRestoreModal);

        btnConfirmExecuteRestore?.addEventListener("click", async () => {
            if (inputRestoreConfirmText.value.trim().toUpperCase() !== "RESTORE DATABASE") return;

            if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                Obsidianscout.setButtonLoading(btnConfirmExecuteRestore, true, "Restoring database...");
            }
            try {
                let report;
                if (pendingRestoreType === "named") {
                    report = await apiRequest(`/api/admin/snapshots/restore/${encodeURIComponent(pendingRestoreFileName)}`, {
                        method: "POST"
                    });
                } else if (pendingRestoreType === "upload" && pendingRestoreFile) {
                    const formData = new FormData();
                    formData.append("file", pendingRestoreFile);
                    if (window.Obsidianscout && typeof Obsidianscout.request === "function") {
                        report = await Obsidianscout.request("/api/admin/snapshots/restore-upload", {
                            method: "POST",
                            body: formData
                        });
                    } else {
                        const res = await fetch("/api/admin/snapshots/restore-upload", {
                            method: "POST",
                            body: formData
                        });
                        if (!res.ok) throw new Error(`HTTP ${res.status}`);
                        report = await res.json();
                    }
                }

                closeRestoreModal();
                if (report) {
                    showToastMsg(`Database restored successfully! ${report.totalRowsRestored} records restored.`, "success");
                    alert(`Database restoration complete!\n\nSource: ${report.sourceFile}\nDuration: ${report.durationMs}ms\nTotal Records Restored: ${report.totalRowsRestored}\nSeed Superadmin Preserved: ${report.superadminPreserved ? 'Yes' : 'No'}\n\nThe page will now refresh.`);
                    window.location.reload();
                }
            } catch (err) {
                console.error("Restoration failed:", err);
                showToastMsg("Database restoration failed: " + err.message, "error");
                alert("Database restoration failed: " + err.message);
            } finally {
                if (window.Obsidianscout && typeof Obsidianscout.setButtonLoading === "function") {
                    Obsidianscout.setButtonLoading(btnConfirmExecuteRestore, false);
                }
            }
        });
    }

    function openRestoreModal(type, targetName, file = null) {
        pendingRestoreType = type;
        pendingRestoreFileName = targetName;
        pendingRestoreFile = file;

        const restoreModal = document.getElementById("restore-confirm-modal");
        const targetSnapshotLabel = document.getElementById("restore-target-snapshot-name");
        const inputRestoreConfirmText = document.getElementById("input-restore-confirm-text");
        const btnConfirmExecuteRestore = document.getElementById("btn-confirm-execute-restore");

        if (targetSnapshotLabel) targetSnapshotLabel.textContent = targetName;
        if (inputRestoreConfirmText) inputRestoreConfirmText.value = "";
        if (btnConfirmExecuteRestore) btnConfirmExecuteRestore.disabled = true;

        restoreModal?.classList.add("show");
        setTimeout(() => inputRestoreConfirmText?.focus(), 150);
    }

    function closeRestoreModal() {
        const restoreModal = document.getElementById("restore-confirm-modal");
        restoreModal?.classList.remove("show");
        pendingRestoreType = null;
        pendingRestoreFileName = null;
        pendingRestoreFile = null;
    }

    function formatBytes(bytes) {
        if (!bytes || bytes <= 0) return "0 B";
        const k = 1024;
        const sizes = ["B", "KB", "MB", "GB", "TB"];
        const i = Math.floor(Math.log(bytes) / Math.log(k));
        return parseFloat((bytes / Math.pow(k, i)).toFixed(1)) + " " + sizes[i];
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

