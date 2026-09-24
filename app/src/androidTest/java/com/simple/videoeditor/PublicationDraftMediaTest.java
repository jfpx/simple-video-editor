package com.simple.videoeditor;

import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.AndroidTestCase;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;

@androidx.media3.common.util.UnstableApi
public final class PublicationDraftMediaTest extends AndroidTestCase {
    public void testRealCoverReadGrantShareAndDraftOnlyDelete() throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "draft-cover-test.png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        Uri uri = getContext().getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        JSONObject draft = PublicationDraftStore.create("cover");
        PublicationDraftStore store = new PublicationDraftStore(getContext());
        boolean deleted = false;
        try {
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            try (OutputStream output = getContext().getContentResolver().openOutputStream(uri)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { bitmap.recycle(); }
            PublicationDraftMedia.validateCover(getContext(), uri);
            draft.put("coverUri", uri.toString());
            store.save(draft);
            Intent share = PublicationDraftMedia.share(getContext(), draft, true);
            assertEquals("image/png", share.getType());
            assertEquals(uri, share.getParcelableExtra(Intent.EXTRA_STREAM));
            assertEquals(uri, share.getClipData().getItemAt(0).getUri());
            assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            store.delete(draft.getString("id"));
            try (InputStream input = getContext().getContentResolver().openInputStream(uri)) { assertTrue(input.read() >= 0); }
            getContext().getContentResolver().delete(uri, null, null);
            deleted = true;
            try { PublicationDraftMedia.share(getContext(), draft, true); fail(); }
            catch (Exception expected) {}
            try { PublicationDraftMedia.validateCover(getContext(), Uri.parse("file:///data/local/not-allowed.png")); fail(); }
            catch (java.io.IOException expected) {}
        } finally {
            if (!deleted) getContext().getContentResolver().delete(uri, null, null);
            store.delete(draft.getString("id"));
        }
    }

    public void testImmutableConfigCreditsAndSixActualTracks() throws Exception {
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(getContext());
        assertEquals(118, catalog.tracks.size());
        for (OfflineMusicCatalog.Track track : catalog.tracks) {
            EditConfig config = new EditConfig.Builder(Uri.EMPTY, 1000)
                    .backgroundMusic(new BackgroundMusic(true, track.id, .75f, true)).build();
            String credits = PublicationExportSnapshot.credits(getContext(), config);
            assertTrue(credits.contains(track.author));
            assertTrue(credits.contains(track.sourceUrl));
            assertTrue(credits.contains("CC0"));
            assertTrue(credits.contains(track.id));
        }
        EditConfig imported = new EditConfig.Builder(Uri.EMPTY, 1000).replacementMusic(Uri.parse("content://audio/1")).build();
        assertTrue(PublicationExportSnapshot.credits(getContext(), imported).contains("rights unknown"));
        assertFalse(PublicationExportSnapshot.credits(getContext(), imported).contains("CC0"));
    }
}
