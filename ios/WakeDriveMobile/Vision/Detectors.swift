import Foundation
import CoreGraphics
import UIKit

struct PhoneBox {
    let centerX: CGFloat
    let centerY: CGFloat
    let width: CGFloat
    let height: CGFloat
    let confidence: Float
}

struct PoseResult {
    var callingHeld = false
    var handToMouth = false
}

struct HeadPose {
    let yaw: Float
    let pitch: Float
    let roll: Float
}

/// YOLO11n COCO detector – class 67 = cell phone. Output [1,84,8400].
final class PhoneDetector {
    private let runner: OnnxRunner
    private let anchors = 8400
    private let channels = 84
    private let phoneClass = 67

    init() throws { runner = try OnnxRunner(modelName: "yolo11n") }

    func detect(_ image: UIImage) -> PhoneBox? {
        let (chw, scale, offset) = ImageUtils.letterboxed(image, size: 640)
        guard let output = try? runner.run(chw, shape: [1, 3, 640, 640]), output.count >= channels * anchors else { return nil }
        var best: PhoneBox?
        for anchor in 0..<anchors {
            let confidence = output[(4 + phoneClass) * anchors + anchor]
            if confidence < 0.3 { continue }
            if let current = best, confidence <= current.confidence { continue }
            let cx = CGFloat(output[anchor])
            let cy = CGFloat(output[anchors + anchor])
            let w = CGFloat(output[2 * anchors + anchor])
            let h = CGFloat(output[3 * anchors + anchor])
            best = PhoneBox(
                centerX: (cx - offset.x) / scale,
                centerY: (cy - offset.y) / scale,
                width: w / scale,
                height: h / scale,
                confidence: confidence
            )
        }
        return best
    }
}

/// YOLOv8n-pose – output [1,56,8400] (4 box + score + 17 keypoints × 3).
final class PoseDetector {
    private let runner: OnnxRunner
    private let anchors = 8400
    private let channels = 56

    init() throws { runner = try OnnxRunner(modelName: "yolov8n-pose") }

    func detect(_ image: UIImage, phoneVisible: Bool) -> PoseResult {
        let (chw, scale, offset) = ImageUtils.letterboxed(image, size: 640)
        guard let output = try? runner.run(chw, shape: [1, 3, 640, 640]), output.count >= channels * anchors else { return PoseResult() }
        var bestAnchor = -1
        var bestScore: Float = 0
        for anchor in 0..<anchors {
            let score = output[4 * anchors + anchor]
            if score > bestScore { bestScore = score; bestAnchor = anchor }
        }
        guard bestAnchor >= 0, bestScore >= 0.4 else { return PoseResult() }

        func keypoint(_ index: Int) -> (x: CGFloat, y: CGFloat, confidence: Float) {
            let base = (5 + index * 3) * anchors + bestAnchor
            let x = (CGFloat(output[base]) - offset.x) / scale
            let y = (CGFloat(output[base + 1]) - offset.y) / scale
            return (x, y, output[base + 2])
        }
        let nose = keypoint(0)
        let leftEar = keypoint(3)
        let rightEar = keypoint(4)
        let leftShoulder = keypoint(5)
        let rightShoulder = keypoint(6)
        let leftWrist = keypoint(9)
        let rightWrist = keypoint(10)

        let shoulderSpan = max(1, hypot(rightShoulder.x - leftShoulder.x, rightShoulder.y - leftShoulder.y))
        func near(_ wrist: (x: CGFloat, y: CGFloat, confidence: Float), _ target: (x: CGFloat, y: CGFloat, confidence: Float), _ threshold: CGFloat) -> Bool {
            wrist.confidence > 0.25 && target.confidence > 0.2 && hypot(wrist.x - target.x, wrist.y - target.y) < threshold
        }
        let earThreshold = shoulderSpan * 0.5
        func nearEar(_ wrist: (x: CGFloat, y: CGFloat, confidence: Float), _ ear: (x: CGFloat, y: CGFloat, confidence: Float)) -> Bool {
            near(wrist, ear, earThreshold) && (wrist.y < leftShoulder.y || wrist.y < rightShoulder.y)
        }
        let callingHeld = nearEar(leftWrist, leftEar) || nearEar(rightWrist, rightEar)
        let mouthThreshold = max(shoulderSpan * 0.35, 40)
        let handToMouth = !phoneVisible && (near(leftWrist, nose, mouthThreshold) || near(rightWrist, nose, mouthThreshold))
        return PoseResult(callingHeld: callingHeld, handToMouth: handToMouth)
    }
}

/// 6DRepNet360 – yaw / pitch / roll in degrees (pitch positive = up).
final class HeadPoseEstimator {
    private let runner: OnnxRunner

    init() throws { runner = try OnnxRunner(modelName: "6drepnet360") }

    func estimate(_ image: UIImage, face: FaceMetrics) -> HeadPose? {
        guard face.faceDetected, let input = ImageUtils.faceCropTensor(image, box: face) else { return nil }
        guard let output = try? runner.run(input, shape: [1, 3, 224, 224]), output.count >= 3 else { return nil }
        return HeadPose(yaw: output[0], pitch: output[1], roll: output[2])
    }
}
