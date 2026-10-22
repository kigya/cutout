import XCTest
import UIKit
import Photos
import PhotosUI
import React
import ObjectiveC
import UniformTypeIdentifiers
@testable import Cutout

final class CutoutNativeTests: XCTestCase {
  private var retainedCallables: [RCTCallableJSModules] = []

  private func makeModule() -> EraseNative {
    let module = EraseNative()
    let callable = RCTCallableJSModules()
    module.callableJSModules = callable
    retainedCallables.append(callable)
    return module
  }

  func testObjectiveCModuleBridgeExportsExpectedSelectors() {
    guard let bridgeClass = NSClassFromString("Erase") as? AnyClass else {
      XCTFail("The Objective-C React Native module name must resolve to the Swift implementation.")
      return
    }
    let exportedSelectors = [
      "getHome:rejecter:", "prepareModel:rejecter:", "setBubble:resolver:rejecter:",
      "hideBubble:rejecter:", "openPermission:resolver:rejecter:", "pickAndCut:resolver:rejecter:",
      "cutClipboard:rejecter:", "cutFixture:rejecter:", "cancelCut:rejecter:",
      "bubbleAction:resolver:rejecter:", "getBubbleState:rejecter:", "readAlpha:resolver:rejecter:",
      "writeMasked:alphaBase64:width:height:resolver:rejecter:", "publishPng:resolver:rejecter:",
      "saveToGallery:resolver:rejecter:", "getLocale:rejecter:", "setLocale:resolver:rejecter:",
      "restart:rejecter:", "consumePendingEdit:rejecter:", "moveBubble:dy:", "settleBubble:rejecter:",
      "setBubbleSize:height:", "getPreferences:rejecter:",
      "acknowledgeDiagnosticsNotice:rejecter:",
      "setPreferences:analytics:crashes:resolver:rejecter:", "logProductEvent:values:resolver:rejecter:"
    ]
    for selector in exportedSelectors {
      XCTAssertTrue(class_respondsToSelector(bridgeClass, NSSelectorFromString(selector)), "Missing native bridge selector: \(selector)")
    }
  }

  func testHomeSnapshotReportsUnsupportedAndroidCapabilities() {
    let module = makeModule()
    let value = promiseValue { resolve, reject in module.getHome(resolve, rejecter: reject) }
    let home = value as? [String: Any]

    XCTAssertEqual(home?["bubbleSupported"] as? Bool, false)
    XCTAssertEqual((home?["rows"] as? [Any])?.count, 0)
    XCTAssertEqual(home?["bubbleEnabled"] as? Bool, false)
    XCTAssertEqual(home?["clipboardReady"] as? Bool, true)
  }

  func testNativeBridgeReportsOnlySupportedEventsAndSafeEmptyOutputs() {
    let module = makeModule()
    XCTAssertEqual(module.supportedEvents(), ["eraseLocale", "eraseProgress", "erasePreview"])

    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-cutout-copy-\(UUID().uuidString).png")
    XCTAssertEqual(promiseValue { resolve, reject in
      module.publishPng(missing.path, resolver: resolve, rejecter: reject)
    } as? String, "failed")

    let pending = promiseValue { resolve, reject in module.consumePendingEdit(resolve, rejecter: reject) }
    XCTAssertTrue(pending is NSNull)
  }

  func testMissingBundledModelRejectsPreparePromise() {
    let module = makeModule()
    module.modelURLProvider = { nil }
    let finished = expectation(description: "model preparation rejects when the bundle resource is absent")
    var rejectedCode: String?
    var rejectedMessage: String?
    module.prepareModel({ _ in
      XCTFail("Model preparation must not succeed when the bundled model is absent.")
      finished.fulfill()
    }, rejecter: { code, message, _ in
      rejectedCode = code
      rejectedMessage = message
      finished.fulfill()
    })

    wait(for: [finished], timeout: 10)
    XCTAssertEqual(rejectedCode, "erase")
    XCTAssertEqual(rejectedMessage, "model_failed")
  }

