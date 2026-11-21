'use strict';

function initialize(config) {
    document.documentElement.lang = config.language;
    document.documentElement.classList.toggle('dark', config.dark);
    document.getElementById('map').setAttribute('aria-label', config.mapLabel);
    document.getElementById('band').textContent = config.band;
    document.getElementById('source').href = 'https://eclipse.gsfc.nasa.gov/SEpath/SEpath2001/SE2027Aug02Tpath.html';
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
    const {north, south, center} = eclipsePath2027;
    const map = createWeatherMap('map', location, 4);
    L.polygon([...north, ...[...south].reverse()], {
        color: '#7145a6', fillColor: '#8958bd', weight: 2, fillOpacity: .37, interactive: false,
    }).addTo(map);
    L.polyline(center, {color: '#7145a6', weight: 2, dashArray: '4 5', interactive: false}).addTo(map);
    const name = document.createElement('span');
    name.textContent = config.place;
    L.circleMarker(location, {radius: 6, color: 'white', weight: 2, fillColor: '#335ba7', fillOpacity: 1})
        .bindTooltip(name, {direction: 'top', permanent: true})
        .addTo(map);

    let closest = L.latLng(center[0]);
    for (const point of center) {
        if (location.distanceTo(point) < location.distanceTo(closest)) closest = L.latLng(point);
    }
    map.fitBounds(L.latLngBounds([location, closest]).pad(.25), {padding: [34, 34], maxZoom: 5});
}
