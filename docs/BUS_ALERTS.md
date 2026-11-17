# Configurable bus alerts

Create a reminder and choose **Bus alerts · Choose stops and lines**. Existing M2 reminders open in the same editor without changing their schedule.

- Add up to eight stop/line/destination pairs. Search the bundled Abu Dhabi stop catalogue by English or Arabic name or stop code, or add a custom stop with its exact Maps boarding name and full address.
- Enter a bus line, or choose a line already in the reminder. The picker does not claim to enumerate every line serving a stop.
- A destination is required because Google Routes searches an origin-to-destination journey. Choose a destination reached in the intended direction.
- Include or exclude individual buses. Keep at least one included. Remove requires confirmation; removing or disabling the final included bus is unavailable.
- Choose departure time, minutes remaining, stop name and direction with independent checkboxes. Keep time or minutes selected. Changes apply on the normal reminder Save.
- All included buses share the reminder's alert time and repeat settings. The initial schedule remains weekdays at 4:50 PM UAE time. Create separate reminders for different schedules.
- One existing Routes API key is shared by all bus alerts. There is no background polling. Manual checking uses one request; playback uses at most one request per distinct included pair, with a maximum of eight. Failed lookups do not trigger retries. An aggregate timeout bounds lookup work.
- Google returns a limited set of route alternatives, which may contain scheduled estimates. Only the first transit boarding leg, requested bus line, matching stop name (normalized, with known M2 aliases), and nearby coordinates when supplied are accepted. Transfers, different lines, past departures, and departures over three hours away are rejected. A missing match is not evidence that no bus is running.
- Only the original M2 / Al Hayal / Mazyad Mall preset has the existing offline timetable. Never reuse its times for another stop, line or direction.
- The versioned configuration travels with the reminder's existing text field, including backup/restore. Editors, alert messages, missed cards and widget fallbacks show the readable summary. Playback decodes settings and does not speak the serialized data. No database schema change or API-key migration is required.

## Map-based stop picker

The full-screen picker starts with the offline list near the selected stop. Choose Map to load a native Google map, tappable catalogue markers, and stops sorted by distance from the map centre. Search by English/Arabic name or code. The map follows search results, and panning updates nearby stops. Tap a marker or list result, review the highlighted stop, then **Use stop**. Cancel preserves the previous stop. Use stop stays in the top bar; focusing search gives the results the available space without recreating the map. Up to 120 distinct visible marker locations are rendered at once; zoom in for dense areas.

Map tiles load only while the picker is open. Switching to List or closing the picker stops the map lifecycle. Opening a map does not call Routes API or poll bus estimates. No location permission is requested. List/search works offline, including on devices without Google Play services.

A Maps SDK **ready** callback is not proof of key authorization. The UI reports **Google map loaded** only after the SDK's map-loaded callback. A timeout offers Retry/List and setup help without treating an unloaded map as success.

Native Maps SDK credentials are configured at build time, separately from the runtime bus-estimate key. Add `maps.sdk.key=YOUR_KEY` to the git-ignored `maps.local.properties` in the project root. Enable **Maps SDK for Android**, retain Routes API access if sharing a key, and allow the app package/signing SHA-1 in Android application restrictions. Rebuild after replacing the native map key. Builds without a map key still support the offline list. Never commit the private properties file or generated manifest/APK.

This personal build uses the already-saved bus key through that ignored local configuration. It does not change Cloud restrictions, billing, stored bus credentials or reminders. Google Play services and successful authorization are still required to display tiles. Google documentation: [Android SDK setup](https://developers.google.com/maps/documentation/android-sdk/config), [map events](https://developers.google.com/maps/documentation/android-sdk/events).

## Import a Google Maps link

In Google Maps, open the actual boarding stop, choose Share, then Copy link. In the stop picker choose **Google Maps link**, paste, and **Read link**. Review/edit the boarding-stop name, then **Use stop** and Apply the bus changes. The normal reminder Save still controls persistence.

Full place/search links are parsed locally. Short `maps.app.goo.gl` and `goo.gl/maps` links use at most five HTTPS redirects to Google Maps hosts, with short timeouts and no API key, cookies or page scraping. No Routes, Places or Maps SDK request is made by link import. Opening Google Maps uses a [standard Maps URL](https://developers.google.com/maps/documentation/urls/guide).

Only explicit pin coordinates are imported; an `@latitude,longitude` map camera is not treated as a stop. Address text embedded in a place label is prefilled for review. When no explicit address is present, a single exact name match in the bundled catalogue can supply missing coordinates; ambiguous or partial matches cannot. Some links contain only a name or place ID. When coordinates cannot be read or matched, the user confirms the exact name and full address including city. Unreadable/offline short links offer manual entry. Directions links are rejected because their endpoint might be a different place. Imported stops use the normal custom-stop configuration and do not inherit the M2 offline timetable.

Keep list/search and link import available without a native map key. Do not read the clipboard until the user chooses Paste link. Require review before using imported data, and never save the link itself as a stop address.

## Stop catalogue

The asset contains 4,070 public stop records downloaded on 17 November 2026 from [Abu Dhabi Spatial Data Infrastructure, OpenData Bus Stops layer 801](https://arcgis.sdi.abudhabi.ae/agspublish/rest/services/OpenData/ADSDI_OpenData/MapServer/801). It includes English/Arabic names, codes and coordinates. Download date does not imply that every source name is current; for example stop 11700B retains an older street-name label. The original Tawjeeh stop is also available under its familiar name. Use custom stop entry when the official snapshot and Google use different names. The app does not upload the catalogue or fetch it on every search.

## UI conventions

Use a searchable picker for the large stop catalogue, exact line text entry with existing-line suggestions, and a separate destination field for direction. Use independent checkboxes for spoken details, switches for including individual journeys, and confirmation for removal. Keep configuration in the reminder draft until Save. Avoid exposing serialized settings or internal custom-stop identifiers in user-facing text.

## Validation

BusAlertTest covers round-trip/legacy compatibility, invalid data, bounds, speech choices, timetable isolation, and route/stop/transfer/time selection. MapsStopLinkTest covers full/short links, pin-versus-camera coordinates, encoding, redirect bounds, and invalid/unsafe links. BusAlertEditorTest uses synthetic data on an isolated emulator to choose a catalogue stop, import a Maps link, add another line, change spoken details and cancel edits. Do not run reminder/alarm tests on a personal phone.

Google API behavior is documented in [Get a transit route](https://developers.google.com/maps/documentation/routes/transit-route).