  func testFixtureProcessingReportsModelFailureWhenBundleResourceIsMissing() {
    let module = makeModule()
    module.modelURLProvider = { nil }
    let result = promiseValue(timeout: 60) { resolve, reject in
      module.cutFixture(resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(result?["status"] as? String, "failed")
    XCTAssertEqual(result?["message"] as? String, "model_failed")
  }

  func testUnsupportedBubbleBridgeMethodsStaySafeNoops() {
    let module = makeModule()
    XCTAssertNil(promiseValue { resolve, reject in
      module.setBubble(true, resolver: resolve, rejecter: reject)
    })
    XCTAssertNil(promiseValue { resolve, reject in module.hideBubble(resolve, rejecter: reject) })
    XCTAssertNil(promiseValue { resolve, reject in
      module.openPermission("overlay", resolver: resolve, rejecter: reject)
    })

    let state = promiseValue { resolve, reject in module.getBubbleState(resolve, rejecter: reject) } as? [String: Any]
    XCTAssertEqual(state?["expanded"] as? Bool, false)
    XCTAssertEqual(state?["phase"] as? String, "idle")
    XCTAssertEqual(state?["clipboardReady"] as? Bool, true)

    let actionState = promiseValue { resolve, reject in
      module.bubbleAction("close", resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(actionState?["expanded"] as? Bool, false)
    XCTAssertEqual(promiseValue { resolve, reject in module.settleBubble(resolve, rejecter: reject) } as? Bool, false)
    module.moveBubble(0, dy: 0)
    module.setBubbleSize(120, height: 120)
  }

  func testPhotosPermissionOpensAppSettingsAndUnsupportedKindsRemainNoops() {
    let module = makeModule()
    var openedURL: URL?
    module.appSettingsOpener = { url, completion in
      openedURL = url
      completion(true)
    }

    XCTAssertNil(promiseValue { resolve, reject in
      module.openPermission("photos", resolver: resolve, rejecter: reject)
    })
    XCTAssertEqual(openedURL, URL(string: UIApplication.openSettingsURLString))

    var unsupportedOpenCount = 0
    module.appSettingsOpener = { _, completion in
      unsupportedOpenCount += 1
      completion(true)
    }
    XCTAssertNil(promiseValue { resolve, reject in
      module.openPermission("overlay", resolver: resolve, rejecter: reject)
    })
    XCTAssertEqual(unsupportedOpenCount, 0)
  }

  func testPhotosSettingsFailureRejectsWithContextualError() {
    let module = makeModule()
    let settled = expectation(description: "failed settings launch rejects")
    var rejectedCode: String?
    var rejectedMessage: String?
    module.appSettingsOpener = { _, completion in completion(false) }

    module.openPermission("photos", resolver: { _ in
      XCTFail("The Photos settings request should reject when Settings cannot open.")
      settled.fulfill()
    }, rejecter: { code, message, _ in
      rejectedCode = code
      rejectedMessage = message
      settled.fulfill()
    })

    wait(for: [settled], timeout: 5)
    XCTAssertEqual(rejectedCode, "erase")
    XCTAssertEqual(rejectedMessage, "settings_unavailable")
  }

  func testDiagnosticsBaselineIsAppliedOnceAndNoticeAcknowledgementIsIndependent() {
    let defaults = UserDefaults.standard
    let keys = [
      "erase.analytics", "erase.crashes", DiagnosticsConsentBaseline.baselineVersionKey,
      DiagnosticsConsentBaseline.noticeAcknowledgedKey
    ]
    let saved = Dictionary(uniqueKeysWithValues: keys.map { ($0, defaults.object(forKey: $0)) })
    defer { for (key, value) in saved { restore(value, key: key, defaults: defaults) } }

    defaults.set(false, forKey: "erase.analytics")
    defaults.set(false, forKey: "erase.crashes")
    defaults.set(0, forKey: DiagnosticsConsentBaseline.baselineVersionKey)
    defaults.set(false, forKey: DiagnosticsConsentBaseline.noticeAcknowledgedKey)

    XCTAssertTrue(DiagnosticsConsentBaseline.applyIfNeeded(defaults: defaults))
    XCTAssertTrue(defaults.bool(forKey: "erase.analytics"))
    XCTAssertTrue(defaults.bool(forKey: "erase.crashes"))
    XCTAssertEqual(defaults.integer(forKey: DiagnosticsConsentBaseline.baselineVersionKey), DiagnosticsConsentBaseline.version)

    let module = makeModule()
    let initial = promiseValue { resolve, reject in module.getPreferences(resolve, rejecter: reject) } as? [String: Any]
    XCTAssertEqual(initial?["analytics"] as? Bool, true)
    XCTAssertEqual(initial?["crashes"] as? Bool, true)
    XCTAssertEqual(initial?["diagnosticsNoticePending"] as? Bool, true)

    // A later explicit opt-out survives startup migrations and closing the notice.
    defaults.set(false, forKey: "erase.analytics")
    defaults.set(false, forKey: "erase.crashes")
    XCTAssertFalse(DiagnosticsConsentBaseline.applyIfNeeded(defaults: defaults))
    XCTAssertFalse(defaults.bool(forKey: "erase.analytics"))
    XCTAssertFalse(defaults.bool(forKey: "erase.crashes"))
    XCTAssertNil(promiseValue { resolve, reject in module.acknowledgeDiagnosticsNotice(resolve, rejecter: reject) })

    let acknowledged = promiseValue { resolve, reject in module.getPreferences(resolve, rejecter: reject) } as? [String: Any]
    XCTAssertEqual(acknowledged?["analytics"] as? Bool, false)
    XCTAssertEqual(acknowledged?["crashes"] as? Bool, false)
    XCTAssertEqual(acknowledged?["diagnosticsNoticePending"] as? Bool, false)
  }

  func testPreferencesNormalizeThemeAndPersistConsentBooleans() {
    let defaults = UserDefaults.standard
    let oldTheme = defaults.object(forKey: "erase.theme")
    let oldAnalytics = defaults.object(forKey: "erase.analytics")
    let oldCrashes = defaults.object(forKey: "erase.crashes")
    defer {
      restore(oldTheme, key: "erase.theme", defaults: defaults)
      restore(oldAnalytics, key: "erase.analytics", defaults: defaults)
      restore(oldCrashes, key: "erase.crashes", defaults: defaults)
    }

    let module = makeModule()
    let setResult = promiseValue { resolve, reject in
      module.setPreferences("sepia", analytics: false, crashes: true, resolver: resolve, rejecter: reject)
    }
    XCTAssertNil(setResult)

    let value = promiseValue { resolve, reject in module.getPreferences(resolve, rejecter: reject) }
    let preferences = value as? [String: Any]
    XCTAssertEqual(preferences?["theme"] as? String, "system")
    XCTAssertEqual(preferences?["analytics"] as? Bool, false)
    XCTAssertEqual(preferences?["crashes"] as? Bool, true)
  }

  func testLocaleOverrideRoundTripsAndSystemModeClearsOverride() {
    let defaults = UserDefaults.standard
    let oldLocale = defaults.object(forKey: "erase.locale")
    let oldAnalytics = defaults.object(forKey: "erase.analytics")
    defaults.set(false, forKey: "erase.analytics")
    defer {
      restore(oldLocale, key: "erase.locale", defaults: defaults)
      restore(oldAnalytics, key: "erase.analytics", defaults: defaults)
    }

    let module = makeModule()
    _ = promiseValue { resolve, reject in module.setLocale("fr", resolver: resolve, rejecter: reject) }
    let selected = promiseValue { resolve, reject in module.getLocale(resolve, rejecter: reject) } as? [String: Any]
    XCTAssertEqual(selected?["selected"] as? String, "fr")

    _ = promiseValue { resolve, reject in module.setLocale("system", resolver: resolve, rejecter: reject) }
    let system = promiseValue { resolve, reject in module.getLocale(resolve, rejecter: reject) } as? [String: Any]
    XCTAssertEqual(system?["selected"] as? String, "")
    XCTAssertNotNil(system?["system"] as? String)
  }

  func testInvalidMaskedImageAndMissingAlphaInputFailClosed() {
    let module = makeModule()
    let masked = promiseValue { resolve, reject in
      module.writeMasked("/missing/image.png", alphaBase64: "not-base64", width: 0, height: 2,
                         resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(masked?["status"] as? String, "failed")
    XCTAssertEqual(masked?["message"] as? String, "alpha")

    let alpha = promiseValue { resolve, reject in
      module.readAlpha("/missing/image.png", resolver: resolve, rejecter: reject)
    } as? String
    XCTAssertEqual(alpha, "")

    let copied = promiseValue { resolve, reject in
      module.publishPng("/missing/image.png", resolver: resolve, rejecter: reject)
    } as? String
    XCTAssertEqual(copied, "failed")
  }

  func testWriteMaskedPreservesSourcePixelsAndAppliesAlphaPlane() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let source = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format).image { context in
      UIColor(red: 0.8, green: 0.2, blue: 0.1, alpha: 1).setFill()
      context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
    let input = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-mask-input-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: input) }
    try XCTUnwrap(source.pngData()).write(to: input)

    let module = makeModule()
    let mask = Data([255, 0, 0, 0]).base64EncodedString()
    let value = promiseValue { resolve, reject in
      module.writeMasked(input.path, alphaBase64: mask, width: 2, height: 2,
                         resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(value?["status"] as? String, "ready")
    let output = try XCTUnwrap(value?["path"] as? String)
    defer { try? FileManager.default.removeItem(atPath: output) }

    let alphaText = promiseValue { resolve, reject in
      module.readAlpha(output, resolver: resolve, rejecter: reject)
    } as? String
    let alpha = try XCTUnwrap(Data(base64Encoded: alphaText ?? ""))
    XCTAssertEqual(Array(alpha.prefix(4)), [255, 0, 0, 0])
  }

  func testWriteMaskedRejectsShortAlphaPlaneAndReportsEmptyMask() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let source = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format).image { context in
      UIColor.white.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
    let input = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-mask-edge-input-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: input) }
    try XCTUnwrap(source.pngData()).write(to: input)

    let module = makeModule()
    let shortPlane = promiseValue { resolve, reject in
      module.writeMasked(input.path, alphaBase64: Data([255, 0, 0]).base64EncodedString(), width: 2, height: 2,
                         resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(shortPlane?["status"] as? String, "failed")
    XCTAssertEqual(shortPlane?["message"] as? String, "alpha")

    let emptyMask = promiseValue { resolve, reject in
      module.writeMasked(input.path, alphaBase64: Data(repeating: 0, count: 4).base64EncodedString(), width: 2, height: 2,
                         resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(emptyMask?["status"] as? String, "empty")
    let output = try XCTUnwrap(emptyMask?["path"] as? String)
    defer { try? FileManager.default.removeItem(atPath: output) }
    let alphaText = promiseValue { resolve, reject in module.readAlpha(output, resolver: resolve, rejecter: reject) } as? String
    XCTAssertEqual(Data(base64Encoded: alphaText ?? ""), Data(repeating: 0, count: 4))
  }

  func testImageReadersRejectMissingFilesAndInvalidDimensions() {
    let module = makeModule()
    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-cutout-alpha-\(UUID().uuidString).png")
    XCTAssertEqual(promiseValue { resolve, reject in
      module.readAlpha(missing.path, resolver: resolve, rejecter: reject)
    } as? String, "")

    for (width, height) in [(0, 1), (1, 0), (2049, 1)] {
      let result = promiseValue { resolve, reject in
        module.writeMasked(missing.path, alphaBase64: "", width: NSNumber(value: width), height: NSNumber(value: height),
                           resolver: resolve, rejecter: reject)
      } as? [String: Any]
      XCTAssertEqual(result?["status"] as? String, "failed")
      XCTAssertEqual(result?["message"] as? String, "alpha")
    }
  }

  func testDownsampledImageDecodesValidPngAndRejectsInvalidBytes() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let image = UIGraphicsImageRenderer(size: CGSize(width: 80, height: 40), format: format).image { context in
      UIColor.systemOrange.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 80, height: 40))
    }
    let data = try XCTUnwrap(image.pngData())
    let decoded = try XCTUnwrap(makeModule().downsampledImage(data)?.cgImage)
    XCTAssertEqual(decoded.width, 80)
    XCTAssertEqual(decoded.height, 40)
    XCTAssertNil(makeModule().downsampledImage(Data([0x00, 0xFF, 0x12])))
    XCTAssertNil(makeModule().downsampledImage(Data()))
  }

  func testAlphaCropReturnsTightBoundsAndRejectsEmptyImage() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = false
    let image = UIGraphicsImageRenderer(size: CGSize(width: 8, height: 6), format: format).image { context in
      UIColor.red.setFill()
      context.fill(CGRect(x: 3, y: 2, width: 2, height: 3))
    }
    let crop = try XCTUnwrap(makeModule().cropAlpha(image)?.cgImage)
    XCTAssertEqual(crop.width, 2)
    XCTAssertEqual(crop.height, 3)

    let empty = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4), format: format).image { _ in }
    XCTAssertNil(makeModule().cropAlpha(empty))
  }

