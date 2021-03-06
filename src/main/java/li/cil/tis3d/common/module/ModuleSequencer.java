package li.cil.tis3d.common.module;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import li.cil.tis3d.api.machine.Casing;
import li.cil.tis3d.api.machine.Face;
import li.cil.tis3d.api.machine.Pipe;
import li.cil.tis3d.api.machine.Port;
import li.cil.tis3d.api.prefab.module.AbstractModuleRotatable;
import li.cil.tis3d.api.util.RenderUtil;
import li.cil.tis3d.client.renderer.TextureLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

public final class ModuleSequencer extends AbstractModuleRotatable {
    // --------------------------------------------------------------------- //
    // Persisted data

    private final boolean[][] configuration = new boolean[COL_COUNT][ROW_COUNT];
    private int position = -1;
    private int delay = 4;
    private int stepsRemaining = 0;

    // --------------------------------------------------------------------- //
    // Computed data

    // NBT data names.
    private static final String TAG_CONFIGURATION = "configuration";
    private static final String TAG_POSITION = "position";
    private static final String TAG_DELAY = "delay";
    private static final String TAG_STEPS_REMAINING = "stepsRemaining";

    // Data packet types.
    private static final byte DATA_TYPE_CONFIGURATION = 0;
    private static final byte DATA_TYPE_POSITION = 1;

    private static final int COL_COUNT = 8;
    private static final int ROW_COUNT = 8;

    // Rendering info.
    private static final float CELLS_U0 = 5 / 32f;
    private static final float CELLS_V0 = 5 / 32f;
    private static final float CELLS_SIZE_U = 1 / 32f;
    private static final float CELLS_SIZE_V = 1 / 32f;
    private static final float CELLS_STEP_U = CELLS_SIZE_U + 2 / 32f;
    private static final float CELLS_STEP_V = CELLS_SIZE_V + 2 / 32f;
    private static final float CELLS_OUTER_U0 = 8 / 64f;
    private static final float CELLS_OUTER_V0 = 8 / 64f;
    private static final float CELLS_OUTER_SIZE_U = 6 / 64f;
    private static final float CELLS_OUTER_SIZE_V = 6 / 64f;
    private static final float CELLS_OUTER_STEP_U = CELLS_OUTER_SIZE_U;
    private static final float CELLS_OUTER_STEP_V = CELLS_OUTER_SIZE_V;
    private static final float BAR_U0 = 8 / 64f;
    private static final float BAR_V0 = 8 / 64f;
    private static final float BAR_SIZE_U = 6 / 64f;
    private static final float BAR_SIZE_V = 48 / 64f;
    private static final float BAR_STEP_U = BAR_SIZE_U;

    private short output;

    // --------------------------------------------------------------------- //

    public ModuleSequencer(final Casing casing, final Face face) {
        super(casing, face);
    }

    // --------------------------------------------------------------------- //
    // Module

    @Override
    public void step() {
        stepInput();
        stepOutput();
    }

    @Override
    public void onEnabled() {
        super.onEnabled();
    }

    @Override
    public void onDisabled() {
        position = -1;
        stepsRemaining = 0;
    }

    @Override
    public boolean onActivate(final EntityPlayer player, final EnumHand hand, final float hitX, final float hitY, final float hitZ) {
        if (player.isSneaking()) {
            return false;
        }

        // Handle input on the client and send it to the server for higher
        // hit position resolution (MC sends this to the server at a super
        // low resolution for some reason).
        final World world = getCasing().getCasingWorld();
        if (world.isRemote) {
            final Vec3d uv = hitToUV(new Vec3d(hitX, hitY, hitZ));
            final int col = uvToCol((float) uv.x);
            final int row = uvToRow((float) uv.y);
            if (col >= 0 && row >= 0) {
                configuration[col][row] = !configuration[col][row];
                sendConfiguration(Side.SERVER);
            }
        }

        return true;
    }

    @Override
    public void onData(final ByteBuf data) {
        if (getCasing().getCasingWorld().isRemote) {
            if (data.readBoolean()) {
                decodeConfiguration(data.readLong(), configuration);
            } else {
                position = data.readByte();
            }
        } else {
            decodeConfiguration(data.readLong(), configuration);
            sendConfiguration(Side.CLIENT);
        }
    }

    @SideOnly(Side.CLIENT)
    @Override
    public void render(final boolean enabled, final float partialTicks) {
        if (!isVisible()) {
            return;
        }

        rotateForRendering();
        RenderUtil.ignoreLighting();
        GlStateManager.enableBlend();

        GlStateManager.disableTexture2D();
        GlStateManager.depthMask(false);

        if (enabled) {
            // Draw bar in background indicating current position in sequence.
            final float barU0 = BAR_U0 + BAR_STEP_U * position;
            final float brightness = 0.75f + 0.25f * (delay == 0 ? 1 : (1 - (delay - stepsRemaining) / (float) delay));
            GlStateManager.color(0.2f, 0.3f, 0.35f, brightness);
            RenderUtil.drawUntexturedQuad(barU0, BAR_V0, BAR_SIZE_U, BAR_SIZE_V);
        }

        // Draw base grid of sequencer entries.
        GlStateManager.enableTexture2D();
        GlStateManager.color(1, 1, 1, enabled ? 1 : 0.5f);
        RenderUtil.drawQuad(RenderUtil.getSprite(TextureLoader.LOCATION_OVERLAY_MODULE_SEQUENCER));
        GlStateManager.disableTexture2D();

        GlStateManager.depthMask(true);

        final Minecraft mc = Minecraft.getMinecraft();
        if (mc != null && mc.player != null && mc.player.getDistanceSqToCenter(getCasing().getPosition()) < 64) {
            // Draw configuration of sequencer.
            GlStateManager.color(0.8f, 0.85f, 0.875f, enabled ? 1 : 0.5f);
            for (int col = 0; col < COL_COUNT; col++) {
                for (int row = 0; row < ROW_COUNT; row++) {
                    if (configuration[col][row]) {
                        final float u0 = CELLS_U0 + CELLS_STEP_U * col;
                        final float v0 = CELLS_V0 + CELLS_STEP_V * row;
                        RenderUtil.drawUntexturedQuad(u0, v0, CELLS_SIZE_U, CELLS_SIZE_V);
                    }
                }
            }
        }

        // Draw selection overlay for focused cell, if any.
        final Vec3d hitPos = getPlayerLookAt();
        if (hitPos != null) {
            final Vec3d uv = hitToUV(hitPos);
            final int col = uvToCol((float) uv.x);
            final int row = uvToRow((float) uv.y);
            if (col >= 0 && row >= 0) {
                GlStateManager.color(0.7f, 0.8f, 0.9f, 0.5f);
                final float u = CELLS_OUTER_U0 + col * CELLS_OUTER_STEP_U;
                final float v = CELLS_OUTER_V0 + row * CELLS_OUTER_STEP_V;
                RenderUtil.drawUntexturedQuad(u, v, CELLS_OUTER_SIZE_U, CELLS_OUTER_SIZE_V);
            }
        }

        GlStateManager.disableBlend();
    }

