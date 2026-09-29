import AppIntents
import SwiftUI
import WidgetKit

/// The Control Center (and Lock Screen) control, like Android's Quick Settings tile: on
/// or off, with the frame and pin while a camera receives the location.
struct GeoShutterControl: ControlWidget {
    static let kind = "com.sarmadjari.geoshutter.control"

    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: Self.kind, provider: Provider()) { status in
            ControlWidgetToggle(
                "GeoShutter",
                isOn: status.enabled,
                action: SetGeoShutterEnabledIntent()
            ) { isOn in
                Label(
                    status.valueText(isOn: isOn),
                    image: StatusSymbol.name(sending: isOn && status.sending)
                )
            }
            .tint(StatusColors.brand)
        }
        .displayName("GeoShutter")
        .description("Turn GeoShutter on or off.")
    }

    struct Status {
        let enabled: Bool
        let sending: Bool
        let headline: String?

        /// While switching on, before the app reported back, "On"; then its headline.
        func valueText(isOn: Bool) -> String {
            guard isOn else { return String(localized: "Off") }
            return enabled ? headline ?? String(localized: "On") : String(localized: "On")
        }
    }

    struct Provider: ControlValueProvider {
        var previewValue: Status {
            Status(enabled: true, sending: true, headline: String(localized: "Sending location"))
        }

        func currentValue() async throws -> Status {
            // Before the app ever wrote a status, GeoShutter is on (its default).
            guard let status = WidgetStatus.load() else {
                return Status(enabled: true, sending: false, headline: nil)
            }
            return Status(enabled: status.enabled, sending: status.sending, headline: status.headline)
        }
    }
}
