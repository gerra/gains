import SwiftUI
import UIKit
import ComposeApp

/// Runs before the first view exists: the notification delegate must be in place by the end of
/// launch for a tap on the workout notification that cold starts the app to open the workout.
class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        MainViewControllerKt.prepareLiveSessionNotices()
        return true
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

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
