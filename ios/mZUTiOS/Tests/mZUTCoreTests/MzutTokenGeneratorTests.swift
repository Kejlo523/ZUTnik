import XCTest
@testable import mZUTCore

final class MzutTokenGeneratorTests: XCTestCase {
    func testGenerateTokenWithoutPasswordReturnsProvidedBase() {
        let base = String(repeating: "A", count: 32)

        let token = MzutTokenGenerator.generateToken(
            login: "student",
            password: nil,
            randomBase: base
        )

        XCTAssertEqual(token, base)
    }

    func testGenerateTokenWithPasswordIsDeterministicForSameInput() {
        let base = "12345678901234567890123456789012"
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? .current

        let date = calendar.date(from: DateComponents(
            calendar: calendar,
            year: 2026,
            month: 2,
            day: 21,
            hour: 12,
            minute: 0,
            second: 0
        ))!

        let first = MzutTokenGenerator.generateToken(
            login: "st123456",
            password: "secret",
            now: date,
            randomBase: base,
            calendar: calendar
        )

        let second = MzutTokenGenerator.generateToken(
            login: "st123456",
            password: "secret",
            now: date,
            randomBase: base,
            calendar: calendar
        )

        XCTAssertEqual(first, second)
        XCTAssertEqual(first.count, 32)
        XCTAssertNotEqual(first, base)
    }
}
