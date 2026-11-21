'use strict';

const radians = Math.PI / 180;
const maxLatitude = 85;
const observerEccentricitySquared = .00669437999014;

function observer(latitude) {
    const phi = latitude * radians;
    const sin = Math.sin(phi);
    const normal = 1 / Math.sqrt(1 - observerEccentricitySquared * sin * sin);
    return [Math.cos(phi) * normal, (1 - observerEccentricitySquared) * sin * normal];
}

function polynomial(coefficients, hour) {
    return coefficients.reduceRight((value, coefficient) => value * hour + coefficient, 0);
}

function solarSamples(elements, stepMinutes) {
    const [, , , deltaT, start, end, x, y, d, mu, l1, l2] = elements;
    const count = Math.ceil((end - start) * 60 / stepMinutes);
    return Array.from({length: count + 1}, (_, index) => {
        const time = start + (end - start) * index / count;
        const declination = polynomial(d, time) * radians;
        const hourAngle = (polynomial(mu, time) - deltaT * 360 / 86164) * radians;
        return {
            x: polynomial(x, time), y: polynomial(y, time),
            sinD: Math.sin(declination), cosD: Math.cos(declination),
            sinH: Math.sin(hourAngle), cosH: Math.cos(hourAngle), hourAngle,
            partialRadius: polynomial(l1, time), centralRadius: polynomial(l2, time),
        };
    });
}

function insideShadow(sample, horizontal, vertical, sinLongitude, cosLongitude, radius, slope) {
    const sinH = sample.sinH * cosLongitude + sample.cosH * sinLongitude;
    const cosH = sample.cosH * cosLongitude - sample.sinH * sinLongitude;
    const meridian = horizontal * cosH;
    const zeta = vertical * sample.sinD + meridian * sample.cosD;
    if (zeta <= 0) return false;
    const x = sample.x - horizontal * sinH;
    const y = sample.y - (vertical * sample.cosD - meridian * sample.sinD);
    const size = radius - zeta * slope;
    return x * x + y * y <= size * size;
}

// Encode contiguous cells as [south, north, west, east, band]. Longitude is split at ±180°.
function coverageBands(step, classify) {
    const longitudes = Array.from({length: Math.round(360 / step)}, (_, i) => {
        const angle = (-180 + (i + .5) * step) * radians;
        return [Math.sin(angle), Math.cos(angle)];
    });
    const bands = [];
    for (let row = 0; row < 2 * maxLatitude / step; row++) {
        const south = -maxLatitude + row * step;
        const north = Math.min(maxLatitude, south + step);
        const latitude = (south + north) / 2;
        const [horizontal, vertical] = observer(latitude);
        let runBand = 0;
        let runStart = 0;
        for (let column = 0; column <= longitudes.length; column++) {
            const band = column === longitudes.length ? 0
                : classify(row, column, horizontal, vertical, ...longitudes[column], latitude);
            if (band === runBand) continue;
            if (runBand) bands.push([south, north, runStart * step - 180, column * step - 180, runBand]);
            runStart = column;
            runBand = band;
        }
    }
    return bands;
}

function centerOfShadow(sample) {
    const distanceSquared = sample.x * sample.x + sample.y * sample.y;
    if (distanceSquared >= 1) return null;
    const zeta = Math.sqrt(1 - distanceSquared);
    const sinLatitude = sample.y * sample.cosD + zeta * sample.sinD;
    const latitude = Math.asin(Math.max(-1, Math.min(1, sinLatitude))) / radians;
    const meridian = zeta * sample.cosD - sample.y * sample.sinD;
    const longitude = (Math.atan2(sample.x, meridian) - sample.hourAngle) / radians;
    return [latitude, ((longitude + 540) % 360) - 180];
}

function centralBands(samples, slope) {
    const step = .25;
    const cellColumns = 360 / step;
    const cells = new Map();
    for (const sample of samples) {
        const center = centerOfShadow(sample);
        if (!center) continue;
        const [latitude, longitude] = center;
        const spread = 4 / Math.max(.12, Math.cos(latitude * radians));
        for (let row = Math.max(0, Math.floor((latitude + maxLatitude - 4) / step));
                row < Math.min(2 * maxLatitude / step, (latitude + maxLatitude + 4) / step); row++) {
            const [horizontal, vertical] = observer(-maxLatitude + (row + .5) * step);
            for (let column = Math.floor((longitude + 180 - spread) / step);
                    column < (longitude + 180 + spread) / step; column++) {
                const wrappedColumn = (column + cellColumns) % cellColumns;
                if (cells.get(row)?.has(wrappedColumn)) continue;
                const angle = (-180 + (wrappedColumn + .5) * step) * radians;
                if (!insideShadow(sample, horizontal, vertical, Math.sin(angle), Math.cos(angle),
                        sample.centralRadius, slope)) continue;
                if (!cells.has(row)) cells.set(row, new Set());
                cells.get(row).add(wrappedColumn);
            }
        }
    }
    return coverageBands(step, (row, column) => cells.get(row)?.has(column) ? 2 : 0);
}

function solarGeometry(date, useExactPath) {
    if (typeof solarElements === 'undefined') importScripts('eclipse-elements.js');
    const elements = solarElements[date];
    if (!elements) return null;
    const [type, latitude, longitude, , , , , , , , , , partialSlope, centralSlope] = elements;
    const samples = solarSamples(elements, 3);
    const bands = coverageBands(1, (_row, _column, horizontal, vertical, sinLon, cosLon) =>
        samples.some(sample => insideShadow(sample, horizontal, vertical, sinLon, cosLon,
            sample.partialRadius, partialSlope)) ? 1 : 0);
    if (!useExactPath && /[ATH]/.test(type)) bands.push(...centralBands(solarSamples(elements, .75), centralSlope));
    return {type, bands, center: [latitude, longitude]};
}

function lunarGeometry(contacts) {
    if (contacts.length < 2) return null;
    const samples = contacts.map(([latitude, longitude, horizon]) => {
        const declination = latitude * radians;
        const longitudeRadians = longitude * radians;
        return [Math.sin(declination), Math.cos(declination), Math.sin(longitudeRadians),
            Math.cos(longitudeRadians), Math.sin(horizon * radians)];
    });
    const bands = coverageBands(1, (_row, _column, _horizontal, _vertical, sinLon, cosLon, latitude) => {
        const phi = latitude * radians;
        const sinLat = Math.sin(phi);
        const cosLat = Math.cos(phi);
        let visible = 0;
        for (const [sinD, cosD, sinMoonLon, cosMoonLon, horizon] of samples) {
            const altitude = sinLat * sinD + cosLat * cosD * (cosLon * cosMoonLon + sinLon * sinMoonLon);
            if (altitude >= horizon) visible++;
        }
        return visible === 0 ? 0 : visible === samples.length ? 2 : 1;
    });
    return {bands};
}

self.onmessage = ({data}) => {
    const {date, lunar, contacts, useExactPath} = data;
    self.postMessage(lunar ? lunarGeometry(contacts) : solarGeometry(date, useExactPath));
};
