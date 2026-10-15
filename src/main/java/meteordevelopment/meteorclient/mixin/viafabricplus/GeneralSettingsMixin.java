/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin.viafabricplus;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.viaversion.viafabricplus.api.settings.impl.Orientation;
import com.viaversion.viafabricplus.settings.impl.GeneralSettingsImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GeneralSettingsImpl.class)
public abstract class GeneralSettingsMixin {
    // Moves the default of the multiplayer screen button (first RIGHT_TOP in the constructor) to the bottom right,
    // so it doesn't overlap Meteor's own buttons.
    @ModifyExpressionValue(method = "<init>", at = @At(value = "FIELD", target = "Lcom/viaversion/viafabricplus/api/settings/impl/Orientation;RIGHT_TOP:Lcom/viaversion/viafabricplus/api/settings/impl/Orientation;", ordinal = 0), remap = false)
    private Orientation modifyDefaultPosition(Orientation original) {
        return Orientation.RIGHT_BOTTOM;
    }
}
