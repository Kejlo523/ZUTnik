import Foundation

public enum CachePolicy {
    private static let second: TimeInterval = 1
    private static let minute: TimeInterval = 60 * second
    private static let hour: TimeInterval = 60 * minute

    public static let studiesTTL: TimeInterval = 10 * minute
    public static let semestersTTL: TimeInterval = 10 * minute
    public static let gradesTTL: TimeInterval = 20 * minute
    public static let infoTTL: TimeInterval = 30 * minute

    public static let newsTTL: TimeInterval = 4 * hour
    public static let aboutStatsTTL: TimeInterval = 12 * hour

    public static let planFilterTTL: TimeInterval = 12 * hour
    public static let planAlbumTTL: TimeInterval = 6 * hour
    public static let planUserScopeTTL: TimeInterval = 20 * minute
    public static let planSessionTTL: TimeInterval = 6 * hour
}
