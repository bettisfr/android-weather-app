'use strict';

const output = document.getElementById('time');
const source = document.getElementById('source');
const steps = document.getElementById('steps');
const play = document.getElementById('play');
const modes = document.getElementById('modes');
const autoButton = document.getElementById('auto');
const infraredButton = document.getElementById('infrared');
const frameBlobs = new Map();
const frameCacheSize = 12;
let config;
let times;
let products;
let mode;
let frames = [];
let timer = null;
let renderTimer;
let preloadVersion = 0;
let requestedFrame;
let map;
let globalOverlay;
let playing = false;
let regionalLayer = null;
let regionalOverlay;

// The published Mercator image is 836 × 500, with the equator on row 250.
const outerLatitude = Math.atan(Math.sinh(250 * 2 * Math.PI / 836)) * 180 / Math.PI;

function status(message) {
    const note = document.getElementById('status');
    note.textContent = message;
    note.hidden = !message;
}

function initialize(parameters) {
    config = parameters;
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    steps.setAttribute('aria-label', config.timelineLabel);
    play.setAttribute('aria-label', config.play);
    modes.setAttribute('aria-label', config.modeLabel);
    autoButton.textContent = config.auto;
    infraredButton.textContent = config.observed;
    times = new Intl.DateTimeFormat(config.language, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
        hourCycle: config.clock24 ? 'h23' : 'h12', timeZone: config.timeZone,
    });
    document.getElementById('map').setAttribute('aria-label', config.timelineLabel);
    map = createWeatherMap('map', [Math.max(-85, Math.min(85, config.lat)), config.lon], 9, {worldCopyJump: true});
    const city = map.createPane('cityMarker');
    city.style.zIndex = 450;
    L.circleMarker([config.lat, config.lon], {
        pane: 'cityMarker', radius: 7, color: '#fff', weight: 3, fillColor: '#273b6a', fillOpacity: 1, interactive: false,
    }).addTo(map);
    map.on('moveend', () => {
        preloadVersion++;
        if (regionalLayer) queueFrame(Number(steps.value));
    });
    status(config.loading);
}

function setProducts(available) {
    products = available;
    modes.hidden = !available.auto || !available.infrared;
    selectMode(available.auto ? 'auto' : 'infrared');
}

function selectMode(nextMode) {
    const product = products[nextMode];
    if (!product || nextMode === mode) return;
    const previousTime = frames[Number(steps.value)]?.time;
    setPlaying(false);
    mode = nextMode;
    regionalLayer = product.layer;
    autoButton.setAttribute('aria-pressed', mode === 'auto');
    infraredButton.setAttribute('aria-pressed', mode === 'infrared');
    status(config.loading);
    frames = [...product.frames].reverse();
    steps.max = frames.length - 1;
    steps.disabled = frames.length < 2;
    play.disabled = frames.length < 2;
    const matchingFrame = frames.findIndex(frame => frame.time >= previousTime);
    preloadVersion++;
    showFrame(matchingFrame >= 0 ? matchingFrame : frames.length - 1, true);
}

function regionalFrame(time) {
    const coordinates = map.options.crs;
    const bounds = map.getBounds();
    const sw = coordinates.project(bounds.getSouthWest());
    const ne = coordinates.project(bounds.getNorthEast());
    const size = map.getSize();
    const scale = Math.min(2, 1536 / Math.max(size.x, size.y));
    const params = new URLSearchParams({
        service: 'WMS', version: '1.1.1', request: 'GetMap', layers: regionalLayer,
        styles: '', format: 'image/vnd.jpeg-png', transparent: 'true', srs: 'EPSG:3857',
        interpolations: 'bilinear', bbox: [sw.x, sw.y, ne.x, ne.y].join(','),
        width: Math.max(1, Math.round(size.x * scale)),
        height: Math.max(1, Math.round(size.y * scale)),
        time: new Date(time).toISOString(),
    });
    return {bounds, url: config.regionalSource + 'geoserver/wms?' + params};
}

