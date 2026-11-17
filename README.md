# Roadwise

An Android GPS navigation app that mixes Google Maps–style search and routing with Waze-style community alerts, and works fully offline.

## What it does

**Maps and search (like Google Maps)**
- Vector map with automatic day/night styles (switches at local sunset), a **Satellite** view (Esri World Imagery aerial photos with street labels, from the Layers button), 3D driving view and a compass
- Search for places, addresses and coordinates, with voice search and "nearby" categories (gas, food, coffee, parking, EV charging, groceries, hotels, hospitals)
- Saved places (Home, Work, Favorites), recent searches, sharing a place, and opening `geo:` links from other apps
- Routes for driving, biking and walking, up to 3 alternatives, and options to avoid tolls, highways or ferries

**Navigation and alerts (like Waze)**
- Turn-by-turn voice guidance (text-to-speech on the phone, so it works offline), automatic rerouting, ETA and arrival time
- Speed-limit sign and speedometer, with a chime when you're over the limit (you set the tolerance)
- Community reports: police, crash, hazard, traffic, closure, speed trap, roadwork and weather. Each can be confirmed or dismissed ("still there" / "not there"), and you get spoken alerts when one is on your route
- Road-closure reports are routed around automatically
- Speed-camera alerts from OpenStreetMap
- "Preview" button that drives a route with simulated GPS, for demos and testing

**Share location**
- Send your current location as a map link that works in any messaging app
- While navigating, share your trip and ETA
- Live location for 15 minutes, 1 hour, 8 hours or until you arrive. People with the link watch you move on a web map in their browser; they don't need the app

**Android Auto**
- The car screen shows the same live map, with turn-by-turn cards (including on the instrument cluster in cars that support it).
- You can search with Home, Work, recents and nearby gas, food, coffee and EV charging, pick a route and tap Go.
- Hazard reports are one tap, "Hey Google, navigate to …" works, and you can pan and zoom the map.
- Because Roadwise is sideloaded (not from the Play Store), Android Auto only lists it after you turn on **Unknown sources**: Android Auto settings → tap Version 10 times to unlock developer settings → ⋮ → Developer settings → Unknown sources.

**Offline**
- One tap downloads your whole state. You can also pick several states from a list, or draw an area on the map
- Each download includes:
  - the map (day and night styles)
  - routing data for on-phone turn-by-turn directions (BRouter)
  - a search index of places and streets
  - speed cameras
- If the phone has no data connection, the app switches to offline routing and search automatically

## Layout
- `app/`: Android app (Kotlin, Jetpack Compose, MapLibre)
- `brouter/`: the BRouter offline routing engine, bundled (MIT licence, see `LICENSE-BRouter.txt`)
- `server/`: reports and live-location server (Node, no dependencies)

## Build
```
export JAVA_HOME="D:\Android SDK\jbr"
./gradlew :app:assembleRelease   # -> app/build/outputs/apk/release/app-release.apk
```
The release build is signed with the debug key, which is fine for sideloading. Google Play would need a real keystore.

## Server
- Runs hidden and starts automatically at Windows sign-in (`server/install-autostart.ps1`). Stop it with `server/stop.ps1`.
- Listens on `127.0.0.1:48120`. Tailnet-only HTTPS: `https://<your-machine>.<tailnet>.ts.net:9443`
- In the app, go to Settings → Live reports → Report server URL, then tap Save & test
- Tests: `node server/test/server-test.js`

## Data sources
Map data © OpenStreetMap contributors.

| What | Source |
|---|---|
| Map tiles | OpenFreeMap |
| Satellite imagery | Esri World Imagery (needs internet) |
| Online routing | Valhalla (FOSSGIS) |
| Search | Photon (Komoot) |
| Places and cameras | Overpass API |
| Offline routing data | brouter.de |

## Known limits
- There are no live traffic speeds, because no free source exists. Traffic comes from community "Traffic" reports.
- Offline street names come from the downloaded map, so a few small or unnamed roads may show just "Turn left".
