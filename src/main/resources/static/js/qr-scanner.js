
function t(key, fallback) {
    return (window.Obsidianscout && typeof Obsidianscout.t === 'function') ? Obsidianscout.t(key, fallback) : fallback;
}

import QrScanner from '/vendor/qr-scanner.min.js';
QrScanner.WORKER_PATH = '/vendor/qr-scanner-worker.min.js';

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
    const readerContainer = document.getElementById("reader");

    let isScanning = false;
    let currentStream = null;
    let animationFrameId = null;
    let scanInProgress = false;
    let isSuccessState = false;
    let lastDetection = null;
    let frameCount = 0;

    let videoElement = null;
    let overlayCanvasElement = null;
    const offscreenCanvas = document.createElement("canvas");
    let jabInterface = null;

    if (typeof window.JabcodeJSInterface !== "undefined") {
        try {
            jabInterface = new window.JabcodeJSInterface();
        } catch (e) {
            console.error("Failed to initialize JabcodeJSInterface:", e);
        }
    }

    let queue = JSON.parse(localStorage.getItem("obsidianscout:scanned_qr_entries") || "[]");

    // Render initial queue
    renderQueue();
    updateUploadButtonState();

    // Check camera permission and list devices using QrScanner
    async function initCameraSourceList() {
        cameraSelect.innerHTML = '<option value="">Detecting cameras...</option>';
        toggleScanBtn.disabled = true;

        try {
            const cameras = await QrScanner.listCameras(true);
            cameraSelect.innerHTML = "";
            if (cameras && cameras.length > 0) {
                cameras.forEach(camera => {
                    const opt = document.createElement("option");
                    opt.value = camera.id;
                    opt.textContent = camera.label || `Camera ${cameraSelect.children.length + 1}`;
                    cameraSelect.appendChild(opt);
                });
                toggleScanBtn.disabled = false;
            } else {
                const opt = document.createElement("option");
                opt.value = "";
                opt.textContent = t('qr_scanner.no_camera_found', "No camera found");
                cameraSelect.appendChild(opt);
                toggleScanBtn.disabled = true;
            }
        } catch (err) {
            console.error("Camera detection error:", err);
            cameraSelect.innerHTML = '<option value="">Permission denied / No camera</option>';
            toggleScanBtn.disabled = true;
            Obsidianscout.showToast("Camera access permission denied", "error");
        }
    }

    initCameraSourceList();

    // Start/Stop Scan handler
    toggleScanBtn.addEventListener("click", () => {
        if (isScanning) {
            stopScanning();
        } else {
            const cameraId = cameraSelect.value;
            startScanning(cameraId);
        }
    });

    async function startScanning(cameraId) {
        toggleScanBtn.disabled = true;
        toggleScanBtn.textContent = t('qr_scanner.starting', "Starting...");

        // Create container wrapper for video and canvas overlay
        readerContainer.innerHTML = "";
        
        const wrapper = document.createElement("div");
        wrapper.style.position = "relative";
        wrapper.style.width = "100%";
        wrapper.style.overflow = "hidden";
        wrapper.style.borderRadius = "var(--radius-sm)";
        wrapper.style.boxShadow = "var(--shadow-md)";
        
        videoElement = document.createElement("video");
        videoElement.autoplay = true;
        videoElement.muted = true;
        videoElement.setAttribute("playsinline", "true");
        videoElement.style.width = "100%";
        videoElement.style.height = "auto";
        videoElement.style.display = "block";
        
        overlayCanvasElement = document.createElement("canvas");
        overlayCanvasElement.style.position = "absolute";
        overlayCanvasElement.style.top = "0";
        overlayCanvasElement.style.left = "0";
        overlayCanvasElement.style.width = "100%";
        overlayCanvasElement.style.height = "100%";
        overlayCanvasElement.style.pointerEvents = "none";
        
        wrapper.appendChild(videoElement);
        wrapper.appendChild(overlayCanvasElement);
        readerContainer.appendChild(wrapper);

        const constraints = {
            video: {
                deviceId: cameraId ? { exact: cameraId } : undefined,
                facingMode: cameraId ? undefined : "environment",
                width: { ideal: 800 },
                height: { ideal: 600 },
                resizeMode: "none"
            },
            audio: false
        };

        try {
            const stream = await navigator.mediaDevices.getUserMedia(constraints);
            currentStream = stream;
            videoElement.srcObject = stream;
            
            // Apply advanced constraints for focus and sharpness if supported
            const track = stream.getVideoTracks()[0];
            if (track) {
                try {
                    const capabilities = typeof track.getCapabilities === "function" ? track.getCapabilities() : {};
                    const adv = {};
                    
                    if (capabilities.focusMode) {
                        if (capabilities.focusMode.includes("continuous")) {
                            adv.focusMode = "continuous";
                        } else if (capabilities.focusMode.includes("manual")) {
                            adv.focusMode = "manual";
                        }
                    }
                    
                    if (capabilities.sharpness) {
                        adv.sharpness = capabilities.sharpness.max || 100;
                    }
                    
                    if (Object.keys(adv).length > 0) {
                        await track.applyConstraints({ advanced: [adv] });
                    }
                } catch (capErr) {
                    console.warn("Could not apply advanced media track constraints:", capErr);
                }
            }
            
            // Wait for video to start playing
            await new Promise((resolve) => {
                if (videoElement.readyState >= 1) {
                    resolve();
                } else {
                    videoElement.onloadedmetadata = () => {
                        resolve();
                    };
                }
            });
            await videoElement.play();
            
            isScanning = true;
            isSuccessState = false;
            scanInProgress = false;
            frameCount = 0;
            lastDetection = null;
            
            toggleScanBtn.disabled = false;
            toggleScanBtn.textContent = t('qr_scanner.stop_scanning', "Stop Scanning");
            toggleScanBtn.className = "btn ghost";
            cameraSelect.disabled = true;
            Obsidianscout.showToast("Scanner active", "success");

            // Continuous focus constraint
            try {
                const track = stream.getVideoTracks()[0];
                if (track && typeof track.getCapabilities === "function") {
                    const capabilities = track.getCapabilities();
                    if (capabilities.focusMode && capabilities.focusMode.includes("continuous")) {
                        await track.applyConstraints({ advanced: [{ focusMode: "continuous" }] });
                    }
                }
            } catch (focusErr) {
                console.warn("Continuous focus failed:", focusErr);
            }

            // Start frame loop
            startProcessingLoop();
        } catch (err) {
            console.error("Failed to start scanning:", err);
            toggleScanBtn.disabled = false;
            toggleScanBtn.textContent = t('qr_scanner.start_scanning', "Start Scanning");
            toggleScanBtn.className = "btn";
            cameraSelect.disabled = false;
            Obsidianscout.showToast("Failed to access camera stream", "error");
            
            // Clean up reader container
            readerContainer.innerHTML = `
                <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:240px; color:var(--muted); font-size:14px; background:rgba(0,0,0,0.02); border-radius:var(--radius-sm);">
                    <span>Scanner Inactive</span>
                </div>
            `;
        }
    }

    function stopScanning() {
        isScanning = false;
        stopProcessingLoop();

        if (currentStream) {
            currentStream.getTracks().forEach(track => {
                track.stop();
            });
            currentStream = null;
        }

        if (videoElement) {
            videoElement.srcObject = null;
            videoElement = null;
        }
        overlayCanvasElement = null;

        toggleScanBtn.disabled = false;
        toggleScanBtn.textContent = t('qr_scanner.start_scanning', "Start Scanning");
        toggleScanBtn.className = "btn";
        cameraSelect.disabled = false;

        // Render inactive status inside reader container
        readerContainer.innerHTML = `
            <div style="display:flex; flex-direction:column; align-items:center; justify-content:center; height:240px; color:var(--muted); font-size:14px; background:rgba(0,0,0,0.02); border-radius:var(--radius-sm);">
                <span>Scanner Inactive</span>
            </div>
        `;
    }

    function startProcessingLoop() {
        if (animationFrameId) {
            cancelAnimationFrame(animationFrameId);
        }
        animationFrameId = requestAnimationFrame(processFrame);
    }

    function stopProcessingLoop() {
        if (animationFrameId) {
            cancelAnimationFrame(animationFrameId);
            animationFrameId = null;
        }
    }

    async function processFrame() {
        if (!isScanning) return;

        if (videoElement && videoElement.readyState === videoElement.HAVE_ENOUGH_DATA) {
            frameCount++;

            const vw = videoElement.videoWidth;
            const vh = videoElement.videoHeight;

            // Resize overlay canvas to match display size of video element
            const displayWidth = videoElement.clientWidth;
            const displayHeight = videoElement.clientHeight;
            if (overlayCanvasElement.width !== displayWidth || overlayCanvasElement.height !== displayHeight) {
                overlayCanvasElement.width = displayWidth;
                overlayCanvasElement.height = displayHeight;
            }

            // Define scan region (center square)
            const scanBoxSizeInVideo = Math.min(vw, vh) * 0.7;
            const sx = (vw - scanBoxSizeInVideo) / 2;
            const sy = (vh - scanBoxSizeInVideo) / 2;

            const scanBoxSizeInDisplay = Math.min(displayWidth, displayHeight) * 0.7;
            const dx = (displayWidth - scanBoxSizeInDisplay) / 2;
            const dy = (displayHeight - scanBoxSizeInDisplay) / 2;

            // Draw HUD (mask, corners, laser)
            drawHUD(overlayCanvasElement, dx, dy, scanBoxSizeInDisplay);

            // If we are in success state, we draw the highlight
            if (isSuccessState) {
                drawHighlight();
            } else if (!scanInProgress) {
                // Perform scanning
                scanInProgress = true;
                
                // Scan cropped sharpened region 80% of the time, fallback to full frame 20% of the time
                const scanCropped = (frameCount % 3 !== 0);
                
                try {
                    if (scanCropped) {
                        offscreenCanvas.width = 400;
                        offscreenCanvas.height = 400;
                        const ctx = offscreenCanvas.getContext("2d");
                        ctx.imageSmoothingEnabled = false;
                        
                        // Draw raw color image for JAB Code scan first
                        ctx.drawImage(videoElement, sx, sy, scanBoxSizeInVideo, scanBoxSizeInVideo, 0, 0, 400, 400);
                        
                        let qrResult = null;
                        let jabResultText = null;

                        // Try JAB Code scan every 6 frames
                        if (jabInterface && frameCount % 6 === 0) {
                            try {
                                const dataUrl = offscreenCanvas.toDataURL("image/png");
                                jabResultText = await jabInterface.decode_message(dataUrl);
                            } catch (e) {
                                // JAB decode failed, normal behavior
                            }
                        }

                        if (jabResultText) {
                            handleJabScanResult(jabResultText);
                        } else {
                            // Apply filters for QR scan
                            ctx.filter = "grayscale(1) contrast(1.6) brightness(1.15)";
                            ctx.drawImage(videoElement, sx, sy, scanBoxSizeInVideo, scanBoxSizeInVideo, 0, 0, 400, 400);
                            ctx.filter = "none";
                            
                            // Sharpen and inline contrast boost
                            sharpenAndContrast(ctx, 400, 400);
                            
                            qrResult = await QrScanner.scanImage(offscreenCanvas, { returnDetailedScanResult: true });
                            if (qrResult && qrResult.data) {
                                handleScanResult(qrResult, sx, sy, scanBoxSizeInVideo, 400);
                            }
                        }
                    } else {
                        // Scan full frame (downscaled for performance)
                        const aspect = vw / vh;
                        const fullW = 600;
                        const fullH = Math.round(600 / aspect);
                        offscreenCanvas.width = fullW;
                        offscreenCanvas.height = fullH;
                        
                        const ctx = offscreenCanvas.getContext("2d");
                        ctx.imageSmoothingEnabled = false;
                        // Draw raw color image first
                        ctx.drawImage(videoElement, 0, 0, vw, vh, 0, 0, fullW, fullH);
                        
                        let qrResult = null;
                        let jabResultText = null;

                        // Try JAB Code scan every 6 frames
                        if (jabInterface && frameCount % 6 === 0) {
                            try {
                                const dataUrl = offscreenCanvas.toDataURL("image/png");
                                jabResultText = await jabInterface.decode_message(dataUrl);
                            } catch (e) {
                                // JAB decode failed, normal behavior
                            }
                        }

                        if (jabResultText) {
                            handleJabScanResult(jabResultText);
                        } else {
                            // Apply filters for QR scan
                            ctx.filter = "grayscale(1) contrast(1.4) brightness(1.1)";
                            ctx.drawImage(videoElement, 0, 0, vw, vh, 0, 0, fullW, fullH);
                            ctx.filter = "none";
                            
                            // Mild sharpen
                            sharpenAndContrast(ctx, fullW, fullH);
                            
                            qrResult = await QrScanner.scanImage(offscreenCanvas, { returnDetailedScanResult: true });
                            if (qrResult && qrResult.data) {
                                handleScanResult(qrResult, 0, 0, vw, fullW);
                            }
                        }
                    }
                } catch (err) {
                    // Silent failure is normal if no code is found
                } finally {
                    scanInProgress = false;
                }
            }
        }

        if (isScanning) {
            animationFrameId = requestAnimationFrame(processFrame);
        }
    }

    function sharpenAndContrast(ctx, width, height) {
        const imgData = ctx.getImageData(0, 0, width, height);
        const data = imgData.data;
        const output = new Uint8ClampedArray(data.length);
        const w4 = width * 4;

        // Copy everything first
        output.set(data);

        // Apply a fast 3x3 sharpening convolution with inline contrast stretching
        // Kernel:
        // [  0, -1,  0 ]
        // [ -1,  5, -1 ]
        // [  0, -1,  0 ]
        for (let y = 1; y < height - 1; y++) {
            const rowOffset = y * w4;
            for (let x = 1; x < width - 1; x++) {
                const idx = rowOffset + x * 4;

                for (let c = 0; c < 3; c++) {
                    const cidx = idx + c;
                    
                    // Sharpening formula
                    let val = 5 * data[cidx] - data[cidx - w4] - data[cidx - 4] - data[cidx + 4] - data[cidx + w4];
                    
                    // Inline contrast stretching (1.6x factor)
                    val = (val - 128) * 1.6 + 128;
                    
                    output[cidx] = val < 0 ? 0 : (val > 255 ? 255 : val);
                }
            }
        }
        ctx.putImageData(new ImageData(output, width, height), 0, 0);
    }

    function drawHUD(canvas, dx, dy, size) {
        const ctx = canvas.getContext("2d");
        const w = canvas.width;
        const h = canvas.height;

        ctx.clearRect(0, 0, w, h);

        // 1. Draw dark background mask outside scanning zone
        ctx.fillStyle = "rgba(12, 17, 23, 0.65)";
        ctx.fillRect(0, 0, w, dy); // Top
        ctx.fillRect(0, dy + size, w, h - (dy + size)); // Bottom
        ctx.fillRect(0, dy, dx, size); // Left
        ctx.fillRect(dx + size, dy, w - (dx + size), size); // Right

        // 2. Draw border around scanning square
        ctx.strokeStyle = "rgba(255, 255, 255, 0.15)";
        ctx.lineWidth = 1.5;
        ctx.strokeRect(dx, dy, size, size);

        // 3. Draw teal rounded corners
        ctx.strokeStyle = "#00ADB5"; // ObsidianScout Teal
        ctx.lineWidth = 4;
        ctx.lineCap = "round";
        const len = 24;
        const rad = 6;

        // Top-Left
        ctx.beginPath();
        ctx.moveTo(dx + len, dy);
        ctx.arcTo(dx, dy, dx, dy + len, rad);
        ctx.lineTo(dx, dy + len);
        ctx.stroke();

        // Top-Right
        ctx.beginPath();
        ctx.moveTo(dx + size - len, dy);
        ctx.arcTo(dx + size, dy, dx + size, dy + len, rad);
        ctx.lineTo(dx + size, dy + len);
        ctx.stroke();

        // Bottom-Left
        ctx.beginPath();
        ctx.moveTo(dx, dy + size - len);
        ctx.arcTo(dx, dy + size, dx + len, dy + size, rad);
        ctx.lineTo(dx + len, dy + size);
        ctx.stroke();

        // Bottom-Right
        ctx.beginPath();
        ctx.moveTo(dx + size, dy + size - len);
        ctx.arcTo(dx + size, dy + size, dx + size - len, dy + size, rad);
        ctx.lineTo(dx + size - len, dy + size);
        ctx.stroke();

        // 4. Sweeping laser line with sine-wave motion
        if (!isSuccessState) {
            const time = Date.now();
            const cycle = (time % 2200) / 2200; // 2.2 second cycle
            const sweepFactor = (Math.sin(cycle * Math.PI * 2 - Math.PI / 2) + 1) / 2;
            const ly = dy + sweepFactor * size;

            // Horizontal gradient for laser line
            const laserGrad = ctx.createLinearGradient(dx, ly, dx + size, ly);
            laserGrad.addColorStop(0, "rgba(0, 173, 181, 0)");
            laserGrad.addColorStop(0.2, "rgba(0, 173, 181, 0.7)");
            laserGrad.addColorStop(0.5, "rgba(255, 255, 255, 0.95)"); // Bright core
            laserGrad.addColorStop(0.8, "rgba(0, 173, 181, 0.7)");
            laserGrad.addColorStop(1, "rgba(0, 173, 181, 0)");

            ctx.strokeStyle = laserGrad;
            ctx.lineWidth = 2.5;
            ctx.beginPath();
            ctx.moveTo(dx, ly);
            ctx.lineTo(dx + size, ly);
            ctx.stroke();

            // Glow trailing aura
            const auraGrad = ctx.createLinearGradient(dx, ly - 12, dx, ly + 12);
            auraGrad.addColorStop(0, "rgba(0, 173, 181, 0)");
            auraGrad.addColorStop(0.5, "rgba(0, 173, 181, 0.12)");
            auraGrad.addColorStop(1, "rgba(0, 173, 181, 0)");
            ctx.fillStyle = auraGrad;
            ctx.fillRect(dx, ly - 12, size, 24);
        }
    }

    function drawHighlight() {
        if (!lastDetection) return;

        const ctx = overlayCanvasElement.getContext("2d");
        const displayWidth = overlayCanvasElement.width;
        const displayHeight = overlayCanvasElement.height;

        if (lastDetection.isJab) {
            // Draw green border around the center scan box
            const scanBoxSizeInDisplay = Math.min(displayWidth, displayHeight) * 0.7;
            const dx = (displayWidth - scanBoxSizeInDisplay) / 2;
            const dy = (displayHeight - scanBoxSizeInDisplay) / 2;
            
            ctx.fillStyle = "rgba(46, 204, 113, 0.25)";
            ctx.fillRect(dx, dy, scanBoxSizeInDisplay, scanBoxSizeInDisplay);
            
            ctx.strokeStyle = "#2ecc71";
            ctx.lineWidth = 3.5;
            ctx.strokeRect(dx, dy, scanBoxSizeInDisplay, scanBoxSizeInDisplay);
            return;
        }

        if (!lastDetection.cornerPoints) return;

        const pts = lastDetection.cornerPoints;
        const sx = lastDetection.sx;
        const sy = lastDetection.sy;
        const scanSizeInVideo = lastDetection.scanSizeInVideo;
        const processingSize = lastDetection.processingSize;

        const vw = videoElement.videoWidth;
        const vh = videoElement.videoHeight;

        const mappedPts = pts.map(pt => mapPoint(pt, sx, sy, scanSizeInVideo, processingSize, vw, vh, displayWidth, displayHeight));

        // 1. Draw glowing green background overlay inside the QR corners
        ctx.fillStyle = "rgba(46, 204, 113, 0.25)";
        ctx.beginPath();
        ctx.moveTo(mappedPts[0].x, mappedPts[0].y);
        for (let i = 1; i < mappedPts.length; i++) {
            ctx.lineTo(mappedPts[i].x, mappedPts[i].y);
        }
        ctx.closePath();
        ctx.fill();

        // 2. Draw thick green borders
        ctx.strokeStyle = "#2ecc71";
        ctx.lineWidth = 3.5;
        ctx.lineJoin = "round";
        ctx.beginPath();
        ctx.moveTo(mappedPts[0].x, mappedPts[0].y);
        for (let i = 1; i < mappedPts.length; i++) {
            ctx.lineTo(mappedPts[i].x, mappedPts[i].y);
        }
        ctx.closePath();
        ctx.stroke();
    }

    function mapPoint(pt, sx, sy, scanSizeInVideo, processingSize, vw, vh, displayWidth, displayHeight) {
        // Map from processing canvas coordinates to original video resolution
        const vx = sx + pt.x * (scanSizeInVideo / processingSize);
        const vy = sy + pt.y * (scanSizeInVideo / processingSize);

        // Map from original video resolution to overlay canvas display size
        const dx = vx * (displayWidth / vw);
        const dy = vy * (displayHeight / vh);

        return { x: dx, y: dy };
    }

    let multiStepSession = null;

    function handleScanResult(result, sx, sy, scanSizeInVideo, processingSize) {
        isSuccessState = true;
        lastDetection = {
            cornerPoints: result.cornerPoints,
            sx: sx,
            sy: sy,
            scanSizeInVideo: scanSizeInVideo,
            processingSize: processingSize
        };

        playBeep();

        setTimeout(() => {
            const data = result.data;
            onScanSuccess(data);
        }, 300);
    }

    function renderMultiStepBanner() {
        let bannerEl = document.getElementById("multi-step-progress-banner");
        if (!multiStepSession) {
            if (bannerEl) bannerEl.remove();
            return;
        }

        if (!bannerEl) {
            bannerEl = document.createElement("div");
            bannerEl.id = "multi-step-progress-banner";
            bannerEl.style.padding = "16px";
            bannerEl.style.marginBottom = "16px";
            bannerEl.style.border = "1px solid var(--primary-accent, #6366f1)";
            bannerEl.style.borderRadius = "16px";
            bannerEl.style.background = "rgba(18, 24, 38, 0.95)";
            bannerEl.style.boxShadow = "0 8px 32px rgba(0, 0, 0, 0.4)";
            
            if (readerContainer && readerContainer.parentNode) {
                readerContainer.parentNode.insertBefore(bannerEl, readerContainer);
            }
        }

        const total = multiStepSession.total;
        const scannedCount = Object.keys(multiStepSession.parts).length;
        const isComplete = scannedCount === total;

        let pillsHtml = "";
        for (let i = 1; i <= total; i++) {
            const isDone = !!multiStepSession.parts[i];
            pillsHtml += `
                <div style="
                    padding: 6px 12px;
                    border-radius: 20px;
                    font-size: 12px;
                    font-weight: 700;
                    display: inline-flex;
                    align-items: center;
                    gap: 6px;
                    background: ${isDone ? "rgba(46, 204, 113, 0.25)" : "rgba(255, 255, 255, 0.08)"};
                    color: ${isDone ? "#2ecc71" : "rgba(255, 255, 255, 0.6)"};
                    border: 1px solid ${isDone ? "#2ecc71" : "rgba(255, 255, 255, 0.15)"};
                ">
                    ${isDone ? "✔" : "⏳"} Part ${i} ${isDone ? "(Scanned)" : "(Pending)"}
                </div>
            `;
        }

        bannerEl.innerHTML = `
            <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 12px;">
                <div>
                    <h4 style="margin: 0; color: ${isComplete ? "#2ecc71" : "#ffffff"}; font-size: 15px; font-weight: 700;">
                        ${isComplete ? "🎉 Multi-Part Entry Complete!" : `📦 Multi-Part Scan in Progress (${scannedCount} of ${total} Scanned)`}
                    </h4>
                    <p style="margin: 4px 0 0 0; color: rgba(255,255,255,0.6); font-size: 12px;">
                        ${isComplete ? "Assembling and adding entry to queue..." : "Scan remaining parts in any order."}
                    </p>
                </div>
                <button type="button" id="btn-cancel-multi-step" class="btn ghost btn-sm" style="color: #e74c3c; border-color: rgba(231, 76, 60, 0.4); padding: 4px 10px; font-size: 12px;">
                    Cancel Scan
                </button>
            </div>
            <div style="display: flex; flex-wrap: wrap; gap: 8px;">
                ${pillsHtml}
            </div>
        `;

        const cancelBtn = document.getElementById("btn-cancel-multi-step");
        if (cancelBtn) {
            cancelBtn.addEventListener("click", () => {
                multiStepSession = null;
                renderMultiStepBanner();
                Obsidianscout.showToast("Multi-part scan cancelled", "info");
            });
        }
    }

    function handleScanResult(result, sx, sy, scanSizeInVideo, processingSize) {
        isSuccessState = true;
        lastDetection = {
            cornerPoints: result.cornerPoints,
            sx: sx,
            sy: sy,
            scanSizeInVideo: scanSizeInVideo,
            processingSize: processingSize
        };

        playBeep();

        setTimeout(() => {
            const data = result.data;
            onScanSuccess(data);
        }, 300);
    }

    function handleJabScanResult(decodedText) {
        isSuccessState = true;
        lastDetection = {
            isJab: true
        };

        playBeep();

        setTimeout(() => {
            onScanSuccess(decodedText);
        }, 300);
    }

    // Decode success handler
    async function onScanSuccess(decodedText) {
        try {
            const trimmed = decodedText.trim();
            let fullText = trimmed;

            if (trimmed.startsWith("OSC:PART:")) {
                const parts = trimmed.split(":");
                if (parts.length >= 5) {
                    const index = parseInt(parts[2], 10);
                    const total = parseInt(parts[3], 10);
                    const chunk = parts.slice(4).join(":");

                    if (!multiStepSession || multiStepSession.total !== total) {
                        multiStepSession = {
                            total: total,
                            parts: {},
                            timestamp: Date.now()
                        };
                    }

                    if (multiStepSession.parts[index]) {
                        Obsidianscout.showToast(`Part ${index} already scanned. Scan remaining part(s).`, "info");
                        setTimeout(() => { isSuccessState = false; }, 800);
                        return;
                    }

                    multiStepSession.parts[index] = chunk;
                    renderMultiStepBanner();

                    const scannedCount = Object.keys(multiStepSession.parts).length;
                    if (scannedCount < total) {
                        Obsidianscout.showToast(`Scanned Part ${index} of ${total}! Scan remaining part(s).`, "warning");
                        setTimeout(() => { isSuccessState = false; }, 800);
                        return;
                    }

                    // All parts collected! Assemble payload
                    const chunksArr = [];
                    for (let i = 1; i <= total; i++) {
                        chunksArr.push(multiStepSession.parts[i]);
                    }
                    fullText = "OSC:" + chunksArr.join("");
                    multiStepSession = null;
                    renderMultiStepBanner();
                }
            }

            stopScanning();
            const decompressedText = await Obsidianscout.decompressData(fullText);
            const entry = JSON.parse(decompressedText);
            
            // Check if this is an alliance bundle with multiple entries
            const bundleData = (entry && entry.data && (entry.data.type === "qual-alliance" || Array.isArray(entry.data.entries)))
                ? entry.data
                : entry;

            const isAllianceBundle = bundleData && (
                bundleData.type === "qual-alliance" ||
                (entry && entry.type === "qual-alliance") ||
                (Array.isArray(bundleData.entries) && bundleData.entries.length > 0)
            );

            if (isAllianceBundle) {
                const subEntries = Array.isArray(bundleData.entries)
                    ? bundleData.entries
                    : (Array.isArray(entry.entries) ? entry.entries : []);

                let addedCount = 0;
                let alreadyCount = 0;
                subEntries.forEach(sub => {
                    const payload = (sub && sub.data && typeof sub.data === "object") ? { ...sub.data } : (typeof sub === "object" && sub !== null ? { ...sub } : {});
                    const subType = (sub && sub.type) || (payload && payload.type) || "qual-scout";
                    const rawTeamNum = payload.targetTeamNumber || payload.teamNumber;
                    if (!rawTeamNum) return;

                    payload.targetTeamNumber = Number(rawTeamNum);
                    if (!payload.eventKey && bundleData.eventKey) payload.eventKey = bundleData.eventKey;
                    if (!payload.matchKey && bundleData.matchKey) payload.matchKey = bundleData.matchKey;
                    if (payload.matchNumber == null && bundleData.matchNumber != null) payload.matchNumber = bundleData.matchNumber;

                    const isDup = queue.some(item => {
                        const itemData = item.data || item;
                        return (item.type === subType || item.type === "qual-scout" || item.type === "qualitative-scouting") &&
                               String(itemData.eventKey || "") === String(payload.eventKey || "") &&
                               Number(itemData.targetTeamNumber) === Number(payload.targetTeamNumber) &&
                               String(itemData.matchKey || "") === String(payload.matchKey || "");
                    });

                    if (isDup) {
                        alreadyCount++;
                    } else {
                        queue.push({
                            id: Date.now() + "_" + Math.random().toString(36).substr(2, 9),
                            type: subType,
                            data: payload,
                            status: "pending",
                            errorMsg: ""
                        });
                        addedCount++;
                    }
                });
                saveQueue();
                renderQueue();
                updateUploadButtonState();
                if (addedCount > 0) {
                    const extra = alreadyCount > 0 ? ` (${alreadyCount} already in queue)` : "";
                    Obsidianscout.showToast(`Scanned alliance: ${addedCount} team entries added to queue${extra}`, "success");
                } else {
                    Obsidianscout.showToast(`All ${subEntries.length || ''} alliance entries already exist in queue`, "info");
                }
                return;
            }

            if (!entry || !entry.type || !entry.data) {
                throw new Error("Invalid scouting QR schema");
            }

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
        } finally {
            setTimeout(() => { isSuccessState = false; }, 800);
        }
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
        if (!type) return "/api/scouting";
        const t = String(type).toLowerCase().replace(/_/g, '-');
        
        if (t === 'scout' || t === 'match-scout' || t === 'match-scouting' || t === 'match') {
            return "/api/scouting";
        }
        if (t === 'pit-scout' || t === 'pit-scouting' || t === 'pit') {
            return "/api/pit-scouting";
        }
        if (t === 'qual-scout' || t === 'qual-scouting' || t === 'qualitative-scouting' || t === 'qual') {
            return "/api/qual-scouting";
        }
        if (t === 'prescout-scout' || t === 'prescout-match') {
            return "/api/prescout/scouting";
        }
        if (t === 'prescout-pit') {
            return "/api/prescout/pit-scouting";
        }
        if (t === 'prescout-qual') {
            return "/api/prescout/qual-scouting";
        }
        return "/api/scouting";
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
        uploadQueueBtn.textContent = t('qr_scanner.uploading', "Uploading...");

        let successCount = 0;
        let failCount = 0;

        for (const item of queue) {
            if (item.status === "success") continue;

            const endpoint = getEndpoint(item.type);

            try {
                item.status = "pending";
                renderQueue();

                const rawData = (item.data && typeof item.data === 'object' && item.data.data) 
                    ? item.data.data 
                    : (item.data ? item.data : item);

                await Obsidianscout.request(endpoint, {
                    method: "POST",
                    json: {
                        data: rawData
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

        uploadQueueBtn.textContent = t('qr_scanner.upload_queue', "Upload Queue");
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

        fileInput.addEventListener("click", (e) => {
            e.stopPropagation();
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

    // Paste Import Logic
    const pasteInput = document.getElementById("paste-input");
    const submitPasteBtn = document.getElementById("btn-submit-paste");

    if (pasteInput && submitPasteBtn) {
        submitPasteBtn.addEventListener("click", async () => {
            const rawText = pasteInput.value.trim();
            if (!rawText) {
                Obsidianscout.showToast("Please paste some data first", "error");
                return;
            }

            try {
                // Try decompressing first if it starts with the compressed prefix
                let decompressedText = rawText;
                if (rawText.startsWith("OSC:")) {
                    decompressedText = await Obsidianscout.decompressData(rawText);
                }

                // Parse as JSON
                let parsed = JSON.parse(decompressedText);
                
                // Process either a list or single entry (flatten any alliance bundles)
                let items = Array.isArray(parsed) ? parsed : [parsed];
                const flattened = [];
                items.forEach(it => {
                    const bundle = (it && it.data && (it.data.type === 'qual-alliance' || Array.isArray(it.data.entries))) ? it.data : it;
                    if (bundle && Array.isArray(bundle.entries)) {
                        bundle.entries.forEach(sub => {
                            const subObj = (typeof sub === 'object' && sub !== null) ? { ...sub } : {};
                            if (!subObj.type) subObj.type = 'qual-scout';
                            if (!subObj.eventKey && bundle.eventKey) subObj.eventKey = bundle.eventKey;
                            if (!subObj.matchKey && bundle.matchKey) subObj.matchKey = bundle.matchKey;
                            if (subObj.matchNumber == null && bundle.matchNumber != null) subObj.matchNumber = bundle.matchNumber;
                            flattened.push(subObj);
                        });
                    } else {
                        flattened.push(it);
                    }
                });
                const entriesToProcess = [];

                flattened.forEach(item => {
                    const normalized = normalizeImportedItem(item, "pasted_data.json");
                    if (normalized) {
                        entriesToProcess.push(normalized);
                    }
                });

                if (entriesToProcess.length > 0) {
                    processEntriesList(entriesToProcess);
                    pasteInput.value = ""; // Clear input on success
                } else {
                    Obsidianscout.showToast("No valid scout entries found in pasted data", "error");
                }
            } catch (err) {
                console.error("Paste import failed:", err);
                Obsidianscout.showToast("Invalid QR data or JSON format", "error");
            }
        });
    }

    function handleImportedFiles(files) {
        if (!files || files.length === 0) return;
        
        const pasteInput = document.getElementById("paste-input");
        const submitPasteBtn = document.getElementById("btn-submit-paste");
        if (!pasteInput || !submitPasteBtn) return;

        Array.from(files).forEach(file => {
            const reader = new FileReader();
            reader.onload = (e) => {
                pasteInput.value = e.target.result;
                submitPasteBtn.click();
            };
            reader.onerror = () => {
                Obsidianscout.showToast(`Error reading ${file.name}`, "error");
            };
            reader.readAsText(file);
        });
    }

    function normalizeImportedItem(item, filename) {
        if (!item || typeof item !== 'object') {
            console.warn("Import warning: Item is not an object", item);
            return null;
        }

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

        // Case 1: Standard QR format { type: "...", data: { ... } } (might also have top-level metadata)
        if (item.type && item.data && typeof item.data === 'object') {
            Object.assign(payload, item.data);
            if (!payload.eventKey && item.eventKey) payload.eventKey = item.eventKey;
            if (!payload.targetTeamNumber && item.targetTeamNumber) payload.targetTeamNumber = Number(item.targetTeamNumber);
            if (!payload.matchKey && item.matchKey) payload.matchKey = item.matchKey;
            if (!payload.matchNumber && item.matchNumber !== undefined) payload.matchNumber = Number(item.matchNumber);
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

        // Helper to normalize different naming conventions for metadata keys
        const getOptionalString = (obj, keys) => {
            for (const key of keys) {
                if (obj[key] !== undefined && obj[key] !== null) {
                    return String(obj[key]).trim();
                }
            }
            return null;
        };

        const getOptionalNumber = (obj, keys) => {
            for (const key of keys) {
                if (obj[key] !== undefined && obj[key] !== null) {
                    const num = Number(obj[key]);
                    if (!isNaN(num)) return num;
                }
            }
            return null;
        };

        // Extract and normalize metadata keys
        const eventKey = getOptionalString(payload, ["eventKey", "event_key", "event", "event_code", "eventCode"]) || getOptionalString(item, ["eventKey", "event_key", "event", "event_code", "eventCode"]);
        const targetTeamNumber = getOptionalNumber(payload, ["targetTeamNumber", "target_team_number", "teamNumber", "team_number", "team", "targetTeam", "target_team"]) || getOptionalNumber(item, ["targetTeamNumber", "target_team_number", "teamNumber", "team_number", "team", "targetTeam", "target_team"]);
        const matchKey = getOptionalString(payload, ["matchKey", "match_key", "match", "match_code", "matchCode"]) || getOptionalString(item, ["matchKey", "match_key", "match", "match_code", "matchCode"]);
        const matchNumber = getOptionalNumber(payload, ["matchNumber", "match_number"]) || getOptionalNumber(item, ["matchNumber", "match_number"]);

        if (eventKey) payload.eventKey = eventKey;
        if (targetTeamNumber) payload.targetTeamNumber = targetTeamNumber;
        if (matchKey) payload.matchKey = matchKey;
        if (matchNumber !== null) payload.matchNumber = matchNumber;

        // Sanity check metadata
        if (!payload.eventKey || !payload.targetTeamNumber) {
            console.warn("Import warning: Missing eventKey or targetTeamNumber in item", {
                hasEventKey: !!payload.eventKey,
                hasTargetTeamNumber: !!payload.targetTeamNumber,
                item: item,
                extractedPayload: payload
            });
            return null;
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
                return item &&
                       item.data &&
                       item.type === entry.type &&
                       (item.data.eventKey || "") === (payload.eventKey || "") &&
                       Number(item.data.targetTeamNumber) === Number(payload.targetTeamNumber) &&
                       (item.data.matchKey || "") === (payload.matchKey || "");
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
