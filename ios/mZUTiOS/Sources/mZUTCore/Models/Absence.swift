import Foundation

public struct Absence: Codable, Equatable, Hashable, Identifiable {
    public var subjectName: String
    public var subjectType: String
    public var subjectKey: String
    public var absenceCount: Int
    public var totalHours: Int

    public init(
        subjectName: String = "",
        subjectType: String = "",
        subjectKey: String = "",
        absenceCount: Int = 0,
        totalHours: Int = 0
    ) {
        self.subjectName = subjectName
        self.subjectType = subjectType
        self.subjectKey = subjectKey
        self.absenceCount = absenceCount
        self.totalHours = totalHours
    }

    public var id: String {
        subjectKey
    }

    public var attendancePercent: Double {
        guard totalHours > 0 else {
            return 100
        }
        let attended = max(0, totalHours - absenceCount)
        return (Double(attended) / Double(totalHours)) * 100
    }
}
