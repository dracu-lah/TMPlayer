import SwiftUI
import TMPlayerKit
import UIKit
import WebKit

// In app trailers (private/TRAILERS-PLAN.md, T5). Kotlin decides: it builds the page
// (TrailerEmbed.pageHtml), runs the state machine (TrailerRules, through TrailerSession), and
// falls back to the YouTube app or Safari when this side says the trailer cannot play. This side
// only shows YouTube's own embedded player in a sheet, reports what the page says and what the
// viewer chooses, and tears the web view down. Nothing is created before the trailer button is
// tapped: Kotlin calls `present` from that tap and from nowhere else. UNVERIFIED on a Mac
// (docs/IOS.md, "Needs a Mac": trailers).

/// Presents the trailer sheet over the app's screens. Installed once as the app starts
/// (Trailers.install in TMPlayerApp).
final class TrailerSheetPresenterImpl: NSObject, TrailerSheetPresenter {
    func present(html: String, texts: TrailerSheetTexts, listener: TrailerSheetListener) -> TrailerSheetHandle? {
        // No screen to present from, or a trailer sheet already up: Kotlin opens it outside instead.
        guard let top = Self.topViewController(), !(top is TrailerSheetController) else { return nil }
        let controller = TrailerSheetController(html: html, texts: texts, listener: listener)
        top.present(controller, animated: true)
        // The swipe down closes the sheet without going through Close.
        controller.presentationController?.delegate = controller
        return controller
    }

    private static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        let window = scene?.windows.first { $0.isKeyWindow } ?? scene?.windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }
}

/// The sheet: a form sheet on the iPad (a fixed size in the middle), a page sheet on the iPhone,
/// full screen on an iPhone on its side. Kotlin drives it through [TrailerSheetHandle].
final class TrailerSheetController: UIHostingController<TrailerSheetView>, TrailerSheetHandle, UIAdaptivePresentationControllerDelegate {
    private let model: TrailerSheetModel
    private let listener: TrailerSheetListener
    private var finished = false
    private var resignObserver: NSObjectProtocol?

    init(html: String, texts: TrailerSheetTexts, listener: TrailerSheetListener) {
        let host = TrailerWebHost(html: html, pauseScript: texts.pauseScript)
        let model = TrailerSheetModel(texts: texts, host: host)
        self.model = model
        self.listener = listener
        super.init(rootView: TrailerSheetView(model: model))
        host.onMessage = { [weak self] raw in self?.listener.onMessage(raw: raw) }
        host.onLeave = { [weak self] in self?.listener.onLeave() }
        model.onClose = { [weak self] in self?.close() }
        model.onLeave = { [weak self] in self?.listener.onLeave() }
        modalPresentationStyle = .formSheet
        // The iPad's sheet: a 16:9 picture under the bar. Ignored where the sheet is not a card.
        preferredContentSize = CGSize(width: 760, height: 520)
        view.backgroundColor = .black
        // Out of sight, nothing plays: paused as the app leaves the foreground (home, lock, the app
        // switcher), left paused on its return. The same on every scene, as one trailer shows at a time.
        resignObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willResignActiveNotification, object: nil, queue: .main
        ) { [weak self] _ in
            self?.model.host.pause()
        }
        // Heard with the silent switch on, like the video player (docs/IOS.md); off the main thread,
        // as the audio server can make it wait.
        DispatchQueue.global(qos: .userInitiated).async { PlaybackAudio.activate() }
    }

    @MainActor required dynamic init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) is not used")
    }

    deinit {
        if let resignObserver { NotificationCenter.default.removeObserver(resignObserver) }
    }

    // MARK: TrailerSheetHandle (Kotlin)

    func showNotice() {
        model.notice = model.texts.unavailable
        UIAccessibility.post(notification: .announcement, argument: model.texts.unavailable)
    }

    func close() {
        if finished { return }
        finish()
        dismiss(animated: true)
    }

    // MARK: Leaving

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        finish()
    }

    /// Once, however the sheet goes (Close, Kotlin, a swipe down): the web view is torn down and
    /// Kotlin is told, so nothing keeps loading or playing behind it.
    private func finish() {
        if finished { return }
        finished = true
        if let resignObserver { NotificationCenter.default.removeObserver(resignObserver) }
        resignObserver = nil
        model.host.teardown()
        DispatchQueue.global(qos: .utility).async { PlaybackAudio.deactivate() }
        listener.onClosed()
    }
}

