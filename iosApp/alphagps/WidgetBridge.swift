import UIKit
import WidgetKit
import sharedKit

/// Connects the shared Kotlin code with the widget extension: the app writes GeoShutter's
/// status into the app group (`IosWidgetBridge`), and then the home-screen widget and the
/// Control Center control read it again and the Live Activity gets it (`LiveActivities`).
enum WidgetBridge {
    static func install() {
        IosWidgetBridge.shared.install { statusChanged() }
        let center = NotificationCenter.default
        // Leaving the app reloads too, which iOS allows while no camera is connected. While
        // one is, it reloads at most every 5 minutes and catches up then (see
        // IosStatusPublisher).
        center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { reload() }
        }
        // Only an open app may start a Live Activity.
        center.addObserver(forName: UIApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { LiveActivities.sync(renewIfOld: true) }
        }
        center.addObserver(forName: UIApplication.willTerminateNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { LiveActivities.endBeforeTermination() }
        }
    }

    private static func statusChanged() {
        reload()
        LiveActivities.sync()
    }

    private static func reload() {
        WidgetCenter.shared.reloadAllTimelines()
        ControlCenter.shared.reloadAllControls()
    }
}

/// The app's side of `SetGeoShutterEnabledIntent` (the Control Center control): the same
/// as *Enable App* in the settings. It returns once the new status is written, so the
/// control shows it right away.
enum GeoShutterAppControl {
    @MainActor
    static func setEnabled(_ enabled: Bool) async {
        IosBluetoothController.shared.setAppEnabled(enabled: enabled)
    }
}
