package meteordevelopment.meteorclient.utils.render.postprocess;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import meteordevelopment.meteorclient.mixininterface.ILevelRenderer;
import meteordevelopment.meteorclient.utils.render.CustomOutlineVertexConsumerProvider;
import net.minecraft.world.entity.Entity;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public abstract class EntityShader extends PostProcessShader {
    public final CustomOutlineVertexConsumerProvider vertexConsumerProvider;

    @Override
    public boolean beginRender() {
        if (super.beginRender()) {
            RenderSystem.getDevice().createCommandEncoder().clearColorTexture(framebuffer.getColorAttachment(), 0);
            return true;
        }

        return false;
    }

    protected EntityShader(RenderPipeline pipeline) {
        super(pipeline);
        this.vertexConsumerProvider = new CustomOutlineVertexConsumerProvider();
    }

    public abstract boolean shouldDraw(Entity entity);

    @Override
    protected void preDraw() {
        ((ILevelRenderer) mc.levelRenderer).meteor$pushEntityOutlineFramebuffer(framebuffer);
    }

    @Override
    protected void postDraw() {
        ((ILevelRenderer) mc.levelRenderer).meteor$popEntityOutlineFramebuffer();
    }

    public void submitVertices() {
        submitVertices(vertexConsumerProvider::draw);
    }
}