/// What the sheet's SwiftUI view shows and calls.
final class TrailerSheetModel: ObservableObject {
    let texts: TrailerSheetTexts
    let host: TrailerWebHost
    @Published var notice: String?
    var onClose: () -> Void = {}
    var onLeave: () -> Void = {}

    init(texts: TrailerSheetTexts, host: TrailerWebHost) {
        self.texts = texts
        self.host = host
    }
}

struct TrailerSheetView: View {
    @ObservedObject var model: TrailerSheetModel

    var body: some View {
        VStack(spacing: 0) {
            bar
            ZStack {
                // The page and YouTube's player carry their own titles for VoiceOver; the container names the trailer.
                TrailerWebView(webView: model.host.webView)
                    .accessibilityElement(children: .contain)
                    .accessibilityLabel(model.texts.title)
                if let notice = model.notice {
                    Text(notice)
                        .font(.headline)
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                        .padding(20)
                        .background(.black.opacity(0.8), in: RoundedRectangle(cornerRadius: 16))
                        .padding(24)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(Color.black)
        .preferredColorScheme(.dark)
    }

    /// Close first (the Escape key on an iPad's keyboard too), then Watch on YouTube. Side by side
    /// where they fit, stacked where the text is large and they do not.
    private var bar: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                closeButton
                Spacer(minLength: 8)
                watchButton
            }
            VStack(alignment: .leading, spacing: 8) {
                closeButton
                watchButton
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    private var closeButton: some View {
        Button(action: model.onClose) {
            Label(model.texts.close, systemImage: "xmark")
                .frame(minHeight: 44)
        }
        .keyboardShortcut(.cancelAction)
        .accessibilityLabel(model.texts.close)
    }

    private var watchButton: some View {
        Button(action: model.onLeave) {
            Label(model.texts.watchOnYoutube, systemImage: "arrow.up.right.square")
                .frame(minHeight: 44)
        }
        .accessibilityLabel(model.texts.watchOnYoutube)
    }
}

/// Hosts the web view the model's [TrailerWebHost] owns (it is made, and torn down, there, not by
/// SwiftUI's view lifecycle).
struct TrailerWebView: UIViewRepresentable {
    let webView: WKWebView

    func makeUIView(context: Context) -> WKWebView {
        webView
    }

    func updateUIView(_ uiView: WKWebView, context: Context) {}
}

/// YouTube's hosts, as Android's TrailerNavigation: https on one of them or a subdomain.
enum TrailerNavigation {
    private static let hosts = ["youtube-nocookie.com", "youtube.com", "youtu.be"]

    static func isYouTube(_ url: URL?) -> Bool {
        guard let url, url.absoluteString.count <= 4096, url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased() else { return false }
        return hosts.contains { host == $0 || host.hasSuffix("." + $0) }
    }
}

/// The WKWebView and everything it needs, owned in one place so it can be torn down once.
///
/// The page is Kotlin's (TrailerEmbed.pageHtml), loaded with https://www.youtube-nocookie.com as
/// its base URL so YouTube gets a referrer and an origin (a bare data page gets its error 153). It
/// speaks through `webkit.messageHandlers.TMPlayerTrailer`; its messages are short strings Kotlin
/// reads as outside data. Media plays without a further gesture because the tap on the trailer
/// button was the gesture. The page never navigates: a YouTube frame inside it may move between
/// YouTube's hosts, a link the viewer follows in the top frame (the title) opens outside, and
/// anything else is refused. The data store is not persistent: nothing YouTube sets outlives the sheet.
final class TrailerWebHost: NSObject, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    static let bridge = "TMPlayerTrailer"
    private static let base = URL(string: "https://www.youtube-nocookie.com")!
    /// Longer than any message the page sends; the rest is never read.
    private static let maxMessage = 64

    let webView: WKWebView
    var onMessage: (String) -> Void = { _ in }
    var onLeave: () -> Void = {}

    private let pauseScript: String
    private var pageLoaded = false
    private var tornDown = false

    init(html: String, pauseScript: String) {
        self.pauseScript = pauseScript
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        // Only because of the tap that opened the sheet; nothing loads or plays before it.
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.allowsPictureInPictureMediaPlayback = false
        configuration.websiteDataStore = .nonPersistent()
        configuration.preferences.javaScriptCanOpenWindowsAutomatically = false
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        // On the web view's own copy of the configuration, which is the one that counts.
        webView.configuration.userContentController.add(WeakScriptHandler(self), name: Self.bridge)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.isOpaque = false
        webView.backgroundColor = .black
        webView.scrollView.backgroundColor = .black
        webView.scrollView.isScrollEnabled = false
        webView.scrollView.bounces = false
        webView.allowsBackForwardNavigationGestures = false
        webView.allowsLinkPreview = false
        webView.loadHTMLString(html, baseURL: Self.base)
    }

    /// The app left the foreground: the trailer pauses and every media element stops.
    func pause() {
        if tornDown { return }
        webView.evaluateJavaScript(pauseScript, completionHandler: nil)
        webView.pauseAllMediaPlayback(completionHandler: nil)
    }

    /// Stops everything and lets the page go. Once, when the sheet goes.
    func teardown() {
        if tornDown { return }
        tornDown = true
        webView.stopLoading()
        webView.configuration.userContentController.removeScriptMessageHandler(forName: Self.bridge)
        webView.navigationDelegate = nil
        webView.uiDelegate = nil
        webView.loadHTMLString("", baseURL: nil)
        webView.removeFromSuperview()
    }

    // MARK: WKScriptMessageHandler

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        // Only our own page's top frame: YouTube's frame inside it is given the handler too and says nothing we read.
        guard !tornDown, message.frameInfo.isMainFrame, let text = message.body as? String else { return }
        onMessage(String(text.prefix(Self.maxMessage)))
    }

    // MARK: WKNavigationDelegate

    func webView(
        _ webView: WKWebView,
        decidePolicyFor navigationAction: WKNavigationAction,
        decisionHandler: @escaping (WKNavigationActionPolicy) -> Void
    ) {
        let url = navigationAction.request.url
        let inSubframe = navigationAction.targetFrame.map { !$0.isMainFrame } ?? false
        // Our own page, once.
        if !pageLoaded, navigationAction.targetFrame?.isMainFrame == true, url?.host == Self.base.host {
            pageLoaded = true
            decisionHandler(.allow)
            return
        }
        // YouTube's player inside the page, between YouTube's hosts and nowhere else.
        if inSubframe, TrailerNavigation.isYouTube(url) || url?.absoluteString == "about:blank" {
            decisionHandler(.allow)
            return
        }
        // A link the viewer followed to YouTube (its title, "Watch on YouTube"): opens outside.
        if !inSubframe, navigationAction.navigationType == .linkActivated, TrailerNavigation.isYouTube(url) {
            onLeave()
        }
        decisionHandler(.cancel)
    }

    /// The web content process was killed (memory): the trailer cannot go on, so Kotlin opens it outside.
    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        if !tornDown { onMessage("error:-1") }
    }

    // MARK: WKUIDelegate

    /// No other windows: a target=_blank link is refused (or, for YouTube's, sent outside) in decidePolicyFor.
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if !tornDown, navigationAction.navigationType == .linkActivated, TrailerNavigation.isYouTube(navigationAction.request.url) {
            onLeave()
        }
        return nil
    }
}

/// A WKUserContentController keeps its handlers alive for good; this one lets go of the host.
private final class WeakScriptHandler: NSObject, WKScriptMessageHandler {
    private weak var target: WKScriptMessageHandler?

    init(_ target: WKScriptMessageHandler) {
        self.target = target
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}
