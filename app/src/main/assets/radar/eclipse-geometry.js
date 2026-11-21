'use strict';

const radians = Math.PI / 180;
const maxLatitude = 85;
const observerEccentricitySquared = .00669437999014;
const centralBand = 7;
const coverageStep = .5;
const centralStep = .25;
const solarThresholds = [.00001, .2, .4, .6, .8, .9];

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

function solarObscuration(sample, horizontal, vertical, sinLongitude, cosLongitude, partialSlope, centralSlope) {
    const sinH = sample.sinH * cosLongitude + sample.cosH * sinLongitude;
    const cosH = sample.cosH * cosLongitude - sample.sinH * sinLongitude;
    const meridian = horizontal * cosH;
    const zeta = vertical * sample.sinD + meridian * sample.cosD;
    if (zeta <= 0) return 0;
    const dx = sample.x - horizontal * sinH;
    const dy = sample.y - (vertical * sample.cosD - meridian * sample.sinD);
    const separationSquared = dx * dx + dy * dy;
    // The reduced penumbral radius is the sum of the apparent radii; the
    // signed central radius is their difference (Sun minus Moon).
    const sum = sample.partialRadius - zeta * partialSlope;
    if (separationSquared >= sum * sum) return 0;
    const difference = sample.centralRadius - zeta * centralSlope;
    const sun = (sum + difference) / 2;
    const moon = (sum - difference) / 2;
    if (sun <= 0 || moon <= 0) return 0;
    if (separationSquared <= difference * difference) return Math.min(1, (moon / sun) ** 2);

    const distance = Math.sqrt(separationSquared);
    const sunSquared = sun * sun;
    const moonSquared = moon * moon;
    const acos = value => Math.acos(Math.max(-1, Math.min(1, value)));
    const sunAngle = 2 * acos((separationSquared + sunSquared - moonSquared) / (2 * distance * sun));
    const moonAngle = 2 * acos((separationSquared + moonSquared - sunSquared) / (2 * distance * moon));
    const overlap = (sunSquared * (sunAngle - Math.sin(sunAngle))
        + moonSquared * (moonAngle - Math.sin(moonAngle))) / 2;
    return Math.max(0, Math.min(1, overlap / (Math.PI * sunSquared)));
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
    const step = centralStep;
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
    return coverageBands(step, (row, column) => cells.get(row)?.has(column) ? centralBand : 0);
}

function solarGeometry(date, useExactPath) {
    if (typeof solarElements === 'undefined') importScripts('eclipse-elements.js');
    const elements = solarElements[date];
    if (!elements) return null;
    const [type, latitude, longitude, , , , , , , , , , partialSlope, centralSlope] = elements;
    const samples = solarSamples(elements, 3);
    const width = 360 / coverageStep;
    const field = new Float32Array(width * (2 * maxLatitude / coverageStep));
    const bands = coverageBands(coverageStep, (row, column, horizontal, vertical, sinLon, cosLon) => {
        let maximum = 0;
        for (const sample of samples) {
            maximum = Math.max(maximum, solarObscuration(sample, horizontal, vertical, sinLon, cosLon,
                partialSlope, centralSlope));
            if (maximum === 1) break;
        }
        field[row * width + column] = maximum;
        return 0;
    });
    if (!useExactPath && /[ATH]/.test(type)) bands.push(...centralBands(solarSamples(elements, .75), centralSlope));
    return {type, bands, field, center: [latitude, longitude]};
}

