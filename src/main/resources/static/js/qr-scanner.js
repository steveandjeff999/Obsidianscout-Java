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

    initScanner();
});

function initScanner() {
    const cameraSelect = document.getElementById("camera-select");
    const toggleScanBtn = document.getElementById("btn-toggle-scan");
    const uploadQueueBtn = document.getElementById("btn-upload-queue");
    const clearQueueBtn = document.getElementById("btn-clear-queue");
    const queueListContainer = document.getElementById("queue-list-container");
    const emptyNotice = document.getElementById("empty-queue-notice");
    const countBadge = document.getElementById("queue-count-badge");

    let html5Qrcode = null;
    let isScanning = false;
    let queue = JSON.parse(localStorage.getItem("obsidianscout:scanned_qr_entries") || "[]");

    // Render initial queue
    renderQueue();
    updateUploadButtonState();

    // Check camera permission and list devices
    Html5Qrcode.getCameras().then(cameras => {
        cameraSelect.innerHTML = "";
        if (cameras && cameras.length > 0) {
            cameras.forEach(camera => {
                const opt = document.createElement("option");
                opt.value = camera.id;
                opt.textContent = camera.label || `Camera ${cameraSelect.children.length + 1}`;
                cameraSelect.appendChild(opt);
            });
        } else {
            const opt = document.createElement("option");
            opt.value = "";
            opt.textContent = "No camera found";
            cameraSelect.appendChild(opt);
            toggleScanBtn.disabled = true;
        }
    }).catch(err => {
        console.error("Camera detection error:", err);
        cameraSelect.innerHTML = '<option value="">Permission denied / No camera</option>';
        toggleScanBtn.disabled = true;
        Obsidianscout.showToast("Camera access permission denied", "error");
    });

    // Start/Stop Scan handler
    toggleScanBtn.addEventListener("click", () => {
        const cameraId = cameraSelect.value;
        if (!cameraId) {
            Obsidianscout.showToast("Please select a camera source", "error");
            return;
        }

        if (!html5Qrcode) {
            html5Qrcode = new Html5Qrcode("reader");
        }

        if (isScanning) {
            stopScanning();
        } else {
            startScanning(cameraId);
        }
    });

    function startScanning(cameraId) {
        toggleScanBtn.disabled = true;
        toggleScanBtn.textContent = "Starting...";

        html5Qrcode.start(
            cameraId,
            {
                fps: 15,
                qrbox: (width, height) => {
                    const min = Math.min(width, height);
                    const size = Math.floor(min * 0.8);
                    return { width: size, height: size };
                },
                aspectRatio: 1.0
            },
            onScanSuccess,
            onScanFailure
        ).then(() => {
            isScanning = true;
            toggleScanBtn.disabled = false;
            toggleScanBtn.textContent = "Stop Scanning";
            toggleScanBtn.className = "btn ghost";
            cameraSelect.disabled = true;
            Obsidianscout.showToast("Scanner active", "success");
        }).catch(err => {
            console.error("Failed to start scanning:", err);
            toggleScanBtn.disabled = false;
            toggleScanBtn.textContent = "Start Scanning";
            toggleScanBtn.className = "btn";
            cameraSelect.disabled = false;
            Obsidianscout.showToast("Failed to access camera", "error");
        });
    }

    function stopScanning() {
        if (!html5Qrcode || !isScanning) return;

        toggleScanBtn.disabled = true;
        toggleScanBtn.textContent = "Stopping...";

        html5Qrcode.stop().then(() => {
            isScanning = false;
            toggleScanBtn.disabled = false;
            toggleScanBtn.textContent = "Start Scanning";
            toggleScanBtn.className = "btn";
            cameraSelect.disabled = false;
            Obsidianscout.showToast("Scanner stopped", "info");
        }).catch(err => {
            console.error("Failed to stop scanning:", err);
            toggleScanBtn.disabled = false;
            Obsidianscout.showToast("Error stopping camera", "error");
        });
    }

    // Decode success handler
    function onScanSuccess(decodedText) {
        try {
            const entry = JSON.parse(decodedText);
            if (!entry || !entry.type || !entry.data) {
                throw new Error("Invalid scouting QR schema");
            }

            // Play scan success sound using AudioContext
            playBeep();

            // Check duplicate
            const payload = entry.data;
            const isDuplicate = queue.some(item => {
                return item.type === entry.type &&
                       item.data.eventKey === payload.eventKey &&
                       item.data.targetTeamNumber === payload.targetTeamNumber &&
                       item.data.matchKey === payload.matchKey;
            });

            if (isDuplicate) {
                Obsidianscout.showToast("Entry already exists in queue", "info");
                return;
            }

            // Add to queue
            const newItem = {
                id: Date.now() + "_" + Math.random().toString(36).substr(2, 9),
                type: entry.type,
                data: payload,
                status: "pending",
                errorMsg: ""
            };

            queue.push(newItem);
            saveQueue();
            renderQueue();
            updateUploadButtonState();

            Obsidianscout.showToast(`Scanned: Team ${payload.targetTeamNumber}`, "success");
        } catch (err) {
            console.warn("Scan warning:", err.message);
            Obsidianscout.showToast("QR code is not a valid ObsidianScout entry", "error");
        }
    }

    function onScanFailure(error) {
        // Quiet failures (runs on every frame scanning fails to decode)
    }

    // Audio beep helper
    function playBeep() {
        try {
            const ctx = new (window.AudioContext || window.webkitAudioContext)();
            const osc = ctx.createOscillator();
            const gain = ctx.createGain();
            osc.type = "sine";
            osc.frequency.setValueAtTime(880, ctx.currentTime); // A5 note
            gain.gain.setValueAtTime(0.1, ctx.currentTime);
            gain.gain.exponentialRampToValueAtTime(0.01, ctx.currentTime + 0.15);
            osc.connect(gain);
            gain.connect(ctx.destination);
            osc.start();
            osc.stop(ctx.currentTime + 0.15);
        } catch (e) {
            // Browser might block AudioContext before user interaction
        }
    }

    // Queue Management
    function saveQueue() {
        localStorage.setItem("obsidianscout:scanned_qr_entries", JSON.stringify(queue));
    }

    function renderQueue() {
        const rows = queueListContainer.querySelectorAll(".scanned-item-row");
        rows.forEach(r => r.remove());

        if (queue.length === 0) {
            emptyNotice.style.display = "block";
            countBadge.textContent = "0 items";
            return;
        }

        emptyNotice.style.display = "none";
        countBadge.textContent = `${queue.length} item${queue.length === 1 ? "" : "s"}`;

        queue.forEach(item => {
            const row = document.createElement("div");
            row.className = "scanned-item-row";
            row.dataset.id = item.id;

            const typeLabel = getFormTypeLabel(item.type);
            const team = item.data.targetTeamNumber || "Unknown";
            const match = item.data.matchKey ? item.data.matchKey.split("_").pop().toUpperCase() : "";
            const event = item.data.eventKey || "No Event";

            const metaText = match ? `Event: ${event} | Match: ${match}` : `Event: ${event}`;

            row.innerHTML = `
                <div class="scanned-item-info">
                    <div class="scanned-item-title">${typeLabel} - Team ${team}</div>
                    <div class="scanned-item-meta">${metaText}</div>
                    ${item.errorMsg ? `<div class="scanned-item-meta" style="color: #c84b31;">Error: ${item.errorMsg}</div>` : ""}
                </div>
                <div class="scanned-item-actions">
                    <span class="scanned-item-badge ${item.status}">${item.status}</span>
                    <button class="btn-remove-item" title="Remove">&times;</button>
                </div>
            `;

            row.querySelector(".btn-remove-item").addEventListener("click", (e) => {
                e.stopPropagation();
                removeQueueItem(item.id);
            });

            queueListContainer.appendChild(row);
        });
    }

    function removeQueueItem(id) {
        queue = queue.filter(item => item.id !== id);
        saveQueue();
        renderQueue();
        updateUploadButtonState();
    }

    function getFormTypeLabel(type) {
        switch (type) {
            case "scout": return "Match Scouting";
            case "pit-scout": return "Pit Scouting";
            case "qual-scout": return "Qual Scouting";
            case "prescout-scout": return "Match Prescouting";
            case "prescout-pit": return "Pit Prescouting";
            case "prescout-qual": return "Qual Prescouting";
            default: return type;
        }
    }

    function getEndpoint(type) {
        switch (type) {
            case "scout": return "/api/scouting";
            case "pit-scout": return "/api/pit-scouting";
            case "qual-scout": return "/api/qual-scouting";
            case "prescout-scout": return "/api/prescout/scouting";
            case "prescout-pit": return "/api/prescout/pit-scouting";
            case "prescout-qual": return "/api/prescout/qual-scouting";
            default: return null;
        }
    }

    // Sync to Server
    function updateUploadButtonState() {
        const hasUploadable = queue.some(item => item.status === "pending" || item.status === "error");
        uploadQueueBtn.disabled = !navigator.onLine || !hasUploadable;
    }

    uploadQueueBtn.addEventListener("click", async () => {
        if (!navigator.onLine) {
            Obsidianscout.showToast("You are offline. Cannot upload.", "error");
            return;
        }

        uploadQueueBtn.disabled = true;
        uploadQueueBtn.textContent = "Uploading...";

        let successCount = 0;
        let failCount = 0;

        for (const item of queue) {
            if (item.status === "success") continue;

            const endpoint = getEndpoint(item.type);
            if (!endpoint) {
                item.status = "error";
                item.errorMsg = "Unknown endpoint type";
                failCount++;
                continue;
            }

            try {
                item.status = "pending";
                renderQueue();

                await Obsidianscout.request(endpoint, {
                    method: "POST",
                    json: {
                        data: item.data
                    }
                });

                item.status = "success";
                item.errorMsg = "";
                successCount++;
            } catch (err) {
                console.error("Upload failed for item:", item, err);
                item.status = "error";
                item.errorMsg = err.message || "Network error";
                failCount++;
            }

            saveQueue();
            renderQueue();
        }

        uploadQueueBtn.textContent = "Upload Queue";
        updateUploadButtonState();

        if (successCount > 0) {
            Obsidianscout.showToast(`Successfully uploaded ${successCount} entries!`, "success");
        }
        if (failCount > 0) {
            Obsidianscout.showToast(`Failed to upload ${failCount} entries. Check details.`, "error");
        }
    });

    clearQueueBtn.addEventListener("click", () => {
        if (queue.length === 0) return;
        if (confirm("Are you sure you want to clear all items in the scanner queue?")) {
            queue = [];
            saveQueue();
            renderQueue();
            updateUploadButtonState();
            Obsidianscout.showToast("Queue cleared", "info");
        }
    });

    // File Import logic
    const dropzone = document.getElementById("import-dropzone");
    const fileInput = document.getElementById("import-file-input");

    if (dropzone && fileInput) {
        dropzone.addEventListener("click", () => {
            fileInput.click();
        });

        fileInput.addEventListener("change", (e) => {
            handleImportedFiles(e.target.files);
            fileInput.value = "";
        });

        dropzone.addEventListener("dragover", (e) => {
            e.preventDefault();
            dropzone.classList.add("dragover");
        });

        dropzone.addEventListener("dragleave", () => {
            dropzone.classList.remove("dragover");
        });

        dropzone.addEventListener("drop", (e) => {
            e.preventDefault();
            dropzone.classList.remove("dragover");
            if (e.dataTransfer.files && e.dataTransfer.files.length > 0) {
                handleImportedFiles(e.dataTransfer.files);
            }
        });
    }

    function handleImportedFiles(files) {
        if (!files || files.length === 0) return;
        
        let filesRead = 0;
        const allEntriesToProcess = [];
        
        Array.from(files).forEach(file => {
            const reader = new FileReader();
            reader.onload = (e) => {
                try {
                    const parsed = JSON.parse(e.target.result);
                    const items = Array.isArray(parsed) ? parsed : [parsed];
                    items.forEach(item => {
                        const normalized = normalizeImportedItem(item, file.name);
                        if (normalized) {
                            allEntriesToProcess.push(normalized);
                        }
                    });
                } catch (err) {
                    console.error("Failed to parse file:", file.name, err);
                    Obsidianscout.showToast(`Error parsing ${file.name}`, "error");
                }
                
                filesRead++;
                if (filesRead === files.length) {
                    processEntriesList(allEntriesToProcess);
                }
            };
            reader.onerror = () => {
                Obsidianscout.showToast(`Error reading ${file.name}`, "error");
                filesRead++;
                if (filesRead === files.length) {
                    processEntriesList(allEntriesToProcess);
                }
            };
            reader.readAsText(file);
        });
    }

    function normalizeImportedItem(item, filename) {
        if (!item || typeof item !== 'object') return null;

        let type = item.type;
        if (!type && item.data && item.data.type) {
            type = item.data.type;
        }
        if (!type && filename) {
            const lower = filename.toLowerCase();
            if (lower.includes("prescout-scout")) type = "prescout-scout";
            else if (lower.includes("prescout-pit")) type = "prescout-pit";
            else if (lower.includes("prescout-qual")) type = "prescout-qual";
            else if (lower.includes("pit")) type = "pit-scout";
            else if (lower.includes("qual")) type = "qual-scout";
            else if (lower.includes("scout")) type = "scout";
        }
        if (!type) {
            type = "scout";
        }

        const payload = {};

        // Case 1: Standard QR format { type: "...", data: { ... } }
        if (item.type && item.data && typeof item.data === 'object') {
            Object.assign(payload, item.data);
        } 
        // Case 2: Ktor ScoutingEntryRecord { id: 123, data: { ... }, eventKey: "...", targetTeamNumber: 123 }
        else if (item.data && typeof item.data === 'object') {
            Object.assign(payload, item.data);
            if (item.eventKey) payload.eventKey = item.eventKey;
            if (item.targetTeamNumber) payload.targetTeamNumber = Number(item.targetTeamNumber);
            if (item.matchKey) payload.matchKey = item.matchKey;
            if (item.matchNumber) payload.matchNumber = Number(item.matchNumber);
        } 
        // Case 3: Flat export format { eventKey: "...", targetTeamNumber: 123, auto_leave: true }
        else {
            Object.assign(payload, item);
        }

        // Sanity check metadata
        if (!payload.eventKey || !payload.targetTeamNumber) {
            return null;
        }

        // Convert targetTeamNumber and matchNumber to numbers
        payload.targetTeamNumber = Number(payload.targetTeamNumber);
        if (payload.matchNumber !== undefined && payload.matchNumber !== null) {
            payload.matchNumber = Number(payload.matchNumber);
        }

        // Ensure type is clean (remove any other wrappers or type properties inside data)
        if (payload.type) {
            delete payload.type;
        }

        return {
            type: type,
            data: payload
        };
    }

    function processEntriesList(entriesList) {
        let addedCount = 0;
        let duplicateCount = 0;

        entriesList.forEach(entry => {
            const payload = entry.data;
            const isDuplicate = queue.some(item => {
                return item.type === entry.type &&
                       item.data.eventKey === payload.eventKey &&
                       item.data.targetTeamNumber === payload.targetTeamNumber &&
                       item.data.matchKey === payload.matchKey;
            });

            if (isDuplicate) {
                duplicateCount++;
                return;
            }

            const newItem = {
                id: Date.now() + "_" + Math.random().toString(36).substr(2, 9) + "_" + Math.random().toString(36).substr(2, 9),
                type: entry.type,
                data: payload,
                status: "pending",
                errorMsg: ""
            };

            queue.push(newItem);
            addedCount++;
        });

        if (addedCount > 0) {
            saveQueue();
            renderQueue();
            updateUploadButtonState();
            Obsidianscout.showToast(`Imported ${addedCount} entries to queue.`, "success");
        }

        if (duplicateCount > 0) {
            Obsidianscout.showToast(`${duplicateCount} duplicate entries skipped.`, "info");
        }

        if (addedCount === 0 && duplicateCount === 0) {
            Obsidianscout.showToast("No new valid entries found in files.", "error");
        }
    }

    // Connection Listeners
    window.addEventListener("online", () => {
        updateUploadButtonState();
        Obsidianscout.showToast("Online: Upload queue enabled", "info");
    });
    window.addEventListener("offline", () => {
        updateUploadButtonState();
        Obsidianscout.showToast("Offline: Upload queue disabled", "info");
    });
}
