'use strict';

const limit = 85.05112878;
let map;
let strings;
let timeFormat;
let auroraCells;
let auroraPopup;

function status(message) {
    const element = document.getElementById('status');
    element.textContent = message;
    element.hidden = !message;
}

function initialize(config) {
    strings = config;
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    const legend = document.querySelector('.legend');
    legend.setAttribute('aria-label', config.intensity);
    if (config.airQuality) {
        document.documentElement.classList.add('air-quality');
        document.getElementById('source').textContent = 'CAMS / Open-Meteo';
        legend.replaceChildren();
        for (const band of config.bands) {
            const key = document.createElement('span');
            key.className = 'aqi-band';
            key.style.setProperty('--band', band.color);
            key.textContent = band.legend;
            legend.append(key);
        }
    } else {
        document.getElementById('low').textContent = config.low;
        document.getElementById('high').textContent = config.high;
    }
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    document.getElementById('source').href = config.source;
    timeFormat = new Intl.DateTimeFormat(config.language, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
        hourCycle: config.clock24 ? 'h23' : 'h12', timeZone: config.timeZone,
    });
    status(config.loading);
    map = createWeatherMap('map', [Math.max(-limit, Math.min(limit, config.lat)), config.lon],
        config.airQuality ? 9 : 3, {worldCopyJump: true});
    const cityPane = map.createPane('cityMarker');
    cityPane.style.zIndex = 450;
    const pin = L.circleMarker([config.lat, config.lon], {
        pane: 'cityMarker', radius: 7, color: '#fff', weight: 3, fillColor: '#273b6a', fillOpacity: 1, interactive: false,
    }).addTo(map);
    pin.bringToFront();
    if (config.airQuality) initializeAirQuality();
    else map.on('click', ({latlng}) => {
        auroraPopup = {latlng, content: document.createElement('div')};
        fillAuroraPopup();
        L.popup().setLatLng(latlng).setContent(auroraPopup.content).openOn(map);
    });
}

function mercatorY(latitude, height) {
    const radians = Math.max(-limit, Math.min(limit, latitude)) * Math.PI / 180;
    return (1 - Math.asinh(Math.tan(radians)) / Math.PI) * height / 2;
}

function setAurora(encoded, validTime) {
    const cells = atob(encoded);
    auroraCells = cells;
    const canvas = document.createElement('canvas');
    canvas.width = 720;
    canvas.height = 1024;
    const context = canvas.getContext('2d');
    const colors = Array.from({length: 101}, (_, intensity) => {
        const hue = 140 - 135 * intensity / 100;
        const opacity = Math.min(.82, .23 + intensity / 100);
        return `hsla(${hue}, 84%, 49%, ${opacity})`;
    });

    for (let latitude = 85; latitude >= -85; latitude--) {
        const top = Math.round(mercatorY(latitude === 85 ? limit : latitude + .5, canvas.height));
        const bottom = Math.round(mercatorY(latitude === -85 ? -limit : latitude - .5, canvas.height));
        const offset = (90 - latitude) * 360;
        for (let column = 0; column < 360; column++) {
            const value = cells.charCodeAt(offset + column);
            if (value < strings.minActivity) continue;
            context.fillStyle = colors[value];
            const x = column * 2 - 1;
            context.fillRect(x, top, 2, bottom - top);
            if (column === 0) context.fillRect(canvas.width - 1, top, 1, bottom - top);
        }
    }

    new GlobalRaster(canvas, limit, {zIndex: 1}).addTo(map);
    document.getElementById('forecast').textContent = strings.forecast + ' · ' + timeFormat.format(validTime);
    showMapUpdate(strings);
    status('');
    fillAuroraPopup();
}

function fillAuroraPopup() {
    if (!auroraPopup) return;
    const {latlng, content} = auroraPopup;
    const position = latlng.wrap();
    const row = 90 - Math.round(position.lat);
    const column = ((Math.round(position.lng) + 180) % 360 + 360) % 360;
    const value = auroraCells && Math.abs(position.lat) <= limit
        ? auroraCells.charCodeAt(row * 360 + column) : NaN;
    content.textContent = Number.isFinite(value)
        ? (value >= strings.minActivity ? strings.localActivity : strings.noLocalActivity)
        : strings.unavailable;
}

const aqiEndpoint = 'https://air-quality-api.open-meteo.com/v1/air-quality';
const aqiCoverage = [[30, -25], [72, 45]];
const aqiCellSize = 0.1;
const maxAqiCells = 750;
let airQualityLayer, airQualityRenderer, loadedAqiBounds, loadedAqiTime = 0;
let aqiRequest, aqiTimer;

