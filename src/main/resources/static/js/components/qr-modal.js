/**
 * Component QR Modal Module - ObsidianScout
 * Generates single or multi-part animated/chunked QR code modals for transferring scouting data offline.
 */

import { safeGetItem, safeSetItem } from '../base/storage.js';
import { showToast } from './toast.js';
import { t } from '../base/i18n.js';
import { compressAndChunkData } from '../services/data-compression.js';

export async function compressImageForQr(dataUrl, maxDimension = 320, quality = 0.45) {
    return new Promise((resolve) => {
        if (!dataUrl || typeof dataUrl !== 'string' || !dataUrl.startsWith('data:image/')) {
            return resolve(dataUrl);
        }
        const img = new Image();
        img.onload = () => {
            let width = img.naturalWidth || img.width;
            let height = img.naturalHeight || img.height;
            if (width > maxDimension || height > maxDimension) {
                if (width > height) {
                    height = Math.round((height * maxDimension) / width);
                    width = maxDimension;
                } else {
                    width = Math.round((width * maxDimension) / height);
                    height = maxDimension;
                }
            }
            const canvas = document.createElement("canvas");
            canvas.width = width;
            canvas.height = height;
            const ctx = canvas.getContext("2d");
            ctx.imageSmoothingEnabled = true;
            ctx.drawImage(img, 0, 0, width, height);
            resolve(canvas.toDataURL("image/jpeg", quality));
        };
        img.onerror = () => resolve(dataUrl);
        img.src = dataUrl;
    });
}

export async function preparePayloadForQr(rawPayload) {
    if (!rawPayload) return rawPayload;
    try {
        const cloned = JSON.parse(JSON.stringify(rawPayload));
        const traverse = async (obj) => {
            if (!obj || typeof obj !== 'object') return;
            for (const k of Object.keys(obj)) {
                const val = obj[k];
                if (typeof val === 'string' && val.startsWith('data:image/')) {
                    obj[k] = await compressImageForQr(val, 320, 0.45);
                } else if (val && typeof val === 'object') {
                    await traverse(val);
                }
            }
        };
        await traverse(cloned);
        return cloned;
    } catch (_) {
        return rawPayload;
    }
}

