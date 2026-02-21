import Foundation

public protocol KeyValueStore {
    func string(forKey key: String) -> String?
    func integer(forKey key: String) -> Int
    func data(forKey key: String) -> Data?
    func bool(forKey key: String) -> Bool

    func set(_ value: String?, forKey key: String)
    func set(_ value: Int, forKey key: String)
    func set(_ value: Bool, forKey key: String)
    func set(_ value: Data?, forKey key: String)
    func removeValue(forKey key: String)
}
