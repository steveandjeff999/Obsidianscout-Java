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

    // Start with no group selected — will be set after loadGroups() resolves
    let currentGroup = "";
    const urlParams = new URLSearchParams(window.location.search);
    const initialGroupParam = urlParams.get("group");
    if (initialGroupParam && initialGroupParam.trim() !== "") {
        currentGroup = initialGroupParam.trim();
    }

    if (navigator.serviceWorker) {
        navigator.serviceWorker.addEventListener("message", (event) => {
            if (event.data && event.data.type === "SWITCH_GROUP" && event.data.groupName) {
                switchGroup(event.data.groupName);
            }
        });
    }

    let pollInterval = null;
    let knownGroups = [];
    let isChatEnabled = true;

    // DOM Elements
    const chatDisabledContainer = document.getElementById("chat-disabled-container");
    const chatActiveContainer = document.getElementById("chat-active-container");
    const groupListContainer = document.getElementById("group-list-container");
    const btnCreateGroup = document.getElementById("btn-create-group");
    const currentGroupTitle = document.getElementById("current-group-title");
    const btnChannelSettings = document.getElementById("btn-channel-settings");
    const messageContainer = document.getElementById("message-container");
    const chatMessageInput = document.getElementById("chat-message-input");
    const btnSendMessage = document.getElementById("btn-send-message");
    const btnBackChannels = document.getElementById("btn-back-channels");

    // Modal elements
    const channelSettingsModal = document.getElementById("channel-settings-modal");
    const channelSettingsTitle = document.getElementById("channel-settings-title");
    const btnCloseChannelSettings = document.getElementById("btn-close-channel-settings");
    const btnCancelChannelSettings = document.getElementById("btn-cancel-channel-settings");
    const btnSaveChannelSettings = document.getElementById("btn-save-channel-settings");
    const btnModalClearChannel = document.getElementById("btn-modal-clear-channel");
    const btnModalDeleteChannel = document.getElementById("btn-modal-delete-channel");
    const channelRolesContainer = document.getElementById("channel-roles-container");
    const channelMembersContainer = document.getElementById("channel-members-container");
    const channelMemberSearch = document.getElementById("channel-member-search");
    const channelDeleteHint = document.getElementById("channel-delete-hint");

    let settingsTargetGroup = "";
    let teamMembersList = [];
    let selectedGroupRoles = [];
    let selectedGroupUserIds = [];

    // Autocomplete state
    let mentionDropdown = null;
    let mentionOptions = [];
    let activeMentionIndex = 0;
    let mentionTriggerIndex = -1;
    let groupUnreads = {};

    // Emoji reaction list
    const emojis = ["👍", "❤️", "🔥", "😂", "😮", "😢"];

    // Check settings first
    async function checkChatSettings() {
        try {
            const settingsResponse = await Obsidianscout.request("/api/settings?local=true");
            isChatEnabled = settingsResponse.settings.chatEnabled;
            if (!isChatEnabled) {
                chatDisabledContainer.classList.remove("hidden");
                chatActiveContainer.classList.add("hidden");
                stopPolling();
                return false;
            } else {
                chatDisabledContainer.classList.add("hidden");
                chatActiveContainer.classList.remove("hidden");
                return true;
            }
        } catch (e) {
            console.error("Failed to fetch settings", e);
            return false;
        }
    }

    async function loadGroups() {
        try {
            const groups = await Obsidianscout.request("/api/chat/groups");
            knownGroups = groups && groups.length > 0 ? groups : [];
            // If the currently selected group is not accessible, reset to first available
            if (knownGroups.length > 0 && !knownGroups.includes(currentGroup)) {
                currentGroup = knownGroups[0];
            }
            renderGroups();
            loadGroupUnreads();
        } catch (e) {
            console.error("Failed to load groups", e);
        }
    }

    async function loadGroupUnreads() {
        try {
            const status = await Obsidianscout.request("/api/chat/unread-status");
            if (status && status.groups) {
                groupUnreads = {};
                status.groups.forEach(g => {
                    groupUnreads[g.groupName] = {
                        unreadCount: g.unreadCount,
                        mentionCount: g.mentionCount
                    };
                });
                renderGroups();
            }
        } catch (e) {
            console.error("Failed to load group unread status:", e);
        }
    }

    function renderGroups() {
        groupListContainer.innerHTML = "";
        const userRole = (me?.role || "").toUpperCase();
        const isAdmin = userRole === "ADMIN" || userRole === "SUPERADMIN";

        knownGroups.forEach(group => {
            const item = document.createElement("div");
            item.className = `group-item ${group === currentGroup ? "active" : ""}`;
            
            const labelSpan = document.createElement("span");
            labelSpan.textContent = `# ${group}`;
            labelSpan.style.overflow = "hidden";
            labelSpan.style.textOverflow = "ellipsis";
            labelSpan.style.whiteSpace = "nowrap";
            item.appendChild(labelSpan);

            const actionsContainer = document.createElement("div");
            actionsContainer.className = "group-item-actions";

            if (group !== currentGroup && groupUnreads[group]) {
                const info = groupUnreads[group];
                if (info.mentionCount > 0) {
                    const badge = document.createElement("span");
                    badge.className = "group-badge";
                    badge.textContent = info.mentionCount;
                    actionsContainer.appendChild(badge);
                } else if (info.unreadCount > 0) {
                    const dot = document.createElement("span");
                    dot.className = "group-dot";
                    actionsContainer.appendChild(dot);
                }
            }

            if (isAdmin) {
                const settingsBtn = document.createElement("button");
                settingsBtn.className = "delete-group-btn";
                settingsBtn.title = Obsidianscout.t("chat.channel_settings", "Channel Settings");
                settingsBtn.setAttribute("aria-label", `Settings for #${group}`);
                settingsBtn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="3"></circle><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"></path></svg>`;
                settingsBtn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    openChannelSettingsModal(group);
                });
                actionsContainer.appendChild(settingsBtn);

                if (knownGroups.length > 1) {
                    const delBtn = document.createElement("button");
                    delBtn.className = "delete-group-btn";
                    delBtn.title = Obsidianscout.t("chat.delete_channel", "Delete Channel");
                    delBtn.setAttribute("aria-label", `Delete channel #${group}`);
                    delBtn.innerHTML = `<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg>`;
                    delBtn.addEventListener("click", (e) => {
                        e.stopPropagation();
                        confirmDeleteGroup(group);
                    });
                    actionsContainer.appendChild(delBtn);
                }
            }

            item.appendChild(actionsContainer);

            item.addEventListener("click", () => {
                switchGroup(group);
            });
            groupListContainer.appendChild(item);
        });
    }

    async function confirmDeleteGroup(group) {
        if (knownGroups.length <= 1) {
            alert(Obsidianscout.t("chat.cannot_delete_last", "Cannot delete the only remaining channel. At least one channel must exist."));
            return;
        }
        const confirmMsg = Obsidianscout.t("chat.delete_channel_confirm", "Are you sure you want to delete the channel '#{group}'? All messages in this channel will be permanently deleted.").replace("{group}", group);
        if (!confirm(confirmMsg)) return;

        try {
            await Obsidianscout.request(`/api/chat/groups/${encodeURIComponent(group)}`, {
                method: "DELETE"
            });
            const remainingGroups = knownGroups.filter(g => g !== group);
            if (currentGroup === group) {
                const nextGroup = remainingGroups.length > 0 ? remainingGroups[0] : null;
                if (nextGroup) switchGroup(nextGroup);
            }
            await loadGroups();
            if (channelSettingsModal && !channelSettingsModal.classList.contains("hidden")) {
                closeChannelSettingsModal();
            }
        } catch (e) {
            console.error("Failed to delete channel:", e);
            alert(Obsidianscout.t("chat.error_delete_channel", "Failed to delete channel."));
        }
    }

    async function confirmClearGroup(group) {
        const confirmMsg = Obsidianscout.t("chat.clear_messages_confirm", "Are you sure you want to clear all messages in '#{group}'? This action cannot be undone.").replace("{group}", group);
        if (!confirm(confirmMsg)) return;

        try {
            await Obsidianscout.request(`/api/chat/groups/${encodeURIComponent(group)}/clear`, {
                method: "POST"
            });
            if (currentGroup === group) {
                lastMessagesHash = "";
                await loadMessages();
            }
            if (channelSettingsModal && !channelSettingsModal.classList.contains("hidden")) {
                closeChannelSettingsModal();
            }
            Obsidianscout.showToast(Obsidianscout.t("chat.messages_cleared", "Channel messages cleared."), "success");
        } catch (e) {
            console.error("Failed to clear channel messages:", e);
            alert(Obsidianscout.t("chat.error_clear_channel", "Failed to clear channel messages."));
        }
    }

    // Channel Settings Modal
    const availableRoles = [
        { id: "ADMIN", label: "Admin" },
        { id: "ANALYTICS", label: "Analytics" },
        { id: "SCOUT", label: "Scout" }
    ];

    async function openChannelSettingsModal(group) {
        settingsTargetGroup = group;
        channelSettingsTitle.textContent = `# ${group} - ${Obsidianscout.t("chat.channel_settings", "Channel Settings")}`;

        if (knownGroups.length <= 1) {
            btnModalDeleteChannel.disabled = true;
            btnModalDeleteChannel.style.opacity = "0.5";
            btnModalDeleteChannel.style.cursor = "not-allowed";
            if (channelDeleteHint) channelDeleteHint.style.display = "block";
        } else {
            btnModalDeleteChannel.disabled = false;
            btnModalDeleteChannel.style.opacity = "1";
            btnModalDeleteChannel.style.cursor = "pointer";
            if (channelDeleteHint) channelDeleteHint.style.display = "none";
        }

        channelRolesContainer.innerHTML = `<div style="font-size: 12px; color: var(--muted); font-style: italic;">${Obsidianscout.t("chat.loading", "Loading...")}</div>`;
        channelMembersContainer.innerHTML = `<div style="font-size: 12px; color: var(--muted); font-style: italic;">${Obsidianscout.t("chat.loading", "Loading...")}</div>`;
        channelMemberSearch.value = "";
        channelSettingsModal.classList.remove("hidden");

        try {
            const [details, members] = await Promise.all([
                Obsidianscout.request(`/api/chat/groups/${encodeURIComponent(group)}/details`),
                Obsidianscout.request("/api/chat/team-members")
            ]);
            teamMembersList = members || [];
            selectedGroupRoles = details.allowedRoles ? [...details.allowedRoles] : [];
            selectedGroupUserIds = details.allowedUserIds ? [...details.allowedUserIds] : [];
            renderChannelRoles();
            renderChannelMembers();
        } catch (e) {
            console.error("Failed to load channel details", e);
            channelRolesContainer.innerHTML = `<div style="color: #ef4444; font-size: 12px;">Failed to load channel settings</div>`;
            channelMembersContainer.innerHTML = ``;
        }
    }

    function closeChannelSettingsModal() {
        channelSettingsModal.classList.add("hidden");
    }

    function renderChannelRoles() {
        channelRolesContainer.innerHTML = "";
        availableRoles.forEach(r => {
            const label = document.createElement("label");
            label.style.cssText = "display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--ink); padding: 4px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface-2); cursor: pointer; user-select: none;";
            
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = r.id;
            checkbox.checked = selectedGroupRoles.includes(r.id);
            checkbox.addEventListener("change", () => {
                if (checkbox.checked) {
                    if (!selectedGroupRoles.includes(r.id)) selectedGroupRoles.push(r.id);
                } else {
                    selectedGroupRoles = selectedGroupRoles.filter(x => x !== r.id);
                }
            });

            label.appendChild(checkbox);
            label.appendChild(document.createTextNode(r.label));
            channelRolesContainer.appendChild(label);
        });
    }

    function renderChannelMembers() {
        channelMembersContainer.innerHTML = "";
        const query = channelMemberSearch.value.trim().toLowerCase();
        const filtered = teamMembersList.filter(m => {
            if (!query) return true;
            return m.username.toLowerCase().includes(query) || m.role.toLowerCase().includes(query);
        });

        if (filtered.length === 0) {
            channelMembersContainer.innerHTML = `<div style="font-size: 12px; color: var(--muted); font-style: italic; padding: 4px;">No members match search</div>`;
            return;
        }

        filtered.forEach(m => {
            const label = document.createElement("label");
            label.style.cssText = "display: flex; align-items: center; justify-content: space-between; font-size: 13px; color: var(--ink); padding: 4px 6px; border-radius: 4px; cursor: pointer; user-select: none;";
            
            const left = document.createElement("div");
            left.style.cssText = "display: flex; align-items: center; gap: 8px;";

            const checkbox = document.createElement("input");
            checkbox.type = "checkbox";
            checkbox.value = m.userId;
            checkbox.checked = selectedGroupUserIds.includes(m.userId);
            checkbox.addEventListener("change", () => {
                if (checkbox.checked) {
                    if (!selectedGroupUserIds.includes(m.userId)) selectedGroupUserIds.push(m.userId);
                } else {
                    selectedGroupUserIds = selectedGroupUserIds.filter(x => x !== m.userId);
                }
            });

            const nameSpan = document.createElement("span");
            nameSpan.textContent = m.username;

            left.appendChild(checkbox);
            left.appendChild(nameSpan);

            const roleBadge = document.createElement("span");
            roleBadge.style.cssText = "font-size: 10px; color: var(--muted); background: var(--surface-3); padding: 2px 6px; border-radius: 4px;";
            roleBadge.textContent = m.role;

            label.appendChild(left);
            label.appendChild(roleBadge);
            channelMembersContainer.appendChild(label);
        });
    }

    if (channelMemberSearch) {
        channelMemberSearch.addEventListener("input", renderChannelMembers);
    }
    if (btnCloseChannelSettings) {
        btnCloseChannelSettings.addEventListener("click", closeChannelSettingsModal);
    }
    if (btnCancelChannelSettings) {
        btnCancelChannelSettings.addEventListener("click", closeChannelSettingsModal);
    }
    if (btnModalClearChannel) {
        btnModalClearChannel.addEventListener("click", () => {
            if (settingsTargetGroup) confirmClearGroup(settingsTargetGroup);
        });
    }
    if (btnModalDeleteChannel) {
        btnModalDeleteChannel.addEventListener("click", () => {
            if (settingsTargetGroup) confirmDeleteGroup(settingsTargetGroup);
        });
    }
    if (btnSaveChannelSettings) {
        btnSaveChannelSettings.addEventListener("click", async () => {
            if (!settingsTargetGroup) return;

            const isRestricted = selectedGroupRoles.length > 0 || selectedGroupUserIds.length > 0;
            const hasAdminRole = selectedGroupRoles.includes("ADMIN") || selectedGroupRoles.includes("SUPERADMIN");
            const hasAdminUser = selectedGroupUserIds.some(uid => {
                const member = teamMembersList.find(m => m.userId === uid);
                if (!member) return false;
                const roleUpper = (member.role || "").toUpperCase();
                return roleUpper === "ADMIN" || roleUpper === "SUPERADMIN";
            });

            if (isRestricted && !hasAdminRole && !hasAdminUser) {
                Obsidianscout.showToast(Obsidianscout.t("chat.admin_required", "A channel must include either the Admin role or at least one Admin team member."), "error");
                return;
            }

            Obsidianscout.setButtonLoading(btnSaveChannelSettings, true);
            try {
                await Obsidianscout.request(`/api/chat/groups/${encodeURIComponent(settingsTargetGroup)}/permissions`, {
                    method: "PUT",
                    json: {
                        allowedRoles: selectedGroupRoles,
                        allowedUserIds: selectedGroupUserIds
                    }
                });
                closeChannelSettingsModal();
                Obsidianscout.showToast(Obsidianscout.t("chat.permissions_saved", "Permissions saved successfully."), "success");
                await loadGroups();
            } catch (e) {
                console.error("Failed to save channel permissions", e);
                alert(Obsidianscout.t("chat.error_save_permissions", "Failed to save permissions."));
            } finally {
                Obsidianscout.setButtonLoading(btnSaveChannelSettings, false);
            }
        });
    }
    if (btnChannelSettings) {
        btnChannelSettings.addEventListener("click", () => {
            if (currentGroup) openChannelSettingsModal(currentGroup);
        });
    }

    // Switch groups
    function switchGroup(group) {
        currentGroup = group;
        currentGroupTitle.textContent = `# ${group}`;
        const userRole = (me?.role || "").toUpperCase();
        const isAdmin = userRole === "ADMIN" || userRole === "SUPERADMIN";
        if (btnChannelSettings) {
            btnChannelSettings.style.display = isAdmin ? "inline-flex" : "none";
        }
        renderGroups();
        lastMessagesHash = "";
        
        // Show loading state immediately & clear out old group's chat
        messageContainer.innerHTML = `<div style="text-align: center; color: var(--muted); margin-top: 40px; font-style: italic;">${Obsidianscout.t("chat.loading", "Loading...")}</div>`;
        
        // Load messages immediately & reset polling timer
        loadMessages();
        startPolling();

        // Slide in chat on mobile
        chatActiveContainer.classList.add("show-chat");
    }

    let lastMessagesHash = "";

    // Messages loader
    async function loadMessages() {
        if (!isChatEnabled) return;
        const targetGroup = currentGroup;
        try {
            const messages = await Obsidianscout.request(`/api/chat/messages?group=${encodeURIComponent(targetGroup)}`);
            if (targetGroup === currentGroup) {
                const currentHash = JSON.stringify(messages);
                if (currentHash !== lastMessagesHash) {
                    lastMessagesHash = currentHash;
                    renderMessages(messages);
                    try {
                        await Obsidianscout.request("/api/chat/read", {
                            method: "POST",
                            json: { groupName: targetGroup }
                        });
                        window.dispatchEvent(new CustomEvent("obsidianscout:chat-read", { detail: { groupName: targetGroup } }));
                    } catch (readErr) {
                        console.error("Failed to mark group as read:", readErr);
                    }
                }
            }
        } catch (e) {
            console.error("Failed to load messages", e);
        }
    }

    let editingMessageId = null;

    function renderMessages(messages) {
        // Save scroll height to detect if we should stick scroll to bottom
        const isScrolledToBottom = messageContainer.scrollHeight - messageContainer.clientHeight <= messageContainer.scrollTop + 50;

        // Track open reaction pickers or active editing so we don't destroy them mid-action if rendering happens
        const activePickerMsgId = document.querySelector(".reaction-picker-popover")?.closest(".message-bubble")?.dataset.msgId;
        const activeDropdownMsgId = document.querySelector(".message-actions-dropdown")?.closest(".message-bubble")?.dataset.msgId;
        if (activePickerMsgId || activeDropdownMsgId || editingMessageId) {
            return; // Skip rendering this poll tick to preserve active interactions
        }

        messageContainer.innerHTML = "";

        if (messages.length === 0) {
            const noMsgText = Obsidianscout.t("chat.no_messages", "No messages yet. Say hello!");
            messageContainer.innerHTML = `<div style="text-align: center; color: var(--muted); margin-top: 40px; font-style: italic;">${noMsgText}</div>`;
            return;
        }

        const isAdmin = me.role === "ADMIN" || me.role === "SUPERADMIN";

        messages.forEach(msg => {
            const isMe = msg.userId === me.userId || (msg.username && msg.username.toLowerCase() === me.username.toLowerCase());
            const initials = (msg.username || "?").slice(0, 2).toUpperCase();
            
            // Get avatar color
            let hue = 0;
            for (let i = 0; i < (msg.username || "").length; i++) {
                hue = (hue + msg.username.charCodeAt(i) * 37) % 360;
            }

            const row = document.createElement("div");
            row.className = `message-row ${isMe ? "me" : ""}`;

            // Avatar
            const avatar = document.createElement("div");
            avatar.className = "avatar-placeholder";
            if (msg.profilePicture) {
                avatar.style.backgroundImage = `url(${msg.profilePicture})`;
                avatar.style.backgroundSize = "cover";
                avatar.style.backgroundPosition = "center";
                avatar.textContent = "";
            } else {
                avatar.textContent = initials;
                avatar.style.background = `hsl(${hue}, 60%, 45%)`;
                avatar.style.backgroundImage = "none";
            }
            row.appendChild(avatar);

            // Message Bubble
            const bubble = document.createElement("div");
            bubble.className = "message-bubble";
            bubble.dataset.msgId = msg.id;

            const meta = document.createElement("div");
            meta.className = "message-meta";

            const sender = document.createElement("span");
            sender.className = "message-sender";
            sender.textContent = msg.username;
            meta.appendChild(sender);

            const time = document.createElement("span");
            time.className = "message-time";
            // format timestamp to readable time
            try {
                const date = new Date(msg.createdAt);
                time.textContent = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            } catch(err) {
                time.textContent = msg.createdAt;
            }
            meta.appendChild(time);

            if (msg.isEdited) {
                const editedLabel = document.createElement("span");
                editedLabel.className = "message-edited";
                editedLabel.textContent = Obsidianscout.t("chat.edited", "(edited)");
                if (msg.updatedAt) {
                    try {
                        const updatedDate = new Date(msg.updatedAt);
                        editedLabel.title = updatedDate.toLocaleString();
                    } catch (_) {}
                }
                meta.appendChild(editedLabel);
            }

            // Message actions button (for author or admin)
            if (isMe || isAdmin) {
                const actionsWrapper = document.createElement("div");
                actionsWrapper.className = "message-actions-wrapper";

                const actionsBtn = document.createElement("button");
                actionsBtn.className = "message-actions-btn";
                actionsBtn.innerHTML = "•••";
                actionsBtn.title = Obsidianscout.t("chat.actions", "Message Actions");
                actionsBtn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    toggleMessageActionsDropdown(actionsWrapper, msg, isMe, isAdmin);
                });

                actionsWrapper.appendChild(actionsBtn);
                meta.appendChild(actionsWrapper);
            }

            bubble.appendChild(meta);

            const text = document.createElement("div");
            text.className = "message-text";
            text.textContent = msg.content;
            bubble.appendChild(text);

            // Reactions
            const reactionSection = document.createElement("div");
            reactionSection.className = "reaction-bar";

            // Loop through existing reactions
            Object.entries(msg.reactions).forEach(([emoji, users]) => {
                if (users.length === 0) return;
                const pill = document.createElement("span");
                const hasReacted = users.includes(me.username);
                pill.className = `reaction-pill ${hasReacted ? "active" : ""} ${isMe ? "readonly" : ""}`;
                pill.innerHTML = `<span>${emoji}</span><span>${users.length}</span>`;
                pill.title = users.join(", ");
                if (!isMe) {
                    pill.addEventListener("click", () => {
                        toggleReaction(msg.id, emoji);
                    });
                }
                reactionSection.appendChild(pill);
            });

            // Add reaction button - only show if it is not my message
            if (!isMe) {
                const addReactBtn = document.createElement("button");
                addReactBtn.className = "add-reaction-btn";
                addReactBtn.textContent = "+";
                addReactBtn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    showReactionPicker(addReactBtn, msg.id);
                });
                reactionSection.appendChild(addReactBtn);
            }

            bubble.appendChild(reactionSection);
            row.appendChild(bubble);

            messageContainer.appendChild(row);
        });

        // Auto-scroll to bottom if scrolled to bottom or if active picker wasn't open
        if (isScrolledToBottom && !activePickerMsgId && !editingMessageId) {
            messageContainer.scrollTop = messageContainer.scrollHeight;
        }
    }

    function toggleMessageActionsDropdown(wrapper, msg, isMe, isAdmin) {
        // Remove any open dropdowns
        document.querySelectorAll(".message-actions-dropdown").forEach(d => d.remove());

        const dropdown = document.createElement("div");
        dropdown.className = "message-actions-dropdown";

        if (isMe) {
            const editBtn = document.createElement("button");
            editBtn.className = "message-action-item";
            editBtn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0;"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg> <span>${Obsidianscout.t("chat.edit", "Edit")}</span>`;
            editBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                dropdown.remove();
                startEditMessage(msg.id, msg.content, wrapper.closest(".message-bubble"));
            });
            dropdown.appendChild(editBtn);
        }

        if (isMe || isAdmin) {
            const delBtn = document.createElement("button");
            delBtn.className = "message-action-item danger";
            delBtn.innerHTML = `<svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" style="flex-shrink: 0;"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path><line x1="10" y1="11" x2="10" y2="17"></line><line x1="14" y1="11" x2="14" y2="17"></line></svg> <span>${Obsidianscout.t("chat.delete", "Delete")}</span>`;
            delBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                dropdown.remove();
                confirmDeleteMessage(msg.id);
            });
            dropdown.appendChild(delBtn);
        }

        wrapper.appendChild(dropdown);

        const closeDropdownHandler = (evt) => {
            if (!wrapper.contains(evt.target)) {
                dropdown.remove();
                document.removeEventListener("click", closeDropdownHandler);
            }
        };
        setTimeout(() => {
            document.addEventListener("click", closeDropdownHandler);
        }, 10);
    }

    function startEditMessage(msgId, currentContent, bubble) {
        if (!bubble) return;
        editingMessageId = msgId;
        const textEl = bubble.querySelector(".message-text");
        if (!textEl) return;
        textEl.style.display = "none";

        // Remove any previous edit container inside this bubble
        bubble.querySelector(".message-edit-container")?.remove();

        const editContainer = document.createElement("div");
        editContainer.className = "message-edit-container";

        const textarea = document.createElement("textarea");
        textarea.className = "message-edit-textarea";
        textarea.value = currentContent;

        const actionsRow = document.createElement("div");
        actionsRow.className = "message-edit-actions";

        const hint = document.createElement("span");
        hint.className = "message-edit-hint";
        hint.textContent = "Enter to save • Esc to cancel";

        const cancelBtn = document.createElement("button");
        cancelBtn.className = "btn ghost";
        cancelBtn.textContent = Obsidianscout.t("chat.cancel", "Cancel");
        cancelBtn.addEventListener("click", () => {
            editingMessageId = null;
            editContainer.remove();
            textEl.style.display = "";
        });

        const saveBtn = document.createElement("button");
        saveBtn.className = "btn primary";
        saveBtn.textContent = Obsidianscout.t("chat.save", "Save");

        const submitEdit = async () => {
            const newText = textarea.value.trim();
            if (!newText) return;
            if (newText === currentContent) {
                editingMessageId = null;
                editContainer.remove();
                textEl.style.display = "";
                return;
            }
            Obsidianscout.setButtonLoading(saveBtn, true);
            try {
                await Obsidianscout.request(`/api/chat/messages/${msgId}`, {
                    method: "PUT",
                    json: { content: newText }
                });
                editingMessageId = null;
                lastMessagesHash = "";
                loadMessages();
            } catch (err) {
                console.error("Failed to edit message", err);
                Obsidianscout.showToast(Obsidianscout.t("chat.error_edit", "Failed to edit message"), "error");
                Obsidianscout.setButtonLoading(saveBtn, false);
            }
        };

        saveBtn.addEventListener("click", submitEdit);

        textarea.addEventListener("keydown", (e) => {
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                submitEdit();
            } else if (e.key === "Escape") {
                e.preventDefault();
                editingMessageId = null;
                editContainer.remove();
                textEl.style.display = "";
            }
        });

        actionsRow.appendChild(hint);
        actionsRow.appendChild(cancelBtn);
        actionsRow.appendChild(saveBtn);

        editContainer.appendChild(textarea);
        editContainer.appendChild(actionsRow);

        textEl.after(editContainer);
        textarea.focus();
        textarea.setSelectionRange(textarea.value.length, textarea.value.length);
    }

    async function confirmDeleteMessage(msgId) {
        const confirmMsg = Obsidianscout.t("chat.delete_confirm", "Are you sure you want to delete this message? This cannot be undone.");
        if (!confirm(confirmMsg)) {
            return;
        }
        try {
            await Obsidianscout.request(`/api/chat/messages/${msgId}`, {
                method: "DELETE"
            });
            lastMessagesHash = "";
            loadMessages();
        } catch (err) {
            console.error("Failed to delete message", err);
            Obsidianscout.showToast(Obsidianscout.t("chat.error_delete", "Failed to delete message"), "error");
        }
    }

    // Reaction Picker popover
    function showReactionPicker(button, msgId) {
        // Remove existing pickers
        document.querySelectorAll(".reaction-picker-popover").forEach(el => el.remove());

        const picker = document.createElement("div");
        picker.className = "reaction-picker-popover";

        emojis.forEach(emoji => {
            const emojiEl = document.createElement("span");
            emojiEl.className = "picker-emoji";
            emojiEl.textContent = emoji;
            emojiEl.addEventListener("click", () => {
                toggleReaction(msgId, emoji);
                picker.remove();
            });
            picker.appendChild(emojiEl);
        });

        button.parentElement.appendChild(picker);

        // Click outside closes picker
        const clickOutsideHandler = () => {
            picker.remove();
            document.removeEventListener("click", clickOutsideHandler);
        };
        setTimeout(() => {
            document.addEventListener("click", clickOutsideHandler);
        }, 10);
    }

    // Toggle reaction endpoint call
    async function toggleReaction(msgId, emoji) {
        try {
            await Obsidianscout.request(`/api/chat/messages/${msgId}/react`, {
                method: "POST",
                json: { emoji }
            });
            loadMessages();
        } catch (e) {
            console.error("Failed to toggle reaction", e);
        }
    }

    // Autocomplete Mentions Logic
    async function loadMentionOptions() {
        try {
            const teamUsers = await Obsidianscout.request("/api/chat/team-users");
            const filteredUsers = teamUsers.filter(u => u.toLowerCase() !== "deleted user");
            mentionOptions = ["everyone", "channel", ...filteredUsers];
        } catch (e) {
            console.error("Failed to load team users for mentions:", e);
            mentionOptions = ["everyone", "channel"];
        }
    }

    function handleChatInput() {
        const cursor = chatMessageInput.selectionStart;
        const value = chatMessageInput.value;
        const textUpToCursor = value.slice(0, cursor);

        // Find the nearest preceding '@' that is at start of line or preceded by a space/newline
        let atIndex = -1;
        for (let i = cursor - 1; i >= 0; i--) {
            if (textUpToCursor[i] === '@') {
                if (i === 0 || textUpToCursor[i - 1] === ' ' || textUpToCursor[i - 1] === '\n') {
                    atIndex = i;
                    break;
                }
            }
        }

        if (atIndex !== -1) {
            const query = textUpToCursor.slice(atIndex + 1);
            if (!query.includes('\n')) {
                const filtered = mentionOptions.filter(opt => 
                    opt.toLowerCase().includes(query.toLowerCase())
                );

                if (filtered.length > 0) {
                    mentionTriggerIndex = atIndex;
                    showMentionDropdown(filtered, query);
                    return;
                }
            }
        }

        closeMentionDropdown();
    }

    function showMentionDropdown(filtered, query) {
        if (!mentionDropdown) {
            mentionDropdown = document.createElement("div");
            mentionDropdown.className = "mention-dropdown";
            const inputArea = document.querySelector(".chat-input-area");
            inputArea.appendChild(mentionDropdown);
        }

        mentionDropdown.innerHTML = "";

        if (activeMentionIndex >= filtered.length) {
            activeMentionIndex = 0;
        }

        filtered.forEach((opt, idx) => {
            const item = document.createElement("div");
            const isActive = idx === activeMentionIndex;
            item.className = `mention-item ${isActive ? "active" : ""} ${["everyone", "channel"].includes(opt) ? "special-mention" : ""}`;
            item.textContent = `@${opt}`;
            
            item.addEventListener("click", () => {
                insertMention(opt);
            });

            mentionDropdown.appendChild(item);
        });

        const activeItem = mentionDropdown.children[activeMentionIndex];
        if (activeItem) {
            activeItem.scrollIntoView({ block: "nearest" });
        }
    }

    function closeMentionDropdown() {
        if (mentionDropdown) {
            mentionDropdown.remove();
            mentionDropdown = null;
        }
        activeMentionIndex = 0;
        mentionTriggerIndex = -1;
    }

    function insertMention(opt) {
        if (mentionTriggerIndex === -1) return;

        const value = chatMessageInput.value;
        const cursor = chatMessageInput.selectionStart;

        const before = value.slice(0, mentionTriggerIndex);
        const after = value.slice(cursor);
        
        const mentionText = `@${opt} `;
        chatMessageInput.value = before + mentionText + after;

        const newCursorPos = mentionTriggerIndex + mentionText.length;
        chatMessageInput.setSelectionRange(newCursorPos, newCursorPos);
        chatMessageInput.focus();

        closeMentionDropdown();
    }

    function handleChatKeyDown(e) {
        if (mentionDropdown) {
            const items = mentionDropdown.querySelectorAll(".mention-item");
            if (items.length > 0) {
                if (e.key === "ArrowDown") {
                    e.preventDefault();
                    activeMentionIndex = (activeMentionIndex + 1) % items.length;
                    handleChatInput();
                } else if (e.key === "ArrowUp") {
                    e.preventDefault();
                    activeMentionIndex = (activeMentionIndex - 1 + items.length) % items.length;
                    handleChatInput();
                } else if (e.key === "Enter" || e.key === "Tab") {
                    e.preventDefault();
                    const query = chatMessageInput.value.slice(mentionTriggerIndex + 1, chatMessageInput.selectionStart);
                    const filtered = mentionOptions.filter(opt => 
                        opt.toLowerCase().includes(query.toLowerCase())
                    );
                    if (filtered[activeMentionIndex]) {
                        insertMention(filtered[activeMentionIndex]);
                    }
                } else if (e.key === "Escape") {
                    e.preventDefault();
                    closeMentionDropdown();
                }
            }
        } else {
            if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                sendMessage();
            }
        }
    }

    // Send Message
    async function sendMessage() {
        const text = chatMessageInput.value.trim();
        if (!text) return;

        Obsidianscout.setButtonLoading(btnSendMessage, true);
        chatMessageInput.value = "";
        try {
            await Obsidianscout.request("/api/chat/messages", {
                method: "POST",
                json: {
                    groupName: currentGroup,
                    content: text
                }
            });
            loadMessages();
        } catch (e) {
            console.error("Failed to send message", e);
            Obsidianscout.showToast(Obsidianscout.t("chat.error_send", "Failed to send message"), "error");
        } finally {
            Obsidianscout.setButtonLoading(btnSendMessage, false);
        }
    }

    btnSendMessage.addEventListener("click", sendMessage);
    chatMessageInput.addEventListener("keydown", handleChatKeyDown);
    chatMessageInput.addEventListener("input", handleChatInput);

    if (btnBackChannels) {
        btnBackChannels.addEventListener("click", () => {
            chatActiveContainer.classList.remove("show-chat");
        });
    }

    document.addEventListener("click", (e) => {
        if (mentionDropdown && !e.target.closest(".chat-input-area")) {
            closeMentionDropdown();
        }
    });

    // Create custom group
    btnCreateGroup.addEventListener("click", async () => {
        const promptText = Obsidianscout.t("chat.create_group_prompt", "Enter new group name (e.g. strategy, scouting):");
        const name = prompt(promptText);
        if (!name) return;
        const sanitized = name.toLowerCase().replace(/[^a-z0-9_-]/g, "").trim();
        if (!sanitized) {
            alert(Obsidianscout.t("chat.create_group_invalid", "Invalid group name!"));
            return;
        }
        try {
            await Obsidianscout.request("/api/chat/groups", {
                method: "POST",
                json: { groupName: sanitized }
            });
        } catch (e) {
            console.error("Failed to save group to server", e);
        }
        if (!knownGroups.includes(sanitized)) {
            knownGroups.push(sanitized);
        }
        switchGroup(sanitized);
    });

    // Polling helpers
    function startPolling() {
        stopPolling();
        pollInterval = setInterval(() => {
            loadMessages();
            loadGroupUnreads();
        }, 2000);
    }

    function stopPolling() {
        if (pollInterval) {
            clearInterval(pollInterval);
            pollInterval = null;
        }
    }

    // Initialize
    const isEnabled = await checkChatSettings();
    if (isEnabled) {
        await Promise.all([
            loadGroups(),
            loadMentionOptions()
        ]);
        
        // After loadGroups(), currentGroup is already set to the first accessible channel.
        // Honour a URL ?group= param only if the server actually returned that group for this user.
        const urlParamsInit = new URLSearchParams(window.location.search);
        const requestedGroup = urlParamsInit.get('group');
        if (requestedGroup && knownGroups.includes(requestedGroup)) {
            currentGroup = requestedGroup;
        } else if (!knownGroups.includes(currentGroup)) {
            currentGroup = knownGroups[0] || "";
        }

        if (currentGroup) {
            switchGroup(currentGroup);
        }

        if ('serviceWorker' in navigator) {
            navigator.serviceWorker.addEventListener('message', (event) => {
                if (event.data && event.data.type === 'SWITCH_GROUP') {
                    if (knownGroups.includes(event.data.groupName)) {
                        switchGroup(event.data.groupName);
                    }
                }
            });
        }
    }
});
