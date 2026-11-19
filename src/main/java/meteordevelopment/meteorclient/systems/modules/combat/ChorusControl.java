/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class ChorusControl extends Module {
    public enum AutoDefendMode {
        None,
        Surround,
        Burrow
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Keybind> confirmKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("confirm-key")
        .description("Key to confirm and apply the held chorus teleport.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Boolean> confirmOnSneak = sgGeneral.add(new BoolSetting.Builder()
        .name("confirm-on-sneak")
        .description("Confirms the held chorus teleport when pressing sneak.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> safeGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("safe-guard")
        .description("Evaluates landing hazards (void, lava/fire, suffocation, crystals) and colors preview.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoConfirmIfSafe = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-confirm-if-safe")
        .description("Automatically confirms immediately when SafeGuard marks the chorus landing safe.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> maxCrystalDamage = sgGeneral.add(new DoubleSetting.Builder()
        .name("max-crystal-damage")
        .description("Maximum nearby crystal damage allowed at landing before SafeGuard flags unsafe.")
        .defaultValue(8.0)
        .min(0.0)
        .sliderMax(20.0)
        .visible(safeGuard::get)
        .build()
    );

    private final Setting<Integer> maxHoldMs = sgGeneral.add(new IntSetting.Builder()
        .name("max-hold-ms")
        .description("Automatically releases the held teleport after this many milliseconds to avoid server kick.")
        .defaultValue(4000)
        .range(500, 15000)
        .sliderRange(500, 10000)
        .build()
    );

    private final Setting<AutoDefendMode> autoDefend = sgGeneral.add(new EnumSetting.Builder<AutoDefendMode>()
        .name("auto-defend")
        .description("Automatically enables Surround or Burrow right after confirming chorus teleport.")
        .defaultValue(AutoDefendMode.Surround)
        .build()
    );

    // Render

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the predicted chorus landing hitbox.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the landing preview box is rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> safeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-side-color")
        .description("Side color when SafeGuard considers the landing safe.")
        .defaultValue(new SettingColor(40, 230, 90, 45))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> safeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("safe-line-color")
        .description("Line color when SafeGuard considers the landing safe.")
        .defaultValue(new SettingColor(40, 230, 90, 220))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> unsafeSideColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-side-color")
        .description("Side color when SafeGuard detects a hazard at the landing.")
        .defaultValue(new SettingColor(255, 50, 50, 50))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> unsafeLineColor = sgRender.add(new ColorSetting.Builder()
        .name("unsafe-line-color")
        .description("Line color when SafeGuard detects a hazard at the landing.")
        .defaultValue(new SettingColor(255, 50, 50, 220))
        .visible(render::get)
        .build()
    );

    private record PendingTeleport(
        ClientboundPlayerPositionPacket packet,
        PositionMoveRotation abs,
        long receivedAtMs,
        boolean safe,
        String hazardReason
    ) {}

    private record PendingRaw(ClientboundPlayerPositionPacket packet, long receivedAtMs) {}

    private ItemStack chorusStack;

    private volatile PendingRaw pendingRaw;
    private volatile PendingTeleport pendingTeleport;
    private volatile long chorusWindowUntilMs;
    private boolean wasOnChorusCooldown;
    private volatile boolean flushing;

    public ChorusControl() {
        super(Categories.Combat, "chorus-control", "Holds Chorus Fruit teleport packets for 3D preview, SafeGuard hazard checks, and auto-Surround/Burrow.");
    }

    @Override
    public void onActivate() {
        pendingRaw = null;
        pendingTeleport = null;
        chorusWindowUntilMs = 0L;
        wasOnChorusCooldown = false;
        flushing = false;
    }

    @Override
    public void onDeactivate() {
        if (pendingRaw != null || pendingTeleport != null) {
            confirmTeleport();
        }
        chorusWindowUntilMs = 0L;
        wasOnChorusCooldown = false;
        flushing = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (!PlayerUtils.isAlive() || mc.level == null) return;

        long now = System.currentTimeMillis();

        if (mc.player.isUsingItem() && mc.player.getUseItem().is(Items.CHORUS_FRUIT)) {
            chorusWindowUntilMs = now + 2000L;
        }

        if (chorusStack == null) chorusStack = new ItemStack(Items.CHORUS_FRUIT);
        boolean onCooldown = mc.player.getCooldowns().isOnCooldown(chorusStack);
        if (onCooldown && !wasOnChorusCooldown) {
            chorusWindowUntilMs = now + 1500L;
        }
        wasOnChorusCooldown = onCooldown;

        PendingRaw raw = pendingRaw;
        if (raw != null && pendingTeleport == null) {
            pendingRaw = null;
            PositionMoveRotation abs = PositionMoveRotation.calculateAbsolute(
                PositionMoveRotation.of(mc.player),
                raw.packet.change(),
                raw.packet.relatives()
            );
            String hazard = safeGuard.get() ? evaluateHazard(abs.position()) : null;
            boolean safe = hazard == null;
            pendingTeleport = new PendingTeleport(raw.packet, abs, raw.receivedAtMs, safe, hazard == null ? "" : hazard);

            if (safe) {
                info("Chorus landing ready (safe).");
            } else {
                warning("Chorus SafeGuard warning: %s", hazard);
            }
        }

        PendingTeleport pt = pendingTeleport;
        if (pt != null) {
            boolean confirmPressed = confirmKey.get().isSet() && confirmKey.get().isPressed();
            boolean sneakConfirm = confirmOnSneak.get() && mc.player.isShiftKeyDown();
            boolean timeoutConfirm = maxHoldMs.get() > 0 && (now - pt.receivedAtMs) >= maxHoldMs.get();
            boolean safeConfirm = autoConfirmIfSafe.get() && pt.safe;

            if (confirmPressed || sneakConfirm || timeoutConfirm || safeConfirm) {
                confirmTeleport();
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (flushing) return;

        if (event.packet instanceof ServerboundUseItemPacket useItem) {
            if (mc.player != null && mc.player.getItemInHand(useItem.hand()).is(Items.CHORUS_FRUIT)) {
                chorusWindowUntilMs = System.currentTimeMillis() + 2500L;
            }
            return;
        }

        PendingRaw raw = pendingRaw;
        PendingTeleport pt = pendingTeleport;
        if (raw == null && pt == null) return;

        int heldId = pt != null ? pt.packet.id() : raw.packet.id();
        if (event.packet instanceof ServerboundMovePlayerPacket) {
            event.cancel();
        } else if (event.packet instanceof ServerboundAcceptTeleportationPacket accept && accept.id() == heldId) {
            event.cancel();
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.level == null) return;
        if (!(event.packet instanceof ClientboundPlayerPositionPacket packet)) return;
        if (pendingRaw != null || pendingTeleport != null) return;
        if (System.currentTimeMillis() > chorusWindowUntilMs) return;

        event.cancel();
        pendingRaw = new PendingRaw(packet, System.currentTimeMillis());
    }

    private void confirmTeleport() {
        PendingTeleport pt = pendingTeleport;
        PendingRaw raw = pendingRaw;
        ClientboundPlayerPositionPacket packet = pt != null ? pt.packet : (raw != null ? raw.packet : null);
        pendingTeleport = null;
        pendingRaw = null;

        if (packet == null || mc.player == null || mc.level == null || mc.getConnection() == null) {
            return;
        }

        flushing = true;
        try {
            mc.getConnection().handleMovePlayer(packet);
        } finally {
            flushing = false;
        }

        switch (autoDefend.get()) {
            case Surround -> {
                Surround surround = Modules.get().get(Surround.class);
                if (surround != null && !surround.isActive()) surround.enable();
            }
            case Burrow -> {
                Burrow burrow = Modules.get().get(Burrow.class);
                if (burrow != null && !burrow.isActive()) burrow.enable();
            }
            case None -> {}
        }
    }

    private String evaluateHazard(Vec3 pos) {
        if (mc.level == null || mc.player == null) return null;

        if (pos.y <= mc.level.getMinY() + 1) {
            return "Void";
        }

        BlockPos feet = BlockPos.containing(pos);
        BlockPos head = feet.above();
        BlockPos below = feet.below();

        if (isHazardBlock(mc.level.getBlockState(feet))
            || isHazardBlock(mc.level.getBlockState(head))
            || isHazardBlock(mc.level.getBlockState(below))) {
            return "Fire/Lava";
        }

        if (!mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
            || !mc.level.getBlockState(head).getCollisionShape(mc.level, head).isEmpty()) {
            return "Suffocation";
        }

        AABB previewBox = mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(pos);
        double crystalLimit = Math.min(maxCrystalDamage.get(), PlayerUtils.getTotalHealth() - 1.0);
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof EndCrystal)) continue;
            if (entity.position().distanceToSqr(pos) > 144.0) continue;
            float dmg = DamageUtils.crystalDamage(mc.player, pos, previewBox, entity.position(), DamageUtils.HIT_FACTORY);
            if (dmg >= crystalLimit) {
                return "Crystal Threat";
            }
        }

        return null;
    }

    private boolean isHazardBlock(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        PendingTeleport pt = pendingTeleport;
        if (!render.get() || pt == null || mc.player == null) return;

        AABB box = mc.player.getDimensions(mc.player.getPose()).makeBoundingBox(pt.abs.position());
        SettingColor side = pt.safe ? safeSideColor.get() : unsafeSideColor.get();
        SettingColor line = pt.safe ? safeLineColor.get() : unsafeLineColor.get();
        event.renderer.box(box, side, line, shapeMode.get(), 0);
    }

    @Override
    public String getInfoString() {
        PendingTeleport pt = pendingTeleport;
        if (pt == null) return null;
        return pt.safe ? "Safe" : pt.hazardReason;
    }
}
