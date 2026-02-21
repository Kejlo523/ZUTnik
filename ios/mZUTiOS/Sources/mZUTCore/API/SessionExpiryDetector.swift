import Foundation

public enum SessionExpiryDetector {
    public static func isSessionExpiredResponse(_ response: [String: Any], hasSession: Bool) -> Bool {
        guard hasSession else {
            return false
        }

        let loginStatus = firstNonEmpty(
            value(response["logInStatus"]),
            value(response["loginInStatus"])
        )

        if !loginStatus.isEmpty && loginStatus.uppercased() != "OK" {
            return true
        }

        let candidates = [
            value(response["status"]),
            value(response["Status"]),
            value(response["message"]),
            value(response["komunikat"]),
            value(response["error"]),
            value(response["blad"]),
            value(response["msg"]),
            value(response["opis"]),
            value(response["description"])
        ]

        return candidates.contains(where: looksLikeSessionExpired)
    }

    public static func extractSessionExpiredReason(_ response: [String: Any]) -> String {
        firstNonEmpty(
            value(response["message"]),
            value(response["komunikat"]),
            value(response["error"]),
            value(response["status"]),
            value(response["Status"]),
            value(response["msg"])
        )
    }

    private static func looksLikeSessionExpired(_ input: String) -> Bool {
        guard !input.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return false
        }

        let normalized = normalize(input)

        if normalized.contains("session expired") || normalized.contains("token expired") || normalized.contains("unauthorized") {
            return true
        }

        if (normalized.contains("sesja") || normalized.contains("session")) &&
            (normalized.contains("wygasl") || normalized.contains("expired")) {
            return true
        }

        if normalized.contains("token") && (
            normalized.contains("wygasl") ||
                normalized.contains("expired") ||
                normalized.contains("invalid") ||
                normalized.contains("niepopraw") ||
                normalized.contains("niewazn") ||
                normalized.contains("bled")
        ) {
            return true
        }

        return normalized.contains("autoryz") &&
            (normalized.contains("brak") || normalized.contains("niepopraw"))
    }

    private static func normalize(_ input: String) -> String {
        input
            .lowercased()
            .folding(options: [.diacriticInsensitive, .caseInsensitive], locale: .current)
    }

    private static func firstNonEmpty(_ values: String...) -> String {
        for value in values {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                return trimmed
            }
        }
        return ""
    }

    private static func value(_ raw: Any?) -> String {
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
}
