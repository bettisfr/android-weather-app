'use strict';

const limit = 85.05112878;
let map;
let strings;
let timeFormat;

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
        document.getElementById('source').textContent = 'EEA';
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
}

function mercatorY(latitude, height) {
    const radians = Math.max(-limit, Math.min(limit, latitude)) * Math.PI / 180;
    return (1 - Math.asinh(Math.tan(radians)) / Math.PI) * height / 2;
}

function setAurora(encoded, validTime) {
    const cells = atob(encoded);
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
}

const eeaImageServer = 'https://air.discomap.eea.europa.eu/arcgis/rest/services/AQMobile_2025/MOSAIC_GLOBAL_AQI/ImageServer';
let airQualityOverlay;
let requestedAirQualityImage;

function outsideAirQualityCoverage(bounds) {
    return bounds.getEast() < -35 || bounds.getWest() > 55
        || bounds.getNorth() < 25 || bounds.getSouth() > 74;
}

function initializeAirQuality() {
    document.getElementById('forecast').textContent = strings.forecast + ' · ' + timeFormat.format(strings.time);
    map.on('moveend', showAirQualityImage);
    map.on('click', showAirQualityReading);
    showAirQualityImage();
}

function showAirQualityImage() {
    requestedAirQualityImage = null;
    const bounds = map.getBounds();
    if (outsideAirQualityCoverage(bounds)) {
        if (airQualityOverlay) airQualityOverlay.remove();
        airQualityOverlay = null;
        status(strings.coverage);
        return;
    }
    const sw = map.options.crs.project(bounds.getSouthWest());
    const ne = map.options.crs.project(bounds.getNorthEast());
    const size = map.getSize();
    const density = Math.min(2, window.devicePixelRatio || 1);
    const colormap = strings.bands.map((band, index) => {
        const rgb = parseInt(band.color.substring(1), 16);
        return [index + 1, (rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255];
    });
    const params = new URLSearchParams({
        f: 'image', bbox: [sw.x, sw.y, ne.x, ne.y].join(','), bboxSR: 3857, imageSR: 3857,
        size: [Math.max(1, Math.ceil(size.x * density)), Math.max(1, Math.ceil(size.y * density))].join(','),
        format: 'png32', transparent: 'true', time: strings.time, interpolation: 'RSP_NearestNeighbor',
        mosaicRule: JSON.stringify({
            ascending: false, mosaicMethod: 'esriMosaicAttribute', mosaicOperation: 'MT_FIRST',
            sortField: 'StdTime', sortValue: '0',
        }),
        renderingRule: JSON.stringify({rasterFunction: 'Colormap', rasterFunctionArguments: {Colormap: colormap}}),
    });
    const image = new Image();
    requestedAirQualityImage = image;
    image.onload = () => {
        if (requestedAirQualityImage !== image) return;
        if (airQualityOverlay) airQualityOverlay.remove();
        airQualityOverlay = L.imageOverlay(image, bounds, {opacity: .65, interactive: false, alt: ''}).addTo(map);
        showMapUpdate(strings);
        status('');
    };
    image.onerror = () => {
        if (requestedAirQualityImage === image) status(strings.unavailable);
    };
    image.src = eeaImageServer + '/exportImage?' + params;
}

async function showAirQualityReading({latlng}) {
    if (outsideAirQualityCoverage(L.latLngBounds(latlng, latlng))) return;
    const text = document.createElement('span');
    text.textContent = strings.loading;
    L.popup().setLatLng(latlng).setContent(text).openOn(map);
    const params = new URLSearchParams({
        f: 'json', geometryType: 'esriGeometryPoint',
        geometry: JSON.stringify({x: latlng.lng, y: latlng.lat, spatialReference: {wkid: 4326}}),
        mosaicRule: JSON.stringify({
            ascending: true, mosaicMethod: 'esriMosaicNone', mosaicOperation: 'MT_FIRST',
            multidimensionalDefinition: [{variableName: 'AQI', dimensionName: 'StdTime', values: [strings.time], isSlice: true}],
        }),
        returnGeometry: 'false', returnCatalogItems: 'false', returnPixelValues: 'true', processAsMultidimensional: 'true',
    });
    try {
        const response = await fetch(eeaImageServer + '/identify?' + params);
        if (!response.ok) throw new Error('Could not read the EEA air quality band');
        const reading = await response.json();
        const band = strings.bands[Number(reading.value) - 1];
        text.textContent = band ? band.label : strings.unavailable;
    } catch (error) {
        text.textContent = strings.unavailable;
    }
}
