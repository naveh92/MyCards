package com.mycards.data;

import android.content.Context;

import com.mycards.data.source.AssetLoader;

import java.io.IOException;
import java.io.InputStream;

/** Bridges the provider layer's {@link AssetLoader} to Android's AssetManager. */
public class AndroidAssetLoader implements AssetLoader {

    private final Context appContext;

    public AndroidAssetLoader(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Override
    public InputStream open(String path) throws IOException {
        return appContext.getAssets().open(path);
    }
}
