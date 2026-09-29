#if DEBUG
import SwiftUI

/// Debug only (`ALPHA_GPS_SCREENSHOT=widgets` in the simulator): the widget in its two
/// sizes, the Control Center control's states and the Live Activity's Lock Screen view,
/// with sample content, to check their layout without adding them to a home screen.
/// `ALPHA_GPS_SCREENSHOT=liveactivity` also starts a Live Activity with sample content.
struct WidgetPreviewScreen: View {
    /// The Live Activity first (`liveactivity`), else the widget.
    var liveActivityFirst = false

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
                if liveActivityFirst {
                    liveActivitySection
                }
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
                if !liveActivityFirst {
                    liveActivitySection
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

    @ViewBuilder
    private var liveActivitySection: some View {
        Text(verbatim: "Live Activity").font(.title2.bold())
        liveActivity(.preview)
        liveActivity(standby)
        HStack(spacing: 14) {
            compactIsland(.preview)
            compactIsland(standby)
        }
    }

    private func liveActivity(_ status: WidgetStatus) -> some View {
        LiveActivityLockScreenView(status: status)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(StatusColors.navy, in: RoundedRectangle(cornerRadius: 24))
    }

    /// Roughly the compact Dynamic Island, around the camera.
    private func compactIsland(_ status: WidgetStatus) -> some View {
        HStack {
            LiveActivitySymbol(sending: status.sending, size: 17)
            Spacer(minLength: 70)
            LiveActivityDots(cameras: status.cameras)
        }
        .padding(.horizontal, 14)
        .frame(height: 37)
        .background(.black, in: Capsule())
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
