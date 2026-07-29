package com.mycards.data.source;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens files bundled in the APK.
 *
 * <p>An interface rather than a direct {@code AssetManager} dependency so provider logic
 * stays testable on a plain JVM.
 */
public interface AssetLoader {
    InputStream open(String path) throws IOException;
}
