# Play Console checklist — Dual Link

Policy sources (checked 13 Nov 2026):
- SMS/Call Log permissions: https://support.google.com/googleplay/android-developer/answer/10208820
- Permissions Declaration Form: https://support.google.com/googleplay/android-developer/answer/9214102
- Foreground service declaration: https://support.google.com/googleplay/android-developer/answer/13392821
- Target API (36 for new apps since 31 Aug 2026): https://support.google.com/googleplay/android-developer/answer/11926878

## Build

- Upload: `flutter build appbundle --release` → `build/app/outputs/bundle/release/app-release.aab`
- Signed with the upload key in `%USERPROFILE%\.duallink-keys\duallink-upload.jks` (password in `android/key.properties`, both git-ignored). **Back both up**; enrol in Play App Signing.
- Sideload test builds for the existing phones: add `-Psideload` (old debug signature).
- targetSdk 36, minSdk 26. Bump `version:` in pubspec.yaml for every upload.

## Permissions Declaration Form (SMS / Call Log)

Two roles, one app:
- **Phone B is the default SMS handler** → READ_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH, SEND_SMS, WRITE_SMS covered by "Default SMS handler".
- **Phone A is not the default handler** → claim the exception **"Cross-device synchronization or transfer of SMS or calls"** (permits READ_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH, SEND_SMS, READ_CALL_LOG). Note it is a temporary exception that Google reviews case by case.

Core functionality text:
> Dual Link transfers SMS and calls between two phones owned by the same user. The phone with the SIM (Phone A) receives SMS and call events and forwards them, encrypted over the user's local Wi-Fi, to the user's second phone (Phone B), which is the default SMS app and shows the messages, lets the user reply (sent from Phone A's SIM) and handle calls. No server is involved; no data leaves the user's two devices.

Video (YouTube unlisted, ~2 min): set up Phone A (disclosure → permissions), pair Phone B by QR, set Phone B as default SMS app, receive an SMS on Phone B, reply, incoming call rings on Phone B and is declined.

Risk to address in the description: "remote control of user phone" is an invalid use. Present call actions as transfer of calls between the user's own devices, always user-initiated.

## Foreground services (App content → Foreground service types)

| Service | Type | Justification |
|---|---|---|
| GatewayService (Phone A) | specialUse | Keeps a local encrypted connection open so SMS and calls reach the user's other phone in real time; user-visible notification; started by the user in setup |
| ClientService (Phone B) | specialUse, microphone | Same connection on the receiving phone; microphone only during a call the user chooses to take on Phone B |

`specialUse` subtype is declared in the manifest. Provide a short video per type.

## Other declarations

- **Full-screen intent (USE_FULL_SCREEN_INTENT):** incoming-call ringing on Phone B → "calling" use case.
- **Battery optimisation (REQUEST_IGNORE_BATTERY_OPTIMIZATIONS):** core function (real-time message delivery between companion devices) breaks under Doze.
- **Data safety:** no data collected or shared by the developer (data moves only between the user's own devices, end-to-end encrypted). Encryption in transit: yes. Deletion: in-app "Unpair & change mode" (wipes messages, keys) or uninstall. Check Google's ML Kit data-disclosure page for what the bundled barcode scanner reports (anonymous diagnostics) and declare it if required.
- **Privacy policy URL:** host `play/privacy-policy.md` on a public page.
- **Content rating:** questionnaire; communication app, no user-generated public content.
- **Target audience:** 18+ (or 13+), not designed for children.
- **Ads:** no.

## Release path (new personal developer accounts)

Closed test with **≥12 testers opted in for 14 continuous days**, then apply for production. Organization accounts skip this.

## Open decision

"Talk from Phone B" (WRITE_SECURE_SETTINGS + self-ADB + call-audio interception) is the highest review risk. Option: ship a Play build without it first (smaller permission set, faster approval) and keep it for direct downloads.
