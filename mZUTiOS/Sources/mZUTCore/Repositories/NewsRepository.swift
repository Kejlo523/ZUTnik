import Foundation

public final class NewsRepository {
    private enum Keys {
        static let cacheData = "news_list_data"
        static let cacheTimestamp = "news_timestamp"
    }

    private static let rssURL = URL(string: "https://www.zut.edu.pl/rssfeed-studenci")!

    private let urlSession: URLSession
    private let store: KeyValueStore
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    public init(
        urlSession: URLSession = .shared,
        store: KeyValueStore = UserDefaultsStore(suiteName: "mzut_news_cache")
    ) {
        self.urlSession = urlSession
        self.store = store
    }

    public func loadNews(forceRefresh: Bool = false) async throws -> [NewsItem] {
        if !forceRefresh,
           let cached = loadCachedIfFresh(maxAge: CachePolicy.newsTTL),
           !cached.isEmpty {
            return cached
        }

        do {
            let networkItems = try await fetchFromNetwork()
            saveToCache(networkItems)
            return networkItems
        } catch {
            if let cached = loadCachedRegardlessOfAge(), !cached.isEmpty {
                return cached
            }
            throw error
        }
    }

    public func cacheTimestamp() -> Date? {
        let seconds = store.integer(forKey: Keys.cacheTimestamp)
        guard seconds > 0 else {
            return nil
        }
        return Date(timeIntervalSince1970: TimeInterval(seconds))
    }

    private func fetchFromNetwork() async throws -> [NewsItem] {
        var request = URLRequest(url: Self.rssURL)
        request.setValue("mZUTv2-iOS-News/1.0", forHTTPHeaderField: "User-Agent")

        let (data, response) = try await urlSession.data(for: request)
        guard let httpResponse = response as? HTTPURLResponse,
              (200..<300).contains(httpResponse.statusCode) else {
            throw MzutAPIError.httpError(code: (response as? HTTPURLResponse)?.statusCode ?? -1)
        }

        let parser = RSSParser(data: data)
        let parsedItems = try parser.parse()

        return parsedItems.enumerated().map { index, payload in
            let title = payload["title"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let link = payload["link"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let pubDate = payload["pubDate"]?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let descriptionHtml = payload["description"] ?? ""
            let contentHtml = payload["content:encoded"] ?? payload["encoded"] ?? ""

            let plainDescription = Self.stripHtml(descriptionHtml)
            let snippet = plainDescription.count > 220
                ? String(plainDescription.prefix(217)) + "..."
                : plainDescription

            let thumbUrl = Self.extractFirstImageURL(from: contentHtml).map(Self.fixImageURL) ?? ""

            return NewsItem(
                id: index,
                title: title,
                date: Self.formatRssDate(pubDate),
                pubDateRaw: pubDate,
                snippet: snippet,
                link: link,
                descriptionHtml: descriptionHtml,
                descriptionText: plainDescription,
                contentHtml: contentHtml,
                thumbUrl: thumbUrl
            )
        }
    }

    private func loadCachedIfFresh(maxAge: TimeInterval) -> [NewsItem]? {
        let now = Date().timeIntervalSince1970
        let timestamp = TimeInterval(store.integer(forKey: Keys.cacheTimestamp))
        guard timestamp > 0, (now - timestamp) <= maxAge else {
            return nil
        }
        return loadCachedRegardlessOfAge()
    }

    private func loadCachedRegardlessOfAge() -> [NewsItem]? {
        guard let data = store.data(forKey: Keys.cacheData),
              let decoded = try? decoder.decode([NewsItem].self, from: data) else {
            return nil
        }
        return decoded
    }

    private func saveToCache(_ items: [NewsItem]) {
        guard let data = try? encoder.encode(items) else {
            return
        }
        store.set(data, forKey: Keys.cacheData)
        store.set(Int(Date().timeIntervalSince1970), forKey: Keys.cacheTimestamp)
    }

    private static func stripHtml(_ html: String) -> String {
        guard !html.isEmpty else {
            return ""
        }

        var text = html.replacingOccurrences(
            of: "<[^>]+>",
            with: " ",
            options: .regularExpression
        )
        let entities: [String: String] = [
            "&nbsp;": " ",
            "&amp;": "&",
            "&lt;": "<",
            "&gt;": ">",
            "&quot;": "\"",
            "&#39;": "'"
        ]

        for (entity, replacement) in entities {
            text = text.replacingOccurrences(of: entity, with: replacement)
        }

        return text
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func extractFirstImageURL(from html: String) -> String? {
        guard !html.isEmpty else {
            return nil
        }

        let pattern = "<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>"
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else {
            return nil
        }

        let range = NSRange(html.startIndex..<html.endIndex, in: html)
        guard let match = regex.firstMatch(in: html, options: [], range: range),
              match.numberOfRanges > 1,
              let capturedRange = Range(match.range(at: 1), in: html) else {
            return nil
        }

        return String(html[capturedRange])
    }

    private static func fixImageURL(_ source: String) -> String {
        if source.hasPrefix("http") {
            return source
        }
        if source.hasPrefix("/") {
            return "https://www.zut.edu.pl\(source)"
        }
        return "https://www.zut.edu.pl/\(source)"
    }

    private static func formatRssDate(_ raw: String) -> String {
        guard !raw.isEmpty else {
            return ""
        }

        let inFormatter = DateFormatter()
        inFormatter.locale = Locale(identifier: "en_US_POSIX")
        inFormatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"

        let outFormatter = DateFormatter()
        outFormatter.locale = Locale(identifier: "pl_PL")
        outFormatter.dateFormat = "dd.MM.yyyy HH:mm"

        guard let date = inFormatter.date(from: raw) else {
            return raw
        }

        return outFormatter.string(from: date)
    }
}

private final class RSSParser: NSObject, XMLParserDelegate {
    private let parser: XMLParser

    private var inItem = false
    private var currentElement = ""
    private var currentItem: [String: String] = [:]
    private var items: [[String: String]] = []

    init(data: Data) {
        parser = XMLParser(data: data)
        super.init()
        parser.delegate = self
    }

    func parse() throws -> [[String: String]] {
        guard parser.parse() else {
            throw parser.parserError ?? MzutAPIError.invalidJSON
        }
        return items
    }

    func parser(
        _ parser: XMLParser,
        didStartElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?,
        attributes attributeDict: [String: String] = [:]
    ) {
        let element = (qName ?? elementName).lowercased()

        if element == "item" {
            inItem = true
            currentItem = [:]
        }

        if inItem {
            currentElement = element
            if currentItem[currentElement] == nil {
                currentItem[currentElement] = ""
            }
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        guard inItem, !currentElement.isEmpty else {
            return
        }
        currentItem[currentElement, default: ""] += string
    }

    func parser(
        _ parser: XMLParser,
        didEndElement elementName: String,
        namespaceURI: String?,
        qualifiedName qName: String?
    ) {
        let element = (qName ?? elementName).lowercased()

        if element == "item" {
            inItem = false
            currentElement = ""
            items.append(currentItem)
            currentItem = [:]
            return
        }

        if inItem {
            currentElement = ""
        }
    }
}