  func testSaveToGalleryResolvesDeniedRequestAndSuccessfulSavePaths() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format).image { context in
      UIColor.systemGreen.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
    let file = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-gallery-test-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: file) }
    try XCTUnwrap(image.pngData()).write(to: file)

    let denied = makeModule()
    denied.photoAuthorizationStatusProvider = { .denied }
    XCTAssertEqual(promiseValue { resolve, reject in denied.saveToGallery(file.path, resolver: resolve, rejecter: reject) } as? String, "denied")

    let requestDenied = makeModule()
    requestDenied.photoAuthorizationStatusProvider = { .notDetermined }
    requestDenied.photoAuthorizationRequester = { completion in completion(.denied) }
    XCTAssertEqual(promiseValue { resolve, reject in requestDenied.saveToGallery(file.path, resolver: resolve, rejecter: reject) } as? String, "denied")

    let requestGranted = makeModule()
    requestGranted.photoAuthorizationStatusProvider = { .notDetermined }
    requestGranted.photoAuthorizationRequester = { completion in completion(.authorized) }
    requestGranted.photoAssetSaver = { _, completion in completion(true) }
    XCTAssertEqual(promiseValue { resolve, reject in requestGranted.saveToGallery(file.path, resolver: resolve, rejecter: reject) } as? String, "saved")

    let saveFailed = makeModule()
    saveFailed.photoAuthorizationStatusProvider = { .limited }
    saveFailed.photoAssetSaver = { _, completion in completion(false) }
    XCTAssertEqual(promiseValue { resolve, reject in saveFailed.saveToGallery(file.path, resolver: resolve, rejecter: reject) } as? String, "failed")

    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-gallery-\(UUID().uuidString).png")
    let invalidImage = makeModule()
    invalidImage.photoAuthorizationStatusProvider = { .authorized }
    XCTAssertEqual(promiseValue { resolve, reject in invalidImage.saveToGallery(missing.path, resolver: resolve, rejecter: reject) } as? String, "failed")
  }

  func testDocumentPickerRejectsOversizeAndInvalidUtf8Svg() throws {
    let module = makeModule()
    let oversized = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-oversize-\(UUID().uuidString).png")
    FileManager.default.createFile(atPath: oversized.path, contents: Data())
    let handle = try FileHandle(forWritingTo: oversized)
    try handle.truncate(atOffset: 32 * 1024 * 1024 + 1)
    try handle.close()
    defer { try? FileManager.default.removeItem(at: oversized) }

    let oversizedResult = try documentPickerResult(module: module, url: oversized)
    XCTAssertEqual(oversizedResult?["message"] as? String, "read_failed")

    let invalidSvg = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-invalid-\(UUID().uuidString).svg")
    try Data([0xFF, 0xFE, 0xFA]).write(to: invalidSvg)
    defer { try? FileManager.default.removeItem(at: invalidSvg) }
    let invalidSvgResult = try documentPickerResult(module: module, url: invalidSvg)
    XCTAssertEqual(invalidSvgResult?["message"] as? String, "read_failed")

    let invalidPng = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-invalid-\(UUID().uuidString).png")
    try Data([0x00, 0x01, 0xFE]).write(to: invalidPng)
    defer { try? FileManager.default.removeItem(at: invalidPng) }
    let invalidImageResult = try documentPickerResult(module: module, url: invalidPng)
    XCTAssertEqual(invalidImageResult?["message"] as? String, "read_failed")

    let oldItems = UIPasteboard.general.items
    defer { UIPasteboard.general.items = oldItems }
    let safeSvg = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-vector-\(UUID().uuidString).svg")
    let svg = "<svg viewBox='0 0 100 80'><rect width='100' height='80' fill='#fff'/><path d='M0 0'/></svg>"
    try Data(svg.utf8).write(to: safeSvg)
    defer { try? FileManager.default.removeItem(at: safeSvg) }
    let vectorResult = try documentPickerResult(module: module, url: safeSvg)
    XCTAssertEqual(vectorResult?["status"] as? String, "svg")
    XCTAssertEqual(vectorResult?["message"] as? String, "copied")
  }

  func testPhotoPickerWithoutSelectionSettlesAsCancelled() throws {
    let module = makeModule()
    let settled = expectation(description: "empty Photos selection settles request")
    var value: [String: Any]?
    let requestID = try XCTUnwrap(module.reservePickerRequest { result in
      value = result as? [String: Any]
      settled.fulfill()
    })
    var configuration = PHPickerConfiguration(photoLibrary: .shared())
    configuration.filter = .images
    let picker = PHPickerViewController(configuration: configuration)
    XCTAssertTrue(module.associatePickerController(picker, requestID: requestID))

    module.picker(picker, didFinishPicking: [])
    wait(for: [settled], timeout: 5)
    XCTAssertEqual(value?["status"] as? String, "failed")
    XCTAssertEqual(value?["message"] as? String, "cancelled")
  }

  func testPhotoPickerProviderErrorSettlesAsReadFailure() throws {
    let module = makeModule()
    let settled = expectation(description: "Photos provider error settles request")
    var value: [String: Any]?
    let requestID = try XCTUnwrap(module.reservePickerRequest { result in
      value = result as? [String: Any]
      settled.fulfill()
    })
    module.armPickerLoadTimeout(requestID, delay: 10)
    module.finishPhotoProviderLoad(nil, error: NSError(domain: "CutoutTests", code: 1), requestID: requestID)
    wait(for: [settled], timeout: 10)
    XCTAssertEqual(value?["status"] as? String, "failed")
    XCTAssertEqual(value?["message"] as? String, "read_failed")

    let malformedSettled = expectation(description: "invalid Photos image bytes settle as read failure")
    var malformedValue: [String: Any]?
    let malformedID = try XCTUnwrap(module.reservePickerRequest { result in
      malformedValue = result as? [String: Any]
      malformedSettled.fulfill()
    })
    module.armPickerLoadTimeout(malformedID, delay: 10)
    module.finishPhotoProviderLoad(Data([0x00, 0x10, 0xFF]), error: nil, requestID: malformedID)
    wait(for: [malformedSettled], timeout: 10)
    XCTAssertEqual(malformedValue?["status"] as? String, "failed")
    XCTAssertEqual(malformedValue?["message"] as? String, "read_failed")
  }

  func testCancellationWinsBeforeFixturePublishesResult() {
    let module = makeModule()
    let fixtureFinished = expectation(description: "fixture reports cancellation")
    var fixtureResult: [String: Any]?
    module.cutFixture({ value in
      fixtureResult = value as? [String: Any]
      fixtureFinished.fulfill()
    }, rejecter: { _, message, _ in
      fixtureResult = ["status": "rejected", "message": message ?? "rejected"]
      fixtureFinished.fulfill()
    })

    let cancelled = promiseValue { resolve, reject in module.cancelCut(resolve, rejecter: reject) }
    XCTAssertNil(cancelled)
    wait(for: [fixtureFinished], timeout: 300)
    XCTAssertEqual(fixtureResult?["status"] as? String, "failed")
    XCTAssertEqual(fixtureResult?["message"] as? String, "cancelled")
  }

  func testConcurrentPickerRequestReturnsBusyAndCancellationSettlesOriginal() {
    let module = makeModule()
    let firstFinished = expectation(description: "first picker request settles")
    var firstValue: [String: Any]?
    module.pickAndCut("file", resolver: { value in
      firstValue = value as? [String: Any]
      firstFinished.fulfill()
    }, rejecter: { _, message, _ in
      firstValue = ["status": "rejected", "message": message ?? "rejected"]
      firstFinished.fulfill()
    })

    let second = promiseValue { resolve, reject in
      module.pickAndCut("gallery", resolver: resolve, rejecter: reject)
    } as? [String: Any]
    XCTAssertEqual(second?["status"] as? String, "failed")
    XCTAssertEqual(second?["message"] as? String, "busy")

    XCTAssertNil(promiseValue { resolve, reject in module.cancelCut(resolve, rejecter: reject) })
    wait(for: [firstFinished], timeout: 10)
    XCTAssertEqual(firstValue?["status"] as? String, "failed")
    XCTAssertEqual(firstValue?["message"] as? String, "cancelled")
  }

  func testPickerProviderTimeoutSettlesOnlyMatchingPendingRequest() throws {
    let module = makeModule()
    let timedOut = expectation(description: "stalled provider request resolves with read failure")
    var result: [String: Any]?
    let requestID = module.reservePickerRequest { value in
      result = value as? [String: Any]
      timedOut.fulfill()
    }
    let request = try XCTUnwrap(requestID)

    module.armPickerLoadTimeout(UUID(), delay: 0.01)
    module.armPickerLoadTimeout(request, delay: 0.02)
    wait(for: [timedOut], timeout: 5)
    XCTAssertEqual(result?["status"] as? String, "failed")
    XCTAssertEqual(result?["message"] as? String, "read_failed")
  }

  func testLateOldPickerCancellationCannotClaimReplacementRequest() throws {
    let module = makeModule()
    let oldSettled = expectation(description: "cancel settles old picker request")
    var oldResult: [String: Any]?
    let oldRequest = try XCTUnwrap(module.reservePickerRequest { value in
      oldResult = value as? [String: Any]
      oldSettled.fulfill()
    })
    let oldPicker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image], asCopy: true)
    XCTAssertTrue(module.associatePickerController(oldPicker, requestID: oldRequest))

    _ = promiseValue { resolve, reject in module.cancelCut(resolve, rejecter: reject) }
    wait(for: [oldSettled], timeout: 5)
    XCTAssertEqual(oldResult?["message"] as? String, "cancelled")

    let replacementSettled = expectation(description: "replacement remains pending until its own delegate callback")
    var replacementResult: [String: Any]?
    let replacementID = try XCTUnwrap(module.reservePickerRequest { value in
      replacementResult = value as? [String: Any]
      replacementSettled.fulfill()
    })
    let replacementPicker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image], asCopy: true)
    XCTAssertTrue(module.associatePickerController(replacementPicker, requestID: replacementID))

    module.documentPickerWasCancelled(oldPicker)
    XCTAssertNil(replacementResult)
    module.documentPickerWasCancelled(replacementPicker)
    wait(for: [replacementSettled], timeout: 5)
    XCTAssertEqual(replacementResult?["message"] as? String, "cancelled")
  }

  func testDocumentPickerRejectsMissingFileWithoutStartingInference() throws {
    let module = makeModule()
    let emptySettled = expectation(description: "empty document selection resolves read failure")
    var emptyResult: [String: Any]?
    let emptyRequest = try XCTUnwrap(module.reservePickerRequest { value in
      emptyResult = value as? [String: Any]
      emptySettled.fulfill()
    })
    let emptyPicker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image], asCopy: true)
    XCTAssertTrue(module.associatePickerController(emptyPicker, requestID: emptyRequest))
    module.documentPicker(emptyPicker, didPickDocumentsAt: [])
    wait(for: [emptySettled], timeout: 5)
    XCTAssertEqual(emptyResult?["message"] as? String, "read_failed")

    let settled = expectation(description: "missing document resolves read failure")
    var result: [String: Any]?
    let request = try XCTUnwrap(module.reservePickerRequest { value in
      result = value as? [String: Any]
      settled.fulfill()
    })
    let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image], asCopy: true)
    XCTAssertTrue(module.associatePickerController(picker, requestID: request))
    let missing = FileManager.default.temporaryDirectory.appendingPathComponent("missing-cutout-\(UUID().uuidString).png")

    module.documentPicker(picker, didPickDocumentsAt: [missing])
    wait(for: [settled], timeout: 5)
    XCTAssertEqual(result?["status"] as? String, "failed")
    XCTAssertEqual(result?["message"] as? String, "read_failed")
  }

  func testFullyTransparentPngExportReturnsEmptyWithoutPublishing() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = false
    let transparent = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format)
      .image { _ in }
    let input = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-transparent-export-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: input) }
    try XCTUnwrap(transparent.pngData()).write(to: input)

    let result = promiseValue { resolve, reject in
      makeModule().publishPng(input.path, resolver: resolve, rejecter: reject)
    } as? String
    XCTAssertEqual(result, "empty")
  }

  func testPngExportPublishesOpaqueImageAndReturnsCopied() throws {
    let oldItems = UIPasteboard.general.items
    defer { UIPasteboard.general.items = oldItems }

    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2), format: format).image { context in
      UIColor.systemBlue.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
    }
    let input = FileManager.default.temporaryDirectory.appendingPathComponent("cutout-copy-export-\(UUID().uuidString).png")
    defer { try? FileManager.default.removeItem(at: input) }
    try XCTUnwrap(image.pngData()).write(to: input)

    let result = promiseValue { resolve, reject in
      makeModule().publishPng(input.path, resolver: resolve, rejecter: reject)
    } as? String
    XCTAssertEqual(result, "copied")
  }

  func testSvgImportRejectsMalformedInputAndPublishesSafeSolidBackdropAsVector() {
    let module = makeModule()
    let malformed = promiseValue { resolve, reject in
      module.processSVG("<html>no svg</html>", resolve: resolve, reject: reject)
    } as? [String: Any]
    XCTAssertEqual(malformed?["status"] as? String, "failed")
    XCTAssertEqual(malformed?["message"] as? String, "read_failed")

    let oldItems = UIPasteboard.general.items
    defer { UIPasteboard.general.items = oldItems }
    let svg = "<svg viewBox='0 0 100 80'><rect width='100' height='80' fill='#fff'/><path d='M0 0'/></svg>"
    let result = promiseValue { resolve, reject in
      module.processSVG(svg, resolve: resolve, reject: reject)
    } as? [String: Any]
    XCTAssertEqual(result?["status"] as? String, "svg")
    XCTAssertEqual(result?["message"] as? String, "copied")

    let tooLarge = "<svg>" + String(repeating: " ", count: 32 * 1024 * 1024) + "</svg>"
    let oversized = promiseValue { resolve, reject in
      module.processSVG(tooLarge, resolve: resolve, reject: reject)
    } as? [String: Any]
    XCTAssertEqual(oversized?["status"] as? String, "failed")
    XCTAssertEqual(oversized?["message"] as? String, "read_failed")
  }

  func testNormalizationAppliesExifOrientationAndCapsLongestSideAt2048() throws {
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = true
    let landscape = UIGraphicsImageRenderer(size: CGSize(width: 2200, height: 1200), format: format).image { context in
      UIColor.systemRed.setFill()
      context.fill(CGRect(x: 0, y: 0, width: 1100, height: 1200))
      UIColor.systemGreen.setFill()
      context.fill(CGRect(x: 1100, y: 0, width: 1100, height: 1200))
    }
    let rotated = UIImage(cgImage: try XCTUnwrap(landscape.cgImage), scale: 1, orientation: .right)
    let normalized = makeModule().normalized(rotated)
    let output = try XCTUnwrap(normalized.cgImage)

    XCTAssertEqual(normalized.imageOrientation, .up)
    XCTAssertLessThanOrEqual(max(output.width, output.height), 2048)
    XCTAssertEqual(output.width, 1117)
    XCTAssertEqual(output.height, 2048)
    let empty = UIImage()
    XCTAssertTrue(makeModule().normalized(empty) === empty)
  }

  func testBundledFixtureRunsLocalModelAndProducesTransparentPng() throws {
    let module = makeModule()
    let prepared = promiseValue(timeout: 300) { resolve, reject in
      module.prepareModel(resolve, rejecter: reject)
    } as? String
    XCTAssertEqual(prepared, "ready")
    let result = promiseValue(timeout: 300) { resolve, reject in module.cutFixture(resolve, rejecter: reject) }
    let response = try XCTUnwrap(result as? [String: Any])
    XCTAssertEqual(response["status"] as? String, "ready", "The fixture must complete ISNet inference; manual fallback is not an inference pass.")

    let previewPath = try XCTUnwrap(response["previewPath"] as? String)
    let cutoutPath = try XCTUnwrap(response["cutoutPath"] as? String)
    XCTAssertTrue(FileManager.default.fileExists(atPath: previewPath))
    XCTAssertTrue(FileManager.default.fileExists(atPath: cutoutPath))

    let image = try XCTUnwrap(UIImage(contentsOfFile: previewPath)?.cgImage)
    XCTAssertGreaterThan(image.width, 0)
    XCTAssertLessThanOrEqual(image.width, 2048)
    XCTAssertGreaterThan(image.height, 0)
    XCTAssertLessThanOrEqual(image.height, 2048)

    var pixels = [UInt8](repeating: 0, count: image.width * image.height * 4)
    let context = try XCTUnwrap(CGContext(data: &pixels, width: image.width, height: image.height,
                                          bitsPerComponent: 8, bytesPerRow: image.width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    let alpha = stride(from: 3, to: pixels.count, by: 4).map { pixels[$0] }
    XCTAssertTrue(alpha.contains(0), "Expected at least one transparent output pixel.")
    XCTAssertTrue(alpha.contains(where: { $0 > 0 }), "Expected a non-empty foreground mask.")
  }

  func testReviewPromptPolicyUsesExportThresholdAndSafeDefaults() {
    XCTAssertFalse(ReviewPromptPolicy.isEligible(exports: 4, now: 1_000, lastAttempt: 0,
                                                   minimumExports: 5, cooldownDays: 90))
    XCTAssertTrue(ReviewPromptPolicy.isEligible(exports: 5, now: 1_000, lastAttempt: 0,
                                                  minimumExports: 5, cooldownDays: 90))
    // Out-of-range Remote Config values fall back to the shipped 5-export threshold.
    XCTAssertFalse(ReviewPromptPolicy.isEligible(exports: 4, now: 1_000, lastAttempt: 0,
                                                   minimumExports: 0, cooldownDays: 2))
    XCTAssertTrue(ReviewPromptPolicy.isEligible(exports: 5, now: 1_000, lastAttempt: 0,
                                                  minimumExports: 0, cooldownDays: 2))
  }

  func testReviewPromptPolicyHonorsCooldownBoundaryAndTimeDirection() {
    let ninetyDays: TimeInterval = 90 * 86_400
    XCTAssertFalse(ReviewPromptPolicy.isEligible(exports: 5, now: ninetyDays, lastAttempt: 1,
                                                   minimumExports: 5, cooldownDays: 90))
    XCTAssertTrue(ReviewPromptPolicy.isEligible(exports: 5, now: ninetyDays + 1, lastAttempt: 1,
                                                  minimumExports: 5, cooldownDays: 90))
    XCTAssertFalse(ReviewPromptPolicy.isEligible(exports: 6, now: ninetyDays - 1, lastAttempt: 1,
                                                   minimumExports: 5, cooldownDays: 90))
    XCTAssertTrue(ReviewPromptPolicy.isEligible(exports: 6, now: 90 * ninetyDays + 1,
                                                  lastAttempt: 1, minimumExports: 5, cooldownDays: 90))
    XCTAssertFalse(ReviewPromptPolicy.isEligible(exports: 5, now: 100, lastAttempt: 101,
                                                   minimumExports: 5, cooldownDays: 90))
  }

  func testAddOnlyPhotoAuthorizationPolicyCoversRequestSaveAndDenial() {
    XCTAssertEqual(PhotoAddAuthorizationPolicy.disposition(for: .notDetermined), .request)
    XCTAssertEqual(PhotoAddAuthorizationPolicy.disposition(for: .authorized), .save)
    XCTAssertEqual(PhotoAddAuthorizationPolicy.disposition(for: .limited), .save)
    XCTAssertEqual(PhotoAddAuthorizationPolicy.disposition(for: .denied), .deny)
    XCTAssertEqual(PhotoAddAuthorizationPolicy.disposition(for: .restricted), .deny)

    var saveCalls = 0
    var requestCalls = 0
    var denialCalls = 0
    let save = { saveCalls += 1 }
    let request = { requestCalls += 1 }
    let deny = { denialCalls += 1 }
    for status in [PHAuthorizationStatus.authorized, .limited] {
      PhotoAddAuthorizationPolicy.perform(for: status, save: save, request: request, deny: deny)
    }
    PhotoAddAuthorizationPolicy.perform(for: .notDetermined, save: save, request: request, deny: deny)
    for status in [PHAuthorizationStatus.denied, .restricted] {
      PhotoAddAuthorizationPolicy.perform(for: status, save: save, request: request, deny: deny)
    }
    XCTAssertEqual(saveCalls, 2)
    XCTAssertEqual(requestCalls, 1)
    XCTAssertEqual(denialCalls, 2)
  }

  func testProductTelemetryAllowsOnlyApprovedEventsAndSanitizedFields() throws {
    XCTAssertNil(ProductTelemetryPolicy.sanitizedParameters(name: "feedback_sent", values: [:]))

    let values: NSDictionary = [
      "screen_name": "settings",
      "source": "gallery",
      "format": "png",
      "duration_ms": NSNumber(value: 250),
      "enabled": true,
      "path": "/private/photo.png",
      "email": "person@example.com",
      "stage": "two words",
      "locale": String(repeating: "x", count: 41),
      "unapproved": "value",
      "large_number": NSNumber(value: Double.infinity),
    ]
    let parameters = try XCTUnwrap(ProductTelemetryPolicy.sanitizedParameters(name: "export_completed", values: values))
    XCTAssertEqual(Set(parameters.keys), Set(["screen_name", "source", "format", "duration_ms", "enabled"]))
    XCTAssertEqual(parameters["screen_name"] as? String, "settings")
    XCTAssertEqual(parameters["source"] as? String, "gallery")
    XCTAssertEqual(parameters["format"] as? String, "png")
    XCTAssertEqual((parameters["duration_ms"] as? NSNumber)?.intValue, 250)
    XCTAssertEqual(parameters["enabled"] as? Int, 1)
    XCTAssertNil(parameters["path"])
    XCTAssertNil(parameters["email"])
  }

  func testProductTelemetryBridgeResolvesWithoutCollectionWhenConsentIsOff() {
    let defaults = UserDefaults.standard
    let oldAnalytics = defaults.object(forKey: "erase.analytics")
    defaults.set(false, forKey: "erase.analytics")
    defer { restore(oldAnalytics, key: "erase.analytics", defaults: defaults) }

    let module = makeModule()
    let result = promiseValue { resolve, reject in
      module.logProductEvent("export_completed", values: ["source": "gallery", "path": "/private/file.png"],
                             resolver: resolve, rejecter: reject)
    }
    XCTAssertNil(result)
  }

  func testSolidSvgBackdropStrippingPreservesVectorContentAndRejectsUnsafePatterns() throws {
    let module = makeModule()
    let valid = "<svg viewBox='0 0 100 80'><rect x='0' y='0' width='100' height='80' fill='#fff'/><path d='M0 0'/></svg>"
    let stripped = try XCTUnwrap(module.stripSolidBackdrop(valid))
    XCTAssertFalse(stripped.contains("<rect"))
    XCTAssertTrue(stripped.contains("<path d='M0 0'/"))

    XCTAssertNil(module.stripSolidBackdrop("not svg"))
    XCTAssertNil(module.stripSolidBackdrop("<svg><rect width='100' height='80' fill='#fff'/></svg>"))
    XCTAssertNil(module.stripSolidBackdrop("<svg viewBox='0 0 100 80'><rect width='90' height='80' fill='#fff'/></svg>"))
    XCTAssertNil(module.stripSolidBackdrop("<svg viewBox='0 0 100 80'><rect width='100' height='80' fill='none'/></svg>"))
    XCTAssertNil(module.stripSolidBackdrop("<svg viewBox='0 0 100 80'><rect width='100' height='80' fill='url(#bg)'/></svg>"))
  }

  private func promiseValue(
    timeout: TimeInterval = 20,
    start: (@escaping RCTPromiseResolveBlock, @escaping RCTPromiseRejectBlock) -> Void,
    file: StaticString = #filePath,
    line: UInt = #line
  ) -> Any? {
    let expectation = expectation(description: "native promise settles")
    var result: Any?
    var rejection: String?
    start({ value in
      result = value
      expectation.fulfill()
    }, { _, message, error in
      rejection = message ?? error?.localizedDescription ?? "rejected"
      expectation.fulfill()
    })
    wait(for: [expectation], timeout: timeout)
    if let rejection {
      XCTFail("Native promise rejected: \(rejection)", file: file, line: line)
    }
    return result
  }

  private func restore(_ value: Any?, key: String, defaults: UserDefaults) {
    if let value { defaults.set(value, forKey: key) }
    else { defaults.removeObject(forKey: key) }
  }

  private func documentPickerResult(module: EraseNative, url: URL) throws -> [String: Any]? {
    let settled = expectation(description: "document selection resolves")
    var result: [String: Any]?
    let requestID = try XCTUnwrap(module.reservePickerRequest { value in
      result = value as? [String: Any]
      settled.fulfill()
    })
    let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image, UTType.svg], asCopy: true)
    XCTAssertTrue(module.associatePickerController(picker, requestID: requestID))
    module.documentPicker(picker, didPickDocumentsAt: [url])
    wait(for: [settled], timeout: 10)
    return result
  }

}
