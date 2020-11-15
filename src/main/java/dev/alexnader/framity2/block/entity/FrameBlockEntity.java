package dev.alexnader.framity2.block.entity;

import com.google.common.collect.Streams;
import dev.alexnader.framity2.block.frame.data.FrameData;
import dev.alexnader.framity2.block.frame.data.Sections;
import dev.alexnader.framity2.gui.FrameGuiDescription;
import dev.alexnader.framity2.items.SpecialItems;
import net.fabricmc.fabric.api.block.entity.BlockEntityClientSerializable;
import net.fabricmc.fabric.api.rendering.data.v1.RenderAttachmentBlockEntity;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.server.PlayerStream;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import com.mojang.datafixers.util.Pair;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static dev.alexnader.framity2.Framity2.OVERLAYS;
import static dev.alexnader.framity2.Framity2.SPECIAL_ITEMS;
import static dev.alexnader.framity2.util.GetItemBeforeEmptyUtil.getItemBeforeEmpty;
import static dev.alexnader.framity2.util.ValidQuery.checkIf;

public class FrameBlockEntity extends LockableContainerBlockEntity implements ExtendedScreenHandlerFactory, RenderAttachmentBlockEntity, BlockEntityClientSerializable {
    public FrameData data;

    public FrameBlockEntity(final BlockEntityType<?> type, final Sections sections) {
        super(type);

        data = new FrameData(sections);
    }

    public Sections sections() {
        return data.sections();
    }

    public Optional<ItemStack>[] items() {
        return data.items();
    }

    public List<Optional<ItemStack>> baseItems() {
        return Arrays.asList(data.items()).subList(data.sections().base().start(), data.sections().base().end());
    }

    public List<Optional<ItemStack>> overlayItems() {
        return Arrays.asList(data.items()).subList(data.sections().overlay().start(), data.sections().overlay().end());
    }

    public List<Optional<ItemStack>> specialItems() {
        return Arrays.asList(data.items()).subList(data.sections().special().start(), data.sections().special().end());
    }

    public Optional<BlockState>[] baseStates() {
        return data.baseStates();
    }

    public void copyFrom(final int slot, final ItemStack stack, final int count, final boolean take) {
        final ItemStack newStack = stack.copy();
        final int realCount = Math.min(count, stack.getCount());

        newStack.setCount(realCount);

        if (take) {
            stack.setCount(stack.getCount() - realCount);
        }

        this.setStack(slot, newStack);
    }

    @Override
    public int getMaxCountPerStack() {
        return 1;
    }

    @Override
    public boolean isValid(final int slot, final ItemStack stack) {
        switch (sections().findSectionIndexOf(slot)) {
        case Sections.BASE_INDEX:
            return checkIf(stack).isValidForBase(s -> Optional.of(s.getBlock().getDefaultState()), world, pos).isPresent();
        case Sections.OVERLAY_INDEX:
            return checkIf(stack).isValidForOverlay();
        case Sections.SPECIAL_INDEX:
            return checkIf(stack).isValidForSpecial();
        default:
            return false;
        }
    }

    private void beforeRemove(final int slot) {
        switch (sections().findSectionIndexOf(slot)) {
        case Sections.BASE_INDEX:
            baseStates()[sections().base().makeRelative(slot)] = Optional.empty();
            break;
        case Sections.OVERLAY_INDEX:
            break;
        case Sections.SPECIAL_INDEX:
            //noinspection ConstantConditions
            SPECIAL_ITEMS.MAP.get(getItemBeforeEmpty(getStack(slot))).onRemove(world, this);
            break;
        default:
            throw new IllegalArgumentException("Invalid slot: " + slot);
        }
    }

