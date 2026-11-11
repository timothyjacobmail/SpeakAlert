import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Standalone JVM/Android benchmark for the actual VoiceProcessor, with synthetic audio only.
 * Run baseline and candidate with the same runtime, arguments, and Kotlin stdlib classpath.
 * Reflection keeps this harness independent of either implementation's compiled classes.
 */
public final class VoiceProcessingBenchmark {
    private static volatile double blackhole;

    public static void main(String[] args) throws Exception {
        int seconds = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int iterations = args.length > 1 ? Integer.parseInt(args[1]) : 9;
        if (seconds < 1 || iterations < 3) throw new IllegalArgumentException("Use seconds >= 1 and iterations >= 3");
        short[] samples = input(seconds * 44100);
        Class<?> processorType = Class.forName("com.ghostgramlabs.speakalert.audio.VoiceProcessor");
        Class<?> analysisType = Class.forName("com.ghostgramlabs.speakalert.audio.VoiceProcessor$Analysis");
        Class<?> callbackType = Class.forName("kotlin.jvm.functions.Function2");
        Object unit = Class.forName("kotlin.Unit").getField("INSTANCE").get(null);
        Constructor<?> constructor = processorType.getConstructor(int.class);
        Method analyze = processorType.getMethod("analyze", short[].class, int.class);
        Method finishAnalysis = processorType.getMethod("finishAnalysis");
        Method gain = processorType.getMethod("normalizationGain", float.class, float.class);
        Method beginRender = processorType.getMethod("beginRender", analysisType, float.class);
        Method render = processorType.getMethod("render", short[].class, int.class, callbackType);
        Method finishRender = processorType.getMethod("finishRender", callbackType);
        Method peak = analysisType.getMethod("getPeak");
        Method floor = analysisType.getMethod("getNoiseFloor");

        class Run {
            long count;
            double energy;
            final Set<Object> buffers = Collections.newSetFromMap(new IdentityHashMap<>());
            void process(boolean inspect) throws Exception {
                count = 0;
                energy = 0;
                buffers.clear();
                Object processor = constructor.newInstance(44100);
                DataOutputStream captured = inspect && args.length > 2
                        ? new DataOutputStream(new BufferedOutputStream(new FileOutputStream(args[2]))) : null;
                short[] chunk = new short[4096];
                for (int offset = 0; offset < samples.length; offset += chunk.length) {
                    int length = Math.min(chunk.length, samples.length - offset);
                    System.arraycopy(samples, offset, chunk, 0, length);
                    analyze.invoke(processor, chunk, length);
                }
                Object analysis = finishAnalysis.invoke(processor);
                Float outputGain = (Float) gain.invoke(processor, peak.invoke(analysis), floor.invoke(analysis));
                beginRender.invoke(processor, analysis, outputGain == null ? 1f : outputGain);
                Object callback = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, values) -> {
                    if (!method.getName().equals("invoke")) throw new UnsupportedOperationException(method.getName());
                    float[] output = (float[]) values[0];
                    int length = (Integer) values[1];
                    if (inspect) buffers.add(output);
                    count += length;
                    for (int i = 0; i < length; i++) {
                        if (!Float.isFinite(output[i])) throw new AssertionError("Non-finite output");
                        energy += (double) output[i] * output[i];
                        if (captured != null) captured.writeFloat(output[i]);
                    }
                    return unit;
                });
                for (int offset = 0; offset < samples.length; offset += chunk.length) {
                    int length = Math.min(chunk.length, samples.length - offset);
                    System.arraycopy(samples, offset, chunk, 0, length);
                    render.invoke(processor, chunk, length, callback);
                }
                finishRender.invoke(processor, callback);
                if (captured != null) captured.close();
                if (count != samples.length) throw new AssertionError("Output sample count changed");
                blackhole = energy;
            }
        }
        Run run = new Run();
        for (int warmup = 0; warmup < 5; warmup++) run.process(false);
        long[] durations = new long[iterations];
        for (int iteration = 0; iteration < iterations; iteration++) {
            long started = System.nanoTime();
            run.process(false);
            durations[iteration] = System.nanoTime() - started;
        }
        Arrays.sort(durations);
        run.process(true);
        System.out.printf(Locale.US, "audio_seconds=%d iterations=%d median_ms=%.3f min_ms=%.3f p90_ms=%.3f samples=%d output_buffers=%d energy=%.9e%n",
                seconds, iterations, durations[iterations / 2] / 1e6, durations[0] / 1e6,
                durations[(int) Math.ceil(iterations * 0.9) - 1] / 1e6, run.count, run.buffers.size(), run.energy);
    }

    private static short[] input(int count) {
        short[] input = new short[count];
        int seed = 37;
        for (int i = 0; i < count; i++) {
            seed = seed * 1664525 + 1013904223;
            double noise = ((seed >>> 8) / (double) 0x1000000 - 0.5) * 700;
            double speech = (i % 22050 < 14500 ? 1 : 0) *
                    (3600 * Math.sin(2 * Math.PI * 330 * i / 44100) + 1100 * Math.sin(2 * Math.PI * 670 * i / 44100));
            input[i] = (short) (speech + noise);
        }
        return input;
    }
}
