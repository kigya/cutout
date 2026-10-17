import XCTest
import UIKit
import Photos
import React
import ObjectiveC
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
    XCTAssertTrue(class_respondsToSelector(bridgeClass, NSSelectorFromString("getHome:rejecter:")))
    XCTAssertTrue(class_respondsToSelector(bridgeClass, NSSelectorFromString("pickAndCut:resolver:rejecter:")))
    XCTAssertTrue(class_respondsToSelector(bridgeClass, NSSelectorFromString("readAlpha:resolver:rejecter:")))
    XCTAssertTrue(class_respondsToSelector(bridgeClass, NSSelectorFromString("setPreferences:analytics:crashes:resolver:rejecter:")))
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
}
