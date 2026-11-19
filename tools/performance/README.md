# Voice-processing benchmark

`VoiceProcessingBenchmark.java` runs the production `VoiceProcessor` on generated 44.1 kHz
mono PCM, including analysis, gain selection, denoising, normalization, and output delivery.
It never accesses the microphone or reminders. AAC decoding/encoding, disk I/O and app UI
are outside this benchmark, so its speedup must not be presented as a whole-app speedup.

The harness warms up with five complete runs and reports the median, minimum, and 90th
percentile over the requested iterations. It verifies output length and finite samples,
and counts distinct output buffers in a separate untimed run. The optional third argument
writes big-endian float samples for a baseline/candidate comparison; timing runs do not write
that file or retain output buffers.

Compile the harness with JDK 17+ and Java 8 bytecode:

```powershell
javac --release 8 -d app/build/performance/harness tools/performance/VoiceProcessingBenchmark.java
```

For a baseline, build commit `72597c6` in an isolated checkout and copy its compiled
`VoiceProcessor*.class` files, preserving package directories, into a baseline classes directory.
For the candidate, also include the compiled `Radix2Fft.class` from the Gradle Java output.
Use the same Kotlin stdlib jar and harness for both:

```powershell
java -cp 'app/build/performance/harness;BASELINE_CLASSES;KOTLIN_STDLIB_JAR' VoiceProcessingBenchmark 10 15 baseline-output.f32
java -cp 'app/build/performance/harness;CANDIDATE_CLASSES;KOTLIN_STDLIB_JAR' VoiceProcessingBenchmark 10 15 candidate-output.f32
```

To measure Android ART, package each classes directory and the compiled harness as jars,
convert those jars plus the Kotlin stdlib to DEX with the Android SDK's `d8 --min-api 26`,
then push each output ZIP to a separate jar path in `/data/local/tmp`. Run:

```powershell
adb shell dalvikvm -cp /data/local/tmp/speakalert-baseline-dex.jar VoiceProcessingBenchmark 10 15
adb shell dalvikvm -cp /data/local/tmp/speakalert-candidate-dex.jar VoiceProcessingBenchmark 10 15
```

Repeat in reverse order. Keep device conditions and harness arguments identical. Avoid
concurrent builds when interpreting host-JVM measurements. This is a repeatable comparative
microbenchmark, not a substitute for an app startup trace or a release-build Macrobenchmark.

## Measurement on 2026-11-11

Vivo V2436, Android 16/API 36, 10 seconds of synthetic PCM (441,000 samples), five warmups
and 15 measured iterations per process, baseline commit `72597c6`:

| Run order | Baseline median | Candidate median | Reduction |
| --- | ---: | ---: | ---: |
| Baseline, candidate | 83.413 ms | 71.358 ms | 14.5% |
| Candidate, baseline | 83.309 ms | 71.420 ms | 14.3% |

Earlier exploratory runs at a different device operating state measured roughly 122 ms and
105 ms respectively, with the same approximately 14% relative improvement. Absolute timing
varies with runtime/device conditions; compare only paired runs using the same harness build.

Distinct delivered output buffers fell from 862 to 2 for the same workload. This count is
specific to output buffers, not total allocations. The desktop-JVM captured baseline and
candidate outputs were bit-identical for all 441,000 samples: maximum and RMS error 0.
The same captured-output comparison on Android was also bit-identical for all 441,000
samples, with maximum and RMS error 0 and matching energy (`1.986504688e+12`).

The Java FFT plan is immutable and shared, precomputes actual swap pairs, removes the
forward/inverse branch from the inner loop, and handles unit-twiddle butterflies without
multiplication. The gain comes from these implementation changes and buffer reuse; this
does not establish that Java is inherently faster than Kotlin. A C/C++/JNI dependency was
not needed for this measured improvement.
