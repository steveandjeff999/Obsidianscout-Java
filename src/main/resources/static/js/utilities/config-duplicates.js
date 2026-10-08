/**
 * Config Duplicate Checker - ObsidianScout
 * Finds duplicate field IDs and duplicate/blank dropdown option values in a scouting config,
 * generates collision-free IDs, and renders a warning banner for the config editors.
 * Classic script (not a module) so editor pages can load it without changing the common.js module graph.
 */
(function () {
    function labelText(label) {
        if (label === null || label === undefined) return "";
        if (typeof label === "object") {
            return String(label.en ?? Object.values(label)[0] ?? "");
        }
        return String(label);
    }

    function escapeHtml(text) {
        return String(text)
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;")
            .replace(/'/g, "&#39;");
    }

    /** Returns `base`, or `base<sep>2`, `base<sep>3`, ... — the first that is not in `existing` (Set or array). */
    function uniqueValue(base, existing, separator = "_") {
        const taken = existing instanceof Set ? existing : new Set(existing || []);
        const root = String(base || "").trim() || "item";
        let candidate = root;
        let counter = 2;
        while (taken.has(candidate)) {
            candidate = `${root}${separator}${counter}`;
            counter += 1;
        }
        return candidate;
    }

    /**
     * Generates a `field_NNNN`-style ID that is not already taken.
     * `existing` may be an array of fields, an array of IDs, or a Set of IDs.
     */
    function uniqueFieldId(existing, prefix = "field_") {
        const taken = existing instanceof Set
            ? existing
            : new Set((existing || []).map((x) => (x && typeof x === "object" ? x.id : x)).filter(Boolean));
        for (let attempt = 0; attempt < 50; attempt++) {
            const candidate = `${prefix}${Math.floor(1000 + Math.random() * 9000)}`;
            if (!taken.has(candidate)) return candidate;
        }
        return uniqueValue(`${prefix}${Date.now()}`, taken);
    }

    function findConfigDuplicates(config) {
        const fields = config && Array.isArray(config.fields) ? config.fields : [];

        const idCounts = new Map();
        fields.forEach((field) => {
            const id = String((field && field.id) ?? "").trim();
            if (id) idCounts.set(id, (idCounts.get(id) || 0) + 1);
        });
        const duplicateFieldIds = [...idCounts]
            .filter(([, count]) => count > 1)
            .map(([id, count]) => ({ id, count }));

        const duplicateOptionValues = [];
        const blankOptionValues = [];
        fields.forEach((field) => {
            if (!field || !Array.isArray(field.options)) return;
            const fieldId = String(field.id ?? "");
            const fieldLabel = labelText(field.label) || fieldId;
            const byValue = new Map();
            field.options.forEach((option) => {
                const value = String((typeof option === "string" ? option : option && option.value) ?? "").trim();
                const optionLabel = typeof option === "string" ? option : labelText(option && option.label);
                if (!value) {
                    blankOptionValues.push({ fieldId, fieldLabel, optionLabel });
                    return;
                }
                if (!byValue.has(value)) byValue.set(value, []);
                byValue.get(value).push(optionLabel || value);
            });
            byValue.forEach((labels, value) => {
                if (labels.length > 1) duplicateOptionValues.push({ fieldId, fieldLabel, value, labels });
            });
        });

        return {
            duplicateFieldIds,
            duplicateOptionValues,
            blankOptionValues,
            hasIssues: duplicateFieldIds.length > 0 || duplicateOptionValues.length > 0 || blankOptionValues.length > 0
        };
    }

    /**
     * Shows (or hides) a warning banner. `anchor` is the element the banner is inserted before;
     * the banner is created once and reused on later calls.
     */
    function renderDuplicateWarning(anchor, config) {
        if (!anchor || !anchor.parentNode) return null;
        let banner = anchor.previousElementSibling;
        if (!banner || !banner.classList.contains("config-duplicate-warning")) {
            banner = document.createElement("div");
            banner.className = "notice config-duplicate-warning";
            banner.setAttribute("role", "alert");
            banner.style.cssText = "border-color: var(--warning); border-left: 4px solid var(--warning); margin-bottom: 12px; color: inherit;";
            anchor.parentNode.insertBefore(banner, anchor);
        }

        const result = findConfigDuplicates(config);
        if (!result.hasIssues) {
            banner.style.display = "none";
            banner.innerHTML = "";
            return result;
        }

        const items = [];
        result.duplicateFieldIds.forEach((d) => {
            items.push(`Field ID <code>${escapeHtml(d.id)}</code> is used by ${d.count} fields — their answers will overwrite each other.`);
        });
        result.duplicateOptionValues.forEach((d) => {
            items.push(`<strong>${escapeHtml(d.fieldLabel)}</strong>: options ${d.labels.map((l) => `"${escapeHtml(l)}"`).join(", ")} ${d.labels.length === 2 ? "both" : "all"} save as <code>${escapeHtml(d.value)}</code>, so they can't be told apart in the data.`);
        });
        result.blankOptionValues.forEach((d) => {
            items.push(`<strong>${escapeHtml(d.fieldLabel)}</strong>: option "${escapeHtml(d.optionLabel || "(no label)")}" has no value — picking it saves nothing.`);
        });

        banner.innerHTML =
            `<div style="font-weight: 700; color: var(--warning); margin-bottom: 6px;">` +
            `<i class="fa-solid fa-triangle-exclamation"></i> Duplicate or missing values in this config</div>` +
            `<ul style="margin: 0; padding-left: 18px;">${items.map((i) => `<li>${i}</li>`).join("")}</ul>` +
            `<div style="margin-top: 6px;">Give each field and each dropdown option its own value before scouting starts.</div>`;
        banner.style.display = "";
        return result;
    }

    window.ObsidianscoutConfigDuplicates = {
        uniqueValue,
        uniqueFieldId,
        findConfigDuplicates,
        renderDuplicateWarning
    };
})();
