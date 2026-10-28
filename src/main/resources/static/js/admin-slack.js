/**
 * Admin Settings > Integrations > Slack.
 * Connect a workspace, pick the channels and mentions that raise banners, choose who sees them, and fix up how
 * Slack people map to team members. Mounted by admin-settings.js each time the settings panel is (re)built.
 */
(function () {
    "use strict";

    const ROLES = ["ADMIN", "ANALYTICS", "CUSTOM", "SCOUT", "SUPERADMIN"];
    /** Checkbox labels: undo the uppercase field-label style. */
    const INLINE_LABEL = "display:flex;align-items:center;gap:8px;text-transform:none;letter-spacing:normal;font-size:14px;font-weight:400;color:var(--ink);";

    function t(key, fallback) {
        return (window.Obsidianscout && typeof Obsidianscout.t === "function") ? Obsidianscout.t(key, fallback) : fallback;
    }

    function icon(name, size) {
        return (window.Obsidianscout && typeof Obsidianscout.faIcon === "function") ? Obsidianscout.faIcon(name, size || 16) : "";
    }

    function toast(message, tone) {
        if (window.Obsidianscout && typeof Obsidianscout.showToast === "function") Obsidianscout.showToast(message, tone || "info");
    }

    /** Small element builder. Text goes in with textContent, never as HTML. */
    function h(tag, attrs, ...children) {
        const el = document.createElement(tag);
        Object.entries(attrs || {}).forEach(([k, v]) => {
            if (v === undefined || v === null || v === false) return;
            if (k === "class") el.className = v;
            else if (k === "style") el.style.cssText = v;
            else if (k === "html") el.innerHTML = v;
            else if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
            else if (k === "checked" || k === "disabled" || k === "value") el[k] = v;
            else el.setAttribute(k, v === true ? "" : v);
        });
        children.flat().forEach((c) => {
            if (c === null || c === undefined || c === false) return;
            el.appendChild(typeof c === "string" ? document.createTextNode(c) : c);
        });
        return el;
    }

    function hint(text, extraStyle) {
        return h("div", { style: "font-size: 12px; color: var(--muted);" + (extraStyle || "") }, text);
    }

    function api(path, options) {
        return Obsidianscout.request(path, Object.assign({ noCache: true }, options || {}));
    }

    function errorText(e, fallback) {
        return (e && (e.message || e.error)) || fallback;
    }

    function defaultRule(channel) {
        return {
            channelId: channel.id,
            channelName: channel.name,
            isPrivate: !!channel.isPrivate,
            trigger: "MENTIONS",
            mentionChannel: true,
            userGroupIds: [],
            mentionLinkedUsers: true,
            keywords: [],
            includeThreadReplies: false,
            audience: { everyone: true, roles: [], userIds: [] },
            push: false
        };
    }

    let state = null;
    let root = null;

    async function mount(el) {
        if (!el) return;
        root = el;
        root.replaceChildren(hint(t("slack.loading", "Loading Slack settings...")));
        state = {
            integration: null,
            channels: null,
            channelsError: "",
            groups: [],
            groupsMessage: "",
            links: null,
            rules: new Map(),
            userLinks: {},
            filter: ""
        };
        try {
            state.integration = await api("/api/slack/integration");
        } catch (e) {
            root.replaceChildren(hint(errorText(e, t("slack.load_failed", "Could not load Slack settings."))));
            return;
        }
        state.integration.rules.forEach((r) => state.rules.set(r.channelId, r));
        state.userLinks = Object.assign({}, state.integration.userLinks);
        if (state.integration.configured) {
            await Promise.all([loadChannels(false), loadGroups(), loadLinks()]);
        }
        render();
    }

    async function loadChannels(refresh) {
        try {
            state.channels = await api("/api/slack/channels" + (refresh ? "?refresh=true" : ""));
            state.channelsError = "";
            // Keep cached names fresh for watched channels.
            state.channels.forEach((c) => {
                const rule = state.rules.get(c.id);
                if (rule) state.rules.set(c.id, Object.assign({}, rule, { channelName: c.name, isPrivate: c.isPrivate }));
            });
        } catch (e) {
            state.channels = [];
            state.channelsError = errorText(e, t("slack.channels_failed", "Could not load channels from Slack."));
        }
    }

    async function loadGroups() {
        try {
            const res = await api("/api/slack/usergroups");
            state.groups = res.groups || [];
            state.groupsMessage = res.message || "";
        } catch (e) {
            state.groups = [];
            state.groupsMessage = errorText(e, "");
        }
    }

    async function loadLinks() {
        try {
            state.links = await api("/api/slack/user-links");
        } catch (e) {
            state.links = { users: [], slackUsers: [] };
        }
    }

    // ---------- rendering ----------

    function statusPill() {
        const i = state.integration;
        let label = t("slack.status_not_connected", "Not connected");
        let color = "var(--muted)";
        if (i.configured && !i.enabled) {
            label = t("slack.status_off", "Off");
        } else if (i.configured) {
            const map = {
                connected: [t("slack.status_connected", "Connected"), "var(--success)"],
                connecting: [t("slack.status_connecting", "Connecting..."), "var(--warning)"],
                reconnecting: [t("slack.status_reconnecting", "Reconnecting"), "var(--warning)"],
                error: [t("slack.status_error", "Error"), "var(--danger)"]
            };
            [label, color] = map[i.status] || [t("slack.status_connecting", "Connecting..."), "var(--warning)"];
        }
        return h("span", {
            style: `display:inline-flex;align-items:center;gap:6px;font-size:12px;font-weight:600;padding:3px 10px;border-radius:9999px;border:1px solid var(--border);color:${color};`
        }, h("span", { style: `width:8px;height:8px;border-radius:50%;background:${color};display:inline-block;` }), label);
    }

    function render() {
        const i = state.integration;
        const header = h("div", { style: "display:flex;align-items:center;gap:10px;flex-wrap:wrap;" },
            h("span", { html: icon("slack", 22), style: "display:inline-flex;" }),
            h("h3", { style: "margin:0;" }, "Slack"),
            statusPill(),
            i.workspaceName ? h("span", { style: "color:var(--muted);font-size:13px;" }, i.workspaceName) : null
        );
        const parts = [header];
        if (i.configured && i.status === "error" && i.statusDetail) {
            parts.push(h("div", { class: "notice", style: "margin-top:12px;color:var(--danger);display:flex;gap:8px;align-items:flex-start;" },
                h("span", { html: icon("triangle-exclamation", 14), style: "margin-top:2px;" }), i.statusDetail));
        } else if (i.configured && i.enabled && i.statusDetail) {
            parts.push(hint(i.statusDetail, "margin-top:8px;"));
        }
        if (i.lastEventAt) {
            parts.push(hint(t("slack.last_message", "Last message received: {t}").replace("{t}", new Date(i.lastEventAt).toLocaleString()), "margin-top:4px;"));
        }
        if (!i.configured) parts.push(renderSetupSteps());
        parts.push(renderConnection());
        if (i.configured) {
            parts.push(renderChannels());
            parts.push(renderLinks());
        }
        parts.push(renderActions());
        root.replaceChildren(...parts);
    }

    function renderSetupSteps() {
        const i = state.integration;
        return h("div", { style: "margin-top:16px;display:grid;gap:10px;" },
            h("p", { class: "notice", style: "margin:0;" }, t("slack.setup_intro",
                "Your team creates its own small Slack app, so nothing outside your server sees your messages. It takes about two minutes:")),
            h("ol", { style: "margin:0;padding-left:20px;display:grid;gap:6px;font-size:14px;" },
                h("li", {}, t("slack.setup_step1", "Create the app in Slack from our template: "),
                    h("a", { href: i.manifestUrl, target: "_blank", rel: "noopener noreferrer", class: "btn secondary",
                        style: "display:inline-flex;align-items:center;gap:6px;padding:4px 12px;font-size:13px;text-decoration:none;margin-left:4px;" },
                        h("span", { html: icon("slack", 14), style: "display:inline-flex;" }), t("slack.create_app", "Create Slack app"))),
                h("li", {}, t("slack.setup_step2", "Pick your workspace and click Create, then Install App > Install to Workspace. Copy the Bot User OAuth Token (starts with xoxb-).")),
                h("li", {}, t("slack.setup_step3", "Go to Basic Information > App-Level Tokens > Generate Token, add the connections:write scope, and copy the token (starts with xapp-).")),
                h("li", {}, t("slack.setup_step4", "Paste both tokens below and click Save. Then pick the channels to watch."))
            )
        );
    }

    function tokenField(id, label, isSet, placeholder) {
        return h("div", { class: "field" },
            h("label", { for: id }, label),
            h("input", {
                id, type: "password", autocomplete: "off", spellcheck: "false",
                placeholder: isSet ? t("slack.token_saved", "Saved. Paste a new token to replace it.") : placeholder
            })
        );
    }

    function renderConnection() {
        const i = state.integration;
        const wrap = h("div", { style: "margin-top:16px;display:grid;gap:12px;" },
            h("div", { class: "form-grid" },
                tokenField("slack-bot-token", t("slack.bot_token", "Bot token"), i.botTokenSet, "xoxb-..."),
                tokenField("slack-app-token", t("slack.app_token", "App-level token"), i.appTokenSet, "xapp-...")
            )
        );
        if (i.configured) {
            wrap.appendChild(h("label", { style: INLINE_LABEL },
                h("input", { id: "slack-enabled", type: "checkbox", checked: !!i.enabled }), " ",
                t("slack.enabled", "Show Slack messages as banners")));
        }
        return wrap;
    }

    function renderChannels() {
        const card = h("div", { style: "margin-top:20px;border-top:1px solid var(--border);padding-top:16px;display:grid;gap:10px;" });
        const search = h("input", {
            type: "search", placeholder: t("slack.search_channels", "Search channels"), value: state.filter,
            style: "flex:1;min-width:160px;",
            oninput: (e) => { state.filter = e.target.value; renderChannelList(list); }
        });
        const refresh = h("button", {
            type: "button", class: "btn ghost", style: "padding:6px 12px;font-size:13px;",
            onclick: async (e) => {
                e.target.disabled = true;
                await loadChannels(true);
                e.target.disabled = false;
                renderChannelList(list);
            }
        }, t("slack.refresh", "Refresh"));
        card.append(
            h("h4", { style: "margin:0;" }, t("slack.channels", "Channels")),
            hint(t("slack.channels_hint", "Tick a channel to watch it, then choose which messages notify and who sees them.")),
            h("div", { style: "display:flex;gap:8px;flex-wrap:wrap;" }, search, refresh)
        );
        if (state.channelsError) card.appendChild(hint(state.channelsError, "color:var(--danger);"));
        const list = h("div", { style: "display:grid;gap:8px;" });
        card.appendChild(list);
        renderChannelList(list);
        return card;
    }

    function renderChannelList(list) {
        const known = new Map((state.channels || []).map((c) => [c.id, c]));
        // Watched channels Slack no longer lists (archived, or the app lost access) still show so they can be removed.
        state.rules.forEach((r, id) => {
            if (!known.has(id)) known.set(id, { id, name: r.channelName || id, isPrivate: r.isPrivate, isMember: false, memberCount: 0, missing: true });
        });
        const q = state.filter.trim().toLowerCase().replace(/^#/, "");
        const rows = Array.from(known.values())
            .filter((c) => !q || c.name.toLowerCase().includes(q))
            .sort((a, b) => (state.rules.has(b.id) - state.rules.has(a.id)) || a.name.localeCompare(b.name))
            .slice(0, 150);
        list.replaceChildren(...rows.map(renderChannelRow));
        if (!rows.length) list.appendChild(hint(t("slack.no_channels", "No channels found.")));
    }

    function renderChannelRow(c) {
        const rule = state.rules.get(c.id);
        const row = h("div", { style: "border:1px solid var(--border);border-radius:10px;padding:10px 12px;display:grid;gap:10px;" });
        const toggle = h("input", {
            type: "checkbox", checked: !!rule,
            onchange: (e) => {
                if (e.target.checked) state.rules.set(c.id, defaultRule(c));
                else state.rules.delete(c.id);
                row.replaceWith(renderChannelRow(c));
            }
        });
        const meta = [];
        if (c.isPrivate) meta.push(h("span", { html: icon("lock", 11), title: t("slack.private", "Private channel"), style: "display:inline-flex;color:var(--muted);" }));
        if (c.memberCount) meta.push(h("span", { style: "color:var(--muted);font-size:12px;" }, t("slack.members", "{n} members").replace("{n}", c.memberCount)));
        const headRow = h("div", { style: "display:flex;align-items:center;gap:8px;flex-wrap:wrap;" },
            h("label", { style: INLINE_LABEL + "font-weight:600;flex:1;min-width:0;" }, toggle, "#" + c.name),
            ...meta
        );
        if (c.missing) {
            headRow.appendChild(h("span", { style: "font-size:12px;color:var(--danger);" }, t("slack.channel_missing", "Not found in Slack")));
        } else if (!c.isMember && c.isPrivate) {
            headRow.appendChild(h("span", { style: "font-size:12px;color:var(--warning);" },
                t("slack.invite_hint", "In Slack, type /invite @Obsidianscout in this channel")));
        } else if (!c.isMember) {
            headRow.appendChild(h("button", {
                type: "button", class: "btn secondary", style: "padding:4px 10px;font-size:12px;",
                onclick: async (e) => {
                    try {
                        await api(`/api/slack/channels/${encodeURIComponent(c.id)}/join`, { method: "POST", button: e.target });
                        c.isMember = true;
                        row.replaceWith(renderChannelRow(c));
                        toast(t("slack.joined", "Joined #{name}").replace("{name}", c.name), "success");
                    } catch (err) {
                        toast(errorText(err, t("slack.join_failed", "Could not join the channel.")), "error");
                    }
                }
            }, t("slack.join", "Add app to channel")));
        }
        row.appendChild(headRow);
        if (rule && !c.isMember && !c.missing) {
            row.appendChild(hint(t("slack.not_member_warning", "The app isn't in this channel yet, so it can't see its messages.")));
        }
        if (rule) row.appendChild(renderRuleEditor(rule, c));
        return row;
    }

    function update(rule, patch) {
        Object.assign(rule, patch);
        state.rules.set(rule.channelId, rule);
    }

    function checkbox(label, checked, onchange, extra) {
        return h("label", { style: INLINE_LABEL + (extra || "") },
            h("input", { type: "checkbox", checked, onchange: (e) => onchange(e.target.checked) }), label);
    }

    function renderRuleEditor(rule, channel) {
        const box = h("div", { style: "display:grid;gap:8px;padding-left:26px;" });

        const trigger = h("select", {
            onchange: (e) => { update(rule, { trigger: e.target.value }); box.replaceWith(renderRuleEditor(rule, channel)); }
        },
            h("option", { value: "MENTIONS" }, t("slack.trigger_mentions", "Only mentions and keywords")),
            h("option", { value: "ALL_MESSAGES" }, t("slack.trigger_all", "Every message"))
        );
        trigger.value = rule.trigger;
        box.appendChild(h("div", { style: "display:flex;align-items:center;gap:8px;flex-wrap:wrap;font-size:14px;" },
            t("slack.notify_on", "Notify on"), trigger));

        if (rule.trigger === "MENTIONS") {
            box.appendChild(checkbox(t("slack.mention_channel", "@channel, @here and @everyone"), !!rule.mentionChannel, (v) => update(rule, { mentionChannel: v })));
            box.appendChild(renderGroups(rule));
            box.appendChild(h("label", { style: "display:grid;gap:4px;font-size:14px;" },
                t("slack.keywords", "Keywords (comma separated)"),
                h("input", {
                    type: "text", value: (rule.keywords || []).join(", "), placeholder: t("slack.keywords_placeholder", "queue, drive team"),
                    onchange: (e) => update(rule, { keywords: e.target.value.split(",").map((s) => s.trim()).filter(Boolean) })
                })));
        }
        box.appendChild(checkbox(t("slack.mention_linked", "A direct @mention notifies that person"), !!rule.mentionLinkedUsers, (v) => update(rule, { mentionLinkedUsers: v })));
        box.appendChild(checkbox(t("slack.thread_replies", "Include thread replies"), !!rule.includeThreadReplies, (v) => update(rule, { includeThreadReplies: v })));
        box.appendChild(renderAudience(rule, channel));
        box.appendChild(checkbox(t("slack.push", "Also send a push notification to phones and browsers"), !!rule.push, (v) => update(rule, { push: v })));
        return box;
    }

    function renderGroups(rule) {
        const wrap = h("div", { style: "display:grid;gap:4px;font-size:14px;" }, t("slack.user_groups", "User groups"));
        if (!state.groups.length) {
            wrap.appendChild(hint(state.groupsMessage || t("slack.no_groups", "This workspace has no user groups.")));
            return wrap;
        }
        const list = h("div", { style: "display:flex;flex-wrap:wrap;gap:4px 14px;" });
        state.groups.forEach((g) => {
            const selected = (rule.userGroupIds || []).includes(g.id);
            list.appendChild(checkbox("@" + g.handle, selected, (v) => {
                const ids = new Set(rule.userGroupIds || []);
                if (v) ids.add(g.id); else ids.delete(g.id);
                update(rule, { userGroupIds: Array.from(ids) });
            }));
        });
        wrap.append(list, hint(t("slack.groups_hint", "A mention of a ticked group notifies everyone who sees this channel; linked group members count as mentioned.")));
        return wrap;
    }

    function renderAudience(rule, channel) {
        const wrap = h("div", { style: "display:grid;gap:6px;font-size:14px;" });
        const audience = rule.audience || { everyone: true, roles: [], userIds: [] };
        const mode = h("select", {
            onchange: (e) => {
                update(rule, { audience: Object.assign({}, audience, { everyone: e.target.value === "everyone" }) });
                wrap.replaceWith(renderAudience(rule, channel));
            }
        },
            h("option", { value: "everyone" }, t("slack.audience_everyone", "Everyone on the team")),
            h("option", { value: "some" }, t("slack.audience_some", "Only selected roles and people"))
        );
        mode.value = audience.everyone ? "everyone" : "some";
        wrap.appendChild(h("div", { style: "display:flex;align-items:center;gap:8px;flex-wrap:wrap;" }, t("slack.who_sees", "Who sees it"), mode));

        if (audience.everyone && channel.isPrivate) {
            wrap.appendChild(hint(t("slack.private_everyone_warning", "This is a private Slack channel. Everyone on your team will see its messages."), "color:var(--warning);"));
        }
        if (!audience.everyone) {
            const roleRow = h("div", { style: "display:flex;flex-wrap:wrap;gap:4px 14px;" });
            ROLES.forEach((role) => roleRow.appendChild(checkbox(role.charAt(0) + role.slice(1).toLowerCase(), (audience.roles || []).includes(role), (v) => {
                const roles = new Set(audience.roles || []);
                if (v) roles.add(role); else roles.delete(role);
                update(rule, { audience: Object.assign({}, audience, { roles: Array.from(roles) }) });
            })));
            wrap.append(hint(t("slack.roles", "Roles")), roleRow);

            const people = (state.links && state.links.users) || [];
            if (people.length) {
                const peopleRow = h("div", { style: "display:flex;flex-wrap:wrap;gap:4px 14px;max-height:160px;overflow:auto;" });
                people.forEach((u) => peopleRow.appendChild(checkbox(u.username, (audience.userIds || []).includes(u.userId), (v) => {
                    const ids = new Set(audience.userIds || []);
                    if (v) ids.add(u.userId); else ids.delete(u.userId);
                    update(rule, { audience: Object.assign({}, audience, { userIds: Array.from(ids) }) });
                })));
                wrap.append(hint(t("slack.people", "People")), peopleRow);
            }
        }
        return wrap;
    }

    function renderLinks() {
        const details = h("details", { style: "margin-top:20px;border-top:1px solid var(--border);padding-top:16px;" },
            h("summary", { style: "cursor:pointer;font-weight:600;" }, t("slack.people_links", "Slack accounts of team members")));
        const users = (state.links && state.links.users) || [];
        const slackUsers = (state.links && state.links.slackUsers) || [];
        const slackName = new Map(slackUsers.map((s) => [s.id, s.realName && s.realName !== s.name ? `${s.name} (${s.realName})` : s.name]));
        details.appendChild(hint(t("slack.links_hint", "Members are matched to Slack by email. Linked members get banners when they are @mentioned directly. Change a match here if it's wrong."), "margin:8px 0;"));
        if (!users.length) {
            details.appendChild(hint(t("slack.no_members", "No team members found.")));
            return details;
        }
        const table = h("div", { style: "display:grid;gap:6px;" });
        users.forEach((u) => {
            const select = h("select", {
                style: "min-width:200px;max-width:100%;",
                onchange: (e) => {
                    const v = e.target.value;
                    if (v === "__auto__") delete state.userLinks[u.userId];
                    else state.userLinks[u.userId] = v;
                }
            });
            const autoLabel = u.autoSlackUserId
                ? t("slack.link_auto_match", "Match by email: {name}").replace("{name}", slackName.get(u.autoSlackUserId) || u.autoSlackUserId)
                : t("slack.link_auto_none", "Match by email (no match)");
            select.append(
                h("option", { value: "__auto__" }, autoLabel),
                h("option", { value: "" }, t("slack.link_none", "Don't link")),
                ...slackUsers.map((s) => h("option", { value: s.id }, slackName.get(s.id)))
            );
            const override = state.userLinks[u.userId];
            select.value = override === undefined ? "__auto__" : override;
            table.appendChild(h("div", { style: "display:flex;align-items:center;gap:10px;flex-wrap:wrap;" },
                h("span", { style: "min-width:140px;font-weight:600;" }, u.username),
                select));
        });
        details.appendChild(table);
        return details;
    }

    function renderActions() {
        const i = state.integration;
        const row = h("div", { style: "margin-top:20px;display:flex;gap:8px;flex-wrap:wrap;" });
        row.appendChild(h("button", { type: "button", class: "btn", onclick: save }, t("slack.save", "Save Slack settings")));
        row.appendChild(h("button", { type: "button", class: "btn secondary", onclick: testConnection }, t("slack.test", "Test connection")));
        if (i.configured) {
            row.appendChild(h("button", {
                type: "button", class: "btn secondary", style: "display:inline-flex;align-items:center;gap:6px;",
                onclick: sendPreview
            }, h("span", { html: icon("bell", 13), style: "display:inline-flex;" }), t("slack.preview", "Send me a test banner")));
            row.appendChild(h("button", { type: "button", class: "btn danger", onclick: disconnect }, t("slack.disconnect", "Disconnect")));
        }
        return row;
    }

    // ---------- actions ----------

    function tokenValue(id) {
        const el = document.getElementById(id);
        return el ? el.value.trim() : "";
    }

    async function save(e) {
        const payload = {
            rules: Array.from(state.rules.values()),
            userLinks: state.userLinks
        };
        const bot = tokenValue("slack-bot-token");
        const app = tokenValue("slack-app-token");
        if (bot) payload.botToken = bot;
        if (app) payload.appToken = app;
        const enabledBox = document.getElementById("slack-enabled");
        if (enabledBox) payload.enabled = enabledBox.checked;
        // First connection: switch it on as soon as both tokens are in.
        else if (bot && app) payload.enabled = true;
        try {
            await api("/api/slack/integration", { method: "PUT", json: payload, button: e.currentTarget });
            toast(t("slack.saved", "Slack settings saved."), "success");
            await mount(root);
        } catch (err) {
            toast(errorText(err, t("slack.save_failed", "Could not save Slack settings.")), "error");
        }
    }

    async function testConnection(e) {
        try {
            const res = await api("/api/slack/integration/test", {
                method: "POST",
                json: { botToken: tokenValue("slack-bot-token") || null, appToken: tokenValue("slack-app-token") || null },
                button: e.currentTarget
            });
            toast(res.message || (res.ok ? "OK" : t("slack.test_failed", "Slack test failed.")), res.ok ? "success" : "error");
        } catch (err) {
            toast(errorText(err, t("slack.test_failed", "Slack test failed.")), "error");
        }
    }

    async function sendPreview(e) {
        try {
            await api("/api/slack/preview", { method: "POST", json: {}, button: e.currentTarget });
        } catch (err) {
            toast(errorText(err, t("slack.preview_failed", "Could not send the test banner.")), "error");
        }
    }

    async function disconnect(e) {
        if (!confirm(t("slack.disconnect_confirm", "Disconnect Slack? The tokens and channel choices are deleted. To stop Slack from keeping the app, also uninstall it in Slack."))) return;
        try {
            await api("/api/slack/integration", { method: "DELETE", button: e.currentTarget });
            toast(t("slack.disconnected", "Slack disconnected."), "success");
            await mount(root);
        } catch (err) {
            toast(errorText(err, t("slack.save_failed", "Could not save Slack settings.")), "error");
        }
    }

    window.ObsidianscoutAdminSlack = { mount };
})();
