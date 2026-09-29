#if DEBUG
import SwiftUI

/// Debug only (`ALPHA_GPS_SCREENSHOT=widgets` in the simulator): the widget in its two
/// sizes and the Control Center control's states, with sample content, to check their
/// layout without adding them to a home screen.
struct WidgetPreviewScreen: View {
    private let standby = WidgetStatus(
        enabled: true,
        sending: false,
        headline: "Standby",
        emptyText: "",
        cameras: [
            .init(id: "1", name: "X100VI-568C", model: "Fujifilm X100VI", state: "standby", note: "Standby"),
            .init(id: "2", name: "ILCE-1M2", model: "Sony α1 II", state: "away", note: nil),
            .init(id: "3", name: "ILCE-7M4", model: "Sony α7 IV", state: "connecting", note: nil),
        ]
    )
    private let off = WidgetStatus(
        enabled: false,
        sending: false,
        headline: "Off",
        emptyText: "",
        cameras: [.init(id: "1", name: "X100VI-568C", model: "Fujifilm X100VI", state: "off", note: nil)]
    )

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(verbatim: "Widget").font(.title2.bold())
                HStack(spacing: 14) {
                    widget(.preview, wide: false)
                    widget(standby, wide: false)
                }
                widget(standby, wide: true)
                HStack(spacing: 14) {
                    widget(off, wide: false)
                    widget(nil, wide: false)
                }
                Text(verbatim: "Written by the app").font(.title2.bold())
                widget(WidgetStatus.load(), wide: false)
                Text(verbatim: "Control").font(.title2.bold())
                HStack(spacing: 22) {
                    control(on: true, sending: true)
                    control(on: true, sending: false)
                    control(on: false, sending: false)
                }
            }
            .padding(20)
        }
        .background(Color(.systemGroupedBackground))
    }

    private func widget(_ status: WidgetStatus?, wide: Bool) -> some View {
        StatusWidgetView(status: status, wide: wide)
            .padding(16)
            .frame(width: wide ? 338 : 158, height: 158)
            .background(Color(.systemBackground), in: RoundedRectangle(cornerRadius: 22))
    }

    private func control(on: Bool, sending: Bool) -> some View {
        Image(StatusSymbol.name(sending: sending))
            .font(.system(size: 24))
            .foregroundStyle(on ? .white : .primary)
            .frame(width: 64, height: 64)
            .background(on ? StatusColors.brand : Color(.tertiarySystemFill), in: Circle())
    }
}
#endif
