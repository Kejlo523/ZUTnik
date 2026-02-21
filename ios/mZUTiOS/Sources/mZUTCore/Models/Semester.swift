import Foundation

public struct Semester: Codable, Equatable, Hashable, Identifiable {
    public var listaSemestrowId: String?
    public var nrSemestru: String?
    public var pora: String?
    public var rokAkademicki: String?
    public var status: String?

    public init(
        listaSemestrowId: String? = nil,
        nrSemestru: String? = nil,
        pora: String? = nil,
        rokAkademicki: String? = nil,
        status: String? = nil
    ) {
        self.listaSemestrowId = listaSemestrowId
        self.nrSemestru = nrSemestru
        self.pora = pora
        self.rokAkademicki = rokAkademicki
        self.status = status
    }

    public var id: String {
        if let listaSemestrowId, !listaSemestrowId.isEmpty {
            return listaSemestrowId
        }
        return label
    }

    public var label: String {
        var pieces: [String] = []
        if let nrSemestru, !nrSemestru.isEmpty {
            pieces.append("Semestr \(nrSemestru)")
        }
        if let pora, !pora.isEmpty {
            pieces.append("(\(pora))")
        }
        if let rokAkademicki, !rokAkademicki.isEmpty {
            pieces.append(rokAkademicki)
        }
        return pieces.isEmpty ? "Semestr" : pieces.joined(separator: " ")
    }
}
