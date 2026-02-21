import Foundation

public actor GradesRepository {
    private struct StudiesCacheEntry {
        var timestamp: Date
        var userId: String
        var list: [Study]
    }

    private struct SemesterCacheEntry {
        var timestamp: Date
        var przynaleznoscId: String
        var list: [Semester]
    }

    private let apiClient: MzutAPIClient
    private let sessionStore: MzutSessionStore

    private var studiesCache: StudiesCacheEntry?
    private var semestersCacheByStudy: [String: SemesterCacheEntry] = [:]

    public init(apiClient: MzutAPIClient, sessionStore: MzutSessionStore) {
        self.apiClient = apiClient
        self.sessionStore = sessionStore
    }

    public func invalidateMemoryCache() {
        studiesCache = nil
        semestersCacheByStudy = [:]
    }

    public func loadStudies(forceRefresh: Bool = false) async throws -> [Study] {
        let sessionSnapshot = await MainActor.run {
            (
                userId: sessionStore.userId,
                authKey: sessionStore.authKey,
                sessionStudies: sessionStore.studies
            )
        }

        guard let userId = sessionSnapshot.userId,
              let authKey = sessionSnapshot.authKey else {
            return []
        }

        let now = Date()
        if !forceRefresh,
           let cache = studiesCache,
           cache.userId == userId,
           now.timeIntervalSince(cache.timestamp) < CachePolicy.studiesTTL {
            return cache.list
        }

        if !forceRefresh,
           studiesCache == nil,
           !sessionSnapshot.sessionStudies.isEmpty {
            let bootstrap = sessionSnapshot.sessionStudies
            studiesCache = StudiesCacheEntry(timestamp: now, userId: userId, list: bootstrap)
            return bootstrap
        }

        let params = [
            "login": userId,
            "token": authKey
        ]

        guard let menu = try await apiClient.callApi(function: "getMenuStudent", params: params) else {
            return []
        }

        let menuRows = JSONHelpers.arrayOfDictionaries(from: menu["Menu"])
        let studies = menuRows.map { row in
            let studyId = Study.normalizeId(JSONHelpers.string(row["przynaleznoscId"]))
            let nazwa = JSONHelpers.string(row["nazwa"]).trimmingCharacters(in: .whitespacesAndNewlines)
            let poziom = JSONHelpers.string(row["poziom"]).trimmingCharacters(in: .whitespacesAndNewlines)

            var label = nazwa
            if !poziom.isEmpty {
                label = label.isEmpty ? "(\(poziom))" : "\(label) (\(poziom))"
            }
            if label.isEmpty {
                label = studyId ?? ""
            }
            return Study(przynaleznoscId: studyId, label: label)
        }

        studiesCache = StudiesCacheEntry(timestamp: now, userId: userId, list: studies)
        await MainActor.run {
            sessionStore.setStudies(studies)
            sessionStore.saveToStorage()
        }
        return studies
    }

    public func loadSemesters(forceRefresh: Bool = false) async throws -> [Semester] {
        let studies = try await loadStudies(forceRefresh: forceRefresh)
        guard !studies.isEmpty else {
            return []
        }

        let sessionSnapshot = await MainActor.run {
            (
                userId: sessionStore.userId,
                authKey: sessionStore.authKey,
                activeStudy: sessionStore.activeStudy
            )
        }

        guard let userId = sessionSnapshot.userId,
              let authKey = sessionSnapshot.authKey else {
            return []
        }

        var activeStudyId = Study.normalizeId(sessionSnapshot.activeStudy?.przynaleznoscId)
        if activeStudyId == nil,
           let firstStudy = studies.first,
           let firstId = Study.normalizeId(firstStudy.przynaleznoscId) {
            activeStudyId = firstId
            await MainActor.run {
                sessionStore.setActiveStudyId(firstId)
                sessionStore.saveToStorage()
            }
        }

        guard let activeStudyId else {
            return []
        }

        let now = Date()
        let cacheKey = "\(userId)_\(activeStudyId)"
        if !forceRefresh,
           let cache = semestersCacheByStudy[cacheKey],
           cache.przynaleznoscId == activeStudyId,
           now.timeIntervalSince(cache.timestamp) < CachePolicy.semestersTTL {
            return cache.list
        }

        let params = [
            "login": userId,
            "token": authKey,
            "przynaleznoscId": activeStudyId,
            "oceny": "true"
        ]

        guard let response = try await apiClient.callApi(function: "getStudies", params: params) else {
            return []
        }

        let rows = JSONHelpers.arrayOfDictionaries(from: response["Przebieg"])
        let semesters = rows.map { row in
            Semester(
                listaSemestrowId: JSONHelpers.string(row["listaSemestrowId"]),
                nrSemestru: JSONHelpers.string(row["nrSemestru"]),
                pora: JSONHelpers.string(row["pora"]),
                rokAkademicki: JSONHelpers.string(row["rokAkademicki"]),
                status: JSONHelpers.firstNonEmpty(
                    JSONHelpers.string(row["status"]),
                    JSONHelpers.string(row["statusO"])
                )
            )
        }

        semestersCacheByStudy[cacheKey] = SemesterCacheEntry(
            timestamp: now,
            przynaleznoscId: activeStudyId,
            list: semesters
        )
        return semesters
    }

    public func loadGrades(for semester: Semester) async throws -> [Grade] {
        guard let semesterId = semester.listaSemestrowId,
              !semesterId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return []
        }
        return try await loadGrades(forSemesterId: semesterId)
    }

    public func loadGrades(forSemesterId semesterId: String) async throws -> [Grade] {
        let sessionSnapshot = await MainActor.run {
            (
                userId: sessionStore.userId,
                authKey: sessionStore.authKey
            )
        }

        guard let userId = sessionSnapshot.userId,
              let authKey = sessionSnapshot.authKey else {
            return []
        }

        let params = [
            "login": userId,
            "token": authKey,
            "listaSemestrowId": semesterId
        ]

        guard let response = try await apiClient.callApi(function: "getGrade", params: params) else {
            return []
        }

        let rows = JSONHelpers.arrayOfDictionaries(from: response["Ocena"])

        return rows.map { row in
            var subject = JSONHelpers.firstNonEmpty(
                JSONHelpers.string(row["przedmiot"]),
                JSONHelpers.string(row["przedmiotO"])
            )

            let form = JSONHelpers.firstNonEmpty(
                JSONHelpers.string(row["formaZajec"]),
                JSONHelpers.string(row["formaZajecO"])
            )

            if !form.isEmpty {
                subject = subject.isEmpty ? "(\(form))" : "\(subject) (\(form))"
            }

            let term = JSONHelpers.firstNonEmpty(
                JSONHelpers.string(row["termin"]),
                JSONHelpers.string(row["terminO"])
            )
            let date = JSONHelpers.string(row["data"])
            let mergedDate: String
            if term.isEmpty {
                mergedDate = date
            } else if date.isEmpty {
                mergedDate = term
            } else {
                mergedDate = "\(term) \(date)"
            }

            return Grade(
                subjectName: subject,
                grade: JSONHelpers.string(row["ocena"]),
                weight: parseEcts(row),
                type: form,
                teacher: JSONHelpers.string(row["pracownik"]),
                date: mergedDate
            )
        }
    }

    private func parseEcts(_ row: [String: Any]) -> Double {
        let direct = JSONHelpers.double(row["ects"])
        if direct > 0 {
            return direct
        }

        for key in ["ectsO", "ECTS", "punktyEcts", "punkty_ects", "punktyEctsO"] {
            let value = JSONHelpers.double(row[key])
            if value > 0 {
                return value
            }
        }
        return 0
    }
}
