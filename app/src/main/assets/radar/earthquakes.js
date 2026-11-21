'use strict';

let map;
let config;
let timeFormat;
let numberFormat;
let markers;

function status(message) {
    const note = document.getElementById('status');
    note.textContent = message;
    note.hidden = !message;
}

function initialize(parameters) {
    config = parameters;
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    document.getElementById('scope').textContent = config.scope;
    document.getElementById('source').href = config.source;
    const legend = document.getElementById('legend');
    legend.setAttribute('aria-label', config.magnitudeLabel);
    legend.textContent = config.magnitudeLabel;
    for (const {label, radius, color} of config.magnitudeBands) {
        const entry = document.createElement('span');
        entry.className = 'band';
        const dot = document.createElement('span');
        dot.className = 'dot';
        dot.style.setProperty('--size', (2 * radius) + 'px');
        dot.style.setProperty('--color', color);
        entry.append(dot, document.createTextNode(label));
        legend.append(entry);
    }
    timeFormat = new Intl.DateTimeFormat(config.language, {
        day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit',
        timeZone: config.timeZone, hourCycle: config.clock24 ? 'h23' : 'h12',
    });
    numberFormat = new Intl.NumberFormat(config.language, {minimumFractionDigits: 1, maximumFractionDigits: 1});
    const center = [config.lat, config.lon];
    map = createWeatherMap('map', center, 8, {preferCanvas: true});
    const coverage = L.circle(center, {
        radius: config.radiusKm * 1000, color: '#546eaa', dashArray: '6 5', weight: 2,
        fillOpacity: .05, interactive: false,
    }).addTo(map);
    map.fitBounds(coverage.getBounds(), {padding: [20, 20], maxZoom: 15});
    L.circleMarker(center, {radius: 6, color: 'white', weight: 2, fillColor: '#435e94', fillOpacity: 1, interactive: false})
        .addTo(map);
    markers = L.layerGroup().addTo(map);
    map.on('click', ({containerPoint}) => {
        let closest;
        let distance = 22;
        markers.eachLayer(marker => {
            const offset = map.latLngToContainerPoint(marker.getLatLng()).distanceTo(containerPoint);
            if (offset <= distance) {
                closest = marker;
                distance = offset;
            }
        });
        if (closest) closest.openPopup();
    });
    status(config.loading);
}

function showEarthquakes(events) {
    markers.clearLayers();
    for (const event of [...events].sort((first, second) => first.magnitude - second.magnitude)) {
        const popup = document.createElement('div');
        const title = document.createElement('strong');
        title.textContent = event.magnitudeType + ' ' + numberFormat.format(event.magnitude) + ' · ' + event.zone;
        const when = document.createElement('div');
        when.textContent = timeFormat.format(event.time);
        const depth = document.createElement('div');
        depth.textContent = config.depthLabel + ' ' + numberFormat.format(event.depth) + ' km';
        const link = document.createElement('a');
        link.href = event.url;
        link.textContent = 'INGV ↗';
        popup.append(title, when, depth, link);
        const band = config.magnitudeBands.find(item => item.limit === undefined || event.magnitude < item.limit);
        L.circleMarker([event.lat, event.lon], {
            radius: band.radius, color: '#303942', weight: 1, fillColor: band.color, fillOpacity: .9, interactive: false,
        }).bindPopup(popup).addTo(markers);
    }
    status(events.length ? '' : config.empty);
}
