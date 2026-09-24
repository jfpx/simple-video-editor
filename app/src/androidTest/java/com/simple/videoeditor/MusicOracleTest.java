package com.simple.videoeditor;

import android.test.AndroidTestCase;
import com.simple.videoeditor.oracle.MusicOracleContract;
import java.io.File;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MusicOracleTest extends AndroidTestCase {
    public void testIndependentReferenceAndAudioNegatives() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), MusicOracleContract.create());
        File[] controls = {
                verifier.prepareMusicAsset(MusicOracleContract.REFERENCE),
                verifier.prepareFixture(),
                verifier.prepareMusicAsset(MusicOracleContract.NO_LOOP)
        };
        for (int i = 0; i < controls.length; i++) {
            JSONObject report = verifier.verify(MusicOracleContract.CASE_ID, controls[i]);
            String expected = i == 0 ? "PASS" : "FAIL";
            assertEquals(report.toString(), expected, report.getString("status"));
            if (i > 0) {
                boolean rejectedAudio = false;
                JSONArray checks = report.getJSONArray("checks");
                for (int j = 0; j < checks.length(); j++) {
                    JSONObject check = checks.getJSONObject(j);
                    if (check.getString("assertion").startsWith("audio.window_")
                            && !check.getBoolean("passed")) rejectedAudio = true;
                }
                assertTrue("Wrong audio must fail content assertions", rejectedAudio);
            }
            report.put("export_codecs", "Frozen control; no production export")
                    .put("reference", new JSONObject().put("version", "music-loop-1"));
            assertTrue(SelfTestRunner.formatCaseResult(report).startsWith(expected + " music_loop\n"));
        }
    }
}
