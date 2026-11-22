package pl.kaktusiarka;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;

/**
 * /kaktusiarka [pietra]
 *
 * Buduje kaktusiarke na calym chunku, w ktorym stoisz, zaczynajac od wysokosci Twoich stop.
 * Jedno pietro (2 bloki wysokosci), w ukladzie szachownicy:
 *   y   : piasek (pola A)
 *   y+1 : kaktusy na piasku (pola A), doniczki pomiedzy nimi (pola B)
 *   y+2 : piasek na doniczkach (pola B) -> lamie kaktusy, ktore urosna, i jest podstawa nastepnego pietra
 * Kolejne pietro zamienia pola A i B miejscami.
 */
public class Kaktusiarka implements ModInitializer {

    private static final int PER_LAYER = 128; // polowa z 16x16

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(Commands.literal("kaktusiarka")
                        .executes(ctx -> build(ctx.getSource(), 1))
                        .then(Commands.argument("pietra", IntegerArgumentType.integer(1, 100))
                                .executes(ctx -> build(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "pietra"))))));
    }

    private static int build(CommandSourceStack source, int layers) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        ChunkPos chunk = player.chunkPosition();
        int minX = chunk.getMinBlockX();
        int minZ = chunk.getMinBlockZ();
        int baseY = player.getBlockY();
        int topY = baseY + 2 * layers; // ostatnia warstwa piasku

        // 1. Wysokosc swiata
        if (level.isOutsideBuildHeight(new BlockPos(minX, baseY, minZ))
                || level.isOutsideBuildHeight(new BlockPos(minX, topY + 1, minZ))) {
            source.sendFailure(Component.literal("Kaktusiarka nie zmiesci sie w limicie wysokosci swiata. Zmniejsz liczbe pieter."));
            return 0;
        }

        // 2. Wolne miejsce na calym chunku
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = baseY; y <= topY; y++) {
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    pos.set(minX + dx, y, minZ + dz);
                    if (!level.getBlockState(pos).canBeReplaced()) {
                        source.sendFailure(Component.literal("Miejsce zajete na " + pos.toShortString()
                                + ". Caly chunk od Twoich stop do Y=" + topY + " musi byc pusty."));
                        return 0;
                    }
                }
            }
        }

        // 3. Pierwsza warstwa piasku nie moze wisiec w powietrzu (spadlaby)
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                if (((dx + dz) & 1) != 0) continue;
                pos.set(minX + dx, baseY - 1, minZ + dz);
                if (FallingBlock.isFree(level.getBlockState(pos))) {
                    source.sendFailure(Component.literal("Pod spodem jest dziura na " + pos.toShortString()
                            + ". Stan na plaskim, pelnym podlozu."));
                    return 0;
                }
            }
        }

        // 4. Materialy
        int needSand = PER_LAYER * (layers + 1);
        int needCactus = PER_LAYER * layers;
        int needPots = PER_LAYER * layers;
        boolean creative = player.getAbilities().instabuild;
        Inventory inv = player.getInventory();

        if (!creative) {
            int sand = count(inv, Items.SAND);
            int cactus = count(inv, Items.CACTUS);
            int pots = count(inv, Items.FLOWER_POT);
            if (sand < needSand || cactus < needCactus || pots < needPots) {
                source.sendFailure(Component.literal("Brakuje materialow na " + layers + " pieter(a):\n"
                        + " Piasek: " + sand + "/" + needSand + "\n"
                        + " Kaktusy: " + cactus + "/" + needCactus + "\n"
                        + " Doniczki: " + pots + "/" + needPots));
                return 0;
            }
            take(inv, Items.SAND, needSand);
            take(inv, Items.CACTUS, needCactus);
            take(inv, Items.FLOWER_POT, needPots);
        }

        // 5. Budowa
        for (int layer = 0; layer < layers; layer++) {
            int y = baseY + 2 * layer;
            int parity = layer & 1; // pola z kaktusami w tym pietrze
            for (int dx = 0; dx < 16; dx++) {
                for (int dz = 0; dz < 16; dz++) {
                    int x = minX + dx;
                    int z = minZ + dz;
                    boolean cactusCell = ((dx + dz) & 1) == parity;
                    if (cactusCell) {
                        if (layer == 0) {
                            level.setBlock(pos.set(x, y, z), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                        }
                        level.setBlock(pos.set(x, y + 1, z), Blocks.CACTUS.defaultBlockState(), Block.UPDATE_ALL);
                    } else {
                        level.setBlock(pos.set(x, y + 1, z), Blocks.FLOWER_POT.defaultBlockState(), Block.UPDATE_ALL);
                        level.setBlock(pos.set(x, y + 2, z), Blocks.SAND.defaultBlockState(), Block.UPDATE_ALL);
                    }
                }
            }
        }

        // 6. Gracz na dach, zeby nie utknal w srodku
        player.teleportTo(minX + 8.5, topY + 1, minZ + 8.5);

        source.sendSuccess(() -> Component.literal("Zbudowano kaktusiarke: " + layers + " pieter(a), "
                + needCactus + " kaktusow"
                + (creative ? " (tryb kreatywny - bez zuzycia materialow)." : ".")), false);
        return 1;
    }

    private static int count(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) total += inv.getItem(i).getCount();
        }
        return total;
    }

    private static void take(Inventory inv, Item item, int amount) {
        ContainerHelper.clearOrCountMatchingItems(inv, stack -> stack.is(item), amount, false);
        inv.setChanged();
    }
}
