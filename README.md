# Sugarglass

A tiny Android app that shows your glucose from **Nightscout** as a glass-style home screen widget, a persistent notification and a number in the status bar. Nothing else.

<!-- screenshots: add widget (light and dark), home screen and notification to docs/ and link them here -->

## What it does

- **Widget, any size.** 1x1 shows the value; 2x1 and wider add the last 3 hours as a line, blurred where it passes behind the number. A soft glow from the bottom turns green, yellow or red with your range.
- **Persistent notification** with value, trend arrow, 5-minute delta and age.
- **Status bar number**, so you see your glucose without pulling the shade down.
- **mg/dL or mmol/L**, and your own urgent low / low / high / urgent high ranges.
- **Light and dark mode**, and on Android 12+ the glass picks up your wallpaper colours (Material You).
- **Adjustable glow**, from off to vivid.
- **In English, Italiano, Français, Deutsch, Español, Português, Nederlands and Polski**, following the system or picked in the settings. Translation fixes are very welcome.

What it deliberately does **not** do: alarms, treatments, uploading, statistics. Use your CGM app or xDrip+/AAPS for those. Sugarglass only reads.

## Install

1. Download the latest `sugarglass-x.y.z.apk` from [Releases](../../releases) and open it on your phone.
2. Open Sugarglass, enter your Nightscout address and, if your site is not public, a token (recommended, with the `readable` role) or your API secret. Tap **Save and start**.
3. Allow notifications and let it **ignore battery optimisation**: without that Android may stop it.
4. Long-press the home screen and add the Sugarglass widget.

### Xiaomi / HyperOS / MIUI, Samsung, Huawei, OnePlus

These systems kill background apps aggressively. Also turn on **Autostart**, set battery to **No restrictions**, and lock the app in the recents screen. See [dontkillmyapp.com](https://dontkillmyapp.com) for your phone.

## Privacy

Sugarglass talks to your Nightscout site and nothing else. Your address and token stay on your phone. No analytics, no ads, no account.

Permissions: internet (Nightscout), notifications, foreground service (to stay alive), start at boot, and the request to be excluded from battery optimisation.

## Not a medical device

Sugarglass is a hobby project and is not a medical device. It can show old, wrong or missing data. **Do not use it to make treatment decisions**; always check your CGM app or a meter.

## Build

JDK 17 and the Android SDK (platform 34).

```
./gradlew test assembleDebug
```

Release builds are signed with a key kept outside the repo: create `keystore.properties` in the project root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`, then `./gradlew assembleRelease`.

## License

MIT
