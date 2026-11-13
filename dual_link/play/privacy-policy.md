# Dual Link — Privacy Policy

_Last updated: 13 November 2026_

Dual Link lets you use the SIM card in one of your phones ("Phone A") from your other phone ("Phone B") over your own Wi-Fi network.

## What data the app uses

| Data | On which phone | Why |
|---|---|---|
| SMS messages (incoming, history, and replies you send) | Phone A reads and sends; Phone B stores and shows them | To show your texts on Phone B and send your replies from Phone A's SIM |
| Call state, caller number, call log | Phone A reads; Phone B shows | To show incoming calls and call history on Phone B, and to decline, answer, end or dial when you tap it on Phone B |
| Contacts | Phone B only | To show names instead of numbers and pick who to text. Never leaves Phone B |
| Microphone audio | Phone B, only during a call when you choose "Talk here" | To carry your voice to the call on Phone A. Never recorded or stored |
| Camera | Phone B, only while scanning the pairing QR code | To read the pairing code. No images are stored |

## Where the data goes

- Data travels **only between your two paired phones**, directly over your local Wi-Fi network, encrypted with a key created on your phone during pairing (AES-256-GCM).
- Dual Link has **no servers, no accounts, no analytics and no advertising**. We (the developer) never receive any of your data.
- The QR scanner uses Google ML Kit, which reads the code on the device. Google may receive anonymous diagnostic data from ML Kit, as described in Google's ML Kit terms; no message, call or contact data is involved.
- Nothing is sold or shared with anyone.

## Storage and deletion

- Messages and call history are stored in the app's private storage on your phones, and on Phone B also in Android's SMS inbox (because Dual Link is the default SMS app there).
- "Unpair & change mode" in the app deletes Dual Link's stored messages, call history copies, settings and keys. Uninstalling the app removes all of its private data. Messages already in Phone B's Android SMS inbox stay there like any other SMS; delete them from the inbox if you want.

## Optional "Talk from Phone B" (Phone A, Advanced)

If you turn this on, Dual Link uses Android's built-in Wireless debugging on Phone A, only on that phone itself (localhost), to route call audio to Phone B. It is off by default and can be turned off at any time.

## Children

Dual Link is not directed at children under 13.

## Contact

Questions: thetechfinn@gmail.com
