import ActivityKit
import SwiftUI

/// GeoShutter's Live Activity: its status on the Lock Screen and in the Dynamic Island
/// while it is on. The app starts, updates and ends it (`LiveActivities`); the widget
/// extension draws it (`GeoShutterLiveActivity`) with the views below.
nonisolated struct GeoShutterActivityAttributes: ActivityAttributes {
    typealias ContentState = WidgetStatus

    /// When the app started it: iOS ends a Live Activity after 8 hours.
    let startedAt: Date
}

nonisolated enum LiveActivityLayout {
    /// The cameras the Lock Screen and the expanded Dynamic Island have room for.
    static let maxCameras = 4
}

extension StatusColors {
    /// The app icon's navy, behind the Live Activity on the Lock Screen.
    static let navy = Color(red: 0x14 / 255.0, green: 0x1C / 255.0, blue: 0x2A / 255.0)
    static let secondaryOnDark = Color.white.opacity(0.68)

    /// A camera's note on a dark background (the Lock Screen and the Dynamic Island).
    static func noteOnDark(_ state: String) -> Color {
        state == "standby"
            ? Color(red: 0x6C / 255.0, green: 0xB4 / 255.0, blue: 0xF5 / 255.0)
            : Color(red: 0xF5 / 255.0, green: 0xB9 / 255.0, blue: 0x42 / 255.0)
    }
}

/// The status symbol in the brand coral: a filled pin while a camera receives the
/// location, an outlined one otherwise.
struct LiveActivitySymbol: View {
    let sending: Bool
    let size: CGFloat

    var body: some View {
        Image(StatusSymbol.name(sending: sending))
            .font(.system(size: size))
            .foregroundStyle(StatusColors.brand)
    }
}

/// The Lock Screen presentation, also the banner on iPhones without a Dynamic Island.
struct LiveActivityLockScreenView: View {
    let status: WidgetStatus

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                LiveActivitySymbol(sending: status.sending, size: 26)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: "GeoShutter")
                        .font(.headline)
                        .foregroundStyle(.white)
                    Text(status.headline)
                        .font(.subheadline)
                        .foregroundStyle(StatusColors.secondaryOnDark)
                        .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            LiveActivityCameras(cameras: status.cameras)
        }
        .padding(16)
    }
}

/// The cameras in two columns, each with its dot and its note or model, as on the widget.
struct LiveActivityCameras: View {
    let cameras: [WidgetStatus.Camera]

    var body: some View {
        LazyVGrid(
            columns: Array(repeating: GridItem(.flexible(), alignment: .topLeading), count: 2),
            alignment: .leading,
            spacing: 8
        ) {
            ForEach(cameras.prefix(LiveActivityLayout.maxCameras)) { camera in
                HStack(alignment: .firstTextBaseline, spacing: 7) {
                    Circle()
                        .fill(StatusColors.dot(camera.state))
                        .frame(width: 8, height: 8)
                    VStack(alignment: .leading, spacing: 0) {
                        Text(camera.name)
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(.white)
                            .lineLimit(1)
                        if let note = camera.note {
                            Text(note)
                                .font(.caption)
                                .foregroundStyle(StatusColors.noteOnDark(camera.state))
                                .lineLimit(1)
                        } else if let model = camera.model {
                            Text(model)
                                .font(.caption)
                                .foregroundStyle(StatusColors.secondaryOnDark)
                                .lineLimit(1)
                        }
                    }
                }
            }
        }
    }
}

/// The expanded Dynamic Island's top row: the symbol and the app's name.
struct LiveActivityTitle: View {
    let sending: Bool

    var body: some View {
        HStack(spacing: 6) {
            LiveActivitySymbol(sending: sending, size: 20)
            Text(verbatim: "GeoShutter")
                .font(.headline)
                .foregroundStyle(.white)
                .lineLimit(1)
        }
    }
}
