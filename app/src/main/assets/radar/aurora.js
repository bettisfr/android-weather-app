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
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    document.getElementById('source').href = config.source;
    document.querySelector('.legend').setAttribute('aria-label', config.intensity);
    document.getElementById('low').textContent = config.low;
    document.getElementById('high').textContent = config.high;
    timeFormat = new Intl.DateTimeFormat(config.language, {
        month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
        hourCycle: config.clock24 ? 'h23' : 'h12', timeZone: config.timeZone,
    });
    status(config.loading);
    map = L.map('map', {minZoom: 2, maxZoom: 11, worldCopyJump: true})
        .setView([Math.max(-limit, Math.min(limit, config.lat)), config.lon], 3);
    map.attributionControl.setPrefix(false);
    const basemap = map.createPane('basemap');
    basemap.style.zIndex = 190;
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        pane: 'basemap',
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
        referrerPolicy: 'strict-origin-when-cross-origin',
    }).addTo(map);
    const pin = L.circleMarker([config.lat, config.lon], {
        radius: 7, color: '#fff', weight: 3, fillColor: '#273b6a', fillOpacity: 1, interactive: false,
    }).addTo(map);
    pin.bringToFront();
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
            if (value < 5) continue;
            context.fillStyle = colors[value];
            const x = column * 2 - 1;
            context.fillRect(x, top, 2, bottom - top);
            if (column === 0) context.fillRect(canvas.width - 1, top, 1, bottom - top);
        }
    }

    const overlay = canvas.toDataURL();
    for (const shift of [-360, 0, 360]) {
        L.imageOverlay(overlay, [[-limit, -180 + shift], [limit, 180 + shift]], {
            interactive: false, alt: strings.intensity, zIndex: 1,
        }).addTo(map);
    }
    document.getElementById('forecast').textContent = strings.forecast + ' · ' + timeFormat.format(validTime);
    status('');
}
