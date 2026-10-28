"""Compile the actual test-only Kotlin detector with cached tools, then check it on the JVM."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
CACHE = Path.home() / ".gradle/caches/modules-2/files-2.1"
JAVA = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin/java")
ANDROID = Path("/Users/jared/Library/Android/sdk/platforms/android-36/android.jar")

CHECK = """
package dev.lip

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

fun main() {
    fun tone(hz: Double, amplitude: Double = 0.25, phase: Double = 0.0) = ShortArray(AudioToneGate.WINDOW_SAMPLES) {
        (sin(2 * PI * hz * it / AudioToneGate.RATE + phase) * amplitude * 32767).toInt().toShort()
    }
    val silence = ShortArray(AudioToneGate.WINDOW_SAMPLES)
    val gate = AudioToneGate()
    val first = gate.observe(tone(1000.0, phase = 0.7))
    check(kotlin.math.abs(first.rms - 0.25 / sqrt(2.0)) < 0.0001) { "RMS must measure actual samples" }
    check(first.tone && first.toneFraction >= 0.99 && !first.silent) { "Dominant 1 kHz tone not detected" }
    repeat(4) { gate.observe(tone(1000.0)) }
    check(gate.toneSeen && !gate.passed) { "Silence must follow sustained tone" }
    repeat(9) { gate.observe(silence) }
    check(!gate.passed) { "Require ten consecutive zero windows" }
    val almostSilent = silence.copyOf().apply { this[0] = 1 }
    gate.observe(almostSilent)
    check(gate.silenceRun == 0) { "Non-silence must reset the final silence run" }
    repeat(10) { gate.observe(silence) }
    check(gate.passed)

    val random = Random(17)
    val oneKhz = tone(1000.0)
    val twoKhz = tone(2000.0)
    val mixed = ShortArray(AudioToneGate.WINDOW_SAMPLES) {
        ((oneKhz[it].toInt() + twoKhz[it].toInt()) / 2).toShort()
    }
    val negatives = listOf(silence, tone(500.0), tone(900.0), tone(1100.0), tone(2000.0),
        tone(1000.0, 0.01), tone(1000.0, 0.99), mixed, ShortArray(AudioToneGate.WINDOW_SAMPLES) { 8192 },
        ShortArray(AudioToneGate.WINDOW_SAMPLES) { random.nextInt(-8192, 8193).toShort() },
        ShortArray(AudioToneGate.WINDOW_SAMPLES) { Short.MIN_VALUE })
    for ((index, samples) in negatives.withIndex()) {
        val rejected = AudioToneGate()
        repeat(10) {
            val window = rejected.observe(samples)
            check(window.rms.isFinite() && window.rms in 0.0..1.0 && window.toneFraction in 0.0..1.0)
        }
        repeat(10) { rejected.observe(silence) }
        check(!rejected.toneSeen && !rejected.passed) { "Absent/foreign/invalid-amplitude tone accepted: $index" }
    }
    val tooShort = AudioToneGate()
    repeat(4) { tooShort.observe(tone(1000.0)) }
    tooShort.observe(silence)
    repeat(4) { tooShort.observe(tone(1000.0)) }
    repeat(10) { tooShort.observe(silence) }
    check(!tooShort.passed) { "A short tone burst must not pass" }
    check(runCatching { AudioToneGate().observe(ShortArray(1)) }.isFailure)
    println("tone gate JVM: 1 kHz+silence accepted; absent, other tones, mixed, noise and timing edges refused")
}
"""


def jar(group, name, version):
    candidates = list((CACHE / group / name / version).glob("*/*.jar"))
    if len(candidates) != 1:
        raise RuntimeError("Expected exactly one resident jar: " + name + " " + version)
    return candidates[0]


def main():
    dependencies = [jar("org.jetbrains.kotlin", name, version) for name, version in (
        ("kotlin-compiler-embeddable", "2.3.10"), ("kotlin-stdlib", "2.3.10"),
        ("kotlin-script-runtime", "2.3.10"), ("kotlin-reflect", "1.6.10"),
        ("kotlin-daemon-embeddable", "2.3.10"))]
    dependencies += [jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"),
                     jar("org.jetbrains", "annotations", "23.0.0")]
    stdlib = dependencies[1]
    with tempfile.TemporaryDirectory(prefix="lip-tone-gate-") as directory:
        root = Path(directory)
        check = root / "AudioToneGateCheck.kt"
        check.write_text(CHECK)
        output = root / "classes"
        subprocess.run([str(JAVA), "-Xms32m", "-Xmx192m", "-XX:MaxMetaspaceSize=192m", "-XX:ActiveProcessorCount=1",
                        "-cp", os.pathsep.join(map(str, dependencies)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-jvm-target", "17", "-classpath",
                        os.pathsep.join(map(str, (stdlib, dependencies[-1], ANDROID))), "-d", str(output),
                        str(ROOT / "app/src/androidTest/java/dev/lip/AudioInjectionRunner.kt"), str(check)],
                       check=True, timeout=45)
        subprocess.run([str(JAVA), "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp",
                        os.pathsep.join(map(str, (output, stdlib))), "dev.lip.AudioToneGateCheckKt"],
                       check=True, timeout=10)


if __name__ == "__main__":
    main()
