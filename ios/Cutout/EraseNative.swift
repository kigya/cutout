import Foundation
import UIKit
import Photos
import PhotosUI
import UniformTypeIdentifiers
import React
import WebKit
import CoreFoundation
import ImageIO
#if canImport(FirebaseCore) && canImport(FirebaseAnalytics) && canImport(FirebaseCrashlytics) && canImport(FirebaseRemoteConfig)
import FirebaseCore
import FirebaseAnalytics
import FirebaseCrashlytics
import FirebaseRemoteConfig
#endif

@objc(Erase)
final class EraseNative: RCTEventEmitter, PHPickerViewControllerDelegate, UIDocumentPickerDelegate, WKNavigationDelegate {
  private var pickerResolve: RCTPromiseResolveBlock?
  private var pickerControllers: [ObjectIdentifier: UUID] = [:]
  private let pickerLock = NSLock()
  private var pickerRequestID: UUID?
  private var pickerAwaitingData = false
  private var pickerLoadTimeout: DispatchWorkItem?
  private var ortEnv: ORTEnv?
  private var ortSession: ORTSession?
  private let modelQueue = DispatchQueue(label: "dev.kigya.cutout.isnet", qos: .userInitiated)
  private let processingQueue = DispatchQueue(label: "dev.kigya.cutout.processing", qos: .userInitiated)
  private var pendingEdit: [String: Any]?
  private let cutLock = NSLock()
  private var currentCut: UUID?
  private var svgJobs: [UUID: (WKWebView, RCTPromiseResolveBlock, RCTPromiseRejectBlock, UUID?)] = [:]
  private let fm = FileManager.default

  @objc override static func requiresMainQueueSetup() -> Bool { true }

  override func supportedEvents() -> [String]! { ["eraseLocale", "eraseProgress", "erasePreview"] }

  static func bootstrapTelemetry() {
    #if canImport(FirebaseCore) && canImport(FirebaseAnalytics) && canImport(FirebaseCrashlytics) && canImport(FirebaseRemoteConfig)
    guard Bundle.main.url(forResource: "GoogleService-Info", withExtension: "plist") != nil else { return }
    if FirebaseApp.app() == nil { FirebaseApp.configure() }
    applyFirebaseConsent()
    #endif
  }

  private static func applyFirebaseConsent() {
    #if canImport(FirebaseCore) && canImport(FirebaseAnalytics) && canImport(FirebaseCrashlytics) && canImport(FirebaseRemoteConfig)
    let defaults = UserDefaults.standard
    let analyticsEnabled = defaults.bool(forKey: "erase.analytics")
    let crashesEnabled = defaults.bool(forKey: "erase.crashes")
    Analytics.setConsent([
      .analyticsStorage: analyticsEnabled ? .granted : .denied,
      .adStorage: .denied,
      .adUserData: .denied,
      .adPersonalization: .denied
    ])
    Analytics.setAnalyticsCollectionEnabled(analyticsEnabled)
    Crashlytics.crashlytics().setCrashlyticsCollectionEnabled(crashesEnabled)
    if !crashesEnabled { Crashlytics.crashlytics().deleteUnsentReports() }
    if analyticsEnabled {
      let remote = RemoteConfig.remoteConfig()
      try? remote.setDefaults(from: ["review_min_exports": 5, "review_cooldown_days": 90])
      remote.fetchAndActivate()
    }
    #endif
  }

  private var cacheURL: URL {
    fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("cutout", isDirectory: true)
  }

  private func ensureCache() throws {
    try fm.createDirectory(at: cacheURL, withIntermediateDirectories: true)
  }

  private func reject(_ reject: RCTPromiseRejectBlock, _ message: String) {
    reject("erase", message, nil)
  }

  @objc(getHome:rejecter:)
  func getHome(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    resolve(["bubbleSupported": false, "rows": [], "bubbleEnabled": false, "showXiaomiTip": false, "clipboardReady": true])
  }

  @objc(prepareModel:rejecter:)
  func prepareModel(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    modelQueue.async {
      do {
        try self.prepareModelIfNeeded()
        DispatchQueue.main.async { resolve("ready") }
      } catch {
        DispatchQueue.main.async { self.reject(reject, "model_failed") }
      }
    }
  }

  private func prepareModelIfNeeded() throws {
    guard ortSession == nil else { return }
    guard let modelURL = Bundle.main.url(forResource: "isnet", withExtension: "onnx") else {
      throw NSError(domain: "Erase", code: 1, userInfo: [NSLocalizedDescriptionKey: "model_missing"])
    }
    let env = try ORTEnv(loggingLevel: ORTLoggingLevel.warning)
    let options = try ORTSessionOptions()
    try options.setIntraOpNumThreads(2)
    try options.setGraphOptimizationLevel(ORTGraphOptimizationLevel.all)
    let session = try ORTSession(env: env, modelPath: modelURL.path, sessionOptions: options)
    ortEnv = env
    ortSession = session
  }

  @objc(setBubble:resolver:rejecter:)
  func setBubble(_ enabled: Bool, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(nil) }

  @objc(hideBubble:rejecter:)
  func hideBubble(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(nil) }

  @objc(openPermission:resolver:rejecter:)
  func openPermission(_ kind: String, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(nil) }

