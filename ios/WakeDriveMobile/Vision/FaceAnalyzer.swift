import MediaPipeTasksVision
import UIKit

struct FaceMetrics {
    var faceDetected = false
    var left: CGFloat = 0
    var top: CGFloat = 0
    var width: CGFloat = 0
    var height: CGFloat = 0
    var ear: Float?
    var mar: Float?
    var blinkScore: Float = 0
    var yawnScore: Float = 0
}

/// MediaPipe Face Landmarker (image mode, one frame per call).
final class FaceAnalyzer {
    private let landmarker: FaceLandmarker

    init() throws {
        guard let path = Bundle.main.path(forResource: "face_landmarker", ofType: "task") else {
            throw NSError(domain: "WakeDrive", code: 11, userInfo: [NSLocalizedDescriptionKey: "缺少 face_landmarker.task"])
        }
        let options = FaceLandmarkerOptions()
        options.baseOptions.modelAssetPath = path
        options.runningMode = .image
        options.numFaces = 1
        options.minFaceDetectionConfidence = 0.5
        options.minFacePresenceConfidence = 0.5
        options.minTrackingConfidence = 0.5
        options.outputFaceBlendshapes = true
        landmarker = try FaceLandmarker(options: options)
    }

    func analyze(_ image: UIImage) -> FaceMetrics {
        guard let mpImage = try? MPImage(uiImage: image),
              let result = try? landmarker.detect(image: mpImage),
              let landmarks = result.faceLandmarks.first,
              landmarks.count > 386 else {
            return FaceMetrics()
        }
        let categories = result.faceBlendshapes.first?.categories ?? []
        func score(_ name: String) -> Float {
            Float(categories.first { $0.categoryName == name }?.score ?? 0)
        }
        func distance(_ a: Int, _ b: Int) -> CGFloat {
            let dx = CGFloat(landmarks[a].x - landmarks[b].x)
            let dy = CGFloat(landmarks[a].y - landmarks[b].y)
            return sqrt(dx * dx + dy * dy)
        }
        func eyeAR(_ horizontal: (Int, Int), _ vertical: [(Int, Int)]) -> CGFloat? {
            let width = distance(horizontal.0, horizontal.1)
            guard width > 0.000001 else { return nil }
            let height = vertical.reduce(CGFloat(0)) { $0 + distance($1.0, $1.1) } / CGFloat(vertical.count)
            return height / width
        }
        let leftEar = eyeAR((33, 133), [(159, 145), (158, 153)])
        let rightEar = eyeAR((362, 263), [(386, 374), (385, 380)])
        let ear: Float? = (leftEar != nil && rightEar != nil) ? Float((leftEar! + rightEar!) / 2) : nil
        let mouthWidth = distance(78, 308)
        let mar: Float? = mouthWidth > 0.000001 ? Float(distance(13, 14) / mouthWidth) : nil

        let xs = landmarks.map { CGFloat($0.x) }
        let ys = landmarks.map { CGFloat($0.y) }
        let minX = xs.min() ?? 0
        let maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0
        let maxY = ys.max() ?? 0

        return FaceMetrics(
            faceDetected: true,
            left: minX,
            top: minY,
            width: maxX - minX,
            height: maxY - minY,
            ear: ear,
            mar: mar,
            blinkScore: (score("eyeBlinkLeft") + score("eyeBlinkRight")) / 2,
            yawnScore: score("jawOpen")
        )
    }
}
