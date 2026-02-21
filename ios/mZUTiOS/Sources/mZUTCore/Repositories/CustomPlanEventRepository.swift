import Foundation

public final class CustomPlanEventRepository {
    private enum Keys {
        static let eventsData = "custom_events_json"
    }

    private let store: KeyValueStore
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(store: KeyValueStore = UserDefaultsStore(suiteName: "mzut_custom_events")) {
        self.store = store
    }

    public func loadAll() -> [CustomPlanEvent] {
        guard let data = store.data(forKey: Keys.eventsData),
              let decoded = try? decoder.decode([CustomPlanEvent].self, from: data) else {
            return []
        }

        return decoded.sorted {
            if $0.date == $1.date {
                return $0.startTime < $1.startTime
            }
            return $0.date < $1.date
        }
    }

    public func saveAll(_ events: [CustomPlanEvent]) {
        store.set(try? encoder.encode(events), forKey: Keys.eventsData)
    }

    public func addEvent(_ event: CustomPlanEvent) {
        var events = loadAll()
        events.append(event)
        saveAll(events)
    }

    public func updateEvent(_ event: CustomPlanEvent) {
        var events = loadAll()
        if let index = events.firstIndex(where: { $0.id == event.id }) {
            events[index] = event
            saveAll(events)
        }
    }

    public func deleteEvent(eventId: Int64) {
        let filtered = loadAll().filter { $0.id != eventId }
        saveAll(filtered)
    }

    public func getEventById(_ id: Int64) -> CustomPlanEvent? {
        loadAll().first { $0.id == id }
    }

    public func getEventCount() -> Int {
        loadAll().count
    }

    public func clearAll() {
        store.removeValue(forKey: Keys.eventsData)
    }
}
