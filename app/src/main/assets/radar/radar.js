/* Radar data © Radar-DPC, CC BY-SA 4.0. The colorized overlay uses the same licence. */
'use strict';

const mapElement = document.getElementById('map');
const statusElement = document.getElementById('status');
const timeline = document.getElementById('timeline');
const play = document.getElementById('play');
const label = document.getElementById('label');
const frame = document.getElementById('frame');
const lightningToggle = document.getElementById('lightning');
const lightningFrames = new Map();
const radarRoot = 'https://s3-prod-dpc-radar-webp-cache.s3.eu-south-1.amazonaws.com/SRI';
const intensity = [
    {min: 0.4, label: '< 2', rgb: [62, 151, 224]},
    {min: 2, label: '2–10', rgb: [20, 150, 105]},
    {min: 10, label: '10–25', rgb: [240, 194, 37]},
    {min: 25, label: '25–50', rgb: [230, 95, 41]},
    {min: 50, label: '≥ 50', rgb: [156, 47, 139]},
];
const color = Array.from({length: 256}, (_, red) => {
    const mmPerHour = red * 100 / 255;
    return intensity.reduce((current, level) => mmPerHour >= level.min ? level.rgb : current, null);
});

let strings, map, radar, displayedRadar, displayedIndex, displayedTime, latest, interval, timeFormat, frameCount;
let lightningLayer, lightningIcon;
let playTimer, seekTimer, finishTransition, playing = false;

function status(message) {
    statusElement.textContent = message;
    statusElement.hidden = !message;
}

const RadarTiles = L.TileLayer.extend({
    createTile(coords, done) {
        const tile = document.createElement('canvas');
        const size = this.getTileSize();
        tile.width = size.x;
        tile.height = size.y;
        const raw = new Image();
        raw.crossOrigin = 'anonymous';
        raw.onload = () => {
            try {
                const context = tile.getContext('2d', {willReadFrequently: true});
                context.drawImage(raw, 0, 0, tile.width, tile.height);
                const image = context.getImageData(0, 0, tile.width, tile.height);
                const rgba = image.data;
                for (let i = 0; i < rgba.length; i += 4) {
                    const category = color[rgba[i]];
                    if (!category || rgba[i + 3] === 0) {
                        rgba[i + 3] = 0;
                        continue;
                    }
                    [rgba[i], rgba[i + 1], rgba[i + 2]] = category;
                    rgba[i + 3] = 165;
                }
                context.putImageData(image, 0, 0);
                done(null, tile);
            } catch (error) {
                done(error, tile);
            }
        };
        raw.onerror = () => done(new Error('Radar tile unavailable'), tile);
        raw.src = this.getTileUrl(coords);
        return tile;
    },
});

function initialize(config) {
    strings = config;
    frameCount = config.frameCount;
    timeline.max = frameCount - 1;
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    label.textContent = config.observed;
    document.getElementById('lightning-label').textContent = config.lightning;
    document.getElementById('latest').textContent = config.latest;
    mapElement.setAttribute('aria-label', config.mapLabel);
    timeline.setAttribute('aria-label', config.timelineLabel);
    setPlaying(false);
    status(config.loading);
    timeFormat = new Intl.DateTimeFormat(config.language, {
        hour: '2-digit', minute: '2-digit', hourCycle: config.clock24 ? 'h23' : 'h12', timeZone: config.timeZone,
    });
    const legend = document.getElementById('legend');
    legend.textContent = 'mm/h';
    for (const {label, rgb} of intensity) {
        const entry = document.createElement('span');
        const swatch = document.createElement('span');
        entry.className = 'key';
        swatch.className = 'swatch';
        swatch.style.backgroundColor = 'rgb(' + rgb.join(',') + ')';
        entry.append(swatch, document.createTextNode(label));
        legend.append(entry);
    }

    map = createWeatherMap(mapElement, [config.lat, config.lon], 9);
    const lightningPane = map.createPane('lightningPane');
    lightningPane.style.zIndex = 430;
    lightningPane.style.pointerEvents = 'none';
    lightningIcon = L.divIcon({
        className: 'lightning-strike', html: '<span></span>', iconSize: [14, 16], iconAnchor: [7, 8],
    });
    lightningLayer = L.layerGroup().addTo(map);
    map.attributionControl.addAttribution('<a href="https://mappe.protezionecivile.gov.it/it/mappe-e-dashboard-rischi/piattaforma-radar/">Radar-DPC</a> · <a href="https://creativecommons.org/licenses/by-sa/4.0/">CC BY-SA 4.0</a>');
    L.circleMarker([config.lat, config.lon], {
        radius: 7, color: '#fff', weight: 3, fillColor: '#273b6a', fillOpacity: 1,
        className: 'city-marker', interactive: false,
    }).addTo(map);
    map.on('movestart zoomstart', () => { if (finishTransition) finishTransition(); });
    map.on('moveend', renderLightning);
}

function setRadarTime(time, step) {
    latest = time;
    interval = step;
    timeline.disabled = false;
    play.disabled = false;
    showFrame(frameCount - 1);
}

