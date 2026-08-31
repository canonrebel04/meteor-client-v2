package meteordevelopment.meteorclient.utils.render.postprocess;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.orbit.EventHandler;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Coordinates the per-entity post-process shaders (Chams, Entity Outline, Storage Outline).
 *
 * <p>Entity vertex submission happens from two mixin call sites in {@code LevelRendererMixin}:
 * the vanilla {@code addMainPass} outline-lambda anchor, and a fallback anchored at
 * {@code FeatureRenderDispatcher.renderAllFeatures} that fires under Iris (which redirects
 * entity rendering away from the vanilla outline path). The per-frame {@link #submittedThisFrame}
 * flag makes the two call sites mutually exclusive — whichever runs first submits, the other
 * becomes a no-op.
 */
public class PostProcessShaders {
    public static EntityShader CHAMS;
    public static EntityShader ENTITY_OUTLINE;
    public static PostProcessShader STORAGE_OUTLINE;

    /**
     * Per-frame guard: set by the first {@code submitEntityVertices*} call, cleared on
     * {@link Render2DEvent} (when the shaders actually composite their textures).
     */
    private static boolean submittedThisFrame;

    private PostProcessShaders() {}

    @PreInit
    public static void init() {
        CHAMS = new ChamsShader();
        ENTITY_OUTLINE = new EntityOutlineShader();
        STORAGE_OUTLINE = new StorageOutlineShader();

        MeteorClient.EVENT_BUS.subscribe(PostProcessShaders.class);
    }

    public static void beginRender() {
        CHAMS.clearTexture();
        ENTITY_OUTLINE.clearTexture();
        STORAGE_OUTLINE.clearTexture();
        submittedThisFrame = false;
    }

    public static void submitEntityVertices() {
        if (submittedThisFrame) return;
        submittedThisFrame = true;

        CHAMS.submitVertices();
        ENTITY_OUTLINE.submitVertices();
    }

    /**
     * Fallback submission point used when the vanilla outline-lambda anchor doesn't fire
     * (Iris active). Runs at FeatureRenderDispatcher.renderAllFeatures TAIL.
     */
    public static void submitEntityVerticesIrisFallback() {
        submitEntityVertices();
    }

    @EventHandler
    private static void onRender(Render2DEvent event) {
        CHAMS.render();
        ENTITY_OUTLINE.render();
        submittedThisFrame = false;
    }

    public static void onResized(int width, int height) {
        if (mc == null) return;

        CHAMS.onResized(width, height);
        ENTITY_OUTLINE.onResized(width, height);
        STORAGE_OUTLINE.onResized(width, height);
    }
}
