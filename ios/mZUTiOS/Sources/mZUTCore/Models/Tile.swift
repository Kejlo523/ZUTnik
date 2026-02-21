import Foundation

public struct Tile: Codable, Equatable, Hashable, Identifiable {
    public enum ActionType: String, Codable, CaseIterable {
        case plan
        case grades
        case info
        case news
        case activity
        case url
        case planSearch = "plan_search"
        case newsLatest = "news_latest"
    }

    public var id: Int64
    public var col: Int
    public var row: Int
    public var colSpan: Int
    public var rowSpan: Int
    public var title: String
    public var description: String
    public var actionType: ActionType
    public var actionData: String?
    public var color: Int
    public var titleResId: Int
    public var descResId: Int

    public init(
        id: Int64,
        col: Int,
        row: Int,
        colSpan: Int,
        rowSpan: Int,
        title: String,
        description: String,
        actionType: ActionType,
        actionData: String? = nil,
        color: Int = 0,
        titleResId: Int = 0,
        descResId: Int = 0
    ) {
        self.id = id
        self.col = col
        self.row = row
        self.colSpan = colSpan
        self.rowSpan = rowSpan
        self.title = title
        self.description = description
        self.actionType = actionType
        self.actionData = actionData
        self.color = color
        self.titleResId = titleResId
        self.descResId = descResId
    }
}
