package com.simple.videoeditor;

import android.content.Context;
import android.opengl.GLES20;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;

@UnstableApi
final class ColorAdjustmentEffect implements GlEffect {
    private final ColorAdjustment adjustment;

    ColorAdjustmentEffect(ColorAdjustment adjustment) { this.adjustment = adjustment; }

    @Override public GlShaderProgram toGlShaderProgram(Context context, boolean useHdr)
            throws VideoFrameProcessingException {
        if (useHdr) throw new VideoFrameProcessingException("Color adjustment supports SDR only");
        return new Program(adjustment);
    }

    // Media3 1.5.1 DEFAULT passes electrical SDR to intermediate effects (its
    // Builder constructor, despite the setter's outdated "linear" Javadoc).
    // Keep the default unchanged: linearize only inside this main-only shader.
    private static final class Program extends BaseGlShaderProgram {
        private final GlProgram program;

        Program(ColorAdjustment adjustment) throws VideoFrameProcessingException {
            super(false, 1);
            try {
                program = new GlProgram(
                        "attribute vec4 aPosition; varying vec2 vTexCoord;"
                                + "void main(){gl_Position=aPosition;vTexCoord=aPosition.xy*.5+.5;}",
                        "precision highp float; uniform sampler2D uTexture; uniform mat4 uColor;"
                                + "varying vec2 vTexCoord;"
                                + "float dec(float e){return e<.0812?e/4.5:pow((e+.099)/1.099,1./.45);}"
                                + "float enc(float l){return l<.018?l*4.5:1.099*pow(l,.45)-.099;}"
                                + "void main(){vec4 p=texture2D(uTexture,vTexCoord);"
                                + "vec3 l=vec3(dec(p.r),dec(p.g),dec(p.b));"
                                + "l=clamp((uColor*vec4(l,1.)).rgb,0.,1.);"
                                + "gl_FragColor=vec4(enc(l.r),enc(l.g),enc(l.b),p.a);}");
                program.setBufferAttribute("aPosition",
                        new float[]{-1,-1,0,1, 1,-1,0,1, -1,1,0,1, 1,1,0,1}, 4);
                program.setFloatsUniform("uColor", adjustment.matrix());
            } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
        }

        @Override public Size configure(int width, int height) { return new Size(width, height); }

        @Override public void drawFrame(int texture, long timeUs) throws VideoFrameProcessingException {
            try {
                program.use();
                program.setSamplerTexIdUniform("uTexture", texture, 0);
                program.bindAttributesAndUniforms();
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                GlUtil.checkGlError();
            } catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e, timeUs); }
        }

        @Override public void release() throws VideoFrameProcessingException {
            try { super.release(); }
            finally {
                try { program.delete(); }
                catch (GlUtil.GlException e) { throw new VideoFrameProcessingException(e); }
            }
        }
    }
}
