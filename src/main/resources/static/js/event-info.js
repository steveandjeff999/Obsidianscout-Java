function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

// Font Awesome Free 6 icons.
const ICONS = {
    thumbtack: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 384 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M32 32C32 14.3 46.3 0 64 0H320c17.7 0 32 14.3 32 32s-14.3 32-32 32H290.5l11.4 148.2c36.7 19.9 65.7 53.2 79.5 94.7l1 3c3.3 9.8 1.6 20.5-4.4 28.8s-15.7 13.3-26 13.3H32c-10.3 0-19.9-4.9-26-13.3s-7.7-19.1-4.4-28.8l1-3c13.8-41.5 42.8-74.8 79.5-94.7L93.5 64H64C46.3 64 32 49.7 32 32zM160 384h64v96c0 17.7-14.3 32-32 32s-32-14.3-32-32V384z"/></svg>',
    pen: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M362.7 19.3L314.3 67.7 444.3 197.7l48.4-48.4c25-25 25-65.5 0-90.5L453.3 19.3c-25-25-65.5-25-90.5 0zm-71 71L58.6 323.5c-10.4 10.4-18 23.3-22.2 37.4L1 481.2C-1.5 489.7 .8 498.8 7 505s15.3 8.5 23.7 6.1l120.3-35.4c14.1-4.2 27-11.8 37.4-22.2L421.7 220.3 291.7 90.3z"/></svg>',
    trash: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 448 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M135.2 17.7C140.6 6.8 151.7 0 163.8 0H284.2c12.1 0 23.2 6.8 28.6 17.7L320 32h96c17.7 0 32 14.3 32 32s-14.3 32-32 32H32C14.3 96 0 81.7 0 64S14.3 32 32 32h96l7.2-14.3zM32 128H416V448c0 35.3-28.7 64-64 64H96c-35.3 0-64-28.7-64-64V128zm96 64c-8.8 0-16 7.2-16 16V432c0 8.8 7.2 16 16 16s16-7.2 16-16V208c0-8.8-7.2-16-16-16zm96 0c-8.8 0-16 7.2-16 16V432c0 8.8 7.2 16 16 16s16-7.2 16-16V208c0-8.8-7.2-16-16-16zm96 0c-8.8 0-16 7.2-16 16V432c0 8.8 7.2 16 16 16s16-7.2 16-16V208c0-8.8-7.2-16-16-16z"/></svg>',
    download: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M288 32c0-17.7-14.3-32-32-32s-32 14.3-32 32V274.7l-73.4-73.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3l128 128c12.5 12.5 32.8 12.5 45.3 0l128-128c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L288 274.7V32zM64 352c-35.3 0-64 28.7-64 64v32c0 35.3 28.7 64 64 64H448c35.3 0 64-28.7 64-64V416c0-35.3-28.7-64-64-64H346.5l-45.3 45.3c-25 25-65.5 25-90.5 0L165.5 352H64zm368 56a24 24 0 1 1 0 48 24 24 0 1 1 0-48z"/></svg>',
    file: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 384 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M0 64C0 28.7 28.7 0 64 0H224V128c0 17.7 14.3 32 32 32H384V448c0 35.3-28.7 64-64 64H64c-35.3 0-64-28.7-64-64V64zm384 64H256V0L384 128z"/></svg>',
    paperclip: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 448 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M364.2 83.8c-24.4-24.4-64-24.4-88.4 0l-184 184c-42.1 42.1-42.1 110.3 0 152.4s110.3 42.1 152.4 0l152-152c10.9-10.9 28.7-10.9 39.6 0s10.9 28.7 0 39.6l-152 152c-64 64-167.6 64-231.6 0s-64-167.6 0-231.6l184-184c46.3-46.3 121.3-46.3 167.6 0s46.3 121.3 0 167.6l-176 176c-28.6 28.6-75 28.6-103.6 0s-28.6-75 0-103.6l144-144c10.9-10.9 28.7-10.9 39.6 0s10.9 28.7 0 39.6l-144 144c-6.7 6.7-6.7 17.7 0 24.4s17.7 6.7 24.4 0l176-176c24.4-24.4 24.4-64 0-88.4z"/></svg>',
    remove: '<svg class="ei-icon" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 384 512" fill="currentColor" aria-hidden="true" focusable="false"><path d="M342.6 150.6c12.5-12.5 12.5-32.8 0-45.3s-32.8-12.5-45.3 0L192 210.7 86.6 105.4c-12.5-12.5-32.8-12.5-45.3 0s-12.5 32.8 0 45.3L146.7 256 41.4 361.4c-12.5 12.5-12.5 32.8 0 45.3s32.8 12.5 45.3 0L192 301.3 297.4 406.6c12.5 12.5 32.8 12.5 45.3 0s12.5-32.8 0-45.3L237.3 256 342.6 150.6z"/></svg>'
};

