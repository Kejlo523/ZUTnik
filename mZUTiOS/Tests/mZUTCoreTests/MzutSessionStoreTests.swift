import XCTest
@testable import mZUTCore

@MainActor
final class MzutSessionStoreTests: XCTestCase {
    func testSessionPersistsAndLoadsFromStore() {
        let inMemoryStore = InMemoryStore()

        let writer = MzutSessionStore(store: inMemoryStore)
        writer.updateUser(
            userId: "st123456",
            username: "Jan Kowalski",
            authKey: "token123",
            imageUrl: "https://example.com/avatar.jpg"
        )
        writer.setStudies([
            Study(przynaleznoscId: "111", label: "Informatyka"),
            Study(przynaleznoscId: "222", label: "Automatyka")
        ])
        writer.setActiveStudyId("222")
        writer.saveToStorage()

        let reader = MzutSessionStore(store: inMemoryStore)

        XCTAssertEqual(reader.userId, "st123456")
        XCTAssertEqual(reader.username, "Jan Kowalski")
        XCTAssertEqual(reader.authKey, "token123")
        XCTAssertEqual(reader.imageUrl, "https://example.com/avatar.jpg")
        XCTAssertEqual(reader.studies.count, 2)
        XCTAssertEqual(reader.activeStudyId, "222")
        XCTAssertEqual(reader.activeStudy?.przynaleznoscId, "222")
    }

    func testClearSessionDataRemovesAuthentication() {
        let inMemoryStore = InMemoryStore()

        let session = MzutSessionStore(store: inMemoryStore)
        session.updateUser(userId: "st1", username: "A", authKey: "t", imageUrl: nil)
        session.saveToStorage()

        XCTAssertTrue(session.isAuthenticated)

        session.clearSessionData()

        XCTAssertFalse(session.isAuthenticated)
        XCTAssertNil(session.userId)
        XCTAssertNil(session.authKey)
        XCTAssertEqual(session.studies.count, 0)
    }
}
