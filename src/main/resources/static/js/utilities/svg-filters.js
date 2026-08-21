/**
 * Utility SVG Filters Module - ObsidianScout
 * Injects hidden SVG liquid glass filter definitions (#liquid-glass, #glass-rim) for refractive glassmorphism backdrops.
 */

export function injectLiquidGlassSVG() {
    if (document.getElementById('liquid-glass-defs')) return;
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.id = 'liquid-glass-defs';
    svg.setAttribute('aria-hidden', 'true');
    svg.style.cssText = 'position:absolute;width:0;height:0;overflow:hidden;pointer-events:none;';
    svg.innerHTML = `
    <defs>
        <!--
            LAYER 1 — PRIMARY REFRACTION FILTER
            feTurbulence generates organic fractal noise.
            feColorMatrix isolates the R and G channels as displacement vectors.
            feDisplacementMap uses those to warp every pixel of the source.
            scale=18 gives ~18px max warp — visible but not glitchy.
        -->
        <filter id="liquid-glass" x="-10%" y="-10%" width="120%" height="120%" color-interpolation-filters="sRGB">
            <feTurbulence
                type="fractalNoise"
                baseFrequency="0.018 0.022"
                numOctaves="4"
                seed="3"
                stitchTiles="stitch"
                result="noise"/>
            <feColorMatrix
                in="noise"
                type="matrix"
                values="1 0 0 0 0
                        0 1 0 0 0
                        0 0 1 0 0
                        0 0 0 1 0"
                result="coloredNoise"/>
            <feDisplacementMap
                in="SourceGraphic"
                in2="coloredNoise"
                scale="18"
                xChannelSelector="R"
                yChannelSelector="G"
                result="displaced"/>
        </filter>

        <!--
            LAYER 2 — RIM DISTORTION FILTER (subtler, for borders/edges)
            Lower scale (8) and higher baseFrequency for a finer-grain edge warp.
        -->
        <filter id="glass-rim" x="-5%" y="-5%" width="110%" height="110%" color-interpolation-filters="sRGB">
            <feTurbulence
                type="fractalNoise"
                baseFrequency="0.035 0.04"
                numOctaves="2"
                seed="7"
                stitchTiles="stitch"
                result="rimNoise"/>
            <feDisplacementMap
                in="SourceGraphic"
                in2="rimNoise"
                scale="8"
                xChannelSelector="R"
                yChannelSelector="G"/>
        </filter>
    </defs>`;
    document.body.insertAdjacentElement('afterbegin', svg);
}
