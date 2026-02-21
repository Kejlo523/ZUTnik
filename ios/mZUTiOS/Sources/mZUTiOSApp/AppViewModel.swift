import Combine
import Foundation
import mZUTCore

@MainActor
final class AppViewModel: ObservableObject {
    @Published var login = ""
    @Published var password = ""
    @Published var isLoading = false
    @Published var errorMessage: String?

    @Published private(set) var isAuthenticated = false
    @Published private(set) var displayName = "Student"
    @Published private(set) var tiles: [Tile] = []

    let dependencies: DependencyContainer

    private var cancellables = Set<AnyCancellable>()

    init(dependencies: DependencyContainer = DependencyContainer()) {
        self.dependencies = dependencies
        self.tiles = dependencies.homeRepository.loadTiles()
        bindSession()
        refreshFromSession()
    }

    func loginUser() {
        guard !isLoading else {
            return
        }

        isLoading = true
        errorMessage = nil

        let currentLogin = login
        let currentPassword = password

        Task {
            do {
                let result = try await dependencies.authRepository.login(login: currentLogin, password: currentPassword)
                await MainActor.run {
                    displayName = result.username
                    isLoading = false
                    password = ""
                }
            } catch {
                await MainActor.run {
                    errorMessage = error.localizedDescription
                    isLoading = false
                }
            }
        }
    }

    func logout() {
        dependencies.authRepository.logout()
        password = ""
        errorMessage = nil
    }

    func restoreDefaultTiles() {
        tiles = dependencies.homeRepository.restoreDefaults()
    }

    private func bindSession() {
        dependencies.sessionStore.$userId
            .combineLatest(dependencies.sessionStore.$authKey, dependencies.sessionStore.$username)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] userId, authKey, username in
                guard let self else {
                    return
                }
                self.isAuthenticated = !(userId ?? "").isEmpty && !(authKey ?? "").isEmpty
                let cleanUsername = username?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                self.displayName = cleanUsername.isEmpty ? (userId ?? "Student") : cleanUsername
            }
            .store(in: &cancellables)
    }

    private func refreshFromSession() {
        let session = dependencies.sessionStore
        isAuthenticated = session.isAuthenticated

        let username = session.username?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        if !username.isEmpty {
            displayName = username
        } else {
            displayName = session.userId ?? "Student"
        }
    }
}
