import SwiftUI

@main
struct WakeDriveApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
                .preferredColorScheme(.dark)
        }
    }
}

struct RootView: View {
    @State private var loggedIn = ApiClient.shared.isLoggedIn

    var body: some View {
        Group {
            if loggedIn {
                ShellView(onLogout: { loggedIn = false })
            } else {
                LoginView(onLoggedIn: { loggedIn = true })
            }
        }
        .background(Theme.background.ignoresSafeArea())
    }
}

enum Theme {
    static let background = Color(red: 6 / 255, green: 18 / 255, blue: 29 / 255)
    static let card = Color(red: 13 / 255, green: 33 / 255, blue: 48 / 255)
    static let cardStroke = Color(red: 37 / 255, green: 69 / 255, blue: 87 / 255)
    static let cyan = Color(red: 66 / 255, green: 217 / 255, blue: 245 / 255)
    static let muted = Color(red: 127 / 255, green: 166 / 255, blue: 184 / 255)
    static let text = Color(red: 217 / 255, green: 243 / 255, blue: 251 / 255)
    static let danger = Color(red: 1, green: 107 / 255, blue: 94 / 255)
    static let warning = Color(red: 1, green: 200 / 255, blue: 87 / 255)
    static let safe = Color(red: 69 / 255, green: 225 / 255, blue: 155 / 255)
}

extension View {
    func wdCard(padding: CGFloat = 16) -> some View {
        self.padding(padding)
            .background(Theme.card)
            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).stroke(Theme.cardStroke, lineWidth: 1))
    }
}