    @Override
    public ItemStack removeStack(final int slot, final int amount) {
        beforeRemove(slot);

        return Optional.of(slot)
            .filter(s -> sections().itemIndices().contains(s))
            .filter(s -> amount > 0)
            .flatMap(s -> items()[s])
            .map(orig -> new Pair<>(orig, orig.split(amount)))
            .map(pair -> {
                markDirty();
                if (pair.getFirst().isEmpty()) {
                    items()[slot] = Optional.empty();
                }
                return pair.getSecond();
            })
            .orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack removeStack(final int slot) {
        beforeRemove(slot);

        markDirty();

        final Optional<ItemStack> result = items()[slot];

        items()[slot] = Optional.empty();

        return result.orElse(ItemStack.EMPTY);
    }

    @Override
    public ItemStack getStack(final int slot) {
        return items()[slot].orElse(ItemStack.EMPTY);
    }

    @Override
    public int size() {
        return items().length;
    }

    @Override
    public boolean isEmpty() {
        return Arrays.stream(items()).noneMatch(Optional::isPresent);
    }

    @Override
    public boolean canPlayerUse(final PlayerEntity player) {
        return true;
    }

    @Override
    public void clear() {
        for (int i = 0, size = size(); i < size; i++) {
            items()[i] = Optional.empty();
        }
    }

    @Override
    public void setStack(final int slot, final ItemStack stack) {
        final int sectionIndex = sections().findSectionIndexOf(slot);

        final Runnable setStack = () -> {
            items()[slot] = Optional.of(stack);
            stack.setCount(Math.min(stack.getCount(), getMaxCountPerStack()));
            markDirty();
        };

        switch (sectionIndex) {
        case Sections.BASE_INDEX:
            setStack.run();
            final int baseSlot = sections().base().makeRelative(slot);
            baseStates()[baseSlot] = baseItems().get(baseSlot)
                .map(ItemStack::getItem)
                .filter(i -> i instanceof BlockItem)
                .map(i -> ((BlockItem) i).getBlock().getDefaultState());
            break;
        case Sections.SPECIAL_INDEX:
            final SpecialItems.SpecialItem old = SPECIAL_ITEMS.MAP.get(getItemBeforeEmpty(getStack(slot)));
            if (old != null && world != null) {
                old.onRemove(world, this);
            }

            setStack.run();

            final SpecialItems.SpecialItem _new = SPECIAL_ITEMS.MAP.get(getStack(slot).getItem());
            if (_new != null && world != null) {
                _new.onAdd(world, this);
            }
        default:
            setStack.run();
            break;
        }
    }

    @Override
    public void markDirty() {
        super.markDirty();

        final World world = this.world;
        if (world != null) {
            final BlockState state = world.getBlockState(pos);
            final Block block = state.getBlock();

            if (world.isClient) {
                MinecraftClient.getInstance().worldRenderer.updateBlock(world, pos, getCachedState(), state, 1);
            } else {
                sync();

                PlayerStream.watching(this).forEach(p -> ((ServerPlayerEntity) p).networkHandler.sendPacket(this.toUpdatePacket()));

                world.updateNeighborsAlways(pos.offset(Direction.UP), block);
            }
        }
    }

    @Override
    public Stream<Pair<Optional<BlockState>, Optional<Identifier>>> getRenderAttachmentData() {
        //noinspection UnstableApiUsage
        return Streams.zip(
            Arrays.stream(baseStates()),
            overlayItems().stream().map(i -> i.flatMap(OVERLAYS::getOverlayId)),
            Pair::new
        );
    }

    @Override
    public CompoundTag toTag(final CompoundTag tag) {
        toClientTag(tag);
        return super.toTag(tag);
    }

    @Override
    public void fromTag(final BlockState state, final CompoundTag tag) {
        fromClientTag(tag);
        super.fromTag(state, tag);
    }

    @Override
    public CompoundTag toClientTag(final CompoundTag tag) {
        tag.put("frameData", data.toTag());
        return tag;
    }

    @Override
    public void fromClientTag(final CompoundTag compoundTag) {
        data = FrameData.fromTag(compoundTag.getCompound("frameData"));
        this.markDirty();
    }

    @Override
    protected ScreenHandler createScreenHandler(final int syncId, final PlayerInventory playerInventory) {
        return new FrameGuiDescription(syncId, playerInventory, ScreenHandlerContext.create(world, pos));
    }

    @Override
    public void writeScreenOpeningData(final ServerPlayerEntity serverPlayerEntity, final PacketByteBuf packetByteBuf) {
        packetByteBuf.writeBlockPos(pos);
    }

    @Override
    protected Text getContainerName() {
        return new TranslatableText(getCachedState().getBlock().getTranslationKey());
    }
}
