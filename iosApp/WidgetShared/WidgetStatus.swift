import Foundation

/// GeoShutter's status for the widget and the Control Center control. The app writes it
/// into the app group as JSON (`IosWidgetBridge` and `StatusSnapshot` in the shared
/// Kotlin code) whenever it changes; the widget extension can't run the app's code.
struct WidgetStatus: Codable, Equatable {
    struct Camera: Codable, Equatable, Identifiable {
        let id: String
        let name: String
        /// Brand and model, e.g. "Fujifilm X100VI"; nil when unknown or the same as the name.
        let model: String?
        /// sending, connecting, syncOff, standby, away, or off while GeoShutter is off.
        let state: String
        /// An extra line such as "Standby"; nil when the state needs none.
        let note: String?
    }

    let enabled: Bool
    /// A camera receives the location: the icon shows the pin in the frame.
    let sending: Bool
    /// GeoShutter's state in a few words, e.g. "Sending location".
    let headline: String
    /// Shown instead of the cameras when none is saved.
    let emptyText: String
    let cameras: [Camera]

    static let appGroup = "group.com.sarmadjari.geoshutter"
    static let key = "status"

    /// The status the app wrote last; nil before it ran with a widget-aware version.
    static func load() -> WidgetStatus? {
        guard let json = UserDefaults(suiteName: appGroup)?.string(forKey: key),
              let data = json.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(WidgetStatus.self, from: data)
    }
}

extension WidgetStatus {
    /// Sample content for the widget gallery and the debug preview screen.
    static let preview = WidgetStatus(
        enabled: true,
        sending: true,
        headline: String(localized: "Sending location"),
        emptyText: "",
        cameras: [
            Camera(id: "1", name: "X100VI-568C", model: "Fujifilm X100VI", state: "sending", note: nil),
            Camera(id: "2", name: "ILCE-1M2", model: "Sony α1 II", state: "away", note: nil),
        ]
    )
}
