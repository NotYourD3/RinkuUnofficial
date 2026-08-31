package de.keksuccino.rinku.mixins;

import de.keksuccino.rinku.Rinku;
import net.minecraft.client.renderer.EntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public class MixinGameRenderer {

    @Inject(method = "updateCameraAndRender", at = @At("HEAD"))
    public void head_render_RINKU(float partialTicks, CallbackInfo info) {
        if (Rinku.isInitialized()) {
            Rinku.getApp().getHandle().N_DoMessageLoopWork();
        }
    }

}
