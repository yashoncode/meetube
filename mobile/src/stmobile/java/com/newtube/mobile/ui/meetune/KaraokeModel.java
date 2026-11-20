package com.newtube.mobile.ui.meetune;

/** One worker inference: the complex vocal spectrum of one chunk. */
@FunctionalInterface
public interface KaraokeModel {
    float[] run(float[] input, int frames) throws Exception;
}
