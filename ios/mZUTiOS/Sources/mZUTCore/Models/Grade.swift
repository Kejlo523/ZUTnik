import Foundation

public struct Grade: Codable, Equatable, Hashable, Identifiable {
    public var subjectName: String
    public var grade: String
    public var weight: Double
    public var type: String
    public var teacher: String
    public var date: String
    public var gradeHistory: [String]

    public init(
        subjectName: String = "",
        grade: String = "",
        weight: Double = 0,
        type: String = "",
        teacher: String = "",
        date: String = "",
        gradeHistory: [String] = []
    ) {
        self.subjectName = subjectName
        self.grade = grade
        self.weight = weight
        self.type = type
        self.teacher = teacher
        self.date = date
        self.gradeHistory = gradeHistory
    }

    public var id: String {
        "\(subjectName)|\(grade)|\(date)|\(type)"
    }
}
