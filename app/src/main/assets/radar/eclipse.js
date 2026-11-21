'use strict';

let point;

function showVisibilityPoint(id, lines, url, label) {
    if (point?.id !== id) return;
    point.details.replaceChildren();
    for (const line of lines) {
        const row = document.createElement('div');
        row.textContent = line;
        point.details.append(row);
    }
    const link = document.createElement('a');
    link.href = url;
    link.textContent = label + ' ↗';
    point.details.append(link);
    point.popup.update();
}

function setVisibilityPlace(id, name) {
    if (point?.id !== id) return;
    point.title.textContent = name;
    point.popup.update();
}

function initialize(config) {
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    const solar = config.kind === 'solar';
    const lunar = config.kind === 'lunar';
    const source = document.getElementById('source');
    source.href = config.catalogUrl || (lunar ? 'https://opale.imcce.fr/' : 'https://eclipse.gsfc.nasa.gov/SEcat5/beselm.html');
    source.textContent = (lunar ? 'IMCCE / OPALE' : 'NASA / GSFC') + ' ↗';
    source.hidden = config.catalogUrl === config.detailsUrl;
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
    const map = createWeatherMap('map', location, solar ? 4 : 2);
    let sequence = 0;
    let pendingTap;
    map.on('click', ({latlng}) => {
        clearTimeout(pendingTap);
        const coordinates = latlng.wrap();
        const id = ++sequence;
        const title = document.createElement('strong');
        const localName = coordinates.distanceTo(location) < 1000 ? config.place : null;
        title.textContent = localName || coordinates.lat.toFixed(3) + '°, ' + coordinates.lng.toFixed(3) + '°';
        const body = document.createElement('div');
        body.className = 'visibility-point';
        const details = document.createElement('div');
        details.textContent = config.pointLoading;
        details.setAttribute('aria-live', 'polite');
        body.append(title, details);
        const popup = L.popup({maxWidth: 280}).setLatLng(latlng).setContent(body).openOn(map);
        point = {id, title, details, popup};
        pendingTap = setTimeout(() => {
            window.location.assign('/radar/visibility-point?id=' + id
                + '&lat=' + coordinates.lat.toFixed(5) + '&lon=' + coordinates.lng.toFixed(5));
        }, 140);
    });
    const name = document.createElement('span');
    name.textContent = config.place;
    map.createPane('eclipse-location').style.zIndex = 450;
    L.circleMarker(location, {pane: 'eclipse-location', radius: 6, color: 'white', weight: 2,
        fillColor: '#335ba7', fillOpacity: 1}).bindTooltip(name, {direction: 'top', permanent: true}).addTo(map);
    const legend = document.getElementById('bands');
    const status = document.getElementById('coverage-status');
    const exact = solar && config.date === '2027-08-02';

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
        const colors = lunar ? ['#7797ce', '#435e94']
            : solar ? ['#71a3d5', '#70a79a', '#ceb268', '#e69b59', '#d06c76', '#bd548e', '#8958bd']
                : ['#e3ba77', '#c48227'];
        const pane = map.createPane('eclipse-coverage');
        pane.style.zIndex = 400;
        pane.style.opacity = solar ? .5 : .43;
        const renderer = L.canvas({padding: .5, pane: 'eclipse-coverage'});
        for (const {band, rings} of geometry.layers) {
            L.polygon(rings, {renderer, stroke: false, fillColor: colors[band - 1], fillOpacity: 1,
                fillRule: 'evenodd', interactive: false}).addTo(map);
        }
        if (solar) {
            const title = document.createElement('strong');
            title.className = 'band-title';
            title.textContent = config.obscuration;
            legend.append(title);
            ['<20%', '20–39%', '40–59%', '60–79%', '80–89%', '≥90%'].forEach((label, index) =>
                addLegend(label, colors[index]));
        } else {
            addLegend(config.partial, colors[0]);
        }
        if (exact || geometry.central.length) {
            const label = geometry.type.startsWith('A') ? config.annularity
                : geometry.type.startsWith('H') ? config.mixed : config.totality;
            addLegend(label, colors[6]);
            if (!exact) fitCentralPath(geometry.central);
        } else if (!solar && geometry.layers.some(layer => layer.band === 2)) {
            addLegend(config.whole, colors[1]);
        }
    }

    status.textContent = config.loading;
    status.hidden = false;
    const cacheKey = 'eclipse-geometry-v4';
    const id = config.kind + ':' + config.date;
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
    worker.postMessage({date: config.date, kind: config.kind, contacts: config.contacts, useExactPath: exact});
}
