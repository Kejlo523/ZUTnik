import Foundation

public struct StudyDetails: Codable, Equatable {
    public var album: String
    public var wydzial: String
    public var kierunek: String
    public var forma: String
    public var poziom: String
    public var specjalnosc: String
    public var specjalizacja: String
    public var status: String
    public var rokAkademicki: String
    public var semestrLabel: String

    public init(
        album: String = "",
        wydzial: String = "",
        kierunek: String = "",
        forma: String = "",
        poziom: String = "",
        specjalnosc: String = "",
        specjalizacja: String = "",
        status: String = "",
        rokAkademicki: String = "",
        semestrLabel: String = ""
    ) {
        self.album = album
        self.wydzial = wydzial
        self.kierunek = kierunek
        self.forma = forma
        self.poziom = poziom
        self.specjalnosc = specjalnosc
        self.specjalizacja = specjalizacja
        self.status = status
        self.rokAkademicki = rokAkademicki
        self.semestrLabel = semestrLabel
    }
}

public struct StudyHistoryItem: Codable, Equatable, Identifiable {
    public var label: String
    public var status: String

    public init(label: String = "", status: String = "") {
        self.label = label
        self.status = status
    }

    public var id: String {
        "\(label)|\(status)"
    }
}
