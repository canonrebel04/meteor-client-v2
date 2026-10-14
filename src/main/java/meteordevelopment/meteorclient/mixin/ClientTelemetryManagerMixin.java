/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.systems.modules.misc.NoTelemetry;
import net.minecraft.client.telemetry.ClientTelemetryManager;
import net.minecraft.client.telemetry.TelemetryEventSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientTelemetryManager.class)
public abstract class ClientTelemetryManagerMixin {
    @Inject(method = "createEventSender", at = @At("HEAD"), cancellable = true)
    private void meteor$disableTelemetry(CallbackInfoReturnable<TelemetryEventSender> cir) {
        if (NoTelemetry.shouldBlock()) cir.setReturnValue(TelemetryEventSender.DISABLED);
    }
}
