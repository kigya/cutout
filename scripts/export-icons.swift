import AppKit
import Foundation

// Mechanical platform export. Inputs remain the editable brand assets.
let root = URL(fileURLWithPath: CommandLine.arguments[1], isDirectory: true)
func load(_ path: String) -> CGImage {
    let image = NSImage(contentsOf: root.appendingPathComponent(path))!
    return image.cgImage(forProposedRect: nil, context: nil, hints: nil)!
}
func trimmed(_ image: CGImage) -> CGImage {
    let w = image.width, h = image.height
    var pixels = [UInt8](repeating: 0, count: w * h * 4)
    let rect: CGRect = pixels.withUnsafeMutableBytes { bytes in
        let context = CGContext(data: bytes.baseAddress, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        var left = w, right = 0, top = h, bottom = 0
        for y in 0..<h { for x in 0..<w where bytes[(y * w + x) * 4 + 3] > 32 {
            left = min(left, x); right = max(right, x); top = min(top, y); bottom = max(bottom, y)
        }}
        return CGRect(x: left, y: top, width: max(1, right-left+1), height: max(1, bottom-top+1))
    }
    return image.cropping(to: rect)!
}
func export(_ glyph: CGImage, _ path: String, size: Int, fraction: Double, opaque: Bool = false) throws {
    let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4, space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: (opaque ? CGImageAlphaInfo.noneSkipLast : CGImageAlphaInfo.premultipliedLast).rawValue)!
    if opaque { context.setFillColor(CGColor(red: 0.937, green: 0.965, blue: 1, alpha: 1)); context.fill(CGRect(x: 0, y: 0, width: size, height: size)) }
    let scale = Double(size) * fraction / Double(max(glyph.width, glyph.height))
    let w = Double(glyph.width) * scale, h = Double(glyph.height) * scale
    context.interpolationQuality = .high
    context.draw(glyph, in: CGRect(x: (Double(size)-w)/2, y: (Double(size)-h)/2, width: w, height: h))
    let data = NSBitmapImageRep(cgImage: context.makeImage()!).representation(using: .png, properties: [:])!
    let target = root.appendingPathComponent(path)
    try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
    try data.write(to: target)
}
let colored = trimmed(load("assets/brand/icon-foreground.png"))
let monochrome = trimmed(load("assets/brand/icon-original.png"))
try export(colored, "app/src/main/res/drawable-nodpi/ic_launcher_foreground.png", size: 432, fraction: 0.62)
try export(monochrome, "app/src/main/res/drawable-nodpi/ic_launcher_monochrome.png", size: 432, fraction: 0.62)
try export(monochrome, "app/src/main/res/drawable-nodpi/ic_stat_bubble.png", size: 96, fraction: 0.82)
try export(colored, "src/assets/bubble-icon.png", size: 192, fraction: 0.86)
try export(colored, "assets/brand/icon.png", size: 1024, fraction: 0.70, opaque: true)
let sizes = [20,29,40,58,60,76,80,87,120,152,167,180,1024]
for size in sizes { try export(colored, "ios/Cutout/Images.xcassets/AppIcon.appiconset/icon-\(size).png", size: size, fraction: 0.70, opaque: true) }
