import UIKit
import WidgetKit
import sharedKit

/// Connects the shared Kotlin code with the widget extension: the app writes GeoShutter's
/// status into the app group (`IosWidgetBridge`), and these reloads make the home-screen
/// widget and the Control Center control read it again.
enum WidgetBridge {
    static func install() {
        IosWidgetBridge.shared.install { reload() }
        // Reloads from the foreground aren't rationed: leaving the app refreshes the
        // widget, in case iOS skipped a reload while the app was in the background.
        NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification,
            object: nil,
            queue: .main
        ) { _ in
            reload()
        }
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
