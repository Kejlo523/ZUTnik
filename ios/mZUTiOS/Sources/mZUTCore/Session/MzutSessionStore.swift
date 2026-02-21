import Combine
import Foundation

@MainActor
public final class MzutSessionStore: ObservableObject {
    public nonisolated static let prefsName = "mzut_prefs"

    private enum Keys {
        static let userId = "user_id"
        static let authKey = "auth_key"
        static let username = "username"
        static let imageUrl = "image_url"
        static let activeStudyIndex = "active_study_idx"
        static let activeStudyId = "active_study_id"
        static let studiesData = "studies_json"
    }

    private let store: KeyValueStore
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    @Published public private(set) var userId: String?
    @Published public private(set) var username: String?
    @Published public private(set) var authKey: String?
    @Published public private(set) var imageUrl: String?

    @Published public private(set) var studies: [Study] = []
    @Published public private(set) var activeStudyIndex = 0
    @Published public private(set) var activeStudyId: String?

    public init(store: KeyValueStore = UserDefaultsStore(suiteName: MzutSessionStore.prefsName)) {
        self.store = store
        loadFromStorage()
    }

    public var isAuthenticated: Bool {
        !(userId ?? "").isEmpty && !(authKey ?? "").isEmpty
    }

    public var activeStudy: Study? {
        guard !studies.isEmpty else {
            return nil
        }
        let index = min(max(activeStudyIndex, 0), studies.count - 1)
        return studies[index]
    }

    public func loadFromStorage() {
        userId = store.string(forKey: Keys.userId)
        authKey = store.string(forKey: Keys.authKey)
        username = store.string(forKey: Keys.username)
        imageUrl = store.string(forKey: Keys.imageUrl)
        activeStudyIndex = store.integer(forKey: Keys.activeStudyIndex)
        activeStudyId = Study.normalizeId(store.string(forKey: Keys.activeStudyId))

        if let data = store.data(forKey: Keys.studiesData),
           let decoded = try? decoder.decode([Study].self, from: data) {
            studies = decoded.map { Study(przynaleznoscId: $0.przynaleznoscId, label: $0.label) }
        } else {
            studies = []
        }

        reconcileActiveStudySelection()
    }

    public func saveToStorage() {
        store.set(userId, forKey: Keys.userId)
        store.set(authKey, forKey: Keys.authKey)
        store.set(username, forKey: Keys.username)
        store.set(imageUrl, forKey: Keys.imageUrl)

        reconcileActiveStudySelection()
        store.set(activeStudyIndex, forKey: Keys.activeStudyIndex)
        if let activeStudyId {
            store.set(activeStudyId, forKey: Keys.activeStudyId)
        } else {
            store.removeValue(forKey: Keys.activeStudyId)
        }

        if studies.isEmpty {
            store.removeValue(forKey: Keys.studiesData)
        } else if let encoded = try? encoder.encode(studies) {
            store.set(encoded, forKey: Keys.studiesData)
        }
    }

    public func updateUser(userId: String, username: String, authKey: String, imageUrl: String?) {
        self.userId = userId
        self.username = username
        self.authKey = authKey
        self.imageUrl = imageUrl
        self.studies = []
        self.activeStudyIndex = 0
        self.activeStudyId = nil
    }

    public func setStudies(_ inputStudies: [Study]) {
        studies = inputStudies.map { Study(przynaleznoscId: $0.przynaleznoscId, label: $0.label) }
        reconcileActiveStudySelection()
    }

    public func setActiveStudyIndex(_ index: Int) {
        activeStudyIndex = index
        reconcileActiveStudySelection()
    }

    public func setActiveStudyId(_ studyId: String?) {
        activeStudyId = Study.normalizeId(studyId)
        reconcileActiveStudySelection()
    }

    public func clearSessionData() {
        userId = nil
        authKey = nil
        username = nil
        imageUrl = nil
        studies = []
        activeStudyIndex = 0
        activeStudyId = nil

        store.removeValue(forKey: Keys.userId)
        store.removeValue(forKey: Keys.authKey)
        store.removeValue(forKey: Keys.username)
        store.removeValue(forKey: Keys.imageUrl)
        store.removeValue(forKey: Keys.activeStudyIndex)
        store.removeValue(forKey: Keys.activeStudyId)
        store.removeValue(forKey: Keys.studiesData)
    }

    private func reconcileActiveStudySelection() {
        guard !studies.isEmpty else {
            activeStudyIndex = 0
            activeStudyId = nil
            return
        }

        if let wantedId = Study.normalizeId(activeStudyId),
           let index = studies.firstIndex(where: { Study.normalizeId($0.przynaleznoscId) == wantedId }) {
            activeStudyIndex = index
            activeStudyId = wantedId
            return
        }

        if activeStudyIndex < 0 || activeStudyIndex >= studies.count {
            activeStudyIndex = 0
        }

        activeStudyId = Study.normalizeId(studies[activeStudyIndex].przynaleznoscId)
    }
}
