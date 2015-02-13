package com.nostra13.universalimageloader.cache.memory;

import android.support.v4.util.LruCache;
import com.nostra13.universalimageloader.core.decode.BaseImageDecoder.*;

public class BaseInfoCache extends LruCache<String, ImageFileInfo> {
    private static BaseInfoCache sInstance;

    public BaseInfoCache(int maxSize) {
        super(maxSize);
    }

    public static synchronized BaseInfoCache get() {
        if (sInstance == null) {
            sInstance = new BaseInfoCache(1024);
        }
        return sInstance;
    }
}
