'use strict';

const maximumMapZoom = 23;

function showMapUpdate(config, receivedAt = Date.now()) {
    const updated = document.getElementById('map-updated');
    const format = new Intl.DateTimeFormat(config.language, {
        hour: '2-digit', minute: '2-digit', timeZone: config.timeZone,
        hourCycle: config.clock24 ? 'h23' : 'h12',
    });
    updated.textContent = config.updated + ' · ' + format.format(receivedAt);
    updated.hidden = false;
}

function createWeatherMap(element, center, zoom, options = {}) {
    const map = L.map(element, {minZoom: 0, maxZoom: maximumMapZoom, ...options}).setView(center, zoom);
    map.attributionControl.setPrefix(false);
    const basemap = map.createPane('basemap');
    basemap.style.zIndex = 190;
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        pane: 'basemap', maxZoom: maximumMapZoom, maxNativeZoom: 19,
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
        referrerPolicy: 'strict-origin-when-cross-origin',
    }).addTo(map);
    return map;
}

// Draw only the visible pieces of global imagery so deep zoom never stretches a DOM image to world size.
const GlobalRaster = L.GridLayer.extend({
    initialize(image, latitude, options) {
        L.setOptions(this, {maxZoom: maximumMapZoom, ...options});
        this.image = image;
        this.northLatitude = latitude;
    },

    setImage(image) {
        this.image = image;
        return this.redraw();
    },

    createTile(coords) {
        const size = this.getTileSize();
        const tile = document.createElement('canvas');
        tile.width = size.x;
        tile.height = size.y;
        const worldSize = this._map.options.crs.scale(coords.z);
        const north = this._map.project([this.northLatitude, 0], coords.z).y;
        const rasterHeight = worldSize - 2 * north;
        const inset = rasterHeight * (this.options.verticalInset || 0);
        const tileTop = coords.y * size.y;
        const top = Math.max(tileTop, north + inset);
        const bottom = Math.min(tileTop + size.y, worldSize - north - inset);
        if (bottom <= top) return tile;

        const width = this.image.naturalWidth || this.image.width;
        const height = this.image.naturalHeight || this.image.height;
        const context = tile.getContext('2d');
        context.drawImage(this.image,
            coords.x * size.x * width / worldSize, (top - north) * height / rasterHeight,
            size.x * width / worldSize, (bottom - top) * height / rasterHeight,
            0, top - tileTop, size.x, bottom - top);
        return tile;
    },
});
