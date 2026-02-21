import Foundation

public struct Study: Codable, Equatable, Hashable, Identifiable {
    public var przynaleznoscId: String?
    public var label: String?

    public init(przynaleznoscId: String? = nil, label: String? = nil) {
        self.przynaleznoscId = Study.normalizeId(przynaleznoscId)
        self.label = label
    }

    public var id: String {
        if let normalized = Study.normalizeId(przynaleznoscId) {
            return normalized
        }
        if let label, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return label
        }
        return "study-unknown"
    }

    public var displayLabel: String {
        if let label, !label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return label
        }
        if let normalized = Study.normalizeId(przynaleznoscId) {
            return normalized
        }
        return "Kierunek"
    }

    public static func normalizeId(_ raw: String?) -> String? {
        guard let raw else {
            return nil
        }
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        return normalized.isEmpty ? nil : normalized
    }
}
