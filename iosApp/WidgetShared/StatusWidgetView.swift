import SwiftUI

/// The widget's colors, the same as the Android widget's; the brand coral is the app
/// icon's pin.
enum StatusColors {
    static let brand = Color(red: 1.0, green: 0x5A / 255.0, blue: 0x4E / 255.0)
    static let sending = Color(red: 0x2E / 255.0, green: 0x9E / 255.0, blue: 0x4A / 255.0)
    static let connecting = Color(red: 0xE8 / 255.0, green: 0xA3 / 255.0, blue: 0x17 / 255.0)
    static let away = Color(red: 0xD9 / 255.0, green: 0x30 / 255.0, blue: 0x25 / 255.0)
    static let off = Color(red: 0x9E / 255.0, green: 0x9E / 255.0, blue: 0x9E / 255.0)
    static let standby = Color(red: 0x1E / 255.0, green: 0x88 / 255.0, blue: 0xE5 / 255.0)
    static let standbyText = Color(red: 0x15 / 255.0, green: 0x65 / 255.0, blue: 0xC0 / 255.0)
    static let syncOffText = Color(red: 0xB2 / 255.0, green: 0x6A / 255.0, blue: 0x00 / 255.0)

    static func dot(_ state: String) -> Color {
        switch state {
        case "sending": sending
        case "connecting", "syncOff": connecting
        case "standby": standby
        case "off": off
        default: away
        }
    }
}

/// The status symbols, generated from the app icon by tools/app_icon: the frame with the
/// pin while a camera receives the location, the frame alone otherwise.
enum StatusSymbol {
    static func name(sending: Bool) -> String {
        sending ? "geoshutter.status.sending" : "geoshutter.status.waiting"
    }
}

/// The home-screen widget's content, for monitoring only (a tap opens the app), like the
/// Android widget: GeoShutter's state and each saved camera with a colored dot.
struct StatusWidgetView: View {
    let status: WidgetStatus?
    /// Two columns of cameras (the medium widget) or one.
    let wide: Bool

    private var maxCameras: Int { wide ? 4 : 2 }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            header
            if let status {
                if status.cameras.isEmpty {
                    Text(status.emptyText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    LazyVGrid(
                        columns: Array(repeating: GridItem(.flexible(), alignment: .topLeading), count: wide ? 2 : 1),
                        alignment: .leading,
                        spacing: 8
                    ) {
                        ForEach(status.cameras.prefix(maxCameras)) { camera in
                            CameraRow(camera: camera)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private var header: some View {
        HStack(spacing: 6) {
            Image(StatusSymbol.name(sending: status?.sending == true))
                .font(.system(size: 20))
                .foregroundStyle(StatusColors.brand)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: "GeoShutter")
                    .font(.system(size: 15, weight: .bold))
                    .lineLimit(1)
                Text(status?.headline ?? String(localized: "Open GeoShutter"))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
        }
    }
}

private struct CameraRow: View {
    let camera: WidgetStatus.Camera

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Circle()
                .fill(StatusColors.dot(camera.state))
                .frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 0) {
                Text(camera.name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)
                if let note = camera.note {
                    Text(note)
                        .font(.caption)
                        .foregroundStyle(camera.state == "standby" ? StatusColors.standbyText : StatusColors.syncOffText)
                        .lineLimit(1)
                } else if let model = camera.model {
                    Text(model)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
        }
    }
}
