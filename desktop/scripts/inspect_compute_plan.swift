// Diagnostic only: predicted operation placement, not an energy measurement or execution trace.
import Foundation
import CoreML

func deviceName(_ device: MLComputeDevice) -> String {
    switch device {
    case .cpu: return "CPU"
    case .gpu: return "GPU"
    case .neuralEngine: return "Neural Engine"
    @unknown default: return "unknown"
    }
}
let root = URL(fileURLWithPath: CommandLine.arguments[1])
Task {
    do {
        var reports: [[String: Any]] = []
        let encoders: [(String, String?)] = CommandLine.arguments.contains("--clean") ? [("Encoder", nil)] : [("Encoder", "short8"), ("Encoder", "full15")]
        for (name, function) in encoders + [("Preprocessor", nil), ("Decoder", nil), ("JointDecisionv3", nil)] {
            let config = MLModelConfiguration(); config.computeUnits = .all; config.functionName = function
            let plan = try await MLComputePlan.load(contentsOf: root.appendingPathComponent(name + ".mlmodelc"), configuration: config)
            var preferred: [String: Int] = [:]
            var unsupported: [String: Int] = [:]
            func walk(_ block: MLModelStructure.Program.Block) {
                for op in block.operations {
                    if let usage = plan.deviceUsage(for: op) { preferred[deviceName(usage.preferred), default: 0] += 1 }
                    else { unsupported[op.operatorName, default: 0] += 1 }
                    for nested in op.blocks { walk(nested) }
                }
            }
            if case .program(let program) = plan.modelStructure {
                for value in program.functions.values { walk(value.block) }
            }
            reports.append(["component": name, "function": function ?? "default", "preferredOperationCounts": preferred, "noDeviceMetadata": unsupported])
        }
        let report: [String: Any] = ["policy": "all", "evidence": "Core ML compute plan; not measured runtime utilization or energy", "components": reports]
        print(String(decoding: try JSONSerialization.data(withJSONObject: report, options: [.prettyPrinted, .sortedKeys]), as: UTF8.self)); exit(0)
    } catch { fputs("\(error)\n", stderr); exit(1) }
}
dispatchMain()
