import SwiftUI
import WidgetKit

/// GeoShutter's widget extension: the home-screen widget, the Control Center control and
/// the Live Activity. It only reads the status the app writes into the app group
/// (`WidgetStatus`) or sends to the Live Activity.
@main
struct GeoShutterWidgetsBundle: WidgetBundle {
    var body: some Widget {
        StatusWidget()
        GeoShutterControl()
        GeoShutterLiveActivity()
    }
}
