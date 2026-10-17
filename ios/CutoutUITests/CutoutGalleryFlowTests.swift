import XCTest
import UIKit
import UniformTypeIdentifiers

final class CutoutGalleryFlowTests: XCTestCase {
  func testIPadLandscapeSampleOpensCenteredEditor() {
    XCUIDevice.shared.orientation = .landscapeLeft
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    XCTAssertGreaterThan(app.frame.width, app.frame.height, "This test must exercise an iPad in landscape.")
    let sample = app.buttons["Sample frame"]
    guard sample.waitForExistence(timeout: 20) else {
      XCTFail("The local sample action is unavailable in the test build.\n\(app.debugDescription)")
      return
    }
    sample.tap()

    let done = app.staticTexts["Done"]
    XCTAssertTrue(done.waitForExistence(timeout: 240), "Sample inference did not reach its result screen.\n\(app.debugDescription)")
    let edit = app.buttons["Edit in app"]
    XCTAssertTrue(edit.waitForExistence(timeout: 20))
    edit.tap()

    let copy = app.buttons["Copy"]
    XCTAssertTrue(copy.waitForExistence(timeout: 60), "The landscape editor did not become ready.\n\(app.debugDescription)")
    let save = app.buttons["Save"]
    XCTAssertTrue(app.staticTexts["Erase the extra or restore an edge"].exists)
    XCTAssertTrue(copy.isHittable)
    XCTAssertTrue(save.isHittable)
    let window = app.windows.firstMatch
    let bounds = window.frame
    let actions = [copy.frame, save.frame]
    for action in actions {
      XCTAssertGreaterThanOrEqual(action.minX, bounds.minX, "An editor action begins outside the app window: \(action), window=\(bounds)")
      XCTAssertLessThanOrEqual(action.maxX, bounds.maxX, "An editor action extends past the app window: \(action), window=\(bounds)")
      XCTAssertGreaterThanOrEqual(action.minY, bounds.minY, "An editor action begins above the app window: \(action), window=\(bounds)")
      XCTAssertLessThanOrEqual(action.maxY, bounds.maxY, "An editor action extends below the app window: \(action), window=\(bounds)")
    }
    let geometry = XCTAttachment(string: "screen=\(app.frame)\nwindow=\(bounds)\ncopy=\(copy.frame)\nsave=\(save.frame)\n\(app.debugDescription)")
    geometry.name = "ipad-landscape-editor-geometry"
    geometry.lifetime = .keepAlways
    add(geometry)
    attachScreen("ipad-landscape-editor", app: app)
  }

  func testGalleryOpensSystemPhotoPicker() throws {
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    start.tap()
    let gallery = app.buttons["Gallery"]
    XCTAssertTrue(gallery.waitForExistence(timeout: 20), "Source picker actions were not individually accessible.")
    gallery.tap()
    let photos = app.buttons["Photos"]
    XCTAssertTrue(photos.waitForExistence(timeout: 20), "Gallery did not present the system Photos picker.\n\(app.debugDescription)")

    let tree = XCTAttachment(string: app.debugDescription)
    tree.name = "gallery-photo-picker-accessibility-tree"
    tree.lifetime = .keepAlways
    add(tree)
    attachScreen("gallery-photo-picker", app: app)
  }

  func testGalleryPickerCancellationReturnsHomeWithoutSelection() {
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    start.tap()
    let gallery = app.buttons["Gallery"]
    XCTAssertTrue(gallery.waitForExistence(timeout: 20))
    gallery.tap()

    let cancel = app.buttons["Cancel"]
    XCTAssertTrue(cancel.waitForExistence(timeout: 20), "Photos picker did not expose its Cancel action.")
    cancel.tap()
    XCTAssertTrue(start.waitForExistence(timeout: 20), "Cancelling Photos selection should settle the native request and return Home.")
  }

  func testFilesPickerCancellationReturnsHomeWithoutImport() {
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    start.tap()
    let files = app.buttons["File"]
    guard files.waitForExistence(timeout: 20) else {
      XCTFail("Source picker did not expose its File action.\n\(app.debugDescription)")
      return
    }
    files.tap()

    let cancel = app.buttons["Cancel"]
    guard cancel.waitForExistence(timeout: 20) else {
      XCTFail("Document picker did not expose its Cancel action.\n\(app.debugDescription)")
      return
    }
    cancel.tap()
    XCTAssertTrue(start.waitForExistence(timeout: 20), "Cancelling document selection should settle the native request and return Home.")
  }

