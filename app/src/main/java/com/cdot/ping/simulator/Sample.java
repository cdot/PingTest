package com.cdot.ping.simulator;

class Sample {
    double latitude;
    double longitude;
    double time;
    double depth;
    int strength;
    double fishDepth;
    int fishStrength;
    double temperature;

    Sample(double lat, double lon, double t, double dep, int stren, double fd, int fs) {
        latitude = lat;
        longitude = lon;
        time = t;
        depth = -dep;
        strength = stren;
        fishDepth = -fd;
        fishStrength = fs;
        temperature = 30 - 25 * depth / 36;
    }
}
