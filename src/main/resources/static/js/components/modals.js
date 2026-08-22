/**
 * Component Modals Module - ObsidianScout
 * Full-screen image lightbox preview modal and inline camera video viewfinder capture modal.
 */

import { showToast } from './toast.js';
import { processImageUpload } from '../utilities/media.js';

/**
 * Displays a full-screen image lightbox dialog for inspecting robot mechanism photos.
 */
export function showImageModal(imageSrc, title = "Robot Photo Preview") {
    if (!imageSrc) return;
    const modal = document.createElement("div");
    modal.style.cssText = "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.9);z-index:999999;display:flex;flex-direction:column;align-items:center;justify-content:center;padding:20px;backdrop-filter:blur(8px);";

    const topBar = document.createElement("div");
    topBar.style.cssText = "width:100%;max-width:900px;display:flex;align-items:center;justify-content:space-between;margin-bottom:12px;color:#f8fafc;";
    topBar.innerHTML = `
        <div style="font-weight:700;font-size:1.1rem;display:flex;align-items:center;gap:8px;">
            <span>${title}</span>
        </div>
        <div style="display:flex;align-items:center;gap:12px;">
            <a href="${imageSrc}" download="robot_photo.jpg" class="btn btn-sm" style="background:rgba(255,255,255,0.1);color:#fff;text-decoration:none;padding:6px 12px;border-radius:8px;font-size:0.85rem;">Download</a>
            <button type="button" class="btn-close-lightbox" style="background:none;border:none;color:#cbd5e1;font-size:1.6rem;cursor:pointer;padding:2px 8px;">✕</button>
        </div>
    `;

    const imgContainer = document.createElement("div");
    imgContainer.style.cssText = "max-width:900px;max-height:80vh;display:flex;align-items:center;justify-content:center;border-radius:12px;overflow:hidden;border:1px solid rgba(255,255,255,0.15);background:#000;";

    const img = document.createElement("img");
    img.src = imageSrc;
    img.style.cssText = "max-width:100%;max-height:80vh;object-fit:contain;";

    imgContainer.appendChild(img);
    modal.appendChild(topBar);
    modal.appendChild(imgContainer);
    document.body.appendChild(modal);

    const closeFn = () => {
        if (modal.parentElement) modal.parentElement.removeChild(modal);
    };
    topBar.querySelector(".btn-close-lightbox").addEventListener("click", closeFn);
    modal.addEventListener("click", (e) => {
        if (e.target === modal) closeFn();
    });
}

/**
 * Opens an inline camera viewfinder modal with live video stream, environment/user toggle,
 * and one-tap capture that automatically cleans and downscales the photo.
 */