function showFrame(index) {
    if (finishTransition) finishTransition();
    timeline.value = index;
    const at = new Date(latest - (frameCount - 1 - index) * interval);
    const utc = at.toISOString();
    const folder = `${utc.slice(0, 10).replace(/-/g, '/')}/${utc.slice(11, 16).replace(':', '')}`;
    const frameTime = timeFormat.format(at);
    timeline.setAttribute('aria-valuetext', frameTime);
    if (radar && radar !== displayedRadar) radar.remove();
    const layer = new RadarTiles(`${radarRoot}/${folder}/{z}/{x}/{y}/sri.webp`, {
        maxZoom: maximumMapZoom, maxNativeZoom: 7, noWrap: true, keepBuffer: 1, zIndex: 2, opacity: 0,
    });
    radar = layer;
    if (!playing || !displayedRadar) status(strings.loading);
    const available = new Set();
    layer.on('tileload', event => available.add(event.tile));
    layer.on('tileunload', event => available.delete(event.tile));
    layer.on('load', () => {
        if (layer !== radar) return;
        if (layer === displayedRadar) {
            status(available.size ? '' : strings.noTiles);
            return;
        }
        if (finishTransition) return;
        const display = () => {
            if (displayedRadar) displayedRadar.remove();
            displayedRadar = layer;
            displayedIndex = index;
            displayedTime = at.getTime();
            layer.setOpacity(1);
            frame.textContent = frameTime;
            renderLightning();
            if (available.size && document.getElementById('map-updated').hidden) showMapUpdate(strings);
            status(available.size ? '' : strings.noTiles);
            if (playing) {
                clearTimeout(playTimer);
                playTimer = setTimeout(advance, 540);
            }
        };
        if (playing && displayedIndex + 1 === index && available.size) {
            clearTimeout(playTimer);
            blendFrames(displayedRadar, layer, display);
        } else {
            display();
        }
    });
    layer.addTo(map);
}

function setLightningFrame(time, encoded) {
    // Radar-DPC Blitz v2 uses big-endian coordinates with 18 bits per axis.
    const raw = atob(encoded);
    if (raw.length < 3 || raw.charCodeAt(0) !== 2) return;
    const uint16 = offset => raw.charCodeAt(offset) * 256 + raw.charCodeAt(offset + 1);
    const count = uint16(1);
    const points = [];
    let offset = 3;
    for (let i = 0; i < count; i++) {
        if (offset + 6 > raw.length) return;
        const high = raw.charCodeAt(offset + 4);
        const longitude = (uint16(offset) + ((high >> 6) & 3) * 65536) * 360 / 262144 - 180;
        const latitude = (uint16(offset + 2) + ((high >> 4) & 3) * 65536) * 180 / 262144 - 90;
        points.push([latitude, longitude]);
        offset += raw.charCodeAt(offset + 5) === 255 ? 8 : 6;
        if (offset > raw.length) return;
    }
    lightningFrames.set(time, points);
    if (time <= displayedTime && displayedTime - time < strings.lightningMaxAge) renderLightning();
}

function renderLightning() {
    lightningLayer.clearLayers();
    if (lightningToggle.getAttribute('aria-pressed') !== 'true' || displayedTime === undefined) return;
    const bounds = map.getBounds().pad(0.1);
    // Draw older observations first so recent ones remain visible over them.
    const times = [...lightningFrames.keys()].sort((a, b) => a - b);
    for (const time of times) {
        const age = displayedTime - time;
        if (age < 0 || age >= strings.lightningMaxAge) continue;
        const opacity = 1 - age / strings.lightningMaxAge;
        for (const point of lightningFrames.get(time)) {
            if (!bounds.contains(point)) continue;
            L.marker(point, {
                pane: 'lightningPane', icon: lightningIcon, opacity, interactive: false, keyboard: false,
            }).addTo(lightningLayer);
        }
    }
}

function setPlaying(active) {
    playing = active;
    clearTimeout(playTimer);
    play.textContent = active ? 'Ⅱ' : '▶';
    if (strings) play.setAttribute('aria-label', active ? strings.pause : strings.play);
    if (active) {
        clearTimeout(seekTimer);
        advance();
    } else if (finishTransition) {
        finishTransition();
    }
}

function advance() {
    showFrame((Number(timeline.value) + 1) % frameCount);
    playTimer = setTimeout(advance, 2800);
}

function blendFrames(previous, next, display) {
    let animation;
    let started;
    finishTransition = () => {
        cancelAnimationFrame(animation);
        finishTransition = null;
        display();
    };
    function draw(now) {
        if (started === undefined) started = now;
        const progress = Math.min(1, (now - started) / 210);
        if (progress === 1) {
            finishTransition();
            return;
        }
        previous.setOpacity(1 - progress);
        next.setOpacity(progress);
        animation = requestAnimationFrame(draw);
    }
    animation = requestAnimationFrame(draw);
}

timeline.addEventListener('input', () => {
    if (playing) setPlaying(false);
    clearTimeout(seekTimer);
    seekTimer = setTimeout(() => showFrame(Number(timeline.value)), 120);
});
play.addEventListener('click', () => setPlaying(!playing));
lightningToggle.addEventListener('click', () => {
    lightningToggle.setAttribute('aria-pressed', lightningToggle.getAttribute('aria-pressed') !== 'true');
    renderLightning();
});