function horizonGeometry(contacts) {
    if (contacts.length < 2) return null;
    const samples = contacts.map(([latitude, longitude, horizon]) => {
        const declination = latitude * radians;
        const longitudeRadians = longitude * radians;
        return [Math.sin(declination), Math.cos(declination), Math.sin(longitudeRadians),
            Math.cos(longitudeRadians), Math.sin(horizon * radians)];
    });
    const bands = coverageBands(coverageStep, (_row, _column, _horizontal, _vertical, sinLon, cosLon, latitude) => {
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

function traceContours(bands, step, includes) {
    const width = 360 / step;
    const height = 2 * maxLatitude / step;
    const cells = new Uint8Array(width * height);
    for (const [south, , west, east, band] of bands) {
        if (!includes(band)) continue;
        const offset = Math.round((south + maxLatitude) / step) * width;
        cells.fill(1, offset + Math.round((west + 180) / step), offset + Math.round((east + 180) / step));
    }
    const stride = width + 1;
    const edges = new Map();
    function edge(from, to) {
        if (!edges.has(from)) edges.set(from, []);
        edges.get(from).push(to);
    }
    for (let y = 0; y < height; y++) {
        for (let x = 0; x < width; x++) {
            const cell = y * width + x;
            if (!cells[cell]) continue;
            const corner = y * stride + x;
            if (y === 0 || !cells[cell - width]) edge(corner, corner + 1);
            if (x === width - 1 || !cells[cell + 1]) edge(corner + 1, corner + stride + 1);
            if (y === height - 1 || !cells[cell + width]) edge(corner + stride + 1, corner + stride);
            if (x === 0 || !cells[cell - 1]) edge(corner + stride, corner);
        }
    }
    const directions = [1, stride, -1, -stride];
    const rings = [];
    while (edges.size) {
        const start = edges.keys().next().value;
        const ring = [];
        let previous = start;
        let current = start;
        do {
            ring.push([Math.floor(current / stride), current % stride]);
            const candidates = edges.get(current);
            // At a corner shared by two regions, follow the edge of this region.
            const left = directions[(directions.indexOf(current - previous) + 1) % 4];
            const choice = candidates.length === 2 && candidates[1] - current === left ? 1 : 0;
            const next = candidates.splice(choice, 1)[0];
            if (!candidates.length) edges.delete(current);
            previous = current;
            current = next;
        } while (current !== start);
        rings.push(smoothRing(ring).map(([y, x]) => [y * step - maxLatitude, x * step - 180]));
    }
    return rings;
}

function smoothRing(ring) {
    // Round each join while keeping the contour within the local sampled cells.
    for (let pass = 0; pass < 2; pass++) {
        const rounded = [];
        for (let i = 0; i < ring.length; i++) {
            const [y, x] = ring[i];
            const [nextY, nextX] = ring[(i + 1) % ring.length];
            rounded.push([y * .75 + nextY * .25, x * .75 + nextX * .25],
                [y * .25 + nextY * .75, x * .25 + nextX * .75]);
        }
        ring = rounded;
    }
    return ring;
}

function solarContour(field, threshold) {
    const width = 360 / coverageStep;
    const height = 2 * maxLatitude / coverageStep;
    const stride = width + 2;
    const links = new Map();
    const vertices = new Map();
    function sample(x, y) {
        return x > 0 && x <= width && y > 0 && y <= height ? field[(y - 1) * width + x - 1] : 0;
    }
    function connect(first, second) {
        if (!links.has(first)) links.set(first, []);
        if (!links.has(second)) links.set(second, []);
        links.get(first).push(second);
        links.get(second).push(first);
    }
    for (let y = 0; y <= height; y++) {
        for (let x = 0; x <= width; x++) {
            const values = [sample(x, y), sample(x + 1, y), sample(x + 1, y + 1), sample(x, y + 1)];
            const inside = values.map(value => value >= threshold);
            if (inside.every(Boolean) || !inside.some(Boolean)) continue;
            const corner = y * stride + x;
            const edges = [2 * corner, 2 * (corner + 1) + 1, 2 * (corner + stride), 2 * corner + 1];
            const crossings = [];
            for (let side = 0; side < 4; side++) {
                if (inside[side] === inside[(side + 1) % 4]) continue;
                const edge = edges[side];
                crossings.push(edge);
                if (vertices.has(edge)) continue;
                const point = Math.floor(edge / 2);
                const startX = point % stride;
                const startY = Math.floor(point / stride);
                const vertical = edge % 2 === 1;
                const before = sample(startX, startY);
                const after = sample(startX + (vertical ? 0 : 1), startY + (vertical ? 1 : 0));
                const fraction = (threshold - before) / (after - before);
                vertices.set(edge, [startY + (vertical ? fraction : 0), startX + (vertical ? 0 : fraction)]);
            }
            if (crossings.length === 2) {
                connect(crossings[0], crossings[1]);
            } else {
                const centerInside = values.reduce((sum, value) => sum + value, 0) >= 4 * threshold;
                const paired = centerInside === inside[0];
                connect(edges[0], edges[paired ? 1 : 3]);
                connect(edges[2], edges[paired ? 3 : 1]);
            }
        }
    }
    const rings = [];
    while (links.size) {
        const start = links.keys().next().value;
        const ring = [];
        let previous = -1;
        let current = start;
        do {
            ring.push(vertices.get(current));
            const neighbors = links.get(current);
            const next = neighbors[0] === previous ? neighbors[1] : neighbors[0];
            links.delete(current);
            previous = current;
            current = next;
        } while (current !== start);
        rings.push(smoothRing(ring).map(([y, x]) => [
            Math.max(-maxLatitude, Math.min(maxLatitude, (y - .5) * coverageStep - maxLatitude)),
            Math.max(-180, Math.min(180, (x - .5) * coverageStep - 180)),
        ]));
    }
    return rings;
}

function vectorGeometry(geometry, solar) {
    if (!geometry) return null;
    const {bands, type} = geometry;
    const layers = [];
    const count = solar ? centralBand : 2;
    for (let band = 1; band <= count; band++) {
        const central = solar && band === centralBand;
        const rings = solar && !central ? solarContour(geometry.field, solarThresholds[band - 1])
            : traceContours(bands, central ? centralStep : coverageStep, value =>
                central ? value === centralBand : value >= band);
        if (rings.length) layers.push({band, rings});
    }
    const central = solar ? bands.filter(row => row[4] === centralBand)
        .map(([south, north, west, east]) => [(south + north) / 2, (west + east) / 2]) : [];
    return {type, layers, central};
}

self.onmessage = ({data}) => {
    const {date, kind, contacts, useExactPath} = data;
    const solar = kind === 'solar';
    self.postMessage(vectorGeometry(solar ? solarGeometry(date, useExactPath) : horizonGeometry(contacts), solar));
};
