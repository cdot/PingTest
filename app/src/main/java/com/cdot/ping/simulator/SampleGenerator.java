package com.cdot.ping.simulator;

public interface SampleGenerator {
    Sample getSample();
    void configure(int sensitivity, int noise, int range);
}
