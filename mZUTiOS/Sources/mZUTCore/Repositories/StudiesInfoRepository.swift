import Foundation

public final class StudiesInfoRepository {
    private let apiClient: MzutAPIClient
    private let sessionStore: MzutSessionStore
    private let gradesRepository: GradesRepository

    public init(apiClient: MzutAPIClient, sessionStore: MzutSessionStore, gradesRepository: GradesRepository) {
        self.apiClient = apiClient
        self.sessionStore = sessionStore
        self.gradesRepository = gradesRepository
    }

    public func loadCurrentStudyDetails() async throws -> StudyDetails? {
        let sessionSnapshot = await MainActor.run {
            (
                userId: sessionStore.userId,
                authKey: sessionStore.authKey
            )
        }

        guard let userId = sessionSnapshot.userId,
              let authKey = sessionSnapshot.authKey,
              let studyId = try await getActiveStudyId() else {
            return nil
        }

        let params = [
            "login": userId,
            "token": authKey,
            "przynaleznoscId": studyId
        ]

        guard let response = try await apiClient.callApi(function: "getStudy", params: params) else {
            return nil
        }

        return StudyDetails(
            album: JSONHelpers.string(response["album"]),
            wydzial: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["wydzial"]),
                JSONHelpers.string(response["wydzialAng"])
            ),
            kierunek: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["kierunek"]),
                JSONHelpers.string(response["kierunekAng"])
            ),
            forma: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["forma"]),
                JSONHelpers.string(response["formaAng"])
            ),
            poziom: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["poziom"]),
                JSONHelpers.string(response["poziomAng"])
            ),
            specjalnosc: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["specjalnosc"]),
                JSONHelpers.string(response["specjalnoscO"])
            ),
            specjalizacja: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["specjalizacja"]),
                JSONHelpers.string(response["specjalizacjaO"])
            ),
            status: JSONHelpers.firstNonEmpty(
                JSONHelpers.string(response["status"]),
                JSONHelpers.string(response["statusAng"])
            ),
            rokAkademicki: JSONHelpers.string(response["rokAkademicki"]),
            semestrLabel: [
                JSONHelpers.string(response["nrSemestru"]),
                JSONHelpers.string(response["pora"])
            ]
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        )
    }

    public func loadStudyHistory() async throws -> [StudyHistoryItem] {
        let sessionSnapshot = await MainActor.run {
            (
                userId: sessionStore.userId,
                authKey: sessionStore.authKey
            )
        }

        guard let userId = sessionSnapshot.userId,
              let authKey = sessionSnapshot.authKey,
              let studyId = try await getActiveStudyId() else {
            return []
        }

        let params = [
            "login": userId,
            "token": authKey,
            "przynaleznoscId": studyId,
            "oceny": "true"
        ]

        guard let response = try await apiClient.callApi(function: "getStudies", params: params) else {
            return []
        }

        let rows = JSONHelpers.arrayOfDictionaries(from: response["Przebieg"])

        return rows.map { row in
            let nrSemestru = JSONHelpers.string(row["nrSemestru"])
            let pora = JSONHelpers.string(row["pora"])
            let rok = JSONHelpers.string(row["rokAkademicki"])
            let status = JSONHelpers.firstNonEmpty(
                JSONHelpers.string(row["status"]),
                JSONHelpers.string(row["statusO"])
            )

            let label = ["\(nrSemestru) \(pora)".trimmingCharacters(in: .whitespacesAndNewlines), rok]
                .filter { !$0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
                .joined(separator: " - ")

            return StudyHistoryItem(label: label, status: status)
        }
    }

    private func getActiveStudyId() async throws -> String? {
        let studies = try await gradesRepository.loadStudies(forceRefresh: false)
        guard !studies.isEmpty else {
            return nil
        }

        let activeFromSession = await MainActor.run {
            Study.normalizeId(sessionStore.activeStudy?.przynaleznoscId)
        }
        if let activeFromSession {
            return activeFromSession
        }

        if let firstId = Study.normalizeId(studies.first?.przynaleznoscId) {
            await MainActor.run {
                sessionStore.setActiveStudyId(firstId)
                sessionStore.saveToStorage()
            }
            return firstId
        }

        return nil
    }
}
