import Foundation

public struct CustomPlanEvent: Codable, Equatable, Hashable, Identifiable {
    public enum EventType: String, Codable, CaseIterable {
        case exam
        case pass
        case test
    }

    public var id: Int64
    public var subjectName: String
    public var eventType: EventType
    public var date: String
    public var startTime: String
    public var endTime: String
    public var notes: String
    public var isAutoTime: Bool

    public init(
        id: Int64 = Int64(Date().timeIntervalSince1970 * 1_000),
        subjectName: String = "",
        eventType: EventType = .test,
        date: String = "",
        startTime: String = "",
        endTime: String = "",
        notes: String = "",
        isAutoTime: Bool = false
    ) {
        self.id = id
        self.subjectName = subjectName
        self.eventType = eventType
        self.date = date
        self.startTime = startTime
        self.endTime = endTime
        self.notes = notes
        self.isAutoTime = isAutoTime
    }
}
