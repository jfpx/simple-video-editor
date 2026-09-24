package android.content;

import android.content.res.AssetManager;
import java.io.File;

/** JVM-only substitute; never packaged into the app. */
public final class Context {
    private final AssetManager assets;
    private final File files;

    public Context(File assetRoot, File files) {
        this.assets = new AssetManager(assetRoot);
        this.files = files;
    }

    public Context getApplicationContext() { return this; }
    public AssetManager getAssets() { return assets; }
    public File getFilesDir() { return files; }
}
