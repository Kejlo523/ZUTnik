import Foundation
import Security

public enum MzutTokenGenerator {
    private static let carr = Array("23456789abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ")
    private static let carr2 = Array("vwxyz23456789ABCDEFGHJKkmnopqrstuvwxyzabcdefghijWXYZLMNPQRSTUV")

    public static func generateToken(
        login: String,
        password: String?,
        now: Date = Date(),
        randomBase: String? = nil,
        calendar: Calendar = .current
    ) -> String {
        let base = randomBase ?? randomString(length: 32, alphabet: carr)
        guard let password, !password.isEmpty else {
            return base
        }

        return mutate(base: base, login: login, password: password, now: now, calendar: calendar)
    }

    static func mutate(base: String, login: String, password: String, now: Date, calendar: Calendar) -> String {
        let dayOfMonth = calendar.component(.day, from: now)
        let dayOfWeek = calendar.component(.weekday, from: now)
        let dayOfWeekInMonth = ((dayOfMonth - 1) / 7) + 1

        let combined = login + password
        let length = combined.count

        var indexes = [
            length - 1,
            length - 5,
            length - 8,
            dayOfMonth,
            dayOfWeek,
            dayOfWeekInMonth
        ]

        let sum = indexes.reduce(0, +)
        var localCarr = carr

        if sum % 2 == 0 {
            indexes[0] = dayOfMonth
            indexes[1] = length + 3
            indexes[2] = length + 9
            indexes[3] = dayOfWeek
            indexes[4] = length
            indexes[5] = dayOfWeekInMonth
            localCarr = carr2
        }

        var result = String()
        let chars = Array(base)

        for i in chars.indices {
            if let replacement = replacementCharacter(for: i, indexes: indexes, alphabet: localCarr) {
                result.append(replacement)
            } else {
                result.append(chars[i])
            }
        }

        return result
    }

    private static func replacementCharacter(for index: Int, indexes: [Int], alphabet: [Character]) -> Character? {
        for candidate in indexes where candidate == index {
            if candidate <= 32 && candidate >= 0 && candidate < alphabet.count {
                return alphabet[candidate]
            }
        }
        return nil
    }

    private static func randomString(length: Int, alphabet: [Character]) -> String {
        var bytes = [UInt8](repeating: 0, count: length)
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes)
        if status == errSecSuccess {
            return bytes.map { alphabet[Int($0) % alphabet.count] }.map(String.init).joined()
        }

        var fallback = String()
        var random = SystemRandomNumberGenerator()
        for _ in 0..<length {
            fallback.append(alphabet.randomElement(using: &random) ?? "A")
        }
        return fallback
    }
}

