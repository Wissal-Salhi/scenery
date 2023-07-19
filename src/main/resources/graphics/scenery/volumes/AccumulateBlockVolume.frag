// sceneGraphVisibility should be in main BDVVolume.frag but doing per
// volume uniforms there is wonky and doing them here in a shader segment works better
uniform int sceneGraphVisibility;

vis = vis && bool(sceneGraphVisibility);
if (vis && step > localNear && step < localFar)
{
    vec4 x = sampleVolume(wpos, volumeCache, cacheSize, blockSize, paddedBlockSize, cachePadOffset);
    float newAlpha = x.a;
    vec3 newColor = x.rgb;

    v.rgb = v.rgb + (1.0f - v.a) * newColor * newAlpha;
    v.a = v.a + (1.0f - v.a) * newAlpha;

    if(v.a >= 1.0f) {
        break;
    }
}
