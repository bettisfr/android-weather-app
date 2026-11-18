# SkyHere

SkyHere is a native Android app for checking the weather where you are and in places you save. The interface follows Android’s language settings and is available in English and Italian.

## Features

- Current conditions for the phone's location, with manual search and saved locations.
- Forecast refresh when returning to the app after five minutes or when the local hour changes.
- A scrollable 24-hour forecast and a 10-day forecast with a detail screen for each day.
- Hourly charts for precipitation amount and probability, wind and gusts, humidity, and actual and feels-like temperatures.
- Rain icons that distinguish light, moderate, and heavy rain, plus layouts that adapt to larger Android text sizes.
- Sunrise, solar noon, sunset, civil twilight, and daylight duration, plus an expandable table of sunrise and sunset times for the next ten days.
- Moon phase and illuminated percentage for today and the next ten days, with moonrise, moonset, and the dates of the next full and new moons.
- Solar and lunar eclipses and planetary transits visible from the selected location, with local times, visible duration, and links to event details.
- A Settings panel with configurable units and time format. Defaults are °C, km/h, hPa, and a 24-hour clock. Precipitation is shown in mm.

## Data sources

- Forecasts and location search come from Open-Meteo. Where available, the app uses ItaliaMeteo/ARPAE ICON-2I for the first three days and ECMWF IFS for the extended forecast. Best Match fills in missing forecast fields. [Open-Meteo](https://open-meteo.com/) · [ICON-2I](https://open-meteo.com/en/docs/italia-meteo-arpae-api)
- Open-Meteo supplies moonrise, moonset, and daily phase values. SkyHere estimates illumination from the phase and calculates the primary lunar phase dates, solar noon, and civil twilight on the device. [Open-Meteo forecast API](https://open-meteo.com/en/docs) · [NOAA solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF)
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
