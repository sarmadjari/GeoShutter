/// The extension's side of `SetGeoShutterEnabledIntent`. The intent runs in the app
/// (LiveActivityIntent), whose `GeoShutterAppControl` turns GeoShutter on or off; this
/// copy only lets the extension build and does nothing.
enum GeoShutterAppControl {
    static func setEnabled(_ enabled: Bool) async {}
}