    @Override
    public void readFromNBT(final NBTTagCompound nbt) {
        super.readFromNBT(nbt);

        decodeConfiguration(nbt.getLong(TAG_CONFIGURATION), configuration);
        position = Math.min(Math.max(nbt.getInteger(TAG_POSITION), 0), COL_COUNT - 1);
        delay = Math.min(Math.max(nbt.getInteger(TAG_DELAY), 0), 0xFFFF);
        stepsRemaining = Math.min(Math.max(nbt.getInteger(TAG_STEPS_REMAINING), 0), 0xFFFF);

        initializeOutput();
    }

    @Override
    public void writeToNBT(final NBTTagCompound nbt) {
        super.writeToNBT(nbt);

        nbt.setLong(TAG_CONFIGURATION, encodeConfiguration(configuration));
        nbt.setInteger(TAG_POSITION, position);
        nbt.setInteger(TAG_DELAY, delay);
        nbt.setInteger(TAG_STEPS_REMAINING, stepsRemaining);
    }

    // --------------------------------------------------------------------- //

    private void stepOutput() {
        if (stepsRemaining-- <= 0) {
            stepsRemaining = delay;
            cancelWrite();
            position = (position + 1) % COL_COUNT;
            sendPosition();

            initializeOutput();
            for (final Port port : Port.VALUES) {
                final Pipe sendingPipe = getCasing().getSendingPipe(getFace(), port);
                if (!sendingPipe.isWriting()) {
                    sendingPipe.beginWrite(output);
                }
            }
        }
    }

    private void stepInput() {
        for (final Port port : Port.VALUES) {
            final Pipe receivingPipe = getCasing().getReceivingPipe(getFace(), port);
            if (!receivingPipe.isReading()) {
                receivingPipe.beginRead();
            }
            if (receivingPipe.canTransfer()) {
                delay = receivingPipe.read() & 0xFFFF;
            }
        }
    }

    private void sendConfiguration(final Side toSide) {
        final ByteBuf data = Unpooled.buffer();
        if (toSide == Side.CLIENT) {
            data.writeBoolean(true);
        }
        data.writeLong(encodeConfiguration(configuration));
        getCasing().sendData(getFace(), data, DATA_TYPE_CONFIGURATION);
    }

    private void sendPosition() {
        final ByteBuf data = Unpooled.buffer();
        data.writeBoolean(false);
        data.writeByte(position);
        getCasing().sendData(getFace(), data, DATA_TYPE_POSITION);
    }

    private void initializeOutput() {
        output = 0;
        for (int mask = 1, row = 0; row < ROW_COUNT; row++, mask <<= 1) {
            if (configuration[position][row]) {
                output |= mask;
            }
        }
    }

    private static long encodeConfiguration(final boolean[][] configuration) {
        long encodedConfiguration = 0L;
        long mask = 1;
        for (int col = 0; col < COL_COUNT; col++) {
            for (int row = 0; row < ROW_COUNT; row++, mask <<= 1L) {
                if (configuration[col][row]) {
                    encodedConfiguration |= mask;
                }
            }
        }
        return encodedConfiguration;
    }

    private static void decodeConfiguration(final long encodedConfiguration, final boolean[][] configuration) {
        long mask = 1;
        for (int col = 0; col < COL_COUNT; col++) {
            for (int row = 0; row < ROW_COUNT; row++, mask <<= 1L) {
                configuration[col][row] = (encodedConfiguration & mask) != 0;
            }
        }
    }

    private int uvToCol(final float u) {
        if (u < CELLS_OUTER_U0 || u > CELLS_OUTER_U0 + CELLS_OUTER_STEP_U * COL_COUNT) {
            return -1;
        }

        final float mappedU = (u - CELLS_OUTER_U0) / (CELLS_OUTER_STEP_U * COL_COUNT);
        return (int) (mappedU * COL_COUNT);
    }

    private int uvToRow(final float v) {
        if (v < CELLS_OUTER_V0 || v > CELLS_OUTER_V0 + CELLS_OUTER_STEP_V * ROW_COUNT) {
            return -1;
        }

        final float mappedV = (v - CELLS_OUTER_V0) / (CELLS_OUTER_STEP_V * ROW_COUNT);
        return (int) (mappedV * COL_COUNT);
    }
}
