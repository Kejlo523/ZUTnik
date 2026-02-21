import SwiftUI
import mZUTCore

struct TileCardView: View {
    let tile: Tile

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(tile.title)
                .font(.headline)
                .foregroundStyle(.white)

            Text(tile.description)
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.82))
                .lineLimit(3)

            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, minHeight: 120, alignment: .topLeading)
        .padding(14)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(
                    LinearGradient(
                        colors: [Color(red: 0.13, green: 0.17, blue: 0.26), Color(red: 0.07, green: 0.1, blue: 0.17)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
        .overlay(
            RoundedRectangle(cornerRadius: 16)
                .strokeBorder(.white.opacity(0.06), lineWidth: 1)
        )
    }
}
