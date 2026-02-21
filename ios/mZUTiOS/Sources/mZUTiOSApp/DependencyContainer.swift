import Foundation
import mZUTCore

@MainActor
final class DependencyContainer {
    let sessionStore: MzutSessionStore
    let apiClient: MzutAPIClient
    let authRepository: AuthRepository
    let homeRepository: HomeRepository
    let gradesRepository: GradesRepository
    let studiesInfoRepository: StudiesInfoRepository
    let newsRepository: NewsRepository

    init() {
        let sessionStore = MzutSessionStore()
        let launchArguments = CommandLine.arguments
        if launchArguments.contains("--screenshot-home") {
            sessionStore.updateUser(
                userId: "st123456",
                username: "Student Demo",
                authKey: "Student_TOKEN",
                imageUrl: nil
            )
            sessionStore.saveToStorage()
        }
        let apiClient = MzutAPIClient(sessionStore: sessionStore)
        let gradesRepository = GradesRepository(apiClient: apiClient, sessionStore: sessionStore)

        self.sessionStore = sessionStore
        self.apiClient = apiClient
        self.authRepository = AuthRepository(apiClient: apiClient, sessionStore: sessionStore)
        self.homeRepository = HomeRepository()
        self.gradesRepository = gradesRepository
        self.studiesInfoRepository = StudiesInfoRepository(
            apiClient: apiClient,
            sessionStore: sessionStore,
            gradesRepository: gradesRepository
        )
        self.newsRepository = NewsRepository()
    }
}
