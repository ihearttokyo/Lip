// swift-tools-version: 6.2
import PackageDescription
let package = Package(name: "LipLocal", platforms: [.macOS("26.0")],
    dependencies: [.package(url: "https://github.com/FluidInference/FluidAudio.git",
        revision: "a00964e1aa8eff99c4bce80bc9c8ff2d4dd1568d", traits: [])],
    targets: [.executableTarget(name: "LipLocal", dependencies: [.product(name: "FluidAudio", package: "FluidAudio")], resources: [.copy("Models"), .copy("VocabularyData")])],
    swiftLanguageModes: [.v5])
