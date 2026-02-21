import SwiftUI
import mZUTCore

struct HomeView: View {
    @EnvironmentObject private var appViewModel: AppViewModel

    private let columns = [
        GridItem(.flexible(), spacing: 12),
        GridItem(.flexible(), spacing: 12)
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Czesc, \(firstName(from: appViewModel.displayName))")
                            .font(.title.bold())
                            .foregroundStyle(.white)

                        Text("Port iOS mZUT v2 - warstwa core i glowne moduły")
                            .foregroundStyle(.white.opacity(0.72))
                    }

                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(appViewModel.tiles, id: \.id) { tile in
                            NavigationLink {
                                destination(for: tile)
                            } label: {
                                TileCardView(tile: tile)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(16)
            }
            .background(
                LinearGradient(
                    colors: [Color.black, Color(red: 0.06, green: 0.09, blue: 0.14)],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .ignoresSafeArea()
            )
            .navigationTitle("mZUT")
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Reset") {
                        appViewModel.restoreDefaultTiles()
                    }
                }

                ToolbarItem(placement: .topBarTrailing) {
                    Button("Wyloguj") {
                        appViewModel.logout()
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func destination(for tile: Tile) -> some View {
        switch tile.actionType {
        case .plan, .planSearch:
            PlanPlaceholderView()
        case .grades:
            GradesFeatureView()
        case .info:
            StudiesInfoFeatureView()
        case .news, .newsLatest:
            NewsFeatureView()
        case .url:
            URLFeatureView(urlString: tile.actionData ?? "")
        case .activity:
            GenericActivityPlaceholderView(title: tile.title)
        }
    }

    private func firstName(from raw: String) -> String {
        let clean = raw
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)

        guard !clean.isEmpty else {
            return "Student"
        }

        return clean.components(separatedBy: " ").first ?? clean
    }
}
