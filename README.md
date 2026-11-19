# SkyHere

SkyHere is an Android app for local weather, precipitation radar, and skywatching. Use your current location or choose a saved city. The interface follows Android’s language settings and is available in English and Italian.

## Screenshots

<p>
  <img src="docs/screenshots/forecast.png" width="240" alt="Current weather in Bastia Umbra, regional wind warnings, and the 10-day forecast" />
  <img src="docs/screenshots/hourly-details.png" width="240" alt="Current conditions and the hourly precipitation chart" />
  <img src="docs/screenshots/astronomy.png" width="240" alt="Sun and Moon paths, rise and set times, and lunar phase" />
</p>

<p>
  <img src="docs/screenshots/aurora.png" width="240" alt="Aurora visibility in Abisko, current Kp, and forecast bars" />
  <img src="docs/screenshots/aurora-map.png" width="240" alt="Interactive NOAA aurora map centered on Abisko" />
</p>

## Features

- Current conditions for the phone's location, with manual search and saved locations.
- Pull down from the top of the forecast to refresh it manually. The app also refreshes when you return after five minutes or when the local hour changes.
- A scrollable 24-hour forecast and a 10-day forecast. Daily icons summarize the most frequent condition during daylight hours, with notable events and their approximate times in the day details.
- Hourly charts for precipitation amount and probability, wind and gusts, humidity, and actual and feels-like temperatures.
- Interactive precipitation radar for Italian locations. Pan and zoom the map, scrub through the previous hour, or play the observed five-minute frames with a short crossfade.
- Regional weather warnings for Italian locations, with the hazard, severity, validity period, and a link to MeteoAlarm.
- Illustrated weather conditions, including separate symbols for rain, showers, snow, and hail, plus high-contrast snowflakes. [Weather icon catalog](docs/weather-icons.html)
- Matching Sun and Moon path charts with a marker for the current position. Azimuth and elevation update every minute while the app is open and are shown when the body is at or above the horizon.
- Sunrise, solar noon, sunset, civil twilight, polar night, and midnight sun. During polar night or midnight sun, the whole solar curve stays on one side of the horizon. The expandable 15-day table shows maximum solar elevation and second-resolution sunrise, sunset, and daylight duration.
- Current Moon phase and illuminated percentage, dates of the next full and new moons, and an expandable 15-day table with phases, illumination, and second-resolution moonrise and moonset.
- Today’s estimated clear-sky viewing windows for Mercury, Venus, Mars, Jupiter, and Saturn, with live compass direction, azimuth, elevation, and apparent magnitude when above the horizon. The current status turns green during a viewing window.
- Live phone azimuth and elevation below the planets. Point the rear camera at the sky and compare the sensor readings with a planet’s coordinates. The app uses geographic north when the phone’s location is available and labels magnetic north otherwise.
- Short-term aurora visibility estimates for either hemisphere, based on nearby auroral activity, darkness, and cloud cover. The interactive map shows the NOAA forecast and the selected location. The card also shows the latest estimated Kp and upcoming three-hour Kp forecasts on a scale of 0–9, in local time.
- Solar and lunar eclipses and planetary transits visible from the selected location, with local times, visible duration, and links to event details.
- Light, Dark, and System themes, layouts that adapt to larger Android text sizes, and an adaptive crescent-and-horizon launcher icon.
- Configurable units and time format. Defaults are °C, km/h, hPa, and a 24-hour clock. Precipitation is shown in mm.

## Data sources

- Forecasts and location search come from Open-Meteo. Where available, the app uses ItaliaMeteo/ARPAE ICON-2I for the first three days and ECMWF IFS for the extended forecast. Best Match fills in missing forecast fields. [Open-Meteo](https://open-meteo.com/) · [ICON-2I](https://open-meteo.com/en/docs/italia-meteo-arpae-api)
- Open-Meteo supplies rise and set times for the Sun and Moon and daily lunar phase values. SkyHere calculates daylight duration, lunar illumination, primary lunar phase dates, solar noon, civil twilight, and approximate planetary positions and magnitudes on the device. [Open-Meteo forecast API](https://open-meteo.com/en/docs) · [NOAA solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF) · [Paul Schlyter's orbital calculations](https://www.stjarnhimlen.se/comp/ppcomp.html)
- The radar overlays estimated surface rainfall intensity from Radar-DPC on an OpenStreetMap basemap. The radar data and colorized overlay are attributed to Radar-DPC under CC BY-SA 4.0. The map uses Leaflet. [Radar-DPC](https://mappe.protezionecivile.gov.it/it/mappe-e-dashboard-rischi/piattaforma-radar/) · [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) · [OpenStreetMap attribution](https://www.openstreetmap.org/copyright) · [Leaflet licence](app/src/main/assets/radar/LEAFLET-LICENSE)
- Regional weather warnings come from the public MeteoAlarm Atom feed for Italy. [MeteoAlarm Italy](https://feeds.meteoalarm.org/feeds/meteoalarm-legacy-atom-italy)
- Aurora maps and short-term activity forecasts use NOAA SWPC’s OVATION model. SkyHere estimates local visibility using distance from the forecast activity, solar elevation, and Open-Meteo cloud cover. NOAA also provides the estimated and forecast global Kp index. [NOAA aurora forecast](https://www.spaceweather.gov/products/aurora-30-minute-forecast) · [Estimated Kp](https://services.swpc.noaa.gov/json/planetary_k_index_1m.json) · [Kp forecast](https://services.swpc.noaa.gov/products/noaa-planetary-k-index-forecast.json)
- Eclipse data comes from IMCCE OPALE. Transit visibility windows are estimated using the selected location and the NASA catalog. [IMCCE OPALE](https://opale.imcce.fr/) · [Transit Predictions by Fred Espenak, NASA/GSFC](https://eclipse.gsfc.nasa.gov/transit/catalog/Visible.html)

The app asks for location access while in use. Android resolves the name of the detected location, and city search remains available if location access is denied or unavailable.

## Build and run

Use JDK 17 and Android SDK Platform 34. The app runs on Android 8.0 (API 26) or later. Set `ANDROID_HOME` to your SDK directory or open the project in Android Studio to configure it.

```sh
./gradlew :app:assembleDebug
```

To install the debug build on a connected device or running emulator:

```sh
./gradlew :app:installDebug
```

The installable debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. You can copy it to an Android device to install it for testing.
