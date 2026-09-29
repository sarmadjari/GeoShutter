import ActivityKit
import Foundation
import os

/// Starts, updates and ends GeoShutter's Live Activity from the status the app writes for
/// the widget (`WidgetStatus`): it shows while GeoShutter is on, has cameras and the
/// *Live Activity* switch is on. iOS lets the app start one only while the app is open or
/// from the Control Center control's intent, and ends it after 8 hours, so opening the
/// app replaces one that is older than `renewAfter`.
enum LiveActivities {
    private static let renewAfter: TimeInterval = 2 * 60 * 60
    private static let log = Logger(subsystem: "com.sarmadjari.geoshutter", category: "LiveActivity")

    static func sync(renewIfOld: Bool = false) {
        let running = Activity<GeoShutterActivityAttributes>.activities.filter {
            $0.activityState == .active || $0.activityState == .stale
        }
        guard let status = WidgetStatus.load(), status.showsLiveActivity,
              ActivityAuthorizationInfo().areActivitiesEnabled else {
            end(running)
            return
        }
        let state = status.forLiveActivity
        if let current = running.first,
           !(renewIfOld && Date().timeIntervalSince(current.attributes.startedAt) > renewAfter) {
            end(Array(running.dropFirst()))
            if current.content.state != state {
                Task { await current.update(ActivityContent(state: state, staleDate: nil)) }
            }
            return
        }
        do {
            _ = try Activity.request(
                attributes: GeoShutterActivityAttributes(startedAt: .now),
                content: ActivityContent(state: state, staleDate: nil)
            )
            end(running)
            log.info("Started the Live Activity")
        } catch {
            // In the background iOS refuses to start one: the next time the app is open.
            log.info("Could not start the Live Activity: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// While the app quits (the user closed it): nothing would update the Live Activity
    /// any more, so it would keep showing the last status.
    static func endBeforeTermination() {
        let activities = Activity<GeoShutterActivityAttributes>.activities
        guard !activities.isEmpty else { return }
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            for activity in activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
            done.signal()
        }
        _ = done.wait(timeout: .now() + 2)
    }

    private static func end(_ activities: [Activity<GeoShutterActivityAttributes>]) {
        guard !activities.isEmpty else { return }
        Task {
            for activity in activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
        log.info("Ended the Live Activity")
    }

    #if DEBUG
    /// Debug simulator builds (`ALPHA_GPS_SCREENSHOT=liveactivity`): sample content, to
    /// see the Dynamic Island and the Lock Screen.
    static func startPreview(_ status: WidgetStatus) {
        _ = try? Activity.request(
            attributes: GeoShutterActivityAttributes(startedAt: .now),
            content: ActivityContent(state: status.forLiveActivity, staleDate: nil)
        )
    }
    #endif
}
