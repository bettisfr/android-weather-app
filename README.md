# Local Weather

Local Weather is a native Android app for checking the weather where you are and in places you save. The interface is in US English.

## Features

- Current conditions for the phone's location, with manual search and saved locations.
- A scrollable 24-hour forecast and a 10-day forecast with a detail screen for each day.
- Hourly charts for precipitation, wind, and humidity.
- Sunrise, solar noon, sunset, civil twilight, and daylight duration, plus an expandable table of sunrise and sunset times for the next ten days.
- Configurable units for temperature, wind speed, and pressure.

## Weather data

[Open-Meteo](https://open-meteo.com/) supplies the forecasts and location search. Where available, the app uses [ItaliaMeteo/ARPAE ICON-2I](https://open-meteo.com/en/docs/italia-meteo-arpae-api) for the first three days and ECMWF IFS for the extended forecast. Open-Meteo's Best Match fills in missing forecast fields. Solar noon and civil twilight are calculated in the app using [NOAA solar equations](https://gml.noaa.gov/grad/solcalc/solareqns.PDF).

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

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
