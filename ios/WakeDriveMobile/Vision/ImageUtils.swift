import UIKit

enum ImageUtils {
    /// Upright orientation + optional downscale to limit model input cost.
    static func normalized(_ image: UIImage, maxDimension: CGFloat = 960) -> UIImage {
        let longest = max(image.size.width, image.size.height)
        let scale = longest > maxDimension ? maxDimension / longest : 1
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// Letterboxed RGB tensor (CHW, 0..1) matching the web/Android detectors.
    static func letterboxed(_ image: UIImage, size: Int, pad: UInt8 = 86) -> (chw: [Float], scale: CGFloat, offset: CGPoint) {
        let imageSize = image.size
        let scale = min(CGFloat(size) / imageSize.width, CGFloat(size) / imageSize.height)
        let drawWidth = max(1, (imageSize.width * scale).rounded())
        let drawHeight = max(1, (imageSize.height * scale).rounded())
        let offsetX = (CGFloat(size) - drawWidth) / 2
        let offsetY = (CGFloat(size) - drawHeight) / 2

        var pixels = [UInt8](repeating: pad, count: size * size * 4)
        // RGB below, alpha fixed to 255.
        for index in stride(from: 3, to: pixels.count, by: 4) { pixels[index] = 255 }
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: size * 4,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ), let cgImage = image.cgImage else { return }
            context.setFillColor(red: CGFloat(pad) / 255, green: CGFloat(pad) / 255, blue: CGFloat(pad) / 255, alpha: 1)
            context.fill(CGRect(x: 0, y: 0, width: size, height: size))
            context.interpolationQuality = .high
            // CGContext draws bottom-up; flip so tensor rows match the screen.
            context.translateBy(x: 0, y: CGFloat(size))
            context.scaleBy(x: 1, y: -1)
            context.draw(cgImage, in: CGRect(x: offsetX, y: offsetY, width: drawWidth, height: drawHeight))
        }

        let plane = size * size
        var chw = [Float](repeating: 0, count: plane * 3)
        for index in 0..<plane {
            chw[index] = Float(pixels[index * 4]) / 255
            chw[plane + index] = Float(pixels[index * 4 + 1]) / 255
            chw[2 * plane + index] = Float(pixels[index * 4 + 2]) / 255
        }
        return (chw, scale, CGPoint(x: offsetX, y: offsetY))
    }

    /// 224 BGR ImageNet-normalized crop for 6DRepNet.
    static func faceCropTensor(_ image: UIImage, box: FaceMetrics, size: Int = 224, margin: CGFloat = 0.35) -> [Float]? {
        let imageWidth = image.size.width
        let imageHeight = image.size.height
        let left = max(0, (box.left - box.width * margin) * imageWidth)
        let top = max(0, (box.top - box.height * margin) * imageHeight)
        let right = min(imageWidth, (box.left + box.width + box.width * margin) * imageWidth)
        let bottom = min(imageHeight, (box.top + box.height + box.height * margin) * imageHeight)
        let cropWidth = right - left
        let cropHeight = bottom - top
        guard cropWidth >= 20, cropHeight >= 20, let cgImage = image.cgImage else { return nil }
        let rect = CGRect(x: left, y: top, width: cropWidth, height: cropHeight)
        guard let cropped = cgImage.cropping(to: rect) else { return nil }
        let crop = UIImage(cgImage: cropped)

        var pixels = [UInt8](repeating: 0, count: size * size * 4)
        let colorSpace = CGColorSpaceCreateDeviceRGB()
        pixels.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(
                data: buffer.baseAddress,
                width: size,
                height: size,
                bitsPerComponent: 8,
                bytesPerRow: size * 4,
                space: colorSpace,
                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
            ), let source = crop.cgImage else { return }
            context.interpolationQuality = .high
            context.translateBy(x: 0, y: CGFloat(size))
            context.scaleBy(x: 1, y: -1)
            context.draw(source, in: CGRect(x: 0, y: 0, width: size, height: size))
        }

        let mean: [Float] = [0.485, 0.456, 0.406]
        let std: [Float] = [0.229, 0.224, 0.225]
        let plane = size * size
        var output = [Float](repeating: 0, count: plane * 3)
        for index in 0..<plane {
            let r = Float(pixels[index * 4]) / 255
            let g = Float(pixels[index * 4 + 1]) / 255
            let b = Float(pixels[index * 4 + 2]) / 255
            output[index] = (b - mean[0]) / std[0]
            output[plane + index] = (g - mean[1]) / std[1]
            output[2 * plane + index] = (r - mean[2]) / std[2]
        }
        return output
    }

    static func jpegBase64(_ image: UIImage, maxWidth: CGFloat = 320, quality: CGFloat = 0.55) -> String? {
        let scale = min(1, maxWidth / image.size.width)
        let target = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let resized = UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
        guard let data = resized.jpegData(compressionQuality: quality) else { return nil }
        return "data:image/jpeg;base64," + data.base64EncodedString()
    }
}