export function openInlineCameraModal(options = {}) {
    const onCapture = options.onCapture || (() => {});
    let currentStream = null;
    let currentFacingMode = "environment";

    const modalBackdrop = document.createElement("div");
    modalBackdrop.className = "obsidian-modal-backdrop";
    modalBackdrop.style.cssText = "position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.85);z-index:99999;display:flex;align-items:center;justify-content:center;padding:16px;backdrop-filter:blur(6px);";

    const modalBox = document.createElement("div");
    modalBox.className = "card";
    modalBox.style.cssText = "width:100%;max-width:560px;background:#0f172a;border:1px solid rgba(255,255,255,0.15);border-radius:16px;padding:20px;display:flex;flex-direction:column;gap:14px;box-shadow:0 25px 50px -12px rgba(0,0,0,0.7);";

    const header = document.createElement("div");
    header.style.cssText = "display:flex;align-items:center;justify-content:space-between;";
    header.innerHTML = `
        <div style="display:flex;align-items:center;gap:8px;">
            <h3 style="margin:0;font-size:1.1rem;font-weight:700;color:#f8fafc;">Inline Camera Capture</h3>
        </div>
        <button type="button" class="btn-close-cam" style="background:none;border:none;color:#94a3b8;font-size:1.4rem;cursor:pointer;padding:4px 8px;">✕</button>
    `;

    const videoContainer = document.createElement("div");
    videoContainer.style.cssText = "position:relative;width:100%;background:#000;border-radius:12px;overflow:hidden;min-height:280px;max-height:60vh;display:flex;align-items:center;justify-content:center;";

    const video = document.createElement("video");
    video.autoplay = true;
    video.playsInline = true;
    video.muted = true;
    video.style.cssText = "width:100%;height:100%;object-fit:cover;";

    const loadingOverlay = document.createElement("div");
    loadingOverlay.style.cssText = "position:absolute;color:#94a3b8;font-size:0.9rem;display:flex;flex-direction:column;align-items:center;gap:8px;";
    loadingOverlay.innerHTML = `<span class="btn-spinner" style="display:inline-block;width:24px;height:24px;border:3px solid rgba(255,255,255,0.2);border-top-color:#38bdf8;border-radius:50%;animation:spin 0.8s linear infinite;"></span><span>Accessing camera...</span>`;

    videoContainer.appendChild(video);
    videoContainer.appendChild(loadingOverlay);

    const controls = document.createElement("div");
    controls.style.cssText = "display:flex;align-items:center;justify-content:space-between;gap:12px;margin-top:4px;";

    const btnFlip = document.createElement("button");
    btnFlip.type = "button";
    btnFlip.className = "btn";
    btnFlip.style.cssText = "background:rgba(255,255,255,0.08);color:#f8fafc;border:1px solid rgba(255,255,255,0.12);padding:10px 14px;border-radius:10px;cursor:pointer;display:flex;align-items:center;gap:6px;font-size:0.85rem;font-weight:600;";
    btnFlip.innerHTML = `Flip Camera`;

    const btnCapture = document.createElement("button");
    btnCapture.type = "button";
    btnCapture.className = "btn primary";
    btnCapture.style.cssText = "flex:1;background:linear-gradient(135deg,#0284c7,#0369a1);color:#fff;border:none;padding:12px 20px;border-radius:10px;cursor:pointer;font-weight:700;font-size:0.95rem;display:flex;align-items:center;justify-content:center;gap:8px;box-shadow:0 4px 14px rgba(2,132,199,0.4);";
    btnCapture.innerHTML = `Capture Snapshot`;

    controls.appendChild(btnFlip);
    controls.appendChild(btnCapture);

    modalBox.appendChild(header);
    modalBox.appendChild(videoContainer);
    modalBox.appendChild(controls);
    modalBackdrop.appendChild(modalBox);
    document.body.appendChild(modalBackdrop);

    function stopCurrentStream() {
        if (currentStream) {
            currentStream.getTracks().forEach(track => track.stop());
            currentStream = null;
        }
    }

    async function startCamera(facingMode = "environment") {
        stopCurrentStream();
        loadingOverlay.style.display = "flex";
        try {
            const stream = await navigator.mediaDevices.getUserMedia({
                video: {
                    facingMode: { ideal: facingMode },
                    width: { ideal: 1920 },
                    height: { ideal: 1080 }
                },
                audio: false
            });
            currentStream = stream;
            video.srcObject = stream;
            video.onloadedmetadata = () => {
                video.play();
                loadingOverlay.style.display = "none";
            };
        } catch (err) {
            console.error("Camera access error:", err);
            loadingOverlay.innerHTML = `<span style="color:#ef4444;text-align:center;padding:12px;">⚠️ Camera permission denied or device not found.<br/><small style="color:#94a3b8;">${err.message || ""}</small></span>`;
        }
    }

    startCamera(currentFacingMode);

    btnFlip.addEventListener("click", () => {
        currentFacingMode = (currentFacingMode === "environment") ? "user" : "environment";
        startCamera(currentFacingMode);
    });

    function closeModal() {
        stopCurrentStream();
        if (modalBackdrop.parentElement) {
            modalBackdrop.parentElement.removeChild(modalBackdrop);
        }
    }

    header.querySelector(".btn-close-cam").addEventListener("click", closeModal);
    modalBackdrop.addEventListener("click", (e) => {
        if (e.target === modalBackdrop) closeModal();
    });

    btnCapture.addEventListener("click", async () => {
        if (!video.videoWidth || !video.videoHeight) {
            showToast("Camera stream not ready yet", "error");
            return;
        }
        btnCapture.disabled = true;
        btnCapture.innerHTML = `<span class="btn-spinner"></span> Processing...`;

        try {
            const canvas = document.createElement("canvas");
            canvas.width = video.videoWidth;
            canvas.height = video.videoHeight;
            const ctx = canvas.getContext("2d");
            ctx.drawImage(video, 0, 0, canvas.width, canvas.height);

            canvas.toBlob(async (blob) => {
                if (!blob) {
                    showToast("Failed to capture image frame", "error");
                    btnCapture.disabled = false;
                    return;
                }
                try {
                    const result = await processImageUpload(blob, options.maxDimension || 540, options.quality || 0.65);
                    closeModal();
                    onCapture(result);
                } catch (err) {
                    showToast(err.message || "Failed to process captured image", "error");
                    btnCapture.disabled = false;
                }
            }, "image/jpeg", 0.95);
        } catch (err) {
            showToast(err.message || "Failed to snap photo", "error");
            btnCapture.disabled = false;
        }
    });
}