function loadRegionalBlob(url) {
    let cached = frameBlobs.get(url);
    frameBlobs.delete(url);
    if (!cached) {
        cached = fetch(url).then(response => {
            if (!response.ok || !response.headers.get('content-type')?.startsWith('image/')) {
                throw new Error('Satellite image unavailable');
            }
            return response.blob();
        }).catch(error => {
            if (frameBlobs.get(url) === cached) frameBlobs.delete(url);
            throw error;
        });
    }
    frameBlobs.set(url, cached);
    if (frameBlobs.size > frameCacheSize) frameBlobs.delete(frameBlobs.keys().next().value);
    return cached;
}

function loadImage(url) {
    const image = new Image();
    image.alt = '';
    image.src = url;
    return image.decode().then(() => image);
}

async function loadRegionalImage(url, request) {
    const blob = await loadRegionalBlob(url);
    if (requestedFrame !== request) return null;
    const objectUrl = URL.createObjectURL(blob);
    try {
        return await loadImage(objectUrl);
    } finally {
        URL.revokeObjectURL(objectUrl);
    }
}

function preloadFrames(index) {
    const version = ++preloadVersion;
    const urls = frames.map((_, offset) => frames[(index + 1 + offset) % frames.length])
        .slice(0, -1).map(frame => regionalFrame(frame.time).url);
    let next = 0;
    async function preload() {
        while (version === preloadVersion && next < urls.length) {
            const url = urls[next++];
            try {
                await loadRegionalBlob(url);
            } catch {
                if (version === preloadVersion) await loadRegionalBlob(url).catch(() => {});
            }
        }
    }
    preload();
    preload();
}

function queueFrame(index) {
    clearTimeout(timer);
    clearTimeout(renderTimer);
    requestedFrame = null;
    renderTimer = setTimeout(() => showFrame(index), 120);
}

function showFrame(index, preload = false) {
    clearTimeout(renderTimer);
    steps.value = index;
    const data = frames[index];
    const regional = regionalLayer ? regionalFrame(data.time) : null;
    const request = {};
    requestedFrame = request;
    const pendingImage = regional ? loadRegionalImage(regional.url, request) : loadImage(data.url);
    pendingImage.then(image => {
        if (requestedFrame !== request) return;
        if (regional) {
            if (regionalOverlay) regionalOverlay.remove();
            regionalOverlay = L.imageOverlay(image, regional.bounds, {
                opacity: .80, interactive: false, alt: '',
            }).addTo(map);
        } else if (!globalOverlay) {
            globalOverlay = new GlobalRaster(image, outerLatitude, {opacity: .82, verticalInset: .15}).addTo(map);
        } else {
            globalOverlay.setImage(image);
        }
        const geocolour = mode === 'auto';
        output.textContent = (geocolour ? 'GeoColour' : config.observed) + ' · ' + times.format(data.time);
        source.href = geocolour ? config.geocolourSource : regional ? config.regionalSource : config.globalSource;
        source.textContent = geocolour ? 'EUMETSAT · NASA' : regional ? 'EUMETSAT' : 'NOAA NESDIS';
        source.hidden = false;
        status(!regional && Math.abs(config.lat) > 60 ? config.coverage : '');
        if (regional && preload) preloadFrames(index);
        scheduleFrame();
    }, () => {
        if (requestedFrame === request) {
            status(config.unavailable);
            scheduleFrame();
        }
    });
}

function scheduleFrame() {
    clearTimeout(timer);
    if (!playing) return;
    const index = (Number(steps.value) + 1) % frames.length;
    const next = frames[index];
    if (regionalLayer) loadRegionalBlob(regionalFrame(next.time).url).catch(() => {});
    timer = setTimeout(() => showFrame(index), 1400);
}

function setPlaying(active) {
    playing = active;
    play.textContent = active ? 'Ⅱ' : '▶';
    play.setAttribute('aria-label', active ? config.pause : config.play);
    scheduleFrame();
}

steps.addEventListener('input', () => { setPlaying(false); queueFrame(Number(steps.value)); });
play.addEventListener('click', () => setPlaying(!playing));
autoButton.addEventListener('click', () => selectMode('auto'));
infraredButton.addEventListener('click', () => selectMode('infrared'));
