import Foundation

public final class UserDefaultsStore: KeyValueStore {
    private let defaults: UserDefaults

    public init(suiteName: String? = nil) {
        if let suiteName,
           let namedDefaults = UserDefaults(suiteName: suiteName) {
            self.defaults = namedDefaults
        } else {
            self.defaults = .standard
        }
    }

    public func string(forKey key: String) -> String? {
        defaults.string(forKey: key)
    }

    public func integer(forKey key: String) -> Int {
        defaults.integer(forKey: key)
    }

    public func data(forKey key: String) -> Data? {
        defaults.data(forKey: key)
    }

    public func bool(forKey key: String) -> Bool {
        defaults.bool(forKey: key)
    }

    public func set(_ value: String?, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    public func set(_ value: Int, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    public func set(_ value: Bool, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    public func set(_ value: Data?, forKey key: String) {
        defaults.set(value, forKey: key)
    }

    public func removeValue(forKey key: String) {
        defaults.removeObject(forKey: key)
    }
}