  @objc(pickAndCut:resolver:rejecter:)
  func pickAndCut(_ mode: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    pickerLock.lock()
    guard pickerRequestID == nil else { pickerLock.unlock(); resolve(["status": "failed", "message": "busy"]); return }
    let requestID = UUID()
    pickerRequestID = requestID
    pickerResolve = resolve
    pickerLock.unlock()

    DispatchQueue.main.async {
      guard let presenter = self.topViewController() else {
        self.completePicker(["status": "failed", "message": "picker_failed"], requestID: requestID)
        return
      }
      if mode == "file" {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [UTType.image, UTType.svg], asCopy: true)
        picker.delegate = self
        guard self.associatePickerController(picker, requestID: requestID) else { return }
        presenter.present(picker, animated: true)
      } else {
        var config = PHPickerConfiguration(photoLibrary: .shared())
        config.filter = .images
        config.selectionLimit = 1
        let picker = PHPickerViewController(configuration: config)
        picker.delegate = self
        guard self.associatePickerController(picker, requestID: requestID) else { return }
        presenter.present(picker, animated: true)
      }
    }
  }

  @objc(cutClipboard:rejecter:)
  func cutClipboard(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    // Called only after the user taps Paste in the app; iOS may show its pasteboard access prompt.
    DispatchQueue.main.async {
      if let text = UIPasteboard.general.string, text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().contains("<svg") {
        self.processSVG(text, resolve: resolve, reject: reject)
        return
      }
      guard let image = UIPasteboard.general.image else {
        resolve(["status": "failed", "message": "empty_clipboard"])
        return
      }
      self.process(image: image, rasterizedSvg: false, resolve: resolve, reject: reject)
    }
  }

  @objc(cutFixture:rejecter:)
  func cutFixture(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    guard let url = Bundle.main.url(forResource: "fixture", withExtension: "png"), let image = UIImage(contentsOfFile: url.path) else {
      resolve(["status": "failed", "message": "read_failed"]); return
    }
    process(image: image, rasterizedSvg: false, resolve: resolve, reject: reject)
  }

  @objc(cancelCut:rejecter:)
  func cancelCut(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    pickerLock.lock()
    cutLock.lock()
    let token = currentCut
    currentCut = nil
    let resolvePicker = pickerResolve
    pickerResolve = nil
    pickerRequestID = nil
    pickerAwaitingData = false
    pickerLoadTimeout?.cancel()
    pickerLoadTimeout = nil
    pickerControllers.removeAll()
    cutLock.unlock()
    pickerLock.unlock()

    DispatchQueue.main.async {
      if let token, let job = self.svgJobs.removeValue(forKey: token) {
        job.0.navigationDelegate = nil
        job.0.stopLoading()
        job.0.removeFromSuperview()
        job.1(["status": "failed", "message": "cancelled"])
      }
      resolvePicker?(["status": "failed", "message": "cancelled"])
      resolve(nil)
    }
  }

  @objc(bubbleAction:resolver:rejecter:)
  func bubbleAction(_ action: String, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    resolve(bubbleState())
  }

  @objc(getBubbleState:rejecter:)
  func getBubbleState(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(bubbleState()) }

  private func bubbleState() -> [String: Any] {
    ["expanded": false, "anchorEnd": false, "phase": "idle", "clipboardReady": true,
     "readingClipboard": false, "message": NSNull(), "previewPath": NSNull(), "rasterizedSvg": false,
     "editReady": false, "percent": 0]
  }

  @objc(readAlpha:resolver:rejecter:)
  func readAlpha(_ path: String, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    guard let image = UIImage(contentsOfFile: path), let cg = image.cgImage else { resolve(""); return }
    let w = cg.width, h = cg.height, bytes = w * h
    var rgba = [UInt8](repeating: 0, count: bytes * 4)
    guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { resolve(""); return }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    let alpha = Data(stride(from: 3, to: rgba.count, by: 4).map { rgba[$0] })
    resolve(alpha.base64EncodedString())
  }

  @objc(writeMasked:alphaBase64:width:height:resolver:rejecter:)
  func writeMasked(_ originalPath: String, alphaBase64: String, width: NSNumber, height: NSNumber,
                   resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let w = width.intValue, h = height.intValue
    guard w > 0, h > 0, w <= 2048, h <= 2048, let original = UIImage(contentsOfFile: originalPath),
          let source = resizedCGImage(original, width: w, height: h), let alpha = Data(base64Encoded: alphaBase64), alpha.count >= w * h else {
      resolve(["status": "failed", "message": "alpha"]); return
    }
    let rgbaData = NSMutableData(length: w * h * 4)!
    let pixels = rgbaData.mutableBytes.assumingMemoryBound(to: UInt8.self)
    guard let ctx = CGContext(data: pixels, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
            space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
      resolve(["status": "failed", "message": "original"]); return
    }
    ctx.draw(source, in: CGRect(x: 0, y: 0, width: w, height: h))
    let mask = [UInt8](alpha)
    var opaque = 0
    for i in 0..<(w * h) {
      let oldAlpha = Int(pixels[i * 4 + 3])
      let a = min(oldAlpha, Int(mask[i]))
      if oldAlpha > 0 {
        for channel in 0..<3 { pixels[i * 4 + channel] = UInt8((Int(pixels[i * 4 + channel]) * a + oldAlpha / 2) / oldAlpha) }
      } else {
        pixels[i * 4] = 0; pixels[i * 4 + 1] = 0; pixels[i * 4 + 2] = 0
      }
      pixels[i * 4 + 3] = UInt8(a)
      if a > 0 { opaque += 1 }
    }
    guard let result = ctx.makeImage(), let png = UIImage(cgImage: result).pngData() else {
      resolve(["status": "failed", "message": "write"]); return
    }
    do {
      try ensureCache()
      let url = cacheURL.appendingPathComponent("preview-\(UUID().uuidString).png")
      try png.write(to: url, options: .atomic)
      resolve(["status": opaque > 0 ? "ready" : "empty", "path": url.path])
    } catch { resolve(["status": "failed", "message": "write"]) }
  }

  @objc(publishPng:resolver:rejecter:)
  func publishPng(_ path: String, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    guard let image = UIImage(contentsOfFile: path), let cg = image.cgImage else { resolve("failed"); return }
    var rgba = [UInt8](repeating: 0, count: cg.width * cg.height * 4)
    guard let ctx = CGContext(data: &rgba, width: cg.width, height: cg.height, bitsPerComponent: 8, bytesPerRow: cg.width * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { resolve("failed"); return }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: cg.width, height: cg.height))
    let opaque = stride(from: 3, to: rgba.count, by: 4).reduce(0) { $0 + (rgba[$1] >= 8 ? 1 : 0) }
    guard opaque > 0, let data = image.pngData() else { resolve("empty"); return }
    UIPasteboard.general.setData(data, forPasteboardType: UTType.png.identifier)
    resolve("copied")
  }

  @objc(saveToGallery:resolver:rejecter:)
  func saveToGallery(_ path: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
    let status = PHPhotoLibrary.authorizationStatus(for: .addOnly)
    let save: () -> Void = {
      guard let image = UIImage(contentsOfFile: path) else { resolve("failed"); return }
      PHPhotoLibrary.shared().performChanges({ PHAssetChangeRequest.creationRequestForAsset(from: image) }) { ok, _ in
        DispatchQueue.main.async { resolve(ok ? "saved" : "failed") }
      }
    }
    if status == .authorized || status == .limited { save(); return }
    guard status == .notDetermined else { resolve("denied"); return }
    PHPhotoLibrary.requestAuthorization(for: .addOnly) { result in
      self.recordProductEvent(name: "permission_result", values: ["permission": "photos_add", "result": result == .authorized ? "granted" : "denied"] as NSDictionary)
      DispatchQueue.main.async { result == .authorized ? save() : resolve("denied") }
    }
  }

  @objc(getLocale:rejecter:)
  func getLocale(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let system = Locale.preferredLanguages.first ?? "en"
    resolve(["selected": UserDefaults.standard.string(forKey: "erase.locale") ?? "", "system": system])
  }

  @objc(getPreferences:rejecter:)
  func getPreferences(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let defaults = UserDefaults.standard
    resolve(["theme": defaults.string(forKey: "erase.theme") ?? "system",
             "analytics": defaults.bool(forKey: "erase.analytics"),
             "crashes": defaults.bool(forKey: "erase.crashes")])
  }

  @objc(setPreferences:analytics:crashes:resolver:rejecter:)
  func setPreferences(_ theme: String, analytics: Bool, crashes: Bool,
                      resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let defaults = UserDefaults.standard
    let withdrewAnalytics = defaults.bool(forKey: "erase.analytics") && !analytics
    let safeTheme = ["system", "light", "dark"].contains(theme) ? theme : "system"
    defaults.set(safeTheme, forKey: "erase.theme")
    defaults.set(analytics, forKey: "erase.analytics")
    defaults.set(crashes, forKey: "erase.crashes")
    Self.applyFirebaseConsent()
    #if canImport(FirebaseCore) && canImport(FirebaseAnalytics) && canImport(FirebaseCrashlytics) && canImport(FirebaseRemoteConfig)
    if withdrewAnalytics { Analytics.resetAnalyticsData() }
    #endif
    resolve(nil)
  }

  @objc(logProductEvent:values:resolver:rejecter:)
  func logProductEvent(_ name: String, values: NSDictionary,
                       resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    recordProductEvent(name: name, values: values)
    resolve(nil)
  }

  private func recordProductEvent(name: String, values: NSDictionary) {
    #if canImport(FirebaseCore) && canImport(FirebaseAnalytics) && canImport(FirebaseCrashlytics) && canImport(FirebaseRemoteConfig)
    let events: Set<String> = ["screen_view", "screen_duration", "cutout_started", "cutout_completed", "cutout_failed", "cutout_cancelled", "editor_action", "export_completed", "export_failed", "bubble_changed", "permission_result", "locale_changed"]
    let fields: Set<String> = ["screen_name", "duration_ms", "source", "surface", "format", "action", "result", "permission", "enabled", "locale", "stage"]
    if UserDefaults.standard.bool(forKey: "erase.analytics"), events.contains(name), FirebaseApp.app() != nil {
      var parameters: [String: Any] = [:]
      for (rawKey, value) in values {
        guard let key = rawKey as? String, fields.contains(key) else { continue }
        if let string = value as? String {
          if string.count <= 40 && string.range(of: #"^[a-zA-Z0-9_.-]+$"#, options: .regularExpression) != nil { parameters[key] = string }
        } else if let number = value as? NSNumber {
          if CFGetTypeID(number) == CFBooleanGetTypeID() { parameters[key] = number.boolValue ? 1 : 0 }
          else if number.doubleValue.isFinite { parameters[key] = number }
        }
      }
      Analytics.logEvent(name, parameters: parameters)
    }
    #endif
  }

  @objc(setLocale:resolver:rejecter:)
  func setLocale(_ tag: String, resolver resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let selected = tag == "system" ? "" : tag
    UserDefaults.standard.set(selected, forKey: "erase.locale")
    recordProductEvent(name: "locale_changed", values: ["locale": selected.isEmpty ? "system" : selected] as NSDictionary)
    DispatchQueue.main.async {
      self.sendEvent(withName: "eraseLocale", body: ["selected": selected, "system": Locale.preferredLanguages.first ?? "en"])
    }
    resolve(nil)
  }

  @objc(restart:rejecter:)
  func restart(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    RCTTriggerReloadCommandListeners("Cutout language or layout direction changed")
    resolve(nil)
  }

  @objc(consumePendingEdit:rejecter:)
  func consumePendingEdit(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) {
    let edit = pendingEdit
    pendingEdit = nil
    resolve((edit as Any?) ?? NSNull())
  }

  @objc(moveBubble:dy:)
  func moveBubble(_ dx: NSNumber, dy: NSNumber) {}

  @objc(settleBubble:rejecter:)
  func settleBubble(_ resolve: RCTPromiseResolveBlock, rejecter reject: RCTPromiseRejectBlock) { resolve(false) }

  @objc(setBubbleSize:height:)
  func setBubbleSize(_ width: NSNumber, height: NSNumber) {}

  // MARK: Pickers

  func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
    picker.dismiss(animated: true)
    guard let requestID = takePickerRequest(for: picker) else { return }
    guard let provider = results.first?.itemProvider else {
      completePicker(["status": "failed", "message": "cancelled"], requestID: requestID)
      return
    }

    // The picker has returned a selection. Show preparation while Photos supplies the bytes.
    emitProgress(8)
    armPickerLoadTimeout(requestID)
    provider.loadDataRepresentation(forTypeIdentifier: UTType.image.identifier) { data, error in
      // Ignore late callbacks after timeout or user cancellation; in particular, never copy a
      // cutout produced by a request whose promise has already completed.
      guard self.clearPickerLoadTimeout(requestID) else { return }
      guard let data, error == nil, data.count <= 32 * 1024 * 1024,
            let image = self.downsampledImage(data) else {
        self.completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
        return
      }
      self.process(image: image, rasterizedSvg: false, pickerRequestID: requestID,
                   resolve: { self.completePicker($0 ?? NSNull(), requestID: requestID) },
                   reject: { _, message, _ in
                     self.completePicker(["status": "failed", "message": message ?? "read_failed"] as [String: Any], requestID: requestID)
                   })
    }
  }

  func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
    guard let requestID = takePickerRequest(for: controller) else { return }
    completePicker(["status": "failed", "message": "cancelled"], requestID: requestID)
  }

  func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
    guard let requestID = takePickerRequest(for: controller) else { return }
    guard let url = urls.first else {
      completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
      return
    }
    let scoped = url.startAccessingSecurityScopedResource()
    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
    do {
      let attributes = try fm.attributesOfItem(atPath: url.path)
      guard ((attributes[.size] as? NSNumber)?.intValue ?? Int.max) <= 32 * 1024 * 1024 else {
        completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
        return
      }
      let data = try Data(contentsOf: url, options: .mappedIfSafe)
      if url.pathExtension.lowercased() == "svg" {
        guard let text = String(data: data, encoding: .utf8) else {
          completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
          return
        }
        processSVG(text, pickerRequestID: requestID,
                   resolve: { self.completePicker($0 ?? NSNull(), requestID: requestID) },
                   reject: { _, message, _ in
                     self.completePicker(["status": "failed", "message": message ?? "read_failed"] as [String: Any], requestID: requestID)
                   })
      } else if let image = downsampledImage(data) {
        process(image: image, rasterizedSvg: false, pickerRequestID: requestID,
                resolve: { self.completePicker($0 ?? NSNull(), requestID: requestID) },
                reject: { _, message, _ in
                  self.completePicker(["status": "failed", "message": message ?? "read_failed"] as [String: Any], requestID: requestID)
                })
      } else {
        completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
      }
    } catch {
      completePicker(["status": "failed", "message": "read_failed"], requestID: requestID)
    }
  }

  private func associatePickerController(_ controller: UIViewController, requestID: UUID) -> Bool {
    pickerLock.lock(); defer { pickerLock.unlock() }
    guard pickerRequestID == requestID else { return false }
    pickerControllers[ObjectIdentifier(controller)] = requestID
    return true
  }

  private func takePickerRequest(for controller: UIViewController) -> UUID? {
    pickerLock.lock(); defer { pickerLock.unlock() }
    return pickerControllers.removeValue(forKey: ObjectIdentifier(controller))
  }

  private func armPickerLoadTimeout(_ requestID: UUID) {
    let timeout = DispatchWorkItem { [weak self] in
      self?.completePicker(["status": "failed", "message": "read_failed"], requestID: requestID, onlyIfAwaitingData: true)
    }
    pickerLock.lock()
    guard pickerRequestID == requestID else { pickerLock.unlock(); return }
    pickerLoadTimeout?.cancel()
    pickerAwaitingData = true
    pickerLoadTimeout = timeout
    pickerLock.unlock()
    DispatchQueue.main.asyncAfter(deadline: .now() + 45, execute: timeout)
  }

  private func clearPickerLoadTimeout(_ requestID: UUID) -> Bool {
    pickerLock.lock(); defer { pickerLock.unlock() }
    guard pickerRequestID == requestID, pickerAwaitingData else { return false }
    pickerAwaitingData = false
    pickerLoadTimeout?.cancel()
    pickerLoadTimeout = nil
    return true
  }

  private func completePicker(_ value: Any, requestID: UUID, onlyIfAwaitingData: Bool = false) {
    pickerLock.lock()
    guard pickerRequestID == requestID, !onlyIfAwaitingData || pickerAwaitingData else { pickerLock.unlock(); return }
    let resolve = pickerResolve
    pickerAwaitingData = false
    pickerRequestID = nil
    pickerLoadTimeout?.cancel()
    pickerLoadTimeout = nil
    pickerResolve = nil
    pickerLock.unlock()
    DispatchQueue.main.async { resolve?(value) }
  }

  // MARK: Processing

  private func process(image: UIImage, rasterizedSvg: Bool, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
    process(image: image, rasterizedSvg: rasterizedSvg, pickerRequestID: nil, resolve: resolve, reject: reject)
  }

  private func process(image: UIImage, rasterizedSvg: Bool, pickerRequestID requestID: UUID?, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
    let token = UUID()
    guard beginProcessing(token: token, pickerRequestID: requestID) else { return }
    process(image: image, rasterizedSvg: rasterizedSvg, token: token, pickerRequestID: requestID, resolve: resolve, reject: reject)
  }

  private func process(image: UIImage, rasterizedSvg: Bool, token: UUID, pickerRequestID requestID: UUID? = nil, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
    processingQueue.async {
      do {
        guard self.isCurrentCut(token) else { throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"]) }
        self.emitProgress(8)
        let normalized = self.normalized(image)
        guard let sourcePNG = normalized.pngData() else { throw NSError(domain: "Erase", code: 4) }
        let sourceURL = try self.writeFile(sourcePNG, name: "source")
        self.emitPreview(sourceURL.path)
        guard self.isCurrentCut(token) else { throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"]) }
        try self.modelQueue.sync { try self.prepareModelIfNeeded() }
        // Model construction can take long enough for cancellation or a newer picker request.
        // Recheck before emitting progress or entering the non-cancellable ORT run.
        guard self.isCurrentCut(token) else { throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"]) }
        self.emitProgress(28)
        guard let full = self.segment(normalized), let fullPNG = full.pngData() else {
          guard let edit = try self.withCurrentCut(token, { try self.storeEdit(original: normalized, cutout: normalized) }) else {
            throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"])
          }
          let response: [String: Any] = ["status": "manual", "message": "manual_cut", "originalPath": edit.0, "cutoutPath": edit.1, "width": edit.2, "height": edit.3]
          if !self.finishProcessing(token: token, pickerRequestID: requestID, value: response, resolve: resolve, progress: 100), requestID == nil {
            DispatchQueue.main.async { resolve(["status": "failed", "message": "cancelled"]) }
          }
          return
        }
        guard self.isCurrentCut(token) else { throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"]) }
        self.emitProgress(92)
        let tight = self.cropAlpha(full) ?? full
        let resultURL = try self.writeFile(fullPNG, name: "cutout")
        guard let edit = try self.withCurrentCut(token, { try self.storeEdit(original: normalized, cutout: full) }) else {
          throw NSError(domain: "Erase", code: 3, userInfo: [NSLocalizedDescriptionKey: "cancelled"])
        }
        var response: [String: Any] = ["status": "ready", "message": rasterizedSvg ? "svg_as_png" : "copied", "originalPath": edit.0, "cutoutPath": edit.1, "width": edit.2, "height": edit.3]
        response["previewPath"] = resultURL.path
        let clipboardData = tight.pngData() ?? fullPNG
        if !self.finishProcessing(token: token, pickerRequestID: requestID, value: response, clipboardData: clipboardData, resolve: resolve, progress: 100), requestID == nil {
          DispatchQueue.main.async { resolve(["status": "failed", "message": "cancelled"]) }
        }
      } catch {
        let value = ["status": "failed", "message": self.isCurrentCut(token) ? "model_failed" : "cancelled"]
        if !self.finishProcessing(token: token, pickerRequestID: requestID, value: value, resolve: resolve), requestID == nil {
          DispatchQueue.main.async { resolve(value) }
        }
      }
    }
  }

  private func processSVG(_ text: String, pickerRequestID requestID: UUID? = nil, resolve: @escaping RCTPromiseResolveBlock, reject: @escaping RCTPromiseRejectBlock) {
    guard let data = text.data(using: .utf8), text.lowercased().contains("<svg") else {
      resolve(["status": "failed", "message": "read_failed"]); return
    }
    guard data.count <= 32 * 1024 * 1024 else { resolve(["status": "failed", "message": "read_failed"]); return }
    let token = UUID()
    guard beginProcessing(token: token, pickerRequestID: requestID) else { return }
    if let stripped = stripSolidBackdrop(text) {
      let response = ["status": "svg", "message": "copied"]
      if !finishProcessing(token: token, pickerRequestID: requestID, value: response,
                           clipboardData: Data(stripped.utf8), clipboardType: UTType.svg.identifier,
                           resolve: resolve), requestID == nil {
        resolve(["status": "failed", "message": "cancelled"])
      }
      return
    }
    emitProgress(8)
    // Render only the embedded SVG data. JavaScript and all external loads are disabled.
    DispatchQueue.main.async {
      guard self.isCurrentCut(token) else {
        resolve(["status": "failed", "message": "cancelled"])
        return
      }
      let config = WKWebViewConfiguration()
      config.websiteDataStore = .nonPersistent()
      config.defaultWebpagePreferences.allowsContentJavaScript = false
      let web = WKWebView(frame: CGRect(x: 0, y: 0, width: 1024, height: 1024), configuration: config)
      web.isOpaque = false
      web.backgroundColor = .clear
      web.navigationDelegate = self
      self.svgJobs[token] = (web, resolve, reject, requestID)
      let csp = "default-src 'none'; img-src data:; style-src 'unsafe-inline';"
      let html = "<!doctype html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"\(csp)\"><style>html,body{margin:0;width:1024px;height:1024px;overflow:hidden;background:transparent}img{display:block;width:1024px;height:1024px;object-fit:contain}</style></head><body><img id=\"s\" src=\"data:image/svg+xml;base64,\(data.base64EncodedString())\"></body></html>"
      web.loadHTMLString(html, baseURL: nil)
    }
  }

  func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
    guard let (token, job) = svgJobs.first(where: { $0.value.0 === webView }) else { return }
    webView.takeSnapshot(with: nil) { snapshot, error in
      self.svgJobs.removeValue(forKey: token)
      webView.navigationDelegate = nil
      webView.removeFromSuperview()
      guard self.isCurrentCut(token) else { job.1(["status": "failed", "message": "cancelled"]); return }
      guard error == nil, let snapshot else { job.1(["status": "failed", "message": "read_failed"]); return }
      self.process(image: snapshot, rasterizedSvg: true, token: token, pickerRequestID: job.3, resolve: job.1, reject: job.2)
    }
  }

  func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) {
    finishSVG(webView, message: "read_failed")
  }

  func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
    finishSVG(webView, message: "read_failed")
  }

  private func finishSVG(_ webView: WKWebView, message: String) {
    guard let (token, job) = svgJobs.first(where: { $0.value.0 === webView }) else { return }
    svgJobs.removeValue(forKey: token)
    webView.navigationDelegate = nil
    webView.removeFromSuperview()
    job.1(["status": "failed", "message": isCurrentCut(token) ? message : "cancelled"])
  }

  private func stripSolidBackdrop(_ svg: String) -> String? {
    guard let boxMatch = svg.range(of: #"viewBox\s*=\s*[\"']([^\"']+)[\"']"#, options: .regularExpression),
          let rectMatch = svg.range(of: #"<rect\b[^>]*>"#, options: [.regularExpression, .caseInsensitive]) else { return nil }
    let boxTag = String(svg[boxMatch])
    let boxNumbers = boxTag.replacingOccurrences(of: #"^.*?[\"']|[\"']$"#, with: "", options: .regularExpression)
    let numbers = boxNumbers.split(whereSeparator: { $0 == " " || $0 == "," }).compactMap { Double($0) }
    guard numbers.count == 4, numbers[2] > 0, numbers[3] > 0 else { return nil }
    let tag = String(svg[rectMatch])
    func attr(_ name: String, fallback: String = "0") -> String {
      let pattern = #"\b"# + name + #"\s*=\s*[\"']([^\"']*)[\"']"#
      guard let range = tag.range(of: pattern, options: .regularExpression) else { return fallback }
      let value = String(tag[range]).replacingOccurrences(of: #"^.*?[\"']|[\"']$"#, with: "", options: .regularExpression)
      return value.replacingOccurrences(of: "px", with: "")
    }
    let fill = attr("fill", fallback: "").lowercased()
    guard fill != "none", fill != "transparent", !fill.hasPrefix("url(") else { return nil }
    let w = Double(attr("width")) ?? 0, h = Double(attr("height")) ?? 0
    let x = Double(attr("x")) ?? 0, y = Double(attr("y")) ?? 0
    guard w >= numbers[2] * 0.98, h >= numbers[3] * 0.98, abs(x - numbers[0]) <= numbers[2] * 0.02, abs(y - numbers[1]) <= numbers[3] * 0.02 else { return nil }
    return svg.replacingCharacters(in: rectMatch, with: "")
  }

  private func segment(_ source: UIImage) -> UIImage? {
    guard let cg = source.cgImage, let session = ortSession,
          let inputName = try? session.inputNames().first, let outputName = try? session.outputNames().first else { return nil }
    let size = 1024
    guard let scaled = resizedCGImage(source, width: size, height: size) else { return nil }
    var rgba = [UInt8](repeating: 0, count: size * size * 4)
    guard let ctx = CGContext(data: &rgba, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.draw(scaled, in: CGRect(x: 0, y: 0, width: size, height: size))
    let plane = size * size
    var chw = [Float](repeating: 0, count: plane * 3)
    for i in 0..<plane {
      chw[i] = (Float(rgba[i * 4]) - 128) / 256
      chw[plane + i] = (Float(rgba[i * 4 + 1]) - 128) / 256
      chw[2 * plane + i] = (Float(rgba[i * 4 + 2]) - 128) / 256
    }
    let tensorData = chw.withUnsafeBufferPointer { NSMutableData(bytes: $0.baseAddress!, length: $0.count * MemoryLayout<Float>.size) }
    guard let input = try? ORTValue(tensorData: tensorData, elementType: ORTTensorElementDataType.float, shape: [1, 3, 1024, 1024]),
          let outputs = try? session.run(withInputs: [inputName: input], outputNames: [outputName], runOptions: nil),
          let output = outputs[outputName], let info = try? output.tensorTypeAndShapeInfo(),
          let bytes = try? output.tensorData() else { return nil }
    let shape = info.shape.map { $0.intValue }
    guard shape.count >= 2 else { return nil }
    let mw = shape[shape.count - 1], mh = shape[shape.count - 2], count = mw * mh
    guard mw > 0, mh > 0, bytes.length >= count * MemoryLayout<Float>.size else { return nil }
    let values = bytes.bytes.assumingMemoryBound(to: Float.self)
    var minV = Float.greatestFiniteMagnitude, maxV = -Float.greatestFiniteMagnitude
    for i in 0..<count { minV = min(minV, values[i]); maxV = max(maxV, values[i]) }
    let span = maxV - minV
    guard span >= 0.00001 else { return nil }
    var mask = [Float](repeating: 0, count: count)
    var foreground = 0
    for i in 0..<count { mask[i] = (values[i] - minV) / span; if mask[i] >= 0.5 { foreground += 1 } }
    let fraction = Float(foreground) / Float(count)
    guard fraction >= 0.02, fraction <= 0.95 else { return nil }
    let w = cg.width, h = cg.height
    var original = [UInt8](repeating: 0, count: w * h * 4)
    guard let imageContext = CGContext(data: &original, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    imageContext.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    for y in 0..<h { for x in 0..<w {
      let fx = (Float(x) + 0.5) * Float(mw) / Float(w) - 0.5
      let fy = (Float(y) + 0.5) * Float(mh) / Float(h) - 0.5
      let x0 = max(0, min(mw - 1, Int(floor(fx)))), x1 = max(0, min(mw - 1, Int(floor(fx)) + 1))
      let y0 = max(0, min(mh - 1, Int(floor(fy)))), y1 = max(0, min(mh - 1, Int(floor(fy)) + 1))
      let dx = fx - floor(fx), dy = fy - floor(fy)
      let a = mask[y0 * mw + x0] + (mask[y0 * mw + x1] - mask[y0 * mw + x0]) * dx
      let b = mask[y1 * mw + x0] + (mask[y1 * mw + x1] - mask[y1 * mw + x0]) * dx
      let value = max(0, min(1, ((a + (b - a) * dy) - 0.18) / 0.32))
      let soft = value * value * (3 - 2 * value)
      let base = (y * w + x) * 4
      let oldAlpha = Int(original[base + 3])
      let newAlpha = min(oldAlpha, Int(soft * 255))
      if oldAlpha > 0 {
        for channel in 0..<3 { original[base + channel] = UInt8((Int(original[base + channel]) * newAlpha + oldAlpha / 2) / oldAlpha) }
      } else {
        original[base] = 0; original[base + 1] = 0; original[base + 2] = 0
      }
      original[base + 3] = UInt8(newAlpha)
    }}
    guard let resultContext = CGContext(data: &original, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue),
      let outputCG = resultContext.makeImage() else { return nil }
    return UIImage(cgImage: outputCG)
  }

  private func normalized(_ image: UIImage) -> UIImage {
    guard let cg = image.cgImage else { return image }
    let swapsAxes = [.left, .leftMirrored, .right, .rightMirrored].contains(image.imageOrientation)
    let sourceWidth = swapsAxes ? cg.height : cg.width
    let sourceHeight = swapsAxes ? cg.width : cg.height
    let scale = min(1, 2048 / CGFloat(max(sourceWidth, sourceHeight)))
    let size = CGSize(width: max(1, (CGFloat(sourceWidth) * scale).rounded(.down)), height: max(1, (CGFloat(sourceHeight) * scale).rounded(.down)))
    if image.imageOrientation == .up && size.width == CGFloat(cg.width) && size.height == CGFloat(cg.height) { return image }
    let format = UIGraphicsImageRendererFormat()
    format.scale = 1
    format.opaque = false
    let renderer = UIGraphicsImageRenderer(size: size, format: format)
    let oriented = UIImage(cgImage: cg, scale: 1, orientation: image.imageOrientation)
    return renderer.image { _ in oriented.draw(in: CGRect(origin: .zero, size: size)) }
  }

  private func downsampledImage(_ data: Data) -> UIImage? {
    guard data.count <= 32 * 1024 * 1024,
          let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
          let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: 2048,
            kCGImageSourceShouldCacheImmediately: true
          ] as CFDictionary) else { return nil }
    return UIImage(cgImage: image, scale: 1, orientation: .up)
  }

  private func resizedCGImage(_ image: UIImage, width: Int, height: Int) -> CGImage? {
    guard let cg = image.cgImage, let ctx = CGContext(data: nil, width: width, height: height, bitsPerComponent: 8,
      bytesPerRow: 0, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.interpolationQuality = .high
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: width, height: height))
    return ctx.makeImage()
  }

  private func cropAlpha(_ image: UIImage) -> UIImage? {
    guard let cg = image.cgImage else { return nil }
    let w = cg.width, h = cg.height
    var rgba = [UInt8](repeating: 0, count: w * h * 4)
    guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
      space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
    ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
    var l = w, t = h, r = -1, b = -1
    for y in 0..<h { for x in 0..<w where rgba[(y * w + x) * 4 + 3] > 0 { l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y) } }
    guard r >= l, b >= t, let crop = cg.cropping(to: CGRect(x: l, y: t, width: r - l + 1, height: b - t + 1)) else { return nil }
    return UIImage(cgImage: crop)
  }

  private func writeFile(_ data: Data, name: String) throws -> URL {
    try ensureCache()
    let url = cacheURL.appendingPathComponent("\(name)-\(UUID().uuidString).png")
    try data.write(to: url, options: .atomic)
    return url
  }

  private func storeEdit(original: UIImage, cutout: UIImage) throws -> (String, String, Int, Int) {
    guard let originalPNG = original.pngData(), let cutoutPNG = cutout.pngData() else { throw NSError(domain: "Erase", code: 2) }
    let a = try writeFile(originalPNG, name: "original")
    let b = try writeFile(cutoutPNG, name: "cutout")
    return (a.path, b.path, original.cgImage?.width ?? Int(original.size.width), original.cgImage?.height ?? Int(original.size.height))
  }

  private func topViewController() -> UIViewController? {
    let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first { $0.activationState == .foregroundActive }
    var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
    while let presented = top?.presentedViewController { top = presented }
    return top
  }

  private func setCurrentCut(_ token: UUID?) {
    cutLock.lock(); currentCut = token; cutLock.unlock()
  }

  private func beginProcessing(token: UUID, pickerRequestID requestID: UUID?) -> Bool {
    guard let requestID else { setCurrentCut(token); return true }
    pickerLock.lock()
    guard pickerRequestID == requestID else { pickerLock.unlock(); return false }
    cutLock.lock()
    currentCut = token
    cutLock.unlock()
    pickerLock.unlock()
    return true
  }

  private func withCurrentCut<T>(_ token: UUID, _ action: () throws -> T) rethrows -> T? {
    cutLock.lock(); defer { cutLock.unlock() }
    guard currentCut == token else { return nil }
    return try action()
  }

  /// Publishes a completed result and claims completion under the same lock order used by
  /// cancelCut (pickerLock -> cutLock). For picker jobs this makes cancellation and clipboard
  /// publication mutually exclusive; once publication wins, the picker promise is claimed too.
  private func finishProcessing(token: UUID, pickerRequestID requestID: UUID?, value: Any,
                                clipboardData: Data? = nil, clipboardType: String = UTType.png.identifier,
                                resolve: @escaping RCTPromiseResolveBlock, progress: Int? = nil) -> Bool {
    var didFinish = false
    let finish = {
      self.pickerLock.lock()
      self.cutLock.lock()
      guard self.currentCut == token, requestID == nil || self.pickerRequestID == requestID else {
        self.cutLock.unlock()
        self.pickerLock.unlock()
        return
      }

      if let clipboardData {
        UIPasteboard.general.setData(clipboardData, forPasteboardType: clipboardType)
      }
      self.currentCut = nil

      var completion = resolve
      if requestID != nil {
        completion = self.pickerResolve ?? resolve
        self.pickerResolve = nil
        self.pickerRequestID = nil
        self.pickerAwaitingData = false
        self.pickerLoadTimeout?.cancel()
        self.pickerLoadTimeout = nil
        self.pickerControllers.removeAll()
      }

      self.cutLock.unlock()
      self.pickerLock.unlock()
      if let progress { self.emitProgress(progress) }
      completion(value)
      didFinish = true
    }

    if Thread.isMainThread {
      finish()
    } else {
      DispatchQueue.main.sync(execute: finish)
    }
    return didFinish
  }

  private func lockedCurrentCut() -> UUID? {
    cutLock.lock(); defer { cutLock.unlock() }; return currentCut
  }

  private func isCurrentCut(_ token: UUID) -> Bool { lockedCurrentCut() == token }

  private func emitProgress(_ percent: Int) {
    DispatchQueue.main.async { self.sendEvent(withName: "eraseProgress", body: ["percent": percent]) }
  }

  private func emitPreview(_ path: String) {
    DispatchQueue.main.async { self.sendEvent(withName: "erasePreview", body: ["path": path]) }
  }
}
