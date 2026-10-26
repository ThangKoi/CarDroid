# CarDroid architecture

CarDroid manages the receiver connection on the phone. A display helper runs the launcher and selected Android apps on a separate virtual display, then sends their video and supported playback audio to the receiver. Receiver input is routed back to that display.

```mermaid
flowchart LR
    subgraph Phone[Android phone]
        UI[CarDroid controls]
        Session[Connection and session]
        Auth[Authentication bridge]
        Helper[Display helper]
        Display[Launcher and Android apps]

        UI -->|Start and stop| Session
        Session --> Auth
        Session -->|Display lifecycle| Helper
        Helper -->|Launch and control| Display
        Display -->|Video and playback audio| Helper
    end

    Receiver[CarPlay receiver]
    Session <-->|Bluetooth discovery and setup| Receiver
    Session <-->|Wi-Fi session and control| Receiver
    Helper -->|Encoded media through session| Session
    Session -->|Receiver input| Helper
```

The helper starts through Shizuku or adb with shell privileges. If the separate display is unavailable, the app supports a phone-mirroring fallback through Android screen capture.

The connection layer handles discovery, pairing, Wi-Fi handoff, media transport, and receiver commands. The authentication bridge supplies the authentication operations required by that session. Compatibility depends on the phone, Android version, permissions, and receiver.

See the [protocol notes](PROTOCOL.md) for wire details and the [roadmap](ROADMAP.md) for development priorities.
