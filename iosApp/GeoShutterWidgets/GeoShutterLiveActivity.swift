import ActivityKit
import SwiftUI
import WidgetKit

/// GeoShutter's Live Activity on the Lock Screen and in the Dynamic Island. The app
/// updates it with ActivityKit as the status changes, so it isn't held back like the
/// widget, whose reloads iOS rations.
struct GeoShutterLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: GeoShutterActivityAttributes.self) { context in
            LiveActivityLockScreenView(status: context.state)
                .activityBackgroundTint(StatusColors.navy)
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    LiveActivityTitle(sending: context.state.sending)
                        .padding(.leading, 6)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text(context.state.headline)
                        .font(.subheadline)
                        .foregroundStyle(StatusColors.secondaryOnDark)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .padding(.trailing, 6)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    LiveActivityCameras(cameras: context.state.cameras)
                        .padding(.horizontal, 6)
                        .padding(.top, 8)
                }
            } compactLeading: {
                LiveActivitySymbol(sending: context.state.sending, size: 17)
            } compactTrailing: {
                LiveActivityDots(cameras: context.state.cameras)
            } minimal: {
                LiveActivitySymbol(sending: context.state.sending, size: 17)
            }
            .keylineTint(StatusColors.brand)
        }
    }
}
