'use strict';

const output = document.getElementById('time');
const source = document.getElementById('source');
const steps = document.getElementById('steps');
const play = document.getElementById('play');
let config;
let times;
let frames = [];
let timer = null;
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
    times = new Intl.DateTimeFormat(config.language, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
        hourCycle: config.clock24 ? 'h23' : 'h12', timeZone: config.timeZone,
    });
    document.getElementById('map').setAttribute('aria-label', config.timelineLabel);
    map = createWeatherMap('map', [Math.max(-85, Math.min(85, config.lat)), config.lon], 8, {worldCopyJump: true});
    const city = map.createPane('cityMarker');
    city.style.zIndex = 450;
    L.circleMarker([config.lat, config.lon], {
        pane: 'cityMarker', radius: 7, color: '#fff', weight: 3, fillColor: '#273b6a', fillOpacity: 1, interactive: false,
    }).addTo(map);
    status(config.loading);
}

function setRegionalFrames(product) {
    regionalLayer = product.layer;
    map.on('moveend', () => {
        clearTimeout(timer);
        showFrame(Number(steps.value));
    });
    setFrames(product.frames);
}

function regionalFrame(time) {
    const bounds = map.getBounds();
    const coordinates = map.options.crs;
    const sw = coordinates.project(bounds.getSouthWest());
    const ne = coordinates.project(bounds.getNorthEast());
    const size = map.getSize();
    const density = Math.min(2, window.devicePixelRatio || 1);
    const params = new URLSearchParams({
        service: 'WMS', version: '1.1.1', request: 'GetMap', layers: regionalLayer,
        styles: '', format: 'image/png', transparent: 'true', srs: 'EPSG:3857',
        bbox: [sw.x, sw.y, ne.x, ne.y].join(','),
        width: Math.max(1, Math.min(1536, Math.ceil(size.x * density))),
        height: Math.max(1, Math.min(1536, Math.ceil(size.y * density))),
        time: new Date(time).toISOString(),
    });
    return {bounds, url: config.regionalSource + 'geoserver/wms?' + params};
}

function setFrames(available) {
    frames = available.reverse();
    steps.max = frames.length - 1;
    steps.disabled = frames.length < 2;
    play.disabled = frames.length < 2;
    showFrame(frames.length - 1);
}

function showFrame(index) {
    steps.value = index;
    const data = frames[index];
    const regional = regionalLayer ? regionalFrame(data.time) : null;
    const request = {};
    requestedFrame = request;
    const image = new Image();
    image.onload = () => {
        if (requestedFrame !== request) return;
        if (regional) {
            if (regionalOverlay) regionalOverlay.remove();
            regionalOverlay = L.imageOverlay(regional.url, regional.bounds, {
                opacity: .80, interactive: false, alt: '',
            }).addTo(map);
        } else if (!globalOverlay) {
            globalOverlay = new GlobalRaster(image, outerLatitude, {opacity: .82, verticalInset: .15}).addTo(map);
        } else {
            globalOverlay.setImage(image);
        }
        output.textContent = config.observed + ' · ' + times.format(data.time);
        source.href = regional ? config.regionalSource : config.globalSource;
        source.textContent = regional ? 'EUMETSAT' : 'NOAA NESDIS';
        source.hidden = false;
        status(!regional && Math.abs(config.lat) > 60 ? config.coverage : '');
        scheduleFrame();
    };
    image.onerror = () => {
        if (requestedFrame === request) {
            status(config.unavailable);
            scheduleFrame();
        }
    };
    image.src = regional ? regional.url : data.url;
}

function scheduleFrame() {
    clearTimeout(timer);
    timer = playing ? setTimeout(() => showFrame((Number(steps.value) + 1) % frames.length), 1400) : null;
}

function setPlaying(active) {
    playing = active;
    play.textContent = active ? 'Ⅱ' : '▶';
    play.setAttribute('aria-label', active ? config.pause : config.play);
    scheduleFrame();
}

steps.addEventListener('input', () => { setPlaying(false); showFrame(Number(steps.value)); });
play.addEventListener('click', () => setPlaying(!playing));
