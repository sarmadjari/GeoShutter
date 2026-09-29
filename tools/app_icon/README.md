# App icon

Generates every GeoShutter app icon asset from one set of shapes (`shapes.mjs`): camera
focus brackets (the frame) around a coral location pin on a deep navy gradient (1024 px
artwork), and the status icons derived from them.

```sh
cd tools/app_icon
npm install
npm run build          # everything
node status-icons.mjs  # only the status icons (no dependencies)
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
- `app/src/main/res/drawable/ic_status_{sending,waiting}.xml`,
  `artwork/status-icon-{sending,waiting}.svg` and the iOS custom symbols
  `iosApp/WidgetShared/Assets.xcassets/geoshutter.status.{sending,waiting}.symbolset`
  (`status-icons.mjs`): the status icons of the notification (status bar), the Quick
  Settings tile and the widget on Android, and of the widget and Control Center control
  on the iPhone, in one color. The symbols use Apple's SF Symbols template (Regular
  weight in three scales, filled outlines: the strokes of the frame and of the outlined
  pin are outlined). *Sending*: the frame with a filled pin, while a camera receives the
  location. *Waiting*: the frame with an outlined pin (no dot), while GeoShutter waits, a
  camera is in standby or GeoShutter is off. The frame is drawn a little heavier than in
  the app icon so it holds up at status-bar size.

To change the icons, edit the shapes and colors in `shapes.mjs` (and the status icons'
size in `status-icons.mjs`) and run the scripts again; don't edit the generated files by
hand.
