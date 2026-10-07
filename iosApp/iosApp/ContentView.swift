import SwiftUI
import Studio

struct ComposeView: UIViewControllerRepresentable {
	func makeUIViewController(context: Context) -> UIViewController {
		MainViewControllerKt.mainViewController()
	}

	func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
	var body: some View {
		ComposeView()
	}
}
