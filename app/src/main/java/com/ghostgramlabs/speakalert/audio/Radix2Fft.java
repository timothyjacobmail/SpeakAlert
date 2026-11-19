package com.ghostgramlabs.speakalert.audio;

/**
 * Immutable FFT plan. Calls use only the caller's arrays, so one plan can be shared by
 * concurrent processors without locks or per-transform allocation.
 */
public final class Radix2Fft {
    private final int size;
    private final int[] swaps;
    private final float[] cosines;
    private final float[] forwardSines;
    private final float[] inverseSines;

    public Radix2Fft(int size) {
        if (size < 1 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a power of two");
        }
        this.size = size;
        int bits = Integer.numberOfTrailingZeros(size);
        int pairs = 0;
        for (int i = 0; i < size; i++) {
            int reversed = size == 1 ? 0 : Integer.reverse(i) >>> (32 - bits);
            if (reversed > i) pairs++;
        }
        swaps = new int[pairs * 2];
        int swap = 0;
        for (int i = 0; i < size; i++) {
            int reversed = size == 1 ? 0 : Integer.reverse(i) >>> (32 - bits);
            if (reversed > i) {
                swaps[swap++] = i;
                swaps[swap++] = reversed;
            }
        }
        cosines = new float[size / 2];
        forwardSines = new float[size / 2];
        inverseSines = new float[size / 2];
        for (int i = 0; i < size / 2; i++) {
            double angle = -2.0 * Math.PI * i / size;
            cosines[i] = (float) Math.cos(angle);
            forwardSines[i] = (float) Math.sin(angle);
            inverseSines[i] = -forwardSines[i];
        }
    }

    public void forward(float[] real, float[] imaginary) {
        transform(real, imaginary, forwardSines);
    }

    public void inverse(float[] real, float[] imaginary) {
        transform(real, imaginary, inverseSines);
        float scale = 1.0f / size;
        for (int i = 0; i < size; i++) {
            real[i] *= scale;
            imaginary[i] *= scale;
        }
    }

    private void transform(float[] real, float[] imaginary, float[] sines) {
        if (real == imaginary || real.length < size || imaginary.length < size) {
            throw new IllegalArgumentException("FFT requires separate arrays of at least the plan size");
        }
        // Only visit actual swaps, rather than branching over every sample on every frame.
        for (int k = 0; k < swaps.length; k += 2) {
            int i = swaps[k];
            int j = swaps[k + 1];
            float value = real[i]; real[i] = real[j]; real[j] = value;
            value = imaginary[i]; imaginary[i] = imaginary[j]; imaginary[j] = value;
        }
        // The entire first stage has a unit twiddle: no table loads or multiplication.
        for (int i = 0; i + 1 < size; i += 2) {
            float r = real[i + 1];
            float im = imaginary[i + 1];
            real[i + 1] = real[i] - r;
            imaginary[i + 1] = imaginary[i] - im;
            real[i] += r;
            imaginary[i] += im;
        }
        for (int length = 4; length <= size; length <<= 1) {
            int half = length / 2;
            int step = size / length;
            for (int start = 0; start < size; start += length) {
                int pair = start + half;
                float r = real[pair];
                float im = imaginary[pair];
                real[pair] = real[start] - r;
                imaginary[pair] = imaginary[start] - im;
                real[start] += r;
                imaginary[start] += im;
                int index = step;
                for (int offset = start + 1; offset < start + half; offset++) {
                    pair = offset + half;
                    float cosine = cosines[index];
                    float sine = sines[index];
                    r = real[pair] * cosine - imaginary[pair] * sine;
                    im = real[pair] * sine + imaginary[pair] * cosine;
                    real[pair] = real[offset] - r;
                    imaginary[pair] = imaginary[offset] - im;
                    real[offset] += r;
                    imaginary[offset] += im;
                    index += step;
                }
            }
            // Avoid overflow at the maximum positive power of two.
            if (length == size) break;
        }
    }
}
