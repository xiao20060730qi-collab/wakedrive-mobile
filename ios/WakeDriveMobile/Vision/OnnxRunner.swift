import Foundation
import onnxruntime_objc

/// Single ONNX Runtime session wrapper for the three ONNX models.
final class OnnxRunner {
    private let env: ORTEnv
    private let session: ORTSession

    init(modelName: String) throws {
        guard let path = Bundle.main.path(forResource: modelName, ofType: "onnx") else {
            throw NSError(domain: "WakeDrive", code: 1, userInfo: [NSLocalizedDescriptionKey: "缺少模型 \(modelName).onnx"])
        }
        env = try ORTEnv(loggingLevel: .warning)
        let options = try ORTSessionOptions()
        try options.setGraphOptimizationLevel(.all)
        session = try ORTSession(env: env, modelPath: path, sessionOptions: options)
    }

    func run(_ input: [Float], shape: [Int]) throws -> [Float] {
        let data = input.withUnsafeBufferPointer { Data(buffer: $0) }
        let tensor = try ORTValue(
            tensorData: NSMutableData(data: data),
            elementType: .float,
            shape: shape.map { NSNumber(value: $0) }
        )
        guard let inputName = try session.inputNames().first,
              let outputName = try session.outputNames().first else {
            throw NSError(domain: "WakeDrive", code: 2, userInfo: [NSLocalizedDescriptionKey: "模型输入输出缺失"])
        }
        let outputs = try session.run(withInputs: [inputName: tensor], outputNames: [outputName], runOptions: nil)
        guard let output = outputs[outputName] else {
            throw NSError(domain: "WakeDrive", code: 3, userInfo: [NSLocalizedDescriptionKey: "模型无输出"])
        }
        let outData = try output.tensorData() as Data
        return outData.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    }
}
