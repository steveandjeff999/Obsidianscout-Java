/**
 * Obsidian3DGraph — Custom zero-dependency cross-platform 3D graphing engine.
 * Shared verbatim between the ObsidianScout Web Site and Flutter App (WebView).
 *
 * Supported graphType values:
 *   "scatter"   - 3D scatter markers with labels & hover tooltips
 *   "bar"       - 3D extruded shaded columns from base plane to Z
 *   "line"      - 3D polylines per series
 *   "area"      - 3D translucent vertical ribbons/curtains under 3D lines
 *   "box"       - 3D box-and-whisker plots along Z at each series' (X, Y)
 *   "violin"    - 3D KDE violin bodies (per-series Z distribution) or 3D KDE density surface
 *   "density"   - 3D bivariate KDE density surface across (X, Y) -> Z density
 *   "histogram" - 3D bivariate (X, Y) binned frequency columns along Z
 */
(function (global) {
    "use strict";

    const DEFAULT_PALETTE = [
        "#0b8f88", "#255a9c", "#d97706", "#dc2626", "#7c3aed",
        "#059669", "#db2777", "#0284c7", "#ea580c", "#4f46e5"
    ];

    const DEFAULT_CAMERA = {
        yaw: -0.68,
        pitch: 0.42,
        zoom: 1.0,
        panX: 0,
        panY: 0
    };

    const PRESET_CAMERAS = {
        iso: { yaw: -0.68, pitch: 0.42, zoom: 1.0, panX: 0, panY: 0 },
        top: { yaw: 0.0, pitch: Math.PI / 2 - 0.001, zoom: 1.05, panX: 0, panY: 0 },
        front: { yaw: 0.0, pitch: 0.0, zoom: 1.05, panX: 0, panY: 0 },
        side: { yaw: -Math.PI / 2, pitch: 0.0, zoom: 1.05, panX: 0, panY: 0 }
    };

    function clamp(v, min, max) {
        return Math.max(min, Math.min(max, v));
    }

    function formatNum(v) {
        if (!Number.isFinite(v)) return "0";
        if (Math.abs(v) >= 1000) return v.toFixed(0);
        if (Math.abs(v) >= 100) return v.toFixed(1);
        if (Math.abs(v - Math.round(v)) < 1e-6) return String(Math.round(v));
        return v.toFixed(1);
    }

    function hexToRgb(hex) {
        const clean = String(hex || "#0b8f88").replace("#", "");
        if (clean.length === 3) {
            return {
                r: parseInt(clean[0] + clean[0], 16),
                g: parseInt(clean[1] + clean[1], 16),
                b: parseInt(clean[2] + clean[2], 16)
            };
        }
        const num = parseInt(clean.slice(0, 6), 16);
        if (Number.isNaN(num)) return { r: 11, g: 143, b: 136 };
        return { r: (num >> 16) & 255, g: (num >> 8) & 255, b: num & 255 };
    }

    function rgba(hex, alpha) {
        if (String(hex).startsWith("rgba") || String(hex).startsWith("rgb")) {
            return hex;
        }
        const { r, g, b } = hexToRgb(hex);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }

    function shadeColor(hex, factor) {
        const { r, g, b } = hexToRgb(hex);
        const f = clamp(factor, 0.25, 1.45);
        return `rgb(${clamp(Math.round(r * f), 0, 255)}, ${clamp(Math.round(g * f), 0, 255)}, ${clamp(Math.round(b * f), 0, 255)})`;
    }

    function resolveTheme(customTheme) {
        const dark = customTheme && typeof customTheme.dark === "boolean"
            ? customTheme.dark
            : (typeof document !== "undefined" && document.body && document.body.classList.contains("theme-dark"));
        return Object.assign({
            dark,
            bg: dark ? "#0f1419" : "#ffffff",
            text: dark ? "#e7edf3" : "#1d1a17",
            muted: dark ? "rgba(231, 237, 243, 0.70)" : "rgba(29, 26, 23, 0.65)",
            grid: dark ? "rgba(255, 255, 255, 0.14)" : "rgba(0, 0, 0, 0.11)",
            axis: dark ? "rgba(255, 255, 255, 0.45)" : "rgba(0, 0, 0, 0.42)",
            plane: dark ? "rgba(255, 255, 255, 0.03)" : "rgba(0, 0, 0, 0.025)",
            labelBg: dark ? "rgba(15, 20, 25, 0.78)" : "rgba(255, 255, 255, 0.84)",
            labelBorder: dark ? "rgba(255, 255, 255, 0.14)" : "rgba(0, 0, 0, 0.12)",
            tooltipBg: dark ? "rgba(18, 24, 32, 0.95)" : "rgba(255, 255, 255, 0.97)",
            tooltipBorder: dark ? "rgba(255, 255, 255, 0.2)" : "rgba(0, 0, 0, 0.16)",
            palette: DEFAULT_PALETTE
        }, customTheme || {});
    }

    function computeBounds(points, graphType, histogramBins, densityGrid) {
        let minX = Infinity, maxX = -Infinity;
        let minY = Infinity, maxY = -Infinity;
        let minZ = Infinity, maxZ = -Infinity;

        for (const p of points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
            if (p.z < minZ) minZ = p.z;
            if (p.z > maxZ) maxZ = p.z;
        }

        if (!Number.isFinite(minX)) {
            return { minX: 0, maxX: 1, minY: 0, maxY: 1, minZ: 0, maxZ: 1 };
        }

        if (graphType === "histogram" && histogramBins && histogramBins.length) {
            minZ = 0;
            maxZ = Math.max(1, ...histogramBins.map(b => b.count));
        } else if (graphType === "density" && densityGrid && densityGrid.maxVal > 0) {
            minZ = 0;
            maxZ = densityGrid.maxVal;
        } else if (graphType === "bar" || graphType === "area") {
            if (minZ > 0) minZ = 0;
            if (maxZ < 0) maxZ = 0;
        }

        const pad = (min, max) => {
            const span = max - min;
            if (span < 1e-9) {
                const delta = Math.abs(min) * 0.15 || 1;
                return [min - delta, max + delta];
            }
            return [min - span * 0.06, max + span * 0.06];
        };

        const [x0, x1] = pad(minX, maxX);
        const [y0, y1] = pad(minY, maxY);
        const [z0, z1] = (graphType === "bar" || graphType === "histogram" || graphType === "density")
            ? [minZ, maxZ + Math.max((maxZ - minZ) * 0.08, 1e-6)]
            : pad(minZ, maxZ);

        return { minX: x0, maxX: x1, minY: y0, maxY: y1, minZ: z0, maxZ: z1 };
    }

    function normalizePoint(x, y, z, b) {
        const nx = ((x - b.minX) / (b.maxX - b.minX || 1)) * 2 - 1;
        const ny = ((y - b.minY) / (b.maxY - b.minY || 1)) * 2 - 1;
        const nz = ((z - b.minZ) / (b.maxZ - b.minZ || 1)) * 2 - 1;
        return { x: nx, y: ny, z: nz };
    }

    function project3D(nx, ny, nz, cam, width, height) {
        const cosY = Math.cos(cam.yaw);
        const sinY = Math.sin(cam.yaw);
        const cosP = Math.cos(cam.pitch);
        const sinP = Math.sin(cam.pitch);

        const rx = nx * cosY - ny * sinY;
        const ry = nx * sinY + ny * cosY;

        const ry2 = ry * cosP - nz * sinP;
        const rz2 = ry * sinP + nz * cosP;

        const fov = 4.6;
        const dist = fov + ry2;
        const persp = dist > 0.45 ? (fov / dist) : (fov / 0.45);
        const scale = Math.min(width, height) * 0.34 * cam.zoom * persp;

        const sx = width * 0.5 + cam.panX + rx * scale;
        const sy = height * 0.53 + cam.panY - rz2 * scale;
        return { sx, sy, depth: ry2, scale };
    }

    function computeQuantileStats(values) {
        if (!values.length) return null;
        const s = values.slice().sort((a, b) => a - b);
        const n = s.length;
        const q = (p) => {
            if (n === 1) return s[0];
            const pos = p * (n - 1);
            const lo = Math.floor(pos);
            const hi = Math.ceil(pos);
            return s[lo] + (s[hi] - s[lo]) * (pos - lo);
        };
        const q1 = q(0.25);
        const median = q(0.5);
        const q3 = q(0.75);
        const iqr = q3 - q1;
        const loFence = q1 - 1.5 * iqr;
        const hiFence = q3 + 1.5 * iqr;
        const inliers = s.filter(v => v >= loFence && v <= hiFence);
        const outliers = s.filter(v => v < loFence || v > hiFence);
        return {
            min: inliers.length ? inliers[0] : s[0],
            max: inliers.length ? inliers[inliers.length - 1] : s[n - 1],
            q1,
            median,
            q3,
            mean: s.reduce((a, b) => a + b, 0) / n,
            outliers
        };
    }

    function compute1DKDE(values, samples = 24) {
        if (values.length === 0) return [];
        const min = Math.min(...values);
        const max = Math.max(...values);
        const span = max - min;
        if (span < 1e-9) return [{ z: min, d: 1 }];
        const mean = values.reduce((a, b) => a + b, 0) / values.length;
        const std = Math.sqrt(values.reduce((acc, v) => acc + (v - mean) ** 2, 0) / values.length) || (span * 0.25);
        const bw = Math.max(1.06 * std * Math.pow(values.length, -0.2), span * 0.06);
        const out = [];
        let maxD = 0;
        for (let i = 0; i <= samples; i++) {
            const z = min + (span * i) / samples;
            let sum = 0;
            for (const v of values) {
                const u = (z - v) / bw;
                sum += Math.exp(-0.5 * u * u);
            }
            const d = sum / (values.length * bw * Math.sqrt(2 * Math.PI));
            if (d > maxD) maxD = d;
            out.push({ z, d });
        }
        if (maxD > 0) {
            for (const item of out) item.d /= maxD;
        }
        return out;
    }

    function computeBivariateHistogram(points, binCount = 7) {
        if (!points.length) return [];
        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const p of points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        const spanX = maxX - minX || 1;
        const spanY = maxY - minY || 1;
        const grid = new Map();
        for (const p of points) {
            const ix = Math.min(binCount - 1, Math.max(0, Math.floor(((p.x - minX) / spanX) * binCount)));
            const iy = Math.min(binCount - 1, Math.max(0, Math.floor(((p.y - minY) / spanY) * binCount)));
            const key = `${ix},${iy}`;
            if (!grid.has(key)) {
                grid.set(key, {
                    ix, iy,
                    cx: minX + ((ix + 0.5) / binCount) * spanX,
                    cy: minY + ((iy + 0.5) / binCount) * spanY,
                    dx: spanX / binCount,
                    dy: spanY / binCount,
                    count: 0,
                    teams: new Set()
                });
            }
            const cell = grid.get(key);
            cell.count += 1;
            if (p.label) cell.teams.add(p.label);
        }
        return Array.from(grid.values());
    }

    function compute2DKDEGrid(points, gridSize = 14) {
        if (!points.length) return { rows: [], maxVal: 0 };
        let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
        for (const p of points) {
            if (p.x < minX) minX = p.x;
            if (p.x > maxX) maxX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.y > maxY) maxY = p.y;
        }
        const spanX = maxX - minX || 1;
        const spanY = maxY - minY || 1;
        const hx = Math.max(spanX * 0.16, 1e-5);
        const hy = Math.max(spanY * 0.16, 1e-5);

        const rows = [];
        let maxVal = 0;
        for (let ix = 0; ix <= gridSize; ix++) {
            const row = [];
            const gx = minX + (spanX * ix) / gridSize;
            for (let iy = 0; iy <= gridSize; iy++) {
                const gy = minY + (spanY * iy) / gridSize;
                let d = 0;
                for (const p of points) {
                    const ux = (gx - p.x) / hx;
                    const uy = (gy - p.y) / hy;
                    d += Math.exp(-0.5 * (ux * ux + uy * uy));
                }
                if (d > maxVal) maxVal = d;
                row.push({ x: gx, y: gy, z: d });
            }
            rows.push(row);
        }
        return { rows, maxVal };
    }

    /**
     * Draw a 3D shaded rectangular prism using front-face culling so internal/back
     * faces never bleed through or invert when orbiting underneath.
     */
    function drawBoxPrism(ctx, cx, cy, z0, z1, hx, hy, color, cam, b, w, h, alpha = 0.94) {
        const corners = [
            [-1, -1, 0], [1, -1, 0], [1, 1, 0], [-1, 1, 0],
            [-1, -1, 1], [1, -1, 1], [1, 1, 1], [-1, 1, 1]
        ].map(([dx, dy, dz]) => {
            const n = normalizePoint(cx + dx * hx, cy + dy * hy, dz ? z1 : z0, b);
            return project3D(n.x, n.y, n.z, cam, w, h);
        });

        // Winding order oriented outward: screen-space cross product > 0 means front-facing
        const faces = [
            { idx: [4, 7, 6, 5], shade: 1.18 }, // top (+Z)
            { idx: [0, 1, 2, 3], shade: 0.62 }, // bottom (-Z)
            { idx: [0, 4, 5, 1], shade: 0.94 }, // -Y
            { idx: [1, 5, 6, 2], shade: 0.80 }, // +X
            { idx: [2, 6, 7, 3], shade: 0.88 }, // +Y
            { idx: [3, 7, 4, 0], shade: 0.74 }  // -X
        ];

        const visible = [];
        for (const f of faces) {
            const p0 = corners[f.idx[0]];
            const p1 = corners[f.idx[1]];
            const p2 = corners[f.idx[2]];
            const cross = (p1.sx - p0.sx) * (p2.sy - p0.sy) - (p1.sy - p0.sy) * (p2.sx - p0.sx);
            if (cross <= 0) continue; // back-face culled
            const avgDepth = f.idx.reduce((acc, i) => acc + corners[i].depth, 0) / 4;
            visible.push({ ...f, avgDepth });
        }

        visible.sort((a, c) => c.avgDepth - a.avgDepth);

        for (const f of visible) {
            ctx.beginPath();
            ctx.moveTo(corners[f.idx[0]].sx, corners[f.idx[0]].sy);
            for (let k = 1; k < 4; k++) {
                ctx.lineTo(corners[f.idx[k]].sx, corners[f.idx[k]].sy);
            }
            ctx.closePath();
            ctx.globalAlpha = alpha;
            ctx.fillStyle = shadeColor(color, f.shade);
            ctx.fill();
            ctx.globalAlpha = 1;
            ctx.strokeStyle = shadeColor(color, 0.52);
            ctx.lineWidth = 0.85;
            ctx.stroke();
        }
    }

    class Obsidian3DInstance {
        constructor(container, initialConfig) {
            this.container = container;
            this.config = initialConfig || {};
            this.camera = Object.assign({}, DEFAULT_CAMERA, initialConfig && initialConfig.camera);
            this.showLabels = initialConfig && typeof initialConfig.showLabels === "boolean"
                ? initialConfig.showLabels
                : true;
            this.hoverTarget = null;
            this.hitTargets = [];
            this.placedLabelBoxes = [];
            this.rafId = null;

            this._setupDOM();
            this._bindEvents();
            this.update(this.config);
        }

        _setupDOM() {
            this.container.innerHTML = "";
            this.container.style.position = "relative";
            this.container.style.userSelect = "none";
            this.container.style.touchAction = "none";

            this.canvas = document.createElement("canvas");
            this.canvas.style.width = "100%";
            this.canvas.style.height = "100%";
            this.canvas.style.display = "block";
            this.canvas.style.borderRadius = "10px";
            this.container.appendChild(this.canvas);

            this.toolbar = document.createElement("div");
            this.toolbar.style.cssText = "position:absolute;top:8px;right:8px;display:flex;gap:4px;z-index:5;flex-wrap:wrap;";

            this.labelsBtn = document.createElement("button");
            this.labelsBtn.type = "button";
            this._syncLabelsBtnStyle();
            this.labelsBtn.addEventListener("click", (e) => {
                e.stopPropagation();
                this.showLabels = !this.showLabels;
                this._syncLabelsBtnStyle();
                this.requestRender();
            });
            this.toolbar.appendChild(this.labelsBtn);

            const presets = [
                ["iso", "Iso"],
                ["top", "Top"],
                ["front", "Front"],
                ["side", "Side"]
            ];
            for (const [key, label] of presets) {
                const btn = document.createElement("button");
                btn.type = "button";
                btn.textContent = label;
                btn.style.cssText = "font-size:11px;padding:3px 7px;border-radius:6px;border:1px solid rgba(128,128,128,0.35);background:rgba(20,25,32,0.58);color:#fff;cursor:pointer;backdrop-filter:blur(4px);";
                btn.addEventListener("click", (e) => {
                    e.stopPropagation();
                    this.camera = Object.assign({}, PRESET_CAMERAS[key]);
                    this.requestRender();
                });
                this.toolbar.appendChild(btn);
            }
            this.container.appendChild(this.toolbar);

            this.hint = document.createElement("div");
            this.hint.textContent = "Drag: Orbit · Scroll/Pinch: Zoom · Right-drag: Pan · Double-click: Reset";
            this.hint.style.cssText = "position:absolute;bottom:8px;left:10px;font-size:11px;opacity:0.6;pointer-events:none;";
            this.container.appendChild(this.hint);

            this.ctx = this.canvas.getContext("2d");

            if (typeof ResizeObserver !== "undefined") {
                this.resizeObserver = new ResizeObserver(() => this.requestRender());
                this.resizeObserver.observe(this.container);
            }
        }

        _syncLabelsBtnStyle() {
            if (!this.labelsBtn) return;
            this.labelsBtn.textContent = this.showLabels ? "Labels: On" : "Labels: Off";
            this.labelsBtn.style.cssText = this.showLabels
                ? "font-size:11px;padding:3px 8px;border-radius:6px;border:1px solid rgba(11,143,136,0.8);background:rgba(11,143,136,0.78);color:#fff;cursor:pointer;font-weight:600;backdrop-filter:blur(4px);"
                : "font-size:11px;padding:3px 8px;border-radius:6px;border:1px solid rgba(128,128,128,0.35);background:rgba(20,25,32,0.58);color:#fff;cursor:pointer;backdrop-filter:blur(4px);";
        }

        _bindEvents() {
            let dragging = false;
            let mode = "orbit";
            let lastX = 0;
            let lastY = 0;
            let pinchDist = null;
            const activePointers = new Map();

            const pointerDown = (e) => {
                activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
                if (this.canvas.setPointerCapture) {
                    try { this.canvas.setPointerCapture(e.pointerId); } catch (_) {}
                }
                if (activePointers.size === 2) {
                    const pts = Array.from(activePointers.values());
                    pinchDist = Math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y);
                    lastX = (pts[0].x + pts[1].x) / 2;
                    lastY = (pts[0].y + pts[1].y) / 2;
                    mode = "pinch";
                    return;
                }
                dragging = true;
                mode = (e.button === 2 || e.shiftKey) ? "pan" : "orbit";
                lastX = e.clientX;
                lastY = e.clientY;
            };

            const pointerMove = (e) => {
                if (activePointers.has(e.pointerId)) {
                    activePointers.set(e.pointerId, { x: e.clientX, y: e.clientY });
                }

                if (activePointers.size === 2 && mode === "pinch") {
                    const pts = Array.from(activePointers.values());
                    const dist = Math.hypot(pts[1].x - pts[0].x, pts[1].y - pts[0].y);
                    const midX = (pts[0].x + pts[1].x) / 2;
                    const midY = (pts[0].y + pts[1].y) / 2;
                    if (pinchDist && pinchDist > 0) {
                        this.camera.zoom = clamp(this.camera.zoom * (dist / pinchDist), 0.35, 4.5);
                    }
                    this.camera.panX += midX - lastX;
                    this.camera.panY += midY - lastY;
                    pinchDist = dist;
                    lastX = midX;
                    lastY = midY;
                    this.requestRender();
                    return;
                }

                if (dragging) {
                    const dx = e.clientX - lastX;
                    const dy = e.clientY - lastY;
                    lastX = e.clientX;
                    lastY = e.clientY;
                    if (mode === "pan") {
                        this.camera.panX += dx;
                        this.camera.panY += dy;
                    } else {
                        this.camera.yaw += dx * 0.0085;
                        this.camera.pitch = clamp(this.camera.pitch + dy * 0.0085, -Math.PI / 2 + 0.02, Math.PI / 2 - 0.02);
                    }
                    this.requestRender();
                    return;
                }

                this._updateHover(e.clientX, e.clientY);
            };

            const pointerUp = (e) => {
                activePointers.delete(e.pointerId);
                if (activePointers.size < 2) pinchDist = null;
                if (activePointers.size === 0) dragging = false;
                if (!dragging && e.pointerType === "touch") {
                    this._updateHover(e.clientX, e.clientY);
                }
            };

            const onWheel = (e) => {
                e.preventDefault();
                const factor = e.deltaY < 0 ? 1.1 : 0.91;
                this.camera.zoom = clamp(this.camera.zoom * factor, 0.35, 4.5);
                this.requestRender();
            };

            const onContext = (e) => e.preventDefault();
            const onDblClick = () => {
                this.camera = Object.assign({}, DEFAULT_CAMERA);
                this.requestRender();
            };

            this.canvas.addEventListener("pointerdown", pointerDown);
            this.canvas.addEventListener("pointermove", pointerMove);
            this.canvas.addEventListener("pointerup", pointerUp);
            this.canvas.addEventListener("pointercancel", pointerUp);
            this.canvas.addEventListener("wheel", onWheel, { passive: false });
            this.canvas.addEventListener("contextmenu", onContext);
            this.canvas.addEventListener("dblclick", onDblClick);

            this._cleanupEvents = () => {
                this.canvas.removeEventListener("pointerdown", pointerDown);
                this.canvas.removeEventListener("pointermove", pointerMove);
                this.canvas.removeEventListener("pointerup", pointerUp);
                this.canvas.removeEventListener("pointercancel", pointerUp);
                this.canvas.removeEventListener("wheel", onWheel);
                this.canvas.removeEventListener("contextmenu", onContext);
                this.canvas.removeEventListener("dblclick", onDblClick);
            };
        }

        _updateHover(clientX, clientY) {
            const rect = this.canvas.getBoundingClientRect();
            const mx = clientX - rect.left;
            const my = clientY - rect.top;
            let best = null;
            let bestDist = 20;
            for (const t of this.hitTargets) {
                const d = Math.hypot(t.sx - mx, t.sy - my);
                if (d < bestDist) {
                    bestDist = d;
                    best = t;
                }
            }
            if (best !== this.hoverTarget) {
                this.hoverTarget = best;
                this.requestRender();
            }
        }

        update(nextConfig) {
            this.config = Object.assign({}, this.config, nextConfig || {});
            if (nextConfig && typeof nextConfig.showLabels === "boolean") {
                this.showLabels = nextConfig.showLabels;
                this._syncLabelsBtnStyle();
            }
            this.theme = resolveTheme(this.config.theme);
            this.hint.style.color = this.theme.text;
            this.requestRender();
        }

        requestRender() {
            if (this.rafId !== null) return;
            this.rafId = requestAnimationFrame(() => {
                this.rafId = null;
                this.render();
            });
        }

        render() {
            const rect = this.container.getBoundingClientRect();
            const w = Math.max(200, Math.floor(rect.width || this.container.clientWidth || 640));
            const h = Math.max(220, Math.floor(rect.height || this.container.clientHeight || 460));
            const dpr = Math.min(window.devicePixelRatio || 1, 2);

            if (this.canvas.width !== Math.floor(w * dpr) || this.canvas.height !== Math.floor(h * dpr)) {
                this.canvas.width = Math.floor(w * dpr);
                this.canvas.height = Math.floor(h * dpr);
            }

            const ctx = this.ctx;
            ctx.save();
            ctx.scale(dpr, dpr);

            ctx.fillStyle = this.theme.bg;
            ctx.fillRect(0, 0, w, h);

            const series = Array.isArray(this.config.series) ? this.config.series : [];
            const graphType = String(this.config.graphType || "scatter").toLowerCase();
            const xLabel = this.config.xLabel || "X";
            const yLabel = this.config.yLabel || "Y";
            const zLabel = (graphType === "histogram" || graphType === "density")
                ? (this.config.zLabelOverride || (graphType === "histogram" ? "Count" : "Density"))
                : (this.config.zLabel || "Z");

            const allPoints = [];
            series.forEach((s, sIdx) => {
                const color = s.color || this.theme.palette[sIdx % this.theme.palette.length];
                for (const p of (s.points || [])) {
                    if (Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z)) {
                        allPoints.push({ ...p, seriesName: s.name || "", color });
                    }
                }
            });

            if (allPoints.length === 0) {
                ctx.fillStyle = this.theme.muted;
                ctx.font = "14px system-ui, -apple-system, sans-serif";
                ctx.textAlign = "center";
                ctx.fillText("No numeric 3D data available for these axes.", w / 2, h / 2);
                ctx.restore();
                return;
            }

            const histogramBins = graphType === "histogram" ? computeBivariateHistogram(allPoints, 7) : null;
            const densityGrid = graphType === "density" ? compute2DKDEGrid(allPoints, 14) : null;
            const bounds = computeBounds(allPoints, graphType, histogramBins, densityGrid);

            this.hitTargets = [];
            this.placedLabelBoxes = [];

            // Draw wireframe & floor grid behind geometry
            this._drawWireframeAndGrid(ctx, w, h);

            // Draw 3D geometry
            const pendingLabels = [];
            if (graphType === "bar") {
                this._renderBars(ctx, allPoints, bounds, w, h, pendingLabels);
            } else if (graphType === "line" || graphType === "area") {
                this._renderLinesOrAreas(ctx, series, bounds, w, h, graphType === "area", pendingLabels);
            } else if (graphType === "box") {
                this._renderBoxes(ctx, series, allPoints, bounds, w, h, pendingLabels);
            } else if (graphType === "violin") {
                this._renderViolins(ctx, series, allPoints, bounds, w, h, pendingLabels);
            } else if (graphType === "density") {
                this._renderDensitySurface(ctx, densityGrid, bounds, w, h);
            } else if (graphType === "histogram") {
                this._renderHistogram(ctx, histogramBins, bounds, w, h, pendingLabels);
            } else {
                this._renderScatter(ctx, allPoints, bounds, w, h, pendingLabels);
            }

            // Draw axis ticks & titles AFTER geometry so they are never hidden behind bars
            this._drawAxisAnnotations(ctx, bounds, xLabel, yLabel, zLabel, w, h);

            // Draw toggleable data labels with collision avoidance
            if (this.showLabels && pendingLabels.length) {
                this._drawDataLabels(ctx, pendingLabels, w, h);
            }

            this._drawLegend(ctx, series, w);
            this._drawTooltip(ctx, w, h, xLabel, yLabel, zLabel);
            ctx.restore();
        }

        _drawWireframeAndGrid(ctx, w, h) {
            const t = this.theme;
            const p000 = project3D(-1, -1, -1, this.camera, w, h);
            const p100 = project3D(1, -1, -1, this.camera, w, h);
            const p110 = project3D(1, 1, -1, this.camera, w, h);
            const p010 = project3D(-1, 1, -1, this.camera, w, h);
            const p001 = project3D(-1, -1, 1, this.camera, w, h);
            const p101 = project3D(1, -1, 1, this.camera, w, h);
            const p111 = project3D(1, 1, 1, this.camera, w, h);
            const p011 = project3D(-1, 1, 1, this.camera, w, h);

            ctx.beginPath();
            ctx.moveTo(p000.sx, p000.sy);
            ctx.lineTo(p100.sx, p100.sy);
            ctx.lineTo(p110.sx, p110.sy);
            ctx.lineTo(p010.sx, p010.sy);
            ctx.closePath();
            ctx.fillStyle = t.plane;
            ctx.fill();

            ctx.strokeStyle = t.grid;
            ctx.lineWidth = 1;
            const steps = 4;
            for (let i = 0; i <= steps; i++) {
                const v = -1 + (2 * i) / steps;
                const gx0 = project3D(v, -1, -1, this.camera, w, h);
                const gx1 = project3D(v, 1, -1, this.camera, w, h);
                ctx.beginPath();
                ctx.moveTo(gx0.sx, gx0.sy);
                ctx.lineTo(gx1.sx, gx1.sy);
                ctx.stroke();

                const gy0 = project3D(-1, v, -1, this.camera, w, h);
                const gy1 = project3D(1, v, -1, this.camera, w, h);
                ctx.beginPath();
                ctx.moveTo(gy0.sx, gy0.sy);
                ctx.lineTo(gy1.sx, gy1.sy);
                ctx.stroke();
            }

            const edges = [
                [p000, p100], [p100, p110], [p110, p010], [p010, p000],
                [p001, p101], [p101, p111], [p111, p011], [p011, p001],
                [p000, p001], [p100, p101], [p110, p111], [p010, p011]
            ];
            ctx.strokeStyle = t.grid;
            for (const [a, c] of edges) {
                ctx.beginPath();
                ctx.moveTo(a.sx, a.sy);
                ctx.lineTo(c.sx, c.sy);
                ctx.stroke();
            }

            ctx.strokeStyle = t.axis;
            ctx.lineWidth = 1.6;
            for (const [a, c] of [[p000, p100], [p000, p010], [p000, p001]]) {
                ctx.beginPath();
                ctx.moveTo(a.sx, a.sy);
                ctx.lineTo(c.sx, c.sy);
                ctx.stroke();
            }
        }

        _drawPillText(ctx, text, x, y, font, textColor, paddingX = 6, paddingY = 3) {
            ctx.font = font;
            const tw = ctx.measureText(text).width;
            const bw = tw + paddingX * 2;
            const bh = 16 + paddingY;
            ctx.fillStyle = this.theme.labelBg;
            ctx.strokeStyle = this.theme.labelBorder;
            ctx.lineWidth = 0.8;
            ctx.beginPath();
            ctx.roundRect(x - bw / 2, y - bh / 2, bw, bh, 5);
            ctx.fill();
            ctx.stroke();
            ctx.fillStyle = textColor;
            ctx.textAlign = "center";
            ctx.textBaseline = "middle";
            ctx.fillText(text, x, y);
            ctx.textBaseline = "alphabetic";
        }

        _drawAxisAnnotations(ctx, b, xLabel, yLabel, zLabel, w, h) {
            const t = this.theme;
            ctx.font = "11px system-ui, -apple-system, sans-serif";
            ctx.fillStyle = t.muted;
            ctx.textAlign = "center";

            for (let i = 0; i <= 2; i++) {
                const frac = i / 2;
                const n = -1 + 2 * frac;
                const vx = b.minX + (b.maxX - b.minX) * frac;
                const px = project3D(n, -1.14, -1.04, this.camera, w, h);
                ctx.fillText(formatNum(vx), px.sx, px.sy);

                const vy = b.minY + (b.maxY - b.minY) * frac;
                const py = project3D(-1.14, n, -1.04, this.camera, w, h);
                ctx.fillText(formatNum(vy), py.sx, py.sy);

                const vz = b.minZ + (b.maxZ - b.minZ) * frac;
                const pz = project3D(-1.18, -1.18, n, this.camera, w, h);
                ctx.fillText(formatNum(vz), pz.sx, pz.sy);
            }

            const titleFont = "bold 11.5px system-ui, -apple-system, sans-serif";
            const lx = project3D(0, -1.36, -1.08, this.camera, w, h);
            this._drawPillText(ctx, `X: ${xLabel}`, clamp(lx.sx, 70, w - 70), clamp(lx.sy, 22, h - 24), titleFont, t.text);

            const ly = project3D(-1.36, 0, -1.08, this.camera, w, h);
            this._drawPillText(ctx, `Y: ${yLabel}`, clamp(ly.sx, 70, w - 70), clamp(ly.sy, 22, h - 24), titleFont, t.text);

            const lz = project3D(-1.32, -1.32, 0.25, this.camera, w, h);
            this._drawPillText(ctx, `Z: ${zLabel}`, clamp(lz.sx, 65, w - 65), clamp(lz.sy, 22, h - 24), titleFont, t.text);
        }

        _drawDataLabels(ctx, labels, w, h) {
            // Sort front-to-back (smallest depth first) so closer items get label priority
            const sorted = labels.slice().sort((a, b) => a.depth - b.depth);
            ctx.font = "10.5px system-ui, -apple-system, sans-serif";

            for (const item of sorted) {
                const text = item.text;
                if (!text) continue;
                const tw = ctx.measureText(text).width;
                const bw = tw + 10;
                const bh = 16;
                const lx = clamp(item.sx - bw / 2, 6, w - bw - 6);
                const ly = clamp(item.sy - bh - 8, 6, h - bh - 24);

                // Overlap check against already placed labels
                let overlaps = false;
                for (const box of this.placedLabelBoxes) {
                    if (lx < box.x + box.w + 3 && lx + bw + 3 > box.x &&
                        ly < box.y + box.h + 3 && ly + bh + 3 > box.y) {
                        overlaps = true;
                        break;
                    }
                }
                if (overlaps) continue;

                this.placedLabelBoxes.push({ x: lx, y: ly, w: bw, h: bh });

                ctx.fillStyle = this.theme.labelBg;
                ctx.strokeStyle = item.color ? rgba(item.color, 0.65) : this.theme.labelBorder;
                ctx.lineWidth = 1;
                ctx.beginPath();
                ctx.roundRect(lx, ly, bw, bh, 4);
                ctx.fill();
                ctx.stroke();

                ctx.fillStyle = this.theme.text;
                ctx.textAlign = "center";
                ctx.textBaseline = "middle";
                ctx.fillText(text, lx + bw / 2, ly + bh / 2);
                ctx.textBaseline = "alphabetic";
            }
        }

        _renderScatter(ctx, points, b, w, h, pendingLabels) {
            const projected = points.map((p, idx) => {
                const n = normalizePoint(p.x, p.y, p.z, b);
                const proj = project3D(n.x, n.y, n.z, this.camera, w, h);
                const floorProj = project3D(n.x, n.y, -1, this.camera, w, h);
                return { ...p, ...proj, floorProj, idx };
            }).sort((a, c) => c.depth - a.depth);

            for (const pt of projected) {
                ctx.strokeStyle = rgba(pt.color, 0.22);
                ctx.lineWidth = 1;
                ctx.setLineDash([3, 3]);
                ctx.beginPath();
                ctx.moveTo(pt.sx, pt.sy);
                ctx.lineTo(pt.floorProj.sx, pt.floorProj.sy);
                ctx.stroke();
                ctx.setLineDash([]);

                const isHover = this.hoverTarget && this.hoverTarget.point === pt;
                const radius = isHover ? 8.5 : 5.8;

                ctx.beginPath();
                ctx.arc(pt.sx, pt.sy, radius, 0, Math.PI * 2);
                ctx.fillStyle = pt.color;
                ctx.fill();
                ctx.lineWidth = isHover ? 2.2 : 1.2;
                ctx.strokeStyle = this.theme.bg;
                ctx.stroke();

                const name = pt.label ? `${pt.label}${pt.matchLabel ? " " + pt.matchLabel : ""}` : "";
                pendingLabels.push({
                    sx: pt.sx,
                    sy: pt.sy,
                    depth: pt.depth,
                    color: pt.color,
                    text: `${name ? name + ": " : ""}(${formatNum(pt.x)}, ${formatNum(pt.y)}, ${formatNum(pt.z)})`
                });

                this.hitTargets.push({ sx: pt.sx, sy: pt.sy, point: pt });
            }
        }

        _renderBars(ctx, points, b, w, h, pendingLabels) {
            const baseZ = (b.minZ <= 0 && b.maxZ >= 0) ? 0 : b.minZ;
            const hx = (b.maxX - b.minX) * 0.028;
            const hy = (b.maxY - b.minY) * 0.028;

            const items = points.map((p) => {
                const zLow = Math.min(baseZ, p.z);
                const zHigh = Math.max(baseZ, p.z);
                const nCenter = normalizePoint(p.x, p.y, (zLow + zHigh) * 0.5, b);
                const centerProj = project3D(nCenter.x, nCenter.y, nCenter.z, this.camera, w, h);
                const nTop = normalizePoint(p.x, p.y, p.z, b);
                const topProj = project3D(nTop.x, nTop.y, nTop.z, this.camera, w, h);
                return { p, zLow, zHigh, centerDepth: centerProj.depth, topProj };
            }).sort((a, c) => c.centerDepth - a.centerDepth);

            for (const { p, zLow, zHigh, centerDepth, topProj } of items) {
                drawBoxPrism(ctx, p.x, p.y, zLow, zHigh, hx, hy, p.color, this.camera, b, w, h, 0.94);
                const name = p.label ? `${p.label}${p.matchLabel ? " " + p.matchLabel : ""}` : "";
                pendingLabels.push({
                    sx: topProj.sx,
                    sy: topProj.sy,
                    depth: centerDepth,
                    color: p.color,
                    text: `${name ? name + ": " : ""}(${formatNum(p.x)}, ${formatNum(p.y)}, ${formatNum(p.z)})`
                });
                this.hitTargets.push({ sx: topProj.sx, sy: topProj.sy, point: p });
            }
        }

        _renderLinesOrAreas(ctx, series, b, w, h, withArea, pendingLabels) {
            const baseZ = (b.minZ <= 0 && b.maxZ >= 0) ? 0 : b.minZ;
            const projectedSeries = series.map((s, sIdx) => {
                const color = s.color || this.theme.palette[sIdx % this.theme.palette.length];
                const pts = (s.points || []).filter(p => Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z)).map(p => {
                    const nTop = normalizePoint(p.x, p.y, p.z, b);
                    const nBot = normalizePoint(p.x, p.y, baseZ, b);
                    const top = project3D(nTop.x, nTop.y, nTop.z, this.camera, w, h);
                    const bot = project3D(nBot.x, nBot.y, nBot.z, this.camera, w, h);
                    return { ...p, color, top, bot };
                });
                const avgDepth = pts.length ? pts.reduce((acc, p) => acc + p.top.depth, 0) / pts.length : 0;
                return { name: s.name, color, pts, avgDepth };
            }).sort((a, c) => c.avgDepth - a.avgDepth);

            for (const s of projectedSeries) {
                if (!s.pts.length) continue;
                if (withArea && s.pts.length >= 2) {
                    for (let i = 0; i < s.pts.length - 1; i++) {
                        const a = s.pts[i];
                        const c = s.pts[i + 1];
                        ctx.beginPath();
                        ctx.moveTo(a.top.sx, a.top.sy);
                        ctx.lineTo(c.top.sx, c.top.sy);
                        ctx.lineTo(c.bot.sx, c.bot.sy);
                        ctx.lineTo(a.bot.sx, a.bot.sy);
                        ctx.closePath();
                        ctx.fillStyle = rgba(s.color, 0.28);
                        ctx.fill();
                    }
                }

                ctx.beginPath();
                s.pts.forEach((pt, idx) => {
                    if (idx === 0) ctx.moveTo(pt.top.sx, pt.top.sy);
                    else ctx.lineTo(pt.top.sx, pt.top.sy);
                });
                ctx.strokeStyle = s.color;
                ctx.lineWidth = 2.4;
                ctx.stroke();

                for (const pt of s.pts) {
                    ctx.beginPath();
                    ctx.arc(pt.top.sx, pt.top.sy, 4.5, 0, Math.PI * 2);
                    ctx.fillStyle = s.color;
                    ctx.fill();
                    ctx.strokeStyle = this.theme.bg;
                    ctx.lineWidth = 1;
                    ctx.stroke();

                    pendingLabels.push({
                        sx: pt.top.sx,
                        sy: pt.top.sy,
                        depth: pt.top.depth,
                        color: s.color,
                        text: `${pt.label || s.name}${pt.matchLabel ? " " + pt.matchLabel : ""}: ${formatNum(pt.z)}`
                    });
                    this.hitTargets.push({ sx: pt.top.sx, sy: pt.top.sy, point: pt });
                }
            }
        }

        _renderBoxes(ctx, series, allPoints, b, w, h, pendingLabels) {
            const groups = series.length > 1
                ? series
                : [{ name: "All", color: this.theme.palette[0], points: allPoints }];
            const hx = (b.maxX - b.minX) * 0.032;
            const hy = (b.maxY - b.minY) * 0.032;

            const boxItems = [];
            groups.forEach((s, idx) => {
                const pts = (s.points || []).filter(p => Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z));
                if (!pts.length) return;
                const cx = pts.reduce((a, p) => a + p.x, 0) / pts.length;
                const cy = pts.reduce((a, p) => a + p.y, 0) / pts.length;
                const stats = computeQuantileStats(pts.map(p => p.z));
                if (!stats) return;
                const color = s.color || this.theme.palette[idx % this.theme.palette.length];
                const nMid = normalizePoint(cx, cy, stats.median, b);
                const proj = project3D(nMid.x, nMid.y, nMid.z, this.camera, w, h);
                boxItems.push({ name: s.name || `Series ${idx + 1}`, cx, cy, stats, color, proj });
            });

            boxItems.sort((a, c) => c.proj.depth - a.proj.depth);
            for (const item of boxItems) {
                const { cx, cy, stats, color } = item;
                const nMin = normalizePoint(cx, cy, stats.min, b);
                const nMax = normalizePoint(cx, cy, stats.max, b);
                const pMin = project3D(nMin.x, nMin.y, nMin.z, this.camera, w, h);
                const pMax = project3D(nMax.x, nMax.y, nMax.z, this.camera, w, h);

                ctx.strokeStyle = color;
                ctx.lineWidth = 2;
                ctx.beginPath();
                ctx.moveTo(pMin.sx, pMin.sy);
                ctx.lineTo(pMax.sx, pMax.sy);
                ctx.stroke();

                drawBoxPrism(ctx, cx, cy, stats.q1, stats.q3, hx, hy, color, this.camera, b, w, h, 0.88);

                ctx.beginPath();
                ctx.arc(item.proj.sx, item.proj.sy, 5, 0, Math.PI * 2);
                ctx.fillStyle = "#ffffff";
                ctx.fill();
                ctx.strokeStyle = color;
                ctx.lineWidth = 2;
                ctx.stroke();

                pendingLabels.push({
                    sx: pMax.sx,
                    sy: pMax.sy,
                    depth: item.proj.depth,
                    color,
                    text: `${item.name} (Med ${formatNum(stats.median)})`
                });

                this.hitTargets.push({
                    sx: item.proj.sx,
                    sy: item.proj.sy,
                    point: {
                        label: item.name,
                        x: cx,
                        y: cy,
                        z: stats.median,
                        extra: `Q1: ${formatNum(stats.q1)} | Med: ${formatNum(stats.median)} | Q3: ${formatNum(stats.q3)}`
                    }
                });
            }
        }

        _renderViolins(ctx, series, allPoints, b, w, h, pendingLabels) {
            const groups = series.filter(s => (s.points || []).length >= 2);
            if (!groups.length) {
                const grid = compute2DKDEGrid(allPoints, 14);
                const dBounds = computeBounds(allPoints, "density", null, grid);
                this._renderDensitySurface(ctx, grid, dBounds, w, h);
                return;
            }

            const maxRadiusX = (b.maxX - b.minX) * 0.045;
            const maxRadiusY = (b.maxY - b.minY) * 0.045;
            const items = [];

            groups.forEach((s, idx) => {
                const pts = s.points.filter(p => Number.isFinite(p.x) && Number.isFinite(p.y) && Number.isFinite(p.z));
                const cx = pts.reduce((a, p) => a + p.x, 0) / pts.length;
                const cy = pts.reduce((a, p) => a + p.y, 0) / pts.length;
                const kde = compute1DKDE(pts.map(p => p.z), 18);
                const color = s.color || this.theme.palette[idx % this.theme.palette.length];
                const midZ = pts.reduce((a, p) => a + p.z, 0) / pts.length;
                const nMid = normalizePoint(cx, cy, midZ, b);
                const proj = project3D(nMid.x, nMid.y, nMid.z, this.camera, w, h);
                items.push({ name: s.name, cx, cy, midZ, kde, color, proj });
            });

            items.sort((a, c) => c.proj.depth - a.proj.depth);
            for (const item of items) {
                const segs = 10;
                const rings = item.kde.map(sample => {
                    const rx = Math.max(maxRadiusX * 0.12, maxRadiusX * sample.d);
                    const ry = Math.max(maxRadiusY * 0.12, maxRadiusY * sample.d);
                    const ring = [];
                    for (let k = 0; k < segs; k++) {
                        const ang = (k / segs) * Math.PI * 2;
                        const n = normalizePoint(
                            item.cx + Math.cos(ang) * rx,
                            item.cy + Math.sin(ang) * ry,
                            sample.z,
                            b
                        );
                        ring.push(project3D(n.x, n.y, n.z, this.camera, w, h));
                    }
                    return ring;
                });

                for (let r = 0; r < rings.length - 1; r++) {
                    for (let k = 0; k < segs; k++) {
                        const kNext = (k + 1) % segs;
                        ctx.beginPath();
                        ctx.moveTo(rings[r][k].sx, rings[r][k].sy);
                        ctx.lineTo(rings[r][kNext].sx, rings[r][kNext].sy);
                        ctx.lineTo(rings[r + 1][kNext].sx, rings[r + 1][kNext].sy);
                        ctx.lineTo(rings[r + 1][k].sx, rings[r + 1][k].sy);
                        ctx.closePath();
                        ctx.fillStyle = rgba(item.color, 0.55);
                        ctx.fill();
                        ctx.strokeStyle = rgba(item.color, 0.75);
                        ctx.lineWidth = 0.6;
                        ctx.stroke();
                    }
                }

                pendingLabels.push({
                    sx: item.proj.sx,
                    sy: item.proj.sy,
                    depth: item.proj.depth,
                    color: item.color,
                    text: `${item.name || ""}: ${formatNum(item.midZ)}`
                });
                this.hitTargets.push({
                    sx: item.proj.sx,
                    sy: item.proj.sy,
                    point: { label: item.name, x: item.cx, y: item.cy, z: item.midZ }
                });
            }
        }

        _renderDensitySurface(ctx, grid, b, w, h) {
            if (!grid || !grid.rows || !grid.rows.length) return;
            const rows = grid.rows;
            const quads = [];
            for (let i = 0; i < rows.length - 1; i++) {
                for (let j = 0; j < rows[i].length - 1; j++) {
                    const c00 = rows[i][j];
                    const c10 = rows[i + 1][j];
                    const c11 = rows[i + 1][j + 1];
                    const c01 = rows[i][j + 1];
                    const pts = [c00, c10, c11, c01].map(c => {
                        const n = normalizePoint(c.x, c.y, c.z, b);
                        return project3D(n.x, n.y, n.z, this.camera, w, h);
                    });
                    const avgDepth = pts.reduce((a, p) => a + p.depth, 0) / 4;
                    const avgZ = (c00.z + c10.z + c11.z + c01.z) / 4;
                    const intensity = grid.maxVal > 0 ? avgZ / grid.maxVal : 0;
                    quads.push({ pts, avgDepth, intensity });
                }
            }
            quads.sort((a, c) => c.avgDepth - a.avgDepth);
            const baseColor = this.theme.palette[0];
            for (const q of quads) {
                ctx.beginPath();
                ctx.moveTo(q.pts[0].sx, q.pts[0].sy);
                for (let k = 1; k < 4; k++) ctx.lineTo(q.pts[k].sx, q.pts[k].sy);
                ctx.closePath();
                ctx.fillStyle = rgba(shadeColor(baseColor, 0.55 + q.intensity * 0.85), 0.35 + q.intensity * 0.55);
                ctx.fill();
                ctx.strokeStyle = rgba(baseColor, 0.45);
                ctx.lineWidth = 0.7;
                ctx.stroke();
            }
        }

        _renderHistogram(ctx, bins, b, w, h, pendingLabels) {
            if (!bins || !bins.length) return;
            const items = bins.map(bin => {
                const nCenter = normalizePoint(bin.cx, bin.cy, bin.count * 0.5, b);
                const centerProj = project3D(nCenter.x, nCenter.y, nCenter.z, this.camera, w, h);
                const nTop = normalizePoint(bin.cx, bin.cy, bin.count, b);
                const topProj = project3D(nTop.x, nTop.y, nTop.z, this.camera, w, h);
                return { bin, centerDepth: centerProj.depth, topProj };
            }).sort((a, c) => c.centerDepth - a.centerDepth);

            const baseColor = this.theme.palette[0];
            for (const { bin, centerDepth, topProj } of items) {
                const ratio = b.maxZ > 0 ? bin.count / b.maxZ : 0.5;
                const col = shadeColor(baseColor, 0.65 + ratio * 0.65);
                drawBoxPrism(ctx, bin.cx, bin.cy, 0, bin.count, bin.dx * 0.42, bin.dy * 0.42, col, this.camera, b, w, h, 0.92);
                const teamStr = bin.teams.size ? ` [${Array.from(bin.teams).slice(0, 3).join(",")}]` : "";
                pendingLabels.push({
                    sx: topProj.sx,
                    sy: topProj.sy,
                    depth: centerDepth,
                    color: baseColor,
                    text: `${bin.count} pts${teamStr}`
                });
                this.hitTargets.push({
                    sx: topProj.sx,
                    sy: topProj.sy,
                    point: {
                        label: `Bin (${bin.count} pts)`,
                        x: bin.cx,
                        y: bin.cy,
                        z: bin.count,
                        extra: bin.teams.size ? `Teams: ${Array.from(bin.teams).slice(0, 6).join(", ")}` : ""
                    }
                });
            }
        }

        _drawLegend(ctx, series, w) {
            if (!series || series.length <= 1 || series.length > 14) return;
            ctx.font = "11px system-ui, -apple-system, sans-serif";
            let x = 12;
            let y = 18;
            for (let i = 0; i < series.length; i++) {
                const s = series[i];
                const color = s.color || this.theme.palette[i % this.theme.palette.length];
                const label = String(s.name || "");
                const tw = ctx.measureText(label).width + 22;
                if (x + tw > w - 260) {
                    x = 12;
                    y += 18;
                }
                ctx.fillStyle = color;
                ctx.beginPath();
                ctx.arc(x + 5, y - 4, 4.5, 0, Math.PI * 2);
                ctx.fill();
                ctx.fillStyle = this.theme.text;
                ctx.textAlign = "left";
                ctx.fillText(label, x + 14, y);
                x += tw;
            }
        }

        _drawTooltip(ctx, w, h, xLabel, yLabel, zLabel) {
            if (!this.hoverTarget || !this.hoverTarget.point) return;
            const pt = this.hoverTarget.point;
            const lines = [
                pt.label ? `${pt.label}${pt.matchLabel ? " · " + pt.matchLabel : ""}` : (pt.seriesName || "Point"),
                `${xLabel}: ${formatNum(pt.x)}`,
                `${yLabel}: ${formatNum(pt.y)}`,
                `${zLabel}: ${formatNum(pt.z)}`
            ];
            if (pt.extra) lines.push(pt.extra);

            ctx.font = "12px system-ui, -apple-system, sans-serif";
            const maxW = Math.max(...lines.map(l => ctx.measureText(l).width));
            const boxW = maxW + 20;
            const boxH = lines.length * 17 + 14;
            let bx = this.hoverTarget.sx + 12;
            let by = this.hoverTarget.sy - boxH - 8;
            if (bx + boxW > w - 8) bx = this.hoverTarget.sx - boxW - 12;
            if (by < 8) by = this.hoverTarget.sy + 12;
            bx = clamp(bx, 8, w - boxW - 8);
            by = clamp(by, 8, h - boxH - 8);

            ctx.fillStyle = this.theme.tooltipBg;
            ctx.strokeStyle = this.theme.tooltipBorder;
            ctx.lineWidth = 1;
            ctx.beginPath();
            ctx.roundRect(bx, by, boxW, boxH, 8);
            ctx.fill();
            ctx.stroke();

            ctx.fillStyle = this.theme.text;
            ctx.textAlign = "left";
            lines.forEach((line, idx) => {
                ctx.font = idx === 0 ? "bold 12px system-ui, -apple-system, sans-serif" : "11.5px system-ui, -apple-system, sans-serif";
                ctx.fillText(line, bx + 10, by + 18 + idx * 17);
            });
        }

        destroy() {
            if (this.rafId !== null) cancelAnimationFrame(this.rafId);
            if (this.resizeObserver) this.resizeObserver.disconnect();
            if (this._cleanupEvents) this._cleanupEvents();
            this.container.innerHTML = "";
        }
    }

    const instances = new WeakMap();

    const Obsidian3DGraph = {
        render(container, config) {
            if (!container) return null;
            let inst = instances.get(container);
            if (!inst) {
                inst = new Obsidian3DInstance(container, config);
                instances.set(container, inst);
            } else {
                inst.update(config);
            }
            return inst;
        },
        destroy(container) {
            const inst = instances.get(container);
            if (inst) {
                inst.destroy();
                instances.delete(container);
            }
        }
    };

    if (typeof module !== "undefined" && module.exports) {
        module.exports = Obsidian3DGraph;
    } else {
        global.Obsidian3DGraph = Obsidian3DGraph;
    }
})(typeof window !== "undefined" ? window : globalThis);