function initializeAirQuality() {
    document.getElementById('forecast').textContent = strings.forecast;
    airQualityRenderer = L.canvas();
    airQualityLayer = L.layerGroup().addTo(map);
    map.on('moveend', () => {
        clearTimeout(aqiTimer);
        aqiTimer = setTimeout(showAirQualityCells, 250);
    });
    showAirQualityCells();
}

function aqiBounds(view) {
    const coverage = L.latLngBounds(aqiCoverage);
    if (!coverage.intersects(view)) return null;
    return L.latLngBounds([
        [Math.max(coverage.getSouth(), Math.floor(view.getSouth() * 10) / 10),
         Math.max(coverage.getWest(), Math.floor(view.getWest() * 10) / 10)],
        [Math.min(coverage.getNorth(), Math.ceil(view.getNorth() * 10) / 10),
         Math.min(coverage.getEast(), Math.ceil(view.getEast() * 10) / 10)],
    ]);
}

function exceedsAqiCellLimit(bounds) {
    const rows = Math.ceil((bounds.getNorth() - bounds.getSouth()) / aqiCellSize) + 1;
    const columns = Math.ceil((bounds.getEast() - bounds.getWest()) / aqiCellSize) + 1;
    return rows * columns > maxAqiCells;
}

async function showAirQualityCells() {
    const bounds = aqiBounds(map.getBounds());
    if (!bounds || exceedsAqiCellLimit(bounds)) {
        if (aqiRequest) aqiRequest.abort();
        aqiRequest = null;
        airQualityLayer.remove();
        status(bounds ? strings.zoomIn : strings.coverage);
        return;
    }
    airQualityLayer.addTo(map);
    if (loadedAqiBounds && loadedAqiBounds.contains(bounds) && Date.now() - loadedAqiTime < 5 * 60_000) {
        if (aqiRequest) aqiRequest.abort();
        aqiRequest = null;
        status('');
        return;
    }
    const padded = aqiBounds(map.getBounds().pad(.12));
    const requested = exceedsAqiCellLimit(padded) ? bounds : padded;
    if (aqiRequest) aqiRequest.abort();
    const request = new AbortController();
    aqiRequest = request;
    const timeout = setTimeout(() => request.abort(), 8000);
    const params = new URLSearchParams({
        bounding_box: [requested.getSouth(), requested.getWest(), requested.getNorth(), requested.getEast()].join(','),
        current: 'european_aqi', domains: 'cams_europe', cell_selection: 'nearest', timeformat: 'unixtime',
    });
    status(strings.loading);
    try {
        const response = await fetch(aqiEndpoint + '?' + params, {signal: request.signal});
        if (!response.ok) throw new Error('Air quality API unavailable');
        const result = await response.json();
        if (aqiRequest !== request || request.signal.aborted) return;
        const locations = Array.isArray(result) ? result : [result];
        const valid = locations.filter(location => Number.isFinite(location.current?.european_aqi)
            && Number.isFinite(location.current?.time));
        if (!valid.length) throw new Error('No air quality values');
        airQualityLayer.clearLayers();
        for (const location of valid) addAqiCell(location);
        loadedAqiBounds = requested;
        loadedAqiTime = Date.now();
        document.getElementById('forecast').textContent = strings.forecast + ' · '
            + timeFormat.format(valid[0].current.time * 1000);
        showMapUpdate(strings);
        status('');
    } catch (error) {
        if (aqiRequest === request) status(strings.unavailable);
    } finally {
        clearTimeout(timeout);
        if (aqiRequest === request) aqiRequest = null;
    }
}

function addAqiCell(location) {
    // The API returns centres of the native CAMS Europe 0.1° cells.
    const lat = Math.round(location.latitude * 10000) / 10000;
    const lon = Math.round(location.longitude * 10000) / 10000;
    const half = aqiCellSize / 2;
    const aqi = Math.round(location.current.european_aqi);
    const band = strings.bands.find(candidate => aqi <= candidate.upper);
    if (!band) return;
    const reading = document.createElement('span');
    reading.textContent = 'AQI ' + aqi + ' · ' + band.label;
    L.rectangle([[lat - half, lon - half], [lat + half, lon + half]], {
        renderer: airQualityRenderer, stroke: false, fillColor: band.color, fillOpacity: .48,
    }).bindPopup(reading).addTo(airQualityLayer);
}