export async function showQrModal(payload, typeLabel, teamNum, matchKey) {
    if (typeof QRCode === 'undefined') {
        showToast("QR Library not loaded", "error");
        return;
    }

    let backdrop = document.getElementById("qr-modal-backdrop");
    if (!backdrop) {
        backdrop = document.createElement("div");
        backdrop.id = "qr-modal-backdrop";
        backdrop.className = "modal-backdrop";
        document.body.appendChild(backdrop);
    }

    const matchHtml = matchKey ? `<p><strong data-i18n="qr.match">Match:</strong> <span>${matchKey}</span></p>` : '';
    let displayTeams = teamNum;
    if (payload && Array.isArray(payload.entries) && payload.entries.length > 0) {
        const extracted = payload.entries
            .map(e => e.targetTeamNumber || e.teamNumber)
            .filter(Boolean);
        if (extracted.length > 0) {
            displayTeams = extracted.join(', ');
        }
    }
    const isMultiTeam = String(displayTeams).includes(',');
    const teamLabelText = isMultiTeam ? 'Teams:' : 'Team:';
    const optimizedPayload = await preparePayloadForQr(payload);
    const qrPayload = {
        type: optimizedPayload.type || typeLabel.toLowerCase().replace(/\s+/g, '-'),
        data: optimizedPayload
    };
    const qrString = JSON.stringify(qrPayload);

    let currentChunkSize = parseInt(safeGetItem("obsidianscout:qr_max_chunk_size") || "550", 10);
    if (isNaN(currentChunkSize) || currentChunkSize < 50) currentChunkSize = 550;

    async function renderQrGrid(chunkSize) {
        const qrChunks = await compressAndChunkData(qrString, chunkSize);
        const isMulti = qrChunks.length > 1;

        const modalContainer = backdrop.querySelector(".modal-container");
        if (modalContainer) {
            modalContainer.style.maxWidth = isMulti ? '680px' : '480px';
        }

        const titleEl = backdrop.querySelector(".modal-title");
        if (titleEl) {
            const baseTitle = t('qr.title', 'Scouting Entry QR Code');
            titleEl.textContent = `${baseTitle} ${isMulti ? `(Grid of ${qrChunks.length} Parts)` : ''}`;
        }

        const container = document.getElementById("qr-code-canvas-container");
        if (!container) return;
        container.innerHTML = "";

        qrChunks.forEach((chunkText, idx) => {
            const qrCard = document.createElement("div");
            qrCard.style.display = "flex";
            qrCard.style.flexDirection = "column";
            qrCard.style.alignItems = "center";
            qrCard.style.background = "#ffffff";
            qrCard.style.padding = "12px";
            qrCard.style.borderRadius = "16px";
            qrCard.style.boxShadow = "0 8px 24px rgba(0,0,0,0.3)";

            if (isMulti) {
                const badge = document.createElement("div");
                badge.style.background = "var(--primary-accent, #6366f1)";
                badge.style.color = "#ffffff";
                badge.style.fontSize = "11px";
                badge.style.fontWeight = "bold";
                badge.style.padding = "2px 8px";
                badge.style.borderRadius = "6px";
                badge.style.marginBottom = "8px";
                badge.textContent = `Part ${idx + 1} of ${qrChunks.length}`;
                qrCard.appendChild(badge);
            }

            const qrEl = document.createElement("div");
            qrCard.appendChild(qrEl);
            container.appendChild(qrCard);

            new QRCode(qrEl, {
                text: chunkText,
                width: isMulti ? 200 : 320,
                height: isMulti ? 200 : 320,
                colorDark: "#000000",
                colorLight: "#ffffff",
                correctLevel: QRCode.CorrectLevel.M
            });
        });
    }

    backdrop.innerHTML = `
        <div class="modal-container" style="max-width: 480px; transition: max-width 0.3s ease;">
            <div class="modal-header">
                <h3 class="modal-title" data-i18n="qr.title">Scouting Entry QR Code</h3>
                <button class="modal-close" id="qr-modal-close-btn">&times;</button>
            </div>
            <div class="modal-body qr-modal-body">
                <div class="qr-size-controls">
                    <label for="qr-max-size-select" data-i18n="qr.max_size_label">Max QR Code Size:</label>
                    <select id="qr-max-size-select" class="qr-size-select">
                        <option value="150" ${currentChunkSize === 150 ? 'selected' : ''} data-i18n="qr.size_150">150 chars (Small - Easiest Scan)</option>
                        <option value="250" ${currentChunkSize === 250 ? 'selected' : ''} data-i18n="qr.size_250">250 chars (Medium-Low)</option>
                        <option value="350" ${currentChunkSize === 350 ? 'selected' : ''} data-i18n="qr.size_350">350 chars (Medium)</option>
                        <option value="450" ${currentChunkSize === 450 ? 'selected' : ''} data-i18n="qr.size_450">450 chars (Standard - Default)</option>
                        <option value="600" ${currentChunkSize === 600 ? 'selected' : ''} data-i18n="qr.size_600">600 chars (Large)</option>
                        <option value="800" ${currentChunkSize === 800 ? 'selected' : ''} data-i18n="qr.size_800">800 chars (Max Density)</option>
                    </select>
                </div>
                <div class="qr-code-wrapper" id="qr-code-canvas-container" style="min-height: 320px; display: flex; flex-wrap: wrap; gap: 16px; align-items: center; justify-content: center; padding: 12px;"></div>
                <div class="qr-details" style="margin-top: 16px;">
                    <p><strong data-i18n="qr.type">Type:</strong> <span>${typeLabel}</span></p>
                    <p><strong>${teamLabelText}</strong> <span>${displayTeams}</span></p>
                    ${matchHtml}
                </div>
            </div>
            <div class="modal-footer">
                <button class="btn ghost" id="qr-modal-close-footer-btn" data-i18n="qr.close">Close</button>
            </div>
        </div>
    `;

    await renderQrGrid(currentChunkSize);

    backdrop.classList.add("show");

    const selectEl = document.getElementById("qr-max-size-select");
    if (selectEl) {
        selectEl.addEventListener("change", async (e) => {
            const newSize = parseInt(e.target.value, 10);
            if (!isNaN(newSize)) {
                safeSetItem("obsidianscout:qr_max_chunk_size", newSize.toString());
                await renderQrGrid(newSize);
            }
        });
    }

    const closeBtn = document.getElementById("qr-modal-close-btn");
    const closeFooterBtn = document.getElementById("qr-modal-close-footer-btn");

    const closeModal = () => {
        backdrop.classList.remove("show");
    };

    closeBtn.addEventListener("click", closeModal);
    closeFooterBtn.addEventListener("click", closeModal);
}
