import SwiftUI
import WidgetKit

struct StatusEntry: TimelineEntry {
    let date: Date
    /// nil until the app has written a status.
    let status: WidgetStatus?
}

/// One entry, replaced whenever the app reports a change (WidgetCenter reloads).
struct StatusProvider: TimelineProvider {
    func placeholder(in context: Context) -> StatusEntry {
        StatusEntry(date: .now, status: .preview)
    }

    func getSnapshot(in context: Context, completion: @escaping (StatusEntry) -> Void) {
        let status = WidgetStatus.load()
        completion(StatusEntry(date: .now, status: status ?? (context.isPreview ? .preview : nil)))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<StatusEntry>) -> Void) {
        completion(Timeline(entries: [StatusEntry(date: .now, status: WidgetStatus.load())], policy: .never))
    }
}

/// The home-screen widget, like Android's: whether GeoShutter is on and which cameras
/// receive the location. A tap opens the app.
struct StatusWidget: Widget {
    static let kind = "com.sarmadjari.geoshutter.status"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: StatusProvider()) { entry in
            StatusWidgetEntryView(entry: entry)
        }
        .configurationDisplayName(Text(verbatim: "GeoShutter"))
        .description("Whether GeoShutter is on and which cameras receive your location.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

private struct StatusWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: StatusEntry

    var body: some View {
        StatusWidgetView(status: entry.status, wide: family == .systemMedium)
            .containerBackground(.background, for: .widget)
    }
}
