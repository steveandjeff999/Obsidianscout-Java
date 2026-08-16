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

    let currentGroup = "general";
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
    let knownGroups = ["general"];
    let isChatEnabled = true;

    // DOM Elements
    const chatDisabledContainer = document.getElementById("chat-disabled-container");
    const chatActiveContainer = document.getElementById("chat-active-container");
    const groupListContainer = document.getElementById("group-list-container");
    const btnCreateGroup = document.getElementById("btn-create-group");
    const currentGroupTitle = document.getElementById("current-group-title");
    const messageContainer = document.getElementById("message-container");
    const chatMessageInput = document.getElementById("chat-message-input");
    const btnSendMessage = document.getElementById("btn-send-message");
    const btnBackChannels = document.getElementById("btn-back-channels");

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

    // List of groups
    async function loadGroups() {
        try {
            const groups = await Obsidianscout.request("/api/chat/groups");
            const combined = Array.from(new Set(["general", ...groups]));
            knownGroups = combined;
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
        knownGroups.forEach(group => {
            const item = document.createElement("div");
            item.className = `group-item ${group === currentGroup ? "active" : ""}`;
            
            const labelSpan = document.createElement("span");
            labelSpan.textContent = `# ${group}`;
            item.appendChild(labelSpan);

            if (group !== currentGroup && groupUnreads[group]) {
                const info = groupUnreads[group];
                if (info.mentionCount > 0) {
                    const badge = document.createElement("span");
                    badge.className = "group-badge";
                    badge.textContent = info.mentionCount;
                    item.appendChild(badge);
                } else if (info.unreadCount > 0) {
                    const dot = document.createElement("span");
                    dot.className = "group-dot";
                    item.appendChild(dot);
                }
            }

            item.addEventListener("click", () => {
                switchGroup(group);
            });
            groupListContainer.appendChild(item);
        });
    }

    // Switch groups
    function switchGroup(group) {
        currentGroup = group;
        currentGroupTitle.textContent = `# ${group}`;
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
            editBtn.innerHTML = `✏️ <span>${Obsidianscout.t("chat.edit", "Edit")}</span>`;
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
            delBtn.innerHTML = `🗑️ <span>${Obsidianscout.t("chat.delete", "Delete")}</span>`;
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
        
        const urlParams = new URLSearchParams(window.location.search);
        const initialGroup = urlParams.get('group') || "general";
        const groupToSwitch = knownGroups.includes(initialGroup) ? initialGroup : "general";
        switchGroup(groupToSwitch);

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
