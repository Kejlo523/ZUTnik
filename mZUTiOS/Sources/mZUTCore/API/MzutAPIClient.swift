import Foundation

public enum MzutAPIError: Error, LocalizedError {
    case invalidURL
    case unauthorized(code: Int)
    case httpError(code: Int)
    case invalidJSON
    case sessionExpired(reason: String)

    public var errorDescription: String? {
        switch self {
        case .invalidURL:
            return "Niepoprawny adres API"
        case .unauthorized(let code):
            return "Brak autoryzacji (HTTP \(code))"
        case .httpError(let code):
            return "Blad HTTP \(code)"
        case .invalidJSON:
            return "Niepoprawna odpowiedz JSON"
        case .sessionExpired(let reason):
            return reason.isEmpty ? "Sesja wygasla" : "Sesja wygasla: \(reason)"
        }
    }
}

public final class MzutAPIClient {
    public static let apiBase = "https://www.zut.edu.pl/app-json-proxy/index.php"

    private let urlSession: URLSession
    private weak var sessionStore: MzutSessionStore?

    public init(urlSession: URLSession = .shared, sessionStore: MzutSessionStore? = nil) {
        self.urlSession = urlSession
        self.sessionStore = sessionStore
    }

    public func callApi(function: String, params: [String: String]) async throws -> [String: Any]? {
        guard var components = URLComponents(string: Self.apiBase) else {
            throw MzutAPIError.invalidURL
        }
        components.queryItems = [URLQueryItem(name: "f", value: function)]

        guard let url = components.url else {
            throw MzutAPIError.invalidURL
        }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("mZUT-IOS-V2/1.0", forHTTPHeaderField: "User-Agent")
        request.setValue("application/x-www-form-urlencoded; charset=utf-8", forHTTPHeaderField: "Content-Type")

        let body = params.map { key, value in
            "\(Self.encodeFormComponent(key))=\(Self.encodeFormComponent(value))"
        }
        .joined(separator: "&")

        request.httpBody = body.data(using: .utf8)

        let (data, response) = try await urlSession.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw MzutAPIError.httpError(code: -1)
        }

        if httpResponse.statusCode == 401 || httpResponse.statusCode == 403 {
            await handleSessionExpired(reason: "HTTP \(httpResponse.statusCode)")
            throw MzutAPIError.unauthorized(code: httpResponse.statusCode)
        }

        guard (200..<300).contains(httpResponse.statusCode) else {
            throw MzutAPIError.httpError(code: httpResponse.statusCode)
        }

        if data.isEmpty {
            return nil
        }

        let json = try JSONSerialization.jsonObject(with: data)
        guard let dictionary = json as? [String: Any] else {
            throw MzutAPIError.invalidJSON
        }

        let hasSession = await MainActor.run { [weak sessionStore] in
            sessionStore?.isAuthenticated ?? false
        }
        if SessionExpiryDetector.isSessionExpiredResponse(dictionary, hasSession: hasSession) {
            let reason = SessionExpiryDetector.extractSessionExpiredReason(dictionary)
            await handleSessionExpired(reason: reason)
            throw MzutAPIError.sessionExpired(reason: reason)
        }

        return dictionary
    }

    private func handleSessionExpired(reason: String) async {
        await MainActor.run { [weak sessionStore] in
            sessionStore?.clearSessionData()
        }
    }

    private static func encodeFormComponent(_ input: String) -> String {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return input.addingPercentEncoding(withAllowedCharacters: allowed) ?? input
    }
}
