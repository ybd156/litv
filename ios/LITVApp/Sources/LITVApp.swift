import SwiftUI

@main
struct LITVApp: App {
    @StateObject private var session = Session()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .preferredColorScheme(.dark)
                .onAppear { session.bootstrap() }
        }
    }
}

struct RootView: View {
    @EnvironmentObject var session: Session

    var body: some View {
        Group {
            if session.token.isEmpty {
                BindView()
            } else {
                HomeView()
            }
        }
        .background(Color(red: 0.043, green: 0.055, blue: 0.067).ignoresSafeArea())
    }
}
