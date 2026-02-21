import XCTest
@testable import mZUTCore

final class HomeRepositoryTests: XCTestCase {
    func testLoadTilesReturnsDefaultsWhenEmpty() {
        let repository = HomeRepository(store: InMemoryStore())

        let tiles = repository.loadTiles()

        XCTAssertEqual(tiles.count, 4)
        XCTAssertEqual(tiles.first?.actionType, .plan)
    }

    func testSaveAndLoadTilesRoundTrip() {
        let store = InMemoryStore()
        let repository = HomeRepository(store: store)

        let customTiles = [
            Tile(
                id: 99,
                col: 0,
                row: 0,
                colSpan: 2,
                rowSpan: 1,
                title: "Test",
                description: "Custom",
                actionType: .news
            )
        ]

        repository.saveTiles(customTiles)
        let loadedTiles = repository.loadTiles()

        XCTAssertEqual(loadedTiles, customTiles)
    }
}
