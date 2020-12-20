package com.cdot.ping.simulator;

public class FlatlineSampleGenerator implements SampleGenerator{
    float maxDepth = 36.0f;
    long startTime = -1;

    public Sample getSample() {
        if (startTime < 0)
            startTime = System.currentTimeMillis();
        // We want one complete cycle - 2 pi radians - to correspond to 30 seconds
        // t in the range 0..30
        float t = ((System.currentTimeMillis() - startTime) / 1000.0f) % 30;
        // theta in the range 0..2pi
        double theta = t * (2 * Math.PI) / 30.0;
        // Describing an ellipse of 1 minute (1 nm) radius
        double lat = Math.sin(theta) / 60;
        double lon = Math.cos(theta) / 60;
        return new Sample(lat, lon, System.currentTimeMillis(), -0.01f, 0, 0, 0, 100, 15);
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
