package com.ember.simpleplex

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.allowHardware

class SimplexApp : Application(), SingletonImageLoader.Factory {
    /**
     * Pictures in ordinary memory rather than the GPU's: on the Sony each GPU buffer took 40-216 ms to
     * allocate, holding up the window's own, and blurring a backdrop meant copying it back on the
     * render thread. Uploading an ordinary picture when it's first drawn takes a millisecond or two.
     */
    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context).allowHardware(false).build()
}
