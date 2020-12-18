package com.cdot.ping.simulator;

public class FlatlineSampleGenerator implements SampleGenerator{
    float maxDepth = 36.0f;
    public Sample getSample() {
        Sample s = new Sample(1, 1, System.currentTimeMillis(), maxDepth / 2, 50, 5, 25, 5, 50);
        return s;
    }

    public void configure(int sensitivity, int noise, int range) {
        switch (range) {
            case 0: maxDepth = 3.0f; break;
            case 1: maxDepth = 6.0f; break;
            case 2: maxDepth = 9.0f; break;
            case 3: maxDepth = 18.0f; break;
            case 4: maxDepth = 24.0f; break;
            default: maxDepth = 36.0f; break;
        }
    }
}