const URL_REGEX = /\bhttps?:\/\/[^\s<>"']+/g;

function formatSize(bytes) {
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function formatDate(iso) {
    const d = new Date(iso);
    return isNaN(d.getTime()) ? "" : d.toLocaleString([], { dateStyle: "medium", timeStyle: "short" });
}

/** Fills [el] with [text], turning bare http(s) links into anchors without ever parsing the text as HTML. */
function renderLinkedText(el, text) {
    let last = 0;
    for (const match of text.matchAll(URL_REGEX)) {
        if (match.index > last) el.appendChild(document.createTextNode(text.slice(last, match.index)));
        const a = document.createElement("a");
        a.href = match[0];
        a.textContent = match[0];
        a.target = "_blank";
        a.rel = "noopener noreferrer";
        el.appendChild(a);
        last = match.index + match[0].length;
    }
    if (last < text.length) el.appendChild(document.createTextNode(text.slice(last)));
}

function iconButton(icon, label, extraClass = "") {
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = `ei-icon-btn ${extraClass}`.trim();
    btn.innerHTML = ICONS[icon];
    btn.title = label;
    btn.setAttribute("aria-label", label);
    return btn;
}

document.addEventListener("DOMContentLoaded", async () => {
    Obsidianscout.initTheme();
    const me = await Obsidianscout.requireAuth();
    if (!me) return;

    Obsidianscout.setUserBadge(me);
    Obsidianscout.setActiveNav();
    Obsidianscout.adjustNavForRole(me);
    Obsidianscout.wireLogout();
    Obsidianscout.wireThemeToggle();

    const feed = document.getElementById("ei-feed");
    const subtitle = document.getElementById("ei-subtitle");
    const composer = document.getElementById("ei-composer");
    let posts = [];
    let canEdit = false;
    let maxFileBytes = 10 * 1024 * 1024;

    // Pending file pickers (composer + edit modal) share this helper.
    function wirePicker(input, pendingEl, list) {
        function render() {
            pendingEl.innerHTML = "";
            list.forEach((file, i) => {
                const chip = document.createElement("span");
                chip.className = "ei-chip";
                chip.innerHTML = ICONS.paperclip;
                chip.appendChild(document.createTextNode(` ${file.name} (${formatSize(file.size)})`));
                const rm = document.createElement("button");
                rm.type = "button";
                rm.innerHTML = ICONS.remove;
                rm.setAttribute("aria-label", t("event_info.remove", "Remove"));
                rm.addEventListener("click", () => { list.splice(i, 1); render(); });
                chip.appendChild(rm);
                pendingEl.appendChild(chip);
            });
        }
        function add(files) {
            for (const file of files) {
                if (file.size > maxFileBytes) {
                    Obsidianscout.showToast(t("event_info.err_too_large", "{name} is larger than {size}.")
                        .replace("{name}", file.name).replace("{size}", formatSize(maxFileBytes)), "error");
                    continue;
                }
                list.push(file);
            }
            render();
        }
        input.addEventListener("change", () => { add(input.files); input.value = ""; });
        return { add, render, clear() { list.length = 0; render(); } };
    }

    async function uploadFiles(postId, files) {
        let failed = 0;
        for (const file of files) {
            const formData = new FormData();
            formData.append("file", file, file.name);
            try {
                await Obsidianscout.request(`/api/event-info/${encodeURIComponent(postId)}/files`, { method: "POST", body: formData });
            } catch (error) {
                failed++;
                Obsidianscout.showToast(`${file.name}: ${error.message}`, "error");
            }
        }
        return failed;
    }

    // ---- Composer ----
    const form = document.getElementById("ei-form");
    const drop = document.getElementById("ei-drop");
    const fileInput = document.getElementById("ei-files");
    const pendingFiles = [];
    const picker = wirePicker(fileInput, document.getElementById("ei-pending"), pendingFiles);
    document.getElementById("ei-drop-icon").innerHTML = ICONS.paperclip;

    drop.addEventListener("click", () => fileInput.click());
    drop.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fileInput.click(); } });
    drop.addEventListener("dragover", (e) => { e.preventDefault(); drop.classList.add("dragover"); });
    drop.addEventListener("dragleave", () => drop.classList.remove("dragover"));
    drop.addEventListener("drop", (e) => {
        e.preventDefault();
        drop.classList.remove("dragover");
        if (e.dataTransfer && e.dataTransfer.files) picker.add(e.dataTransfer.files);
    });

    form.addEventListener("submit", async () => {
        const title = document.getElementById("ei-title").value.trim();
        const body = document.getElementById("ei-body").value.trim();
        if (!title && !body && pendingFiles.length === 0) {
            Obsidianscout.showToast(t("event_info.err_empty", "Add a title, an announcement or a file."), "error");
            return;
        }
        const submit = document.getElementById("ei-submit");
        submit.disabled = true;
        try {
            const created = await Obsidianscout.request("/api/event-info", {
                method: "POST",
                json: {
                    title,
                    body,
                    pinned: document.getElementById("ei-pinned").checked,
                    allEvents: document.getElementById("ei-all-events").checked
                }
            });
            const failed = await uploadFiles(created.id, pendingFiles);
            form.reset();
            picker.clear();
            if (failed === 0) Obsidianscout.showToast(t("event_info.toast_posted", "Posted."), "success");
            await loadPosts();
        } catch (error) {
            Obsidianscout.showToast(t("event_info.err_post", "Failed to post: ") + error.message, "error");
        } finally {
            submit.disabled = false;
        }
    });

    // ---- Lightbox ----
    const lightbox = document.getElementById("ei-lightbox");
    const lightboxImg = document.getElementById("ei-lightbox-img");
    function closeLightbox() { lightbox.classList.remove("show"); lightboxImg.removeAttribute("src"); }
    document.getElementById("ei-lightbox-close").addEventListener("click", closeLightbox);
    lightbox.addEventListener("click", (e) => { if (e.target === lightbox) closeLightbox(); });
    document.addEventListener("keydown", (e) => { if (e.key === "Escape") closeLightbox(); });

    // ---- Edit modal ----
    const editModal = document.getElementById("ei-edit-modal");
    const editForm = document.getElementById("ei-edit-form");
    const editPendingFiles = [];
    const editPicker = wirePicker(document.getElementById("ei-edit-files"), document.getElementById("ei-edit-pending"), editPendingFiles);
    let editingPost = null;

    document.getElementById("ei-edit-add-files").addEventListener("click", () => document.getElementById("ei-edit-files").click());

    function closeEdit() {
        editModal.classList.remove("show");
        editingPost = null;
        editPicker.clear();
    }
    document.getElementById("ei-edit-close").addEventListener("click", closeEdit);
    document.getElementById("ei-edit-cancel").addEventListener("click", closeEdit);

    function renderEditExisting() {
        const list = document.getElementById("ei-edit-existing");
        list.innerHTML = "";
        (editingPost.files || []).forEach((file) => {
            const li = document.createElement("li");
            li.className = "ei-file";
            li.innerHTML = ICONS.file;
            const name = document.createElement("span");
            name.className = "ei-file-name";
            name.style.flex = "1";
            name.textContent = file.fileName;
            li.appendChild(name);
            const rm = iconButton("trash", t("event_info.remove", "Remove"), "danger");
            rm.addEventListener("click", async () => {
                if (!confirm(t("event_info.confirm_remove_file", "Remove {name}?").replace("{name}", file.fileName))) return;
                try {
                    await Obsidianscout.request(`/api/event-info/files/${encodeURIComponent(file.id)}`, { method: "DELETE" });
                    editingPost.files = editingPost.files.filter((f) => f.id !== file.id);
                    renderEditExisting();
                    loadPosts();
                } catch (error) {
                    Obsidianscout.showToast(error.message, "error");
                }
            });
            li.appendChild(rm);
            list.appendChild(li);
        });
    }

    function openEdit(post) {
        editingPost = { ...post, files: [...(post.files || [])] };
        document.getElementById("ei-edit-title").value = post.title;
        document.getElementById("ei-edit-body").value = post.body;
        document.getElementById("ei-edit-pinned").checked = post.pinned;
        document.getElementById("ei-edit-all-events").checked = !post.eventKey;
        renderEditExisting();
        editModal.classList.add("show");
    }

    editForm.addEventListener("submit", async () => {
        if (!editingPost) return;
        const save = document.getElementById("ei-edit-save");
        save.disabled = true;
        try {
            await Obsidianscout.request(`/api/event-info/${encodeURIComponent(editingPost.id)}`, {
                method: "PUT",
                json: {
                    title: document.getElementById("ei-edit-title").value,
                    body: document.getElementById("ei-edit-body").value,
                    pinned: document.getElementById("ei-edit-pinned").checked,
                    allEvents: document.getElementById("ei-edit-all-events").checked
                }
            });
            const failed = await uploadFiles(editingPost.id, editPendingFiles);
            closeEdit();
            if (failed === 0) Obsidianscout.showToast(t("event_info.toast_updated", "Post updated."), "success");
            await loadPosts();
        } catch (error) {
            Obsidianscout.showToast(t("event_info.err_update", "Failed to update post: ") + error.message, "error");
        } finally {
            save.disabled = false;
        }
    });

    async function deletePost(post) {
        if (!confirm(t("event_info.confirm_delete", "Delete this post and its files?"))) return;
        try {
            await Obsidianscout.request(`/api/event-info/${encodeURIComponent(post.id)}`, { method: "DELETE" });
            Obsidianscout.showToast(t("event_info.toast_deleted", "Post deleted."), "success");
            await loadPosts();
        } catch (error) {
            Obsidianscout.showToast(t("event_info.err_delete", "Failed to delete post: ") + error.message, "error");
        }
    }

    // ---- Feed ----
    function renderPost(post) {
        const card = document.createElement("article");
        card.className = "card ei-post" + (post.pinned ? " pinned" : "");

        const head = document.createElement("div");
        head.className = "ei-post-head";
        const headText = document.createElement("div");
        if (post.title) {
            const h = document.createElement("h2");
            h.className = "ei-post-title";
            h.textContent = post.title;
            headText.appendChild(h);
        }
        const meta = document.createElement("div");
        meta.className = "ei-meta";
        if (post.pinned) {
            const pin = document.createElement("span");
            pin.className = "ei-badge";
            pin.innerHTML = ICONS.thumbtack;
            pin.appendChild(document.createTextNode(" " + t("event_info.pinned", "Pinned")));
            meta.appendChild(pin);
        }
        const by = document.createElement("span");
        by.textContent = `${post.authorUsername} · ${formatDate(post.createdAt)}`;
        meta.appendChild(by);
        const scope = document.createElement("span");
        scope.className = "ei-badge";
        scope.textContent = post.eventKey ? post.eventKey : t("event_info.every_event", "Every event");
        meta.appendChild(scope);
        headText.appendChild(meta);
        head.appendChild(headText);

        if (canEdit) {
            const actions = document.createElement("div");
            actions.className = "ei-actions";
            const edit = iconButton("pen", t("event_info.edit", "Edit"));
            edit.addEventListener("click", () => openEdit(post));
            const del = iconButton("trash", t("event_info.delete", "Delete"), "danger");
            del.addEventListener("click", () => deletePost(post));
            actions.append(edit, del);
            head.appendChild(actions);
        }
        card.appendChild(head);

        if (post.body) {
            const body = document.createElement("p");
            body.className = "ei-body";
            renderLinkedText(body, post.body);
            card.appendChild(body);
        }

        const images = (post.files || []).filter((f) => f.isImage);
        const others = (post.files || []).filter((f) => !f.isImage);
        if (images.length) {
            const grid = document.createElement("div");
            grid.className = "ei-images";
            images.forEach((img) => {
                const a = document.createElement("a");
                a.href = img.url;
                a.title = img.fileName;
                a.addEventListener("click", (e) => {
                    e.preventDefault();
                    lightboxImg.src = img.url;
                    lightboxImg.alt = img.fileName;
                    lightbox.classList.add("show");
                });
                const el = document.createElement("img");
                el.src = img.url;
                el.alt = img.fileName;
                el.loading = "lazy";
                a.appendChild(el);
                grid.appendChild(a);
            });
            card.appendChild(grid);
        }
        if (others.length) {
            const list = document.createElement("ul");
            list.className = "ei-files";
            others.forEach((file) => {
                const li = document.createElement("li");
                li.className = "ei-file";
                li.innerHTML = ICONS.file;
                const a = document.createElement("a");
                a.className = "ei-file-name";
                a.href = file.url;
                a.target = "_blank";
                a.rel = "noopener";
                a.textContent = file.fileName;
                const size = document.createElement("span");
                size.className = "ei-file-size";
                size.textContent = formatSize(file.sizeBytes);
                const dl = document.createElement("a");
                dl.className = "ei-icon-btn";
                dl.href = `${file.url}?download=1`;
                dl.innerHTML = ICONS.download;
                dl.title = t("event_info.download", "Download");
                dl.setAttribute("aria-label", dl.title);
                li.append(a, size, dl);
                list.appendChild(li);
            });
            card.appendChild(list);
        }
        return card;
    }

    function renderFeed() {
        feed.innerHTML = "";
        if (!posts.length) {
            const empty = document.createElement("div");
            empty.className = "card ei-empty";
            empty.textContent = canEdit
                ? t("event_info.empty_admin", "Nothing posted yet. Use the form above to share announcements, images and files.")
                : t("event_info.empty", "Nothing has been posted for this event yet.");
            feed.appendChild(empty);
            return;
        }
        posts.forEach((post) => feed.appendChild(renderPost(post)));
    }

    async function loadPosts() {
        try {
            const res = await Obsidianscout.request("/api/event-info");
            posts = res.posts || [];
            canEdit = !!res.canEdit;
            if (res.maxFileBytes) maxFileBytes = res.maxFileBytes;
            composer.classList.toggle("hidden", !canEdit);
            subtitle.textContent = res.eventKey
                ? t("event_info.subtitle_event", "Announcements, images and files for {event}.").replace("{event}", res.eventKey)
                : t("event_info.subtitle", "Announcements, images and files from your team's admins.");
            renderFeed();
        } catch (error) {
            feed.innerHTML = "";
            const err = document.createElement("div");
            err.className = "card ei-empty";
            err.textContent = t("event_info.err_load", "Failed to load event info: ") + error.message;
            feed.appendChild(err);
        }
    }

    window.addEventListener("obsidianscout:languagechange", renderFeed);
    await loadPosts();
});
