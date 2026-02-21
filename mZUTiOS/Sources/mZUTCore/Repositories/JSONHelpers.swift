import Foundation

enum JSONHelpers {
    static func string(_ raw: Any?) -> String {
        guard let raw else {
            return ""
        }
        if let text = raw as? String {
            return text
        }
        if let number = raw as? NSNumber {
            return number.stringValue
        }
        return ""
    }

    static func firstNonEmpty(_ values: String...) -> String {
        for value in values {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                return trimmed
            }
        }
        return ""
    }

    static func arrayOfDictionaries(from raw: Any?) -> [[String: Any]] {
        if let dictionaries = raw as? [[String: Any]] {
            return dictionaries
        }
        if let dictionary = raw as? [String: Any] {
            return [dictionary]
        }
        if let array = raw as? [Any] {
            return array.compactMap { $0 as? [String: Any] }
        }
        return []
    }

    static func double(_ raw: Any?) -> Double {
        guard let raw else {
            return 0
        }

        if let value = raw as? Double {
            return value
        }
        if let value = raw as? Int {
            return Double(value)
        }
        if let number = raw as? NSNumber {
            return number.doubleValue
        }
        if let string = raw as? String {
            let normalized = string.trimmingCharacters(in: .whitespacesAndNewlines)
                .replacingOccurrences(of: ",", with: ".")
            return Double(normalized) ?? 0
        }
        return 0
    }
}
