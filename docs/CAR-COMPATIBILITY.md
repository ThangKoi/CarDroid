# Car compatibility

## General

For CarDroid to do anything useful against a car, the car must:

1. **Have a CarPlay receiver in its head unit firmware** — either factory or
   shipped via OTA. If the car can't talk CarPlay to a real iPhone, no
   amount of impersonation will help.
2. **Support wireless CarPlay specifically** — CarDroid's protocol is the
   wireless variant. Wired-only cars use a different transport (USB iAP2)
   that CarDroid doesn't target.
3. **Use Apple's standard iAP2 R23+ framing** — no known car uses a
   proprietary variant, but Tesla notably does not support CarPlay at all.

If all three hold, then completing the handshake also gets you whatever
cluster / HUD integration the car already does with a real iPhone — the
phone sends metadata, the car renders it natively.

## Per-car notes

### Lynk & Co 08 (Geely, Flyme Auto)

- **CarPlay support:** Shipped via OTA after launch. Menu option labeled
  "Apple CarPlay" in connectivity settings.
- **Wireless / wired:** Wireless only (car has no driver-accessible USB-iAP
  port).
- **Cluster behavior:** When CarPlay is active, the 12.3-inch cluster
  renders the car's native navigation panel populated with CarPlay
  metadata (turn arrow, road name, ETA). This is rendered by Flyme Auto's
  cluster firmware, not by mirroring phone pixels.
- **AR-HUD behavior:** Same as cluster — navigation arrows on the HUD are
  driven by the same CarPlay metadata stream.
- **CarDroid target:** Once an MFi chip is wired in, handshake should
  complete and the center screen + cluster + HUD will populate. Cluster
  nav data requires the cluster-metadata bridge (see ROADMAP).
- **Phone screen mirroring:** Not exposed by CarPlay protocol. The center
  screen runs the CarPlay UI; the cluster does not mirror the phone.

### Cars known to work with wireless CarPlay (general guidance)

These cars run conventional CarPlay receivers and should work with CarDroid
+ MFi chip with no special handling:

- BMW iDrive 7+ (2018+ across most models)
- Mercedes MBUX (2018+)
- Audi MIB3 / VAG group MIB3 (2020+)
- Hyundai/Kia ccNC (2022+)
- Ford Sync 4 (2021+)
- GM Infotainment 3 (2019+, where wireless is enabled)
- Honda 2024+
- Mazda Connect (2019+ where wireless added via OTA)

### Cars that won't work

- **Tesla** — no CarPlay at all
- **Rivian, Lucid** — no CarPlay
- **2018 and older from most brands** — typically wired CarPlay only
- **Cars without a CarPlay license** — some Chinese-domestic models
  ship without CarPlay despite premium positioning

## How to check your car

1. Pair a known iPhone with the car and try CarPlay through the menu
2. If the menu doesn't list CarPlay: the receiver isn't installed; nothing
   CarDroid can do
3. If the menu lists CarPlay but the iPhone won't connect: receiver is
   installed but possibly broken/locked; CarDroid may or may not work
4. If a real iPhone connects and works: CarDroid + MFi chip will work too

## Cluster integration matrix

Even with a working CarPlay session, what shows on the cluster depends on
the car:

| Cluster integration tier | What you see during CarPlay | Cars |
|---|---|---|
| **None** | Cluster shows the car's normal gauges; CarPlay UI only on center screen | Older BMW, most pre-2020 cars |
| **Basic** | Next turn arrow + street name embedded in cluster's nav widget | Hyundai/Kia 2022+, VAG MIB2.5 |
| **Rich** | Full nav panel rendering with route, ETA, lane guidance | VAG MIB3, BMW iDrive 8, Mercedes MBUX, Lynk & Co 08 |
| **CarPlay 2 (full takeover)** | Cluster + center + HUD all become CarPlay surfaces | Aston Martin DBX 2025+, Porsche 2025+ (Next-Gen CarPlay) |

CarDroid's BT handshake is the same regardless of tier — the tier difference
is entirely car-side rendering. Once handshake succeeds, your car shows
whatever it's designed to show.

## Things CarDroid still cannot do, even after handshake

- **Mirror the phone screen to the cluster** — the cluster is a separate
  ECU with locked firmware. Not exposed by CarPlay. No car supports it.
- **Add CarPlay to a car that doesn't have it** — no protocol exists for
  a phone to install software in a head unit.
- **Convert wired-only CarPlay to wireless** — that's what Carlinkit-style
  dongles do; they need a USB port the wired-only car exposes. Different
  problem from impersonating an iPhone.
- **Bypass head-unit-side rate limiting or per-phone bans** — some cars
  blacklist BT MACs after too many failed pairings. Use the Bluetooth MAC
  spoof (rooted devices only) if you hit this.
