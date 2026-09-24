package android.content.res;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Read-only filesystem assets for the real OracleVerifier integrity checks. */
public final class AssetManager {
    private final File root;

    public AssetManager(File root) {
        this.root = root;
    }

    public InputStream open(String name) throws IOException {
        File file = new File(root, name.replace('/', File.separatorChar)).getCanonicalFile();
        if (!file.toPath().startsWith(root.getCanonicalFile().toPath())) {
            throw new IOException("Asset outside root: " + name);
        }
        return new FileInputStream(file);
    }
}