  func testClipboardAcceptsSanitizedVectorSvgWithoutRasterFallback() {
    let svg = "<svg viewBox='0 0 100 80'><rect x='0' y='0' width='100' height='80' fill='#fff'/><path d='M1 1'/></svg>"
    UIPasteboard.general.items = []
    UIPasteboard.general.string = svg

    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()
    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    start.tap()
    let clipboard = app.buttons["Clipboard"]
    XCTAssertTrue(clipboard.waitForExistence(timeout: 20))
    let pasteMonitor = addUIInterruptionMonitor(withDescription: "iOS clipboard paste permission") { alert in
      let allow = alert.buttons["Allow Paste"]
      guard allow.exists else { return false }
      allow.tap()
      return true
    }
    clipboard.tap()
    // A harmless tap on the app's title area gives XCTest's interruption monitor
    // a UI event to resolve the system paste prompt without activating another action.
    app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.08)).tap()
    removeUIInterruptionMonitor(pasteMonitor)

    XCTAssertTrue(app.staticTexts["Done"].waitForExistence(timeout: 20), "A valid vector SVG should complete without invoking ISNet.")
    XCTAssertTrue(app.buttons["Save"].exists)
    XCTAssertFalse(app.buttons["Edit in app"].exists, "Vector SVG should remain vector and bypass raster editing.")
    let copiedData = UIPasteboard.general.data(forPasteboardType: UTType.svg.identifier)
    let copiedSvg = copiedData.flatMap { String(data: $0, encoding: .utf8) }
    XCTAssertNotNil(copiedSvg)
    XCTAssertFalse(copiedSvg?.contains("<rect") ?? true, "A uniform solid backdrop should be removed before publishing SVG.")
    XCTAssertTrue(copiedSvg?.contains("<path d='M1 1'/>") ?? false)
  }

  func testEmptyClipboardReturnsToHomeWithReadableMessage() {
    UIPasteboard.general.items = []
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30))
    start.tap()
    let clipboard = app.buttons["Clipboard"]
    XCTAssertTrue(clipboard.waitForExistence(timeout: 20))
    clipboard.tap()
    XCTAssertTrue(app.staticTexts["No picture in the clipboard"].waitForExistence(timeout: 20))
    XCTAssertTrue(start.exists)
  }

  func testGalleryInferenceEditorCopyAndPhotosSave() throws {
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let start = app.buttons["Cut here"]
    XCTAssertTrue(start.waitForExistence(timeout: 30), "Home did not finish loading.\n\(app.debugDescription)")
    attachScreen("01-home", app: app)
    start.tap()

    let gallery = app.buttons["Gallery"]
    XCTAssertTrue(gallery.waitForExistence(timeout: 20), "The source picker did not expose its Gallery action.\n\(app.debugDescription)")
    gallery.tap()

    let photoCell = app.cells.firstMatch
    let photoThumbnail = app.images.matching(NSPredicate(format: "label BEGINSWITH[c] 'Photo,'")).firstMatch
    let deadline = Date().addingTimeInterval(45)
    while Date() < deadline && !photoThumbnail.exists && !photoCell.exists {
      RunLoop.current.run(until: Date().addingTimeInterval(0.25))
    }
    if photoThumbnail.exists {
      // Photos exposes thumbnail accessibility but its remote picker view does not mark
      // the Image node as hittable. Tap the center of its visible frame.
      let frame = photoThumbnail.frame
      XCTAssertGreaterThan(frame.width, 0)
      app.coordinate(withNormalizedOffset: CGVector(dx: frame.midX / app.frame.width,
                                                      dy: frame.midY / app.frame.height)).tap()
    } else if photoCell.exists {
      photoCell.tap()
    } else {
      XCTFail("Photos picker showed no selectable image.\n\(app.debugDescription)")
      return
    }
    attachScreen("02-selected-photo", app: app)

    let done = app.staticTexts["Done"]
    XCTAssertTrue(done.waitForExistence(timeout: 240), "Gallery selection did not reach the result screen.\n\(app.debugDescription)")
    attachScreen("03-inference-result", app: app)

    let edit = app.buttons["Edit in app"]
    XCTAssertTrue(edit.waitForExistence(timeout: 20), "The result screen did not offer editing.\n\(app.debugDescription)")
    edit.tap()
    let copy = app.buttons["Copy"]
    XCTAssertTrue(copy.waitForExistence(timeout: 60), "The editor did not become ready.\n\(app.debugDescription)")
    XCTAssertTrue(app.staticTexts["Erase the extra or restore an edge"].exists, "The editor route should remain visible.")
    let save = app.buttons["Save"]
    XCTAssertTrue(copy.isHittable)
    XCTAssertTrue(save.isHittable)
    attachScreen("04-editor", app: app)

    copy.tap()
    XCTAssertTrue(app.staticTexts["Copied"].waitForExistence(timeout: 30), "Edited PNG was not copied.\n\(app.debugDescription)")

    XCTAssertTrue(save.exists)
    save.tap()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let photosPrompt = springboard.alerts.firstMatch
    if photosPrompt.waitForExistence(timeout: 8) {
      // Match the affirmative action only; `CONTAINS allow` also matches “Don’t Allow”.
      let allow = photosPrompt.buttons.matching(NSPredicate(format: "label BEGINSWITH[c] 'Allow'")).firstMatch
      if allow.waitForExistence(timeout: 5) { allow.tap() }
    }
    XCTAssertTrue(app.staticTexts["Saved to gallery"].waitForExistence(timeout: 30), "Edited PNG was not saved to Photos.\n\(app.debugDescription)")
    attachScreen("05-saved", app: app)
  }

  func testFeedbackEmailEntryAndKeyboardDismissalWithoutSending() {
    let app = XCUIApplication()
    app.launchArguments += ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    app.launch()

    let settings = app.buttons["Settings"]
    XCTAssertTrue(settings.waitForExistence(timeout: 30))
    settings.tap()
    let feedback = app.buttons["Send feedback"]
    XCTAssertTrue(feedback.waitForExistence(timeout: 20))
    feedback.tap()

    let email = app.textFields["Reply email"]
    XCTAssertTrue(email.waitForExistence(timeout: 20))
    email.tap()
    let expected = "kirillborichevskiy@gmail.com"
    XCTAssertTrue(app.keyboards.firstMatch.waitForExistence(timeout: 10), "The native email keyboard did not appear after focusing the field.")
    var entered = ""
    for character in expected {
      email.typeText(String(character))
      entered.append(character)
      let actual = email.value as? String
      guard actual == entered else {
        XCTFail("Native email entry diverged at character \(entered.count): expected \(entered.debugDescription), got \(String(describing: actual))")
        return
      }
    }
    let actual = email.value as? String
    XCTAssertEqual(actual, expected, "Native email text changed during entry.")
    guard actual == expected else { return }

    let keyboard = app.keyboards.firstMatch
    XCTAssertTrue(keyboard.waitForExistence(timeout: 10), "The native email keyboard did not appear.")
    let done = keyboard.buttons.matching(NSPredicate(format: "label CONTAINS[c] %@", "done")).firstMatch
    XCTAssertTrue(done.waitForExistence(timeout: 10), "The native email keyboard did not expose its Done key.")
    guard done.isHittable else {
      XCTFail("The native keyboard Done key is not hittable; no feedback request will be issued.")
      return
    }
    done.tap()
    XCTAssertTrue(keyboard.waitForNonExistence(timeout: 5), "Native Done did not dismiss the keyboard.")

    let send = app.buttons["Send feedback"]
    XCTAssertTrue(send.waitForExistence(timeout: 10))
    guard send.isHittable else {
      XCTFail("Send is not hittable after Done dismissed the keyboard. This test never taps Send.")
      return
    }
    attachScreen("feedback-no-send-ready", app: app)
    // Deliberately stop after asserting the native value and reachable control.
  }

  private func attachScreen(_ name: String, app: XCUIApplication) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }

}
