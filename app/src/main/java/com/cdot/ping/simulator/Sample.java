package com.cdot.ping.simulator;

class Sample {
    // metres to feet
    private static final double m2ft = 3.2808399;

    double latitude; // degrees
    double longitude; // degrees
    long time; // ms
    float depth; // m
    float strength; // %
    float fishDepth; // m
    float fishStrength; // %
    float battery; // %
    float temperature; // C

    /**
     * @param lat latitude degress
     * @param lon longitude degrees
     * @param tim time milliseconds
     * @param dep depth metres
     * @param stren strength percent
     * @param fd fishdepth metres
     * @param fs fish strength percent
     * @param batt battery percent
     * @param temp temperature celcius
     */
    Sample(double lat, double lon, long tim, float dep, float stren, float fd, float fs, float batt, float temp) {
        latitude = lat;
        longitude = lon;
        time = tim;
        depth = dep;
        strength = stren;
        fishDepth = fd;
        fishStrength = fs;
        temperature = temp;
        battery = batt;
    }

    double depthFt() { return depth * m2ft; }
    double fishDepthFt() { return fishDepth * m2ft; }
    double tempF() { return 9 * temperature / 5.0 + 32; }
}
