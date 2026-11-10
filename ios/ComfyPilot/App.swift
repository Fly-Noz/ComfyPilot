import SwiftUI

@main
struct ComfyPilotApp: App {
    @StateObject private var store = SessionStore()
    @StateObject private var updates = UpdateStore()

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(store)
                .environmentObject(updates)
        }
    }
}
