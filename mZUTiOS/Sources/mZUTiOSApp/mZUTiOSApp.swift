import SwiftUI

@main
struct MzutIOSApp: App {
    @StateObject private var viewModel = AppViewModel()

    var body: some Scene {
        WindowGroup {
            Group {
                if viewModel.isAuthenticated {
                    HomeView()
                } else {
                    LoginView()
                }
            }
            .environmentObject(viewModel)
        }
    }
}
