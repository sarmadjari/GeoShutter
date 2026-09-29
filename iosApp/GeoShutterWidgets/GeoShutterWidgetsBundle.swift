import SwiftUI
import WidgetKit

/// GeoShutter's widget extension: the home-screen widget and the Control Center control.
/// It only reads the status the app writes into the app group (`WidgetStatus`).
@main
struct GeoShutterWidgetsBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
        GeoShutterControl()
    }
}
