# App icon

Generates every GeoShutter app icon asset from one set of shapes: camera focus
brackets around a coral location pin on a deep navy gradient (1024 px artwork).

```sh
cd tools/app_icon
npm install
npm run build
```

Writes:

- `artwork/app-icon.svg`: the master artwork, full bleed.
- `app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml`:
  the Android adaptive icon (the artwork on the 72 dp visible area of the 108 dp
  layers) and its one-color layer for themed icons. The splash screen uses the
  launcher icon.
- `iosApp/alphagps/Assets.xcassets/AppIcon.appiconset/`: `icon.png` (no alpha, as
  the App Store requires), `icon-dark.png` and `icon-tinted.png` (glyph on a
  transparent background; iOS supplies the background), and `Contents.json`.
- `fastlane/metadata/android/en-US/images/icon.png`: the F-Droid listing icon (512 px).
- `website/public/icon.svg`: the website's favicon and logo (rounded corners).

To change the icon, edit the shapes and colors at the top of `make-icons.mjs` and run
it again; don't edit the generated files by hand.
