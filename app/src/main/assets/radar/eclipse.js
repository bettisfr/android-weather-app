'use strict';

function initialize(config) {
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    const source = document.getElementById('source');
    source.href = config.lunar ? 'https://opale.imcce.fr/' : 'https://eclipse.gsfc.nasa.gov/SEcat5/beselm.html';
    source.textContent = (config.lunar ? 'IMCCE / OPALE' : 'NASA / GSFC') + ' ↗';
    const details = document.getElementById('details');
    details.href = config.detailsUrl;
    details.textContent = config.openDetails + ' ↗';

    const local = document.getElementById('local');
    for (const [index, line] of config.localDetails.entries()) {
        const element = document.createElement(index === 0 ? 'strong' : 'div');
        element.textContent = line;
        local.append(element);
    }

    const location = L.latLng(config.lat, config.lon);
    const map = createWeatherMap('map', location, config.lunar ? 2 : 4);
    const name = document.createElement('span');
    name.textContent = config.place;
    map.createPane('eclipse-location').style.zIndex = 450;
    L.circleMarker(location, {pane: 'eclipse-location', radius: 6, color: 'white', weight: 2,
        fillColor: '#335ba7', fillOpacity: 1}).bindTooltip(name, {direction: 'top', permanent: true}).addTo(map);
    const legend = document.getElementById('bands');
    const status = document.getElementById('coverage-status');
    const exact = !config.lunar && config.date === '2027-08-02';

    function fitCentralPath(points) {
        let closest = L.latLng(points[0]);
        for (const point of points) {
            if (location.distanceTo(point) < location.distanceTo(closest)) closest = L.latLng(point);
        }
        closest.lng = location.lng + ((closest.lng - location.lng + 540) % 360) - 180;
        map.fitBounds(L.latLngBounds([location, closest]).pad(.25), {padding: [34, 34], maxZoom: 5});
    }

    if (exact) {
        const {north, south, center} = eclipsePath2027;
        map.createPane('eclipse-path').style.zIndex = 420;
        L.polygon([...north, ...[...south].reverse()], {pane: 'eclipse-path', color: '#7145a6',
            fillColor: '#8958bd', weight: 2, fillOpacity: .37, interactive: false}).addTo(map);
        L.polyline(center, {pane: 'eclipse-path', color: '#7145a6', weight: 2,
            dashArray: '4 5', interactive: false}).addTo(map);
        fitCentralPath(center);
        source.href = 'https://eclipse.gsfc.nasa.gov/SEpath/SEpath2001/SE2027Aug02Tpath.html';
    }

    function addLegend(text, color) {
        const item = document.createElement('span');
        item.className = 'band';
        item.style.setProperty('--band-color', color);
        item.textContent = text;
        legend.append(item);
    }

    function render(geometry) {
        status.textContent = geometry ? '' : config.unavailable;
        status.hidden = Boolean(geometry);
        if (!geometry) {
            if (exact) addLegend(config.totality, '#8958bd');
            return;
        }
        const note = document.getElementById('coverage-note');
        note.textContent = config.approximate;
        note.hidden = false;
        const colors = config.lunar ? ['#7797ce', '#435e94'] : ['#a8a0ce', '#8958bd'];
        const renderer = L.canvas({padding: .5});
        const central = [];
        for (const [south, north, west, east, band] of geometry.bands) {
            L.rectangle([[south, west], [north, east]], {
                renderer, stroke: false, fillColor: colors[band - 1], fillOpacity: band === 2 ? .47 : .36,
                interactive: false,
            }).addTo(map);
            if (!config.lunar && band === 2) central.push([(south + north) / 2, (west + east) / 2]);
        }
        addLegend(config.partial, colors[0]);
        if (exact || central.length) {
            const label = geometry.type.startsWith('A') ? config.annularity
                : geometry.type.startsWith('H') ? config.mixed : config.totality;
            addLegend(label, colors[1]);
            if (!exact) fitCentralPath(central);
        } else if (config.lunar && geometry.bands.some(band => band[4] === 2)) {
            addLegend(config.whole, colors[1]);
        }
    }

    status.textContent = config.loading;
    status.hidden = false;
    const cacheKey = 'eclipse-geometry-v1';
    const id = (config.lunar ? 'lunar:' : 'solar:') + config.date;
    try {
        const cached = JSON.parse(localStorage.getItem(cacheKey) || 'null');
        if (cached?.id === id) {
            render(cached.geometry);
            return;
        }
    } catch (error) {
        // The geometry can still be generated when storage is unavailable.
    }
    const worker = new Worker('eclipse-geometry.js');
    worker.onmessage = ({data}) => {
        worker.terminate();
        if (data) {
            try { localStorage.setItem(cacheKey, JSON.stringify({id, geometry: data})); } catch (error) { }
        }
        render(data);
    };
    worker.onerror = () => {
        worker.terminate();
        render(null);
    };
    worker.postMessage({date: config.date, lunar: config.lunar, contacts: config.contacts, useExactPath: exact});
}
