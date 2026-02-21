import Foundation

public final class InMemoryStore: KeyValueStore {
    private var storage: [String: Any] = [:]

    public init() {}

    public func string(forKey key: String) -> String? {
        storage[key] as? String
    }

    public func integer(forKey key: String) -> Int {
        storage[key] as? Int ?? 0
    }

    public func data(forKey key: String) -> Data? {
        storage[key] as? Data
    }

    public func bool(forKey key: String) -> Bool {
        storage[key] as? Bool ?? false
    }

    public func set(_ value: String?, forKey key: String) {
        storage[key] = value
    }

    public func set(_ value: Int, forKey key: String) {
        storage[key] = value
    }

    public func set(_ value: Bool, forKey key: String) {
        storage[key] = value
    }

    public func set(_ value: Data?, forKey key: String) {
        storage[key] = value
    }

    public func removeValue(forKey key: String) {
        storage.removeValue(forKey: key)
    }
}
