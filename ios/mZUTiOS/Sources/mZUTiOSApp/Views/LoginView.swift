import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var appViewModel: AppViewModel

    var body: some View {
        VStack(spacing: 20) {
            Spacer(minLength: 20)

            Image(systemName: "graduationcap.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(.cyan)

            Text("mZUT iOS")
                .font(.largeTitle.bold())

            VStack(spacing: 12) {
                TextField("Login", text: $appViewModel.login)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .padding(12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

                SecureField("Haslo", text: $appViewModel.password)
                    .padding(12)
                    .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))
            }

            if let errorMessage = appViewModel.errorMessage {
                Text(errorMessage)
                    .foregroundStyle(.red)
                    .font(.footnote)
                    .multilineTextAlignment(.center)
            }

            Button {
                appViewModel.loginUser()
            } label: {
                if appViewModel.isLoading {
                    ProgressView()
                        .frame(maxWidth: .infinity)
                } else {
                    Text("Zaloguj")
                        .frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .disabled(appViewModel.isLoading)

            Text("Tryb demo: login Student / haslo Test")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Spacer()
        }
        .padding(24)
        .background(
            LinearGradient(
                colors: [.black, Color(red: 0.08, green: 0.1, blue: 0.16)],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
        )
    }
}
