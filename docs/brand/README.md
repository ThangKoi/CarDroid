# CarDroid identity

CarDroid combines the familiar Android head with a dashboard-shaped play screen on an emerald field. The head and antennae make Android explicit, while the green-and-white screen/play motif recalls CarPlay. Assets are original native vector artwork with true negative-space cutouts, not raster generations.

![CarDroid icon](cardroid-icon.png)

## Palette

| Role | Color |
| --- | --- |
| Icon field | `#087D63` |
| Background | `#081715` |
| Dock / low surface | `#0C211D` |
| Surface | `#122B26` |
| Raised surface | `#1C3932` |
| Primary / mint | `#6CE5C2` |
| Primary container | `#174C3D` |
| Secondary / sky | `#93CBEA` |
| Tertiary / citrus | `#D6E6A5` |
| Text | `#EDF7F2` |
| Muted text | `#A9C2B8` |
| Error / coral | `#FFA798` |

Compose tokens live in `app/src/main/java/com/cardroid/app/CarDroidPalette.kt`. `CarDroidTheme` maps them to all Material 3 surface and semantic roles, and the launcher and native overlay share the same tokens. Android icon XML colors are in `app/src/main/res/values/colors.xml`.

Typography uses Android's native sans-serif family with semibold headlines and tabular clock figures. App icons use the 108 dp adaptive canvas; the mark stays within the safe area. Adaptive, legacy, themed monochrome, and notification variants are included. `cardroid-icon.svg` is the editable full icon; `cardroid-mark.svg` has a transparent background.

Third-party native apps hosted in launcher cells retain their own branding and colors.
