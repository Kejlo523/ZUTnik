import Foundation

public struct AuthResult: Equatable {
    public var userId: String
    public var username: String
    public var authKey: String
    public var imageUrl: String?

    public init(userId: String, username: String, authKey: String, imageUrl: String?) {
        self.userId = userId
        self.username = username
        self.authKey = authKey
        self.imageUrl = imageUrl
    }
}

public enum AuthRepositoryError: Error, LocalizedError {
    case missingCredentials
    case invalidCredentials
    case systemError
    case invalidServerResponse

    public var errorDescription: String? {
        switch self {
        case .missingCredentials:
            return "Wpisz login i haslo"
        case .invalidCredentials:
            return "Niepoprawny login lub haslo"
        case .systemError:
            return "Blad systemu mZUT"
        case .invalidServerResponse:
            return "Niepoprawna odpowiedz serwera"
        }
    }
}

public final class AuthRepository {
    private static let limboLogin = "Student"
    private static let limboPassword = "Test"

    private let apiClient: MzutAPIClient
    private let sessionStore: MzutSessionStore

    public init(apiClient: MzutAPIClient, sessionStore: MzutSessionStore) {
        self.apiClient = apiClient
        self.sessionStore = sessionStore
    }

    public func normalizeLoginIdentifier(_ rawLogin: String?) -> String {
        guard let rawLogin else {
            return ""
        }
        var normalized = rawLogin.trimmingCharacters(in: .whitespacesAndNewlines)
        if let atIndex = normalized.firstIndex(of: "@") {
            normalized = String(normalized[..<atIndex]).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return normalized
    }

    public func login(login rawLogin: String, password: String) async throws -> AuthResult {
        let login = normalizeLoginIdentifier(rawLogin)
        let password = password.trimmingCharacters(in: .whitespacesAndNewlines)

        guard !login.isEmpty, !password.isEmpty else {
            throw AuthRepositoryError.missingCredentials
        }

        if login == Self.limboLogin && password == Self.limboPassword {
            return await MainActor.run {
                let result = AuthResult(
                    userId: "st123456",
                    username: "Student (demo)",
                    authKey: "Student_TOKEN",
                    imageUrl: nil
                )
                sessionStore.updateUser(
                    userId: result.userId,
                    username: result.username,
                    authKey: result.authKey,
                    imageUrl: result.imageUrl
                )
                sessionStore.saveToStorage()
                return result
            }
        }

        let token = MzutTokenGenerator.generateToken(login: login, password: password)
        let tokenJpg = MzutTokenGenerator.generateToken(login: login, password: nil)

        let params = [
            "login": login,
            "password": password,
            "token": token,
            "tokenJpg": tokenJpg
        ]

        guard let auth = try await apiClient.callApi(function: "getAuthorization", params: params) else {
            throw AuthRepositoryError.invalidServerResponse
        }

        let status = JSONHelpers.firstNonEmpty(
            JSONHelpers.string(auth["logInStatus"]),
            JSONHelpers.string(auth["loginInStatus"])
        )

        guard status.uppercased() == "OK" else {
            if status.uppercased() == "SYSTEM ERROR" {
                throw AuthRepositoryError.systemError
            }
            throw AuthRepositoryError.invalidCredentials
        }

        let userId = JSONHelpers.firstNonEmpty(JSONHelpers.string(auth["login"]), login)
        let firstName = JSONHelpers.string(auth["pierwszeImie"])
        let lastName = JSONHelpers.string(auth["nazwisko"])
        let username = [firstName, lastName]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")

        let authKey = JSONHelpers.firstNonEmpty(JSONHelpers.string(auth["token"]), token)
        let tokenJpgFromApi = JSONHelpers.firstNonEmpty(JSONHelpers.string(auth["tokenJpg"]), tokenJpg)
        let imageUrl = "https://www.zut.edu.pl/app-json-proxy/image/?userId=\(userId)&tokenJpg=\(tokenJpgFromApi)"

        let result = AuthResult(
            userId: userId,
            username: username.isEmpty ? userId : username,
            authKey: authKey,
            imageUrl: imageUrl
        )

        return await MainActor.run {
            sessionStore.updateUser(
                userId: result.userId,
                username: result.username,
                authKey: result.authKey,
                imageUrl: result.imageUrl
            )
            sessionStore.saveToStorage()
            return result
        }
    }

    public func logout() {
        Task { @MainActor in
            sessionStore.clearSessionData()
        }
    }
}
