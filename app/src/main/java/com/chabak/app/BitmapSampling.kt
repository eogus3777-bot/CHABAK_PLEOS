package com.chabak.app

internal fun bitmapSample(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Int {
    require(targetWidth > 0 && targetHeight > 0)
    var sample = 1
    while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight && sample < 1024) sample *= 2
    return sample
}
