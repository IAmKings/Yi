import Foundation
import CoreText
import AppKit
let W = 1024, GW = 634
let colorSpace = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: W, height: W, bitsPerComponent: 8, bytesPerRow: 4*W, space: colorSpace, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
ctx.setFillColor(CGColor(red: 0, green: 0, blue: 0, alpha: 0))
ctx.fill(CGRect(x:0,y:0,width:W,height:W))
// paper color #FFFAF0
ctx.setFillColor(CGColor(red: 1, green: 250/255.0, blue: 240/255.0, alpha: 1))
let font = CTFontCreateWithName("Songti SC" as CFString, 634, nil)
let line = CTLineCreateWithAttributedString(NSAttributedString(string: "譯", attributes: [.font: font]))
let bounds = CTLineGetBoundsWithOptions(line, .useOpticalBounds)
ctx.textPosition = CGPoint(x: CGFloat(W)/2 - bounds.midX, y: CGFloat(W)/2 - bounds.midY)
ctx.setFillColor(CGColor(red: 1.0, green: 0.980392, blue: 0.941176, alpha: 1.0))
var lineAttrs: [NSAttributedString.Key: Any] = [:]
let colored = NSMutableAttributedString(attributedString: NSAttributedString(string: "譯", attributes: [.font: font, .foregroundColor: NSColor(red: 1.0, green: 0.980392, blue: 0.941176, alpha: 1.0)]))
let line2 = CTLineCreateWithAttributedString(colored)
CTLineDraw(line2, ctx)
let cgImg = ctx.makeImage()!
let rep = NSBitmapImageRep(cgImage: cgImg)
rep.size = NSSize(width: W, height: W)
let png = rep.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: "app/src/main/res/drawable/ic_launcher_foreground.png"))
print("written")
