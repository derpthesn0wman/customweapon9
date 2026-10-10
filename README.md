# CustomWeapons

Config-driven custom bows/crossbows for Paper/Spigot 1.20+.

## Build
    mvn clean package
The jar appears at `target/CustomWeapons.jar`. Drop it in your server's `plugins/` folder.

## Commands
- `/weapons list`
- `/weapons give <weapon> [player]`   (customweapons.admin)
- `/weapons reload`                   (customweapons.admin)

## Adding a new weapon (no code)
Copy any block under `weapons:` in config.yml, give it a new id, change the
ability/options, then `/weapons reload`.

## Adding a new ability (code)
In `Abilities.java`, add a `register("MY_ABILITY", (plugin, shooter, target, options) -> { ... });`
then use `ability: MY_ABILITY` in config.yml.

## Player Tracker compass
- `/weapons tracker [player]` gives the compass.
- Right-click it to open a GUI of online players (paged) and click one to track them.
- Sneak + right-click clears the target.
- Works in every dimension (lodestone-compass trick). If the target is in another dimension,
  it points to their last known position in your current dimension.
- Settings are under `tracker:` in config.yml.

## Shadow Blade (dash_sword)
Netherite sword. `/weapons give dash_sword`
- Right-click: fling yourself in the direction you look (3s cooldown, ~20 blocks, no fall damage for 6s).
- Crouch + right-click: blindness (255) for 30s on everyone within 50 blocks (120s cooldown).
All numbers are in config.yml under `weapons: dash_sword:`.

## Launch Bow (launch_bow)
Shoot and you ride the arrow (no gravity). Crouch to hop off - the arrow is deleted. No fall damage for 8s after.
Arrows fly through entities and stick in blocks (you stay on it until you crouch).

## Warden Spear (netherite_spear)
Netherite spear (needs Minecraft 1.21.11+; falls back to a netherite sword on older servers).
Hold right-click for 5s to charge, then it fires a warden-style sonic boom along your view line.
Damage, range, charge time and cooldown are configurable. Slows your fall while held so you can use it midair.

The sonic boom is true damage by default (ignores armor); set `true-damage: false` to disable. Each charge stage plays a rising ding with a particle ring (`stages:`).

## Reaper Mace (upgrade_mace)
Mace that upgrades with kills (stored on the item):
- 5 kills: right-click dash (same as the dash sword)
- 10 kills: 20 hearts while the mace is in your inventory
- 15 kills: hits bypass shields
Every consecutive hit shows a particle ring + chime; the 3rd hit in a row calls thunder.
Needs Minecraft 1.21+ for the mace item (falls back to a netherite sword otherwise).

## The Scrambler (scrambler)
Trial key. Hit a player to shuffle their whole inventory (hotbar, main inventory and off-hand;
armor too if `include-armor: true`). 30 second cooldown. Needs Minecraft 1.21+ (tripwire hook on older servers).

## Visual effects
All effects use a shared toolkit (Fx.java). Set `effects.intensity` in config.yml to scale particle amounts
(0.5 = lighter, 2.0 = flashier). Particle types missing on your server version are skipped safely.

The mace dash has 2 charges: dash twice, then a 10 second cooldown (`charges`, `charge-window`, `charge-gap`, `cooldown` under `upgrade_mace: right-click:`).

Mace combo has its own cooldown (`combo: cooldown:`, default 8s) after the thunder hit before the next 3-hit cycle can start.

Mace combo hits must be at least `combo: hit-gap` seconds apart (default 0.8s) to count, so spam clicking doesn't rush the thunder.

## The Chainer (chain_axe) - a Netherite sword
- 15 fully charged hits on the same target chains them in place for 4 seconds. An action-bar meter (like the spear's) counts the stack.
- Crouch + hit two different people and they are linked by a red chain for 15 seconds: damage to one is also dealt to the other
  (their own armor still applies). The link cooldown (15s) shows as Minecraft's item cooldown.

## Custom textures (custom model data)
Every weapon in `config.yml` has a `custom-model-data:` number (1001, 1002, ...). A resource pack uses that number to
swap in your own texture. Change the numbers freely, or add `item-model: "namespace:path"` (Minecraft 1.21.2+).

- A starter resource pack for each Minecraft version is in `resourcepack-template/` (see the README.txt in that folder).
- After changing a weapon's name, lore or model in config.yml, hold the item and run `/weapons update` to refresh it
  (kills on the mace are kept). Items already in inventories don't change by themselves.
- Pack format numbers (verify on minecraft.wiki): 1.20.2=18, 1.20.3-1.20.4=22, 1.20.5-1.20.6=32, 1.21-1.21.1=34,
  1.21.2-1.21.3=42, 1.21.4=46, 1.21.5=55, 1.21.6=63, 1.21.7-1.21.8=64, 1.21.9-1.21.10=69, 1.21.11=75.

## The Sift (abilities, no weapon)
`SIFT_SEND` (on-hit) sends whoever you hit to the world `the_sift`; `SIFT_ENTER` (right-click) sends you there, and back
if you're already inside. No weapon uses them by default - add them to any weapon in config.yml (example in the file).
The world is created (or loaded if it exists) from the `sift:` section of config.yml.

## Key to the Sift (ominous_trial_key)
Ominous trial key (tripwire hook on servers older than 1.21). `/weapons give ominous_trial_key`
- Hit a player: sends them to `the_sift` (30 second cooldown).
- Right-click: sends you to `the_sift`; right-click again inside to come back.

Meter colours: the spear's charge meter is dark blue (`bar-color` under its options), the chain sword's meter is light grey/grey (`bar-color` / `bar-empty-color` under `chain:`). Any & colour codes work.
