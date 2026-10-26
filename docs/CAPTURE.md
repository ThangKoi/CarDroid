# Capture procedure

Some bytes in iAP2 are NDA-protected (link Sync control bits, auth challenge
message IDs, parameter ID assignments inside Identification/WiFiInformation).
The cleanest way to fill them in is to capture your own real iPhone pairing
with your specific head unit and read off the values.

This costs nothing if you already have an iPhone and an Android.

## Why we need a capture

CarDroid ships with educated guesses where the NDA bytes go. They are
likely correct because:
- Public reverse engineering of Carlinkit dongles confirms framing
- Apple's `MFi Accessory Interface Specification` leaked excerpts confirm
  the message-id ranges
- The protocol has been stable since iOS 12

But "likely" is not "verified for your head unit." Some head units have
proprietary parameter extensions. Capturing makes CarDroid exact, not
approximate.

## Capture methods

Pick whichever matches the equipment you already have.

### Method A — Android btsnoop log (no extra hardware)

Best if you have any working Android phone (even an old one). Apple does
not have to participate; we just sniff the air between iPhone and car using
Android's hardware as a third-party Bluetooth scanner.

1. On Android Developer Options: enable **Bluetooth HCI snoop log**
2. Turn Bluetooth off then on (the log starts fresh)
3. Forget the car on the iPhone (Settings → Bluetooth → tap (i) → Forget)
4. Pair the iPhone with the car as if it's the first time
5. Drive the car for ~30s so CarPlay establishes and the cluster shows nav
6. Disconnect, then on Android pull:
   ```bash
   adb shell run-as com.android.bluetooth cat /data/data/com.android.bluetooth/files/snoop_hci.log > pairing.log
   ```
   Path varies by Android version; alternative paths to try:
   - `/sdcard/btsnoop_hci.log`
   - `/data/misc/bluetooth/logs/btsnoop_hci.log`
7. Open `pairing.log` in Wireshark; filter `bthci` to see HCI events and
   `obex || rfcomm` to see iAP2 payloads

### Method B — Embedded sniffer (highest quality, hardware required)

If you have an **Ellisys Bluetooth Tracker** or **Frontline Sodera**, use
it. These are professional-grade BT sniffers with proper iAP2 dissectors.
~$5k+, so unlikely to be your path. Mentioned for completeness.

## Wireshark setup

Install the **iAP2 dissector** if you can find one (Ludwig V's
reverse-engineering repo has a partial one). Without it, you'll see iAP2
frames as raw RFCOMM payloads — still readable, just not pretty-printed.

Useful display filters:

```
bthci_evt                                  # see HCI events (pairing, connect)
btrfcomm                                   # RFCOMM frames (iAP2 carrier)
btrfcomm.frame                             # individual RFCOMM frames
btl2cap.cid == 0x40                        # filter by channel
data contains ff:5a                        # find all iAP2 frames manually
```

## What to extract

Open the trace and look at the first few RFCOMM payloads after the iAP2
service connect. You're after the bytes for these fields:

### 1. Link Sync control byte

The very first iAP2 frame iPhone sends has the SYN bit set in the control
byte (frame[4]). CarDroid defaults this to `0x40`. Read off the actual
value from your capture:

```
Find first packet on the iAP2 RFCOMM channel from the iPhone.
Look at offset 4 of the payload (just past FF 5A LL LL).
That byte's value -> patch into CarDroidPipeline.defaultLinkSyncParams()
   LinkSyncParameters(controlByte = 0xXX.toByte())
```

### 2. Steady-state ACK control byte

After Link Sync, every regular frame has the ACK bit set. Read off the
control byte value from a mid-session frame (offset 4 of any non-SYN
payload). Default `0x40`. Patch into `Iap2Transport.sendControl`:

```kotlin
control = 0xXX.toByte(),
```

### 3. Auth message IDs and parameter shapes

After Link Sync, the iPhone asks for the accessory certificate and then sends a
challenge. Look for the `0xAA00`..`0xAA05` exchange and verify:

- Starts with `40 40` (control session SOM)
- Certificate response `0xAA01` comes from the head unit
- Challenge request `0xAA02` comes from the iPhone
- Authentication response `0xAA03` comes from the head unit
- Comes before the Wi-Fi configuration request (`0x5702`)

Bytes [4..5] of each payload are the message ID. Patch constants in
`Iap2ControlSession.Msg` only if your capture disagrees:

```kotlin
const val MFI_AUTH_CHALLENGE = 0xXXYY
```

### 4. Wi-Fi configuration message IDs

After auth succeeds, the iPhone requests the accessory Wi-Fi configuration.
Verify that the request is `0x5702` and the response is `0x5703`. Patch
`Iap2ControlSession.Msg.REQUEST_WIFI_INFORMATION` and
`Iap2ControlSession.Msg.WIFI_INFORMATION` only if your capture disagrees:

```kotlin
const val REQUEST_WIFI_INFORMATION = 0xXXYY
```

### 5. (Optional) IdentificationInformation parameter IDs

`IPhoneIdentity.kt` builds an IdentificationInformation message with
parameter IDs 0x0000..0x0005. Real iPhones use different IDs; cross-check
your capture against the parameters the iPhone sends in its own
IdentificationInformation (message 0x1D00). Update `CarPlaySession.sendIdentification()`.

## Sanity checks

After patching:

- The Link Sync frame CarDroid emits should be byte-identical to the
  first frame the iPhone emits in your capture
- CarDroid's first response after IdentificationAccepted should land on
  the same message ID range as the iPhone's first challenge response
- The head unit should send the same StartEAProtocolSession message
  to CarDroid that it sent to your iPhone

If any of these diverge, you've patched a wrong byte. The capture is
authoritative.

## When you can't capture

If you don't have an iPhone available, you can still build and run
CarDroid — it will reach the auth-challenge step using the default
educated-guess values. The handshake will fail at MFi auth (NullProvider),
which is the same point it would fail without a capture. The capture only
matters once you have a real chip wired in via `ExternalChipProvider`.
