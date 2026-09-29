import AppIntents

/// Turns GeoShutter on or off from the Control Center control, like *Enable App* in the
/// app's settings. As a LiveActivityIntent it runs in the app's process, where the
/// Bluetooth connections live, also when the app is in the background; the widget
/// extension's copy of `GeoShutterAppControl` does nothing.
struct SetGeoShutterEnabledIntent: SetValueIntent, LiveActivityIntent {
    static let title: LocalizedStringResource = "Turn GeoShutter on or off"

    @Parameter(title: "GeoShutter on")
    var value: Bool

    func perform() async throws -> some IntentResult {
        await GeoShutterAppControl.setEnabled(value)
        return .result()
    }
}
