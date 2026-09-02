import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // CSV shared in via "Open in Gains", Files, or AirDrop.
                    MainViewControllerKt.handleIncomingFile(url: url)
                }
        }
    }
}
