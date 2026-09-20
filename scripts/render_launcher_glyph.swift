import Foundation
import CoreText
import AppKit
let W = 1024
let SAFE = 626 // 66/108 of 108dp canvas → ink must fit inside
func inkBounds(size: CGFloat) -> CGRect {
    let font = CTFontCreateWithName("Songti SC" as CFString, size, nil)
    let line = CTLineCreateWithAttributedString(NSAttributedString(string: "譯", attributes: [.font: font]))
    return CTLineGetBoundsWithOptions(line, .useOpticalBounds)
}
// binary search font size so ink fits SAFE box
var lo: CGFloat = 10, hi: CGFloat = 1024, best: CGFloat = 10
for _ in 0..<40 {
    let mid = (lo + hi) / 2
    let b = inkBounds(size: mid)
    if max(b.width, b.height) <= CGFloat(SAFE) { best = mid; lo = mid } else { hi = mid }
}
let font = CTFontCreateWithName("Songti SC" as CFString, best, nil)
let line = CTLineCreateWithAttributedString(NSAttributedString(string: "譯", attributes: [.font: font]))
let b = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
print("fontSize=\(best) ink=\(b)")
let colorSpace = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: W, height: W, bitsPerComponent: 8, bytesPerRow: 4*W, space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 1, green: 0.980392, blue: 0.941176, alpha: 1))
let colored = NSMutableAttributedString(attributedString: NSAttributedString(string: "譯", attributes: [.font: font, .foregroundColor: NSColor(red: 1, green: 0.980392, blue: 0.941176, alpha: 1)]))
ctx.setFillColor(CGColor(red: 1, green: 0.980392, blue: 0.941176, alpha: 1))
ctx.textPosition = CGPoint(x: CGFloat(W)/2 - b.midX, y: CGFloat(W)/2 - b.midY)
CTLineDraw(line, ctx)
let rep = NSBitmapImageRep(cgImage: ctx.makeImage()!)
let png = rep.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: "scripts/ic_launcher_foreground.png"))
