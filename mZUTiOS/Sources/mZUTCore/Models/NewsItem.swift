import Foundation

public struct NewsItem: Codable, Equatable, Hashable, Identifiable {
    public var id: Int
    public var title: String
    public var date: String
    public var pubDateRaw: String
    public var snippet: String
    public var link: String
    public var descriptionHtml: String
    public var descriptionText: String
    public var contentHtml: String
    public var thumbUrl: String

    public init(
        id: Int,
        title: String = "",
        date: String = "",
        pubDateRaw: String = "",
        snippet: String = "",
        link: String = "",
        descriptionHtml: String = "",
        descriptionText: String = "",
        contentHtml: String = "",
        thumbUrl: String = ""
    ) {
        self.id = id
        self.title = title
        self.date = date
        self.pubDateRaw = pubDateRaw
        self.snippet = snippet
        self.link = link
        self.descriptionHtml = descriptionHtml
        self.descriptionText = descriptionText
        self.contentHtml = contentHtml
        self.thumbUrl = thumbUrl
    }
}
