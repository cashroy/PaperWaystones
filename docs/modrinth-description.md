<!-- Paste into Modrinth > Settings > Description once the project is approved.
     (Modrinth blocks description edits while a project is in review.) -->
**Paper Waystones** brings the waystone experience to Paper servers, completely server-side. Players don't need any mods: the plugin sends a small resource pack automatically, which gives every block and item its own look.

> Independent fan project inspired by BlayTheNinth's [Waystones](https://modrinth.com/mod/waystones) mod. Not affiliated with it, and no code or art is copied from it.

![All the blocks](https://cdn.modrinth.com/data/16iFCfUA/images/f37b4f20f0a58e4f1a3b93c863ddcab6a93276a4.png)

## Blocks
- **Waystones** in 10 styles (stone, sandy, mossy, deepslate, ender, black, nether, purpur, prismarine, muddy). Right-click to activate, right-click again to travel. Shift-right-click to rename, set visibility (activation / team / global) and add modifiers.
- **Sharestones** in 8 materials. Every sharestone of the same material links together, no activation needed.
- **Portstones**: one-way warps to your waystones or to a material's sharestones.
- **Warp Plates**: step on one to be sent to a linked plate. Works for players, mobs and dropped items.

## Items
- **Warp Stone** (plus 8 material-scoped versions): warp to any activated waystone. Uses durability.
- **Warp, Return, Unbound/Bound and Portal Scrolls**: single-use warps. Portal scrolls open a temporary portal anyone can walk through.
- **Twinbound Feather**: wave feathers at a friend to bind them, then warp to each other.
- **Epitaph**: leaves a one-use Fleeting Memorial where you died.
- **Dormant / Attuned Shards**: link warp plates.

## Finding waystones
- **Wild waystones** generate about every 25 chunks in the Overworld, Nether and End, on a small platform styled to the biome (mossy in jungles, sandy in deserts, blackstone in the Nether...).
- **Village waystones** appear in about half of villages (most of them on the `FREQUENT` setting).
- They only appear in **newly generated chunks**, so explore new land to find them. For areas you've already explored, an admin can add them with `/waystones place <x> <y> <z> <type> <name>`, e.g. `/waystones place ~ ~ ~ waystone/stone Spawn`.

![Finding and getting everything](https://cdn.modrinth.com/data/16iFCfUA/images/9003280069878f5fd2ac34ca0ea482ba0175b897.png)

## Crafting
Every block and item is craftable, and all recipes show in the in-game recipe book. A few things are made from other items instead:
- **Bound Scroll**: right-click a waystone, sharestone or warp plate with an Unbound Scroll.
- **Attuned Shard**: you get one when you place a warp plate, or put a Dormant Shard in a plate's menu.
- **Bound feathers**: two players hold right-click with their feathers while facing each other.
- **Fleeting Memorial**: die with an Epitaph in your inventory.

Items don't currently appear in loot chests.

![Block recipes](https://cdn.modrinth.com/data/16iFCfUA/images/62526fb746084c6a670cd81652b9e894e9ffc6f9.png)
![Item recipes](https://cdn.modrinth.com/data/16iFCfUA/images/c87f49f2e3194474ba2b7fc8855e943eddf854f1.png)

## Also
- XP costs per block travelled and across dimensions, cooldowns, warp modifiers (blaze powder, ink sac, feather, magma cream, milk, diamond potency...)
- Leashed mobs (and optionally pets) travel with you
- Favourites in the travel menu
- `/waystone` quick-warp (the mod's "inventory button"), plus admin commands under `/waystones`

## Setup
Drop the jar in `plugins/` and start the server. The plugin hosts its resource pack on port **8164**. Set `resource-pack.public-host` in `config.yml` to the address players connect with, or point `external-url` at your own copy.

**Beta:** this is a first release. Please report bugs on [GitHub](https://github.com/cashroy/PaperWaystones/issues).
