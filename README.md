# CobbleCatchCombo

A Catch Combo system for Cobblemon inspired by Pokémon Let's Go and Pixelmon.

## Features

- **Catch Combo Tracking**: Catching the same Pokémon species consecutively builds a combo
- **Shiny Rate Boost**: Higher combos increase shiny encounter rates (configurable for chained species only or all Pokémon)
- **Guaranteed Perfect IVs**: Higher combos guarantee perfect IVs on spawned Pokémon
- **Visual Feedback**: Boss Bar and/or Action Bar display with progress tracking
- **Notifications**: Milestone achievements and combo break notifications
- **Full Localization**: All messages configurable via language files (en_us, ja_jp included)
- **Text Placeholder API Support**: External placeholders for integration with other mods
- **Persistent Storage**: SQLite database preserves combos across server restarts

## Requirements

- Minecraft 1.21.1
- Fabric Loader 0.16.9+
- Fabric API 0.110.5+
- Fabric Language Kotlin 1.12.3+
- Cobblemon 1.7.1+

## Configuration

Configuration file is located at `config/cobblecatchcombo/config.json`

### Combo Reset Conditions

| Condition | Default | Configurable |
|-----------|---------|--------------|
| Catch different species | Resets | Fixed |
| Player flees battle | Resets | Yes |
| Player dies | No reset | Yes |
| Logout/Server restart | No reset | Fixed |

### Shiny Boost Tiers (Default)

| Combo | Multiplier |
|-------|------------|
| 0-9 | 1.0x |
| 10-19 | 1.5x |
| 20-29 | 2.0x |
| 30-49 | 2.5x |
| 50-99 | 3.0x |
| 100+ | 4.0x |

### IV Boost Tiers (Default)

| Combo | Perfect IVs |
|-------|-------------|
| 0-9 | 0 |
| 10-29 | 1 |
| 30-49 | 2 |
| 50-99 | 3 |
| 100+ | 4 |

## Text Placeholder API Integration

Available placeholders for use with other mods:

| Placeholder | Description |
|-------------|-------------|
| `%cobblecatchcombo:combo_count%` | Current combo count |
| `%cobblecatchcombo:combo_species%` | Species name being chained |
| `%cobblecatchcombo:combo_species_id%` | Species ID (e.g., `cobblemon:eevee`) |
| `%cobblecatchcombo:shiny_multiplier%` | Current shiny rate multiplier |
| `%cobblecatchcombo:shiny_rate%` | Effective shiny rate denominator |
| `%cobblecatchcombo:perfect_ivs%` | Current guaranteed perfect IVs |
| `%cobblecatchcombo:next_tier%` | Combo count for next tier |
| `%cobblecatchcombo:next_tier_remaining%` | Combos remaining to next tier |
| `%cobblecatchcombo:current_tier%` | Current tier number |
| `%cobblecatchcombo:max_combo%` | Player's all-time max combo |
| `%cobblecatchcombo:has_combo%` | Whether player has active combo |

## Commands

| Command | Permission | Description |
|---------|------------|-------------|
| `/catchcombo` | Everyone | View your current combo status |
| `/catchcombo status` | Everyone | Same as above |
| `/catchcombo reload` | OP Level 2 | Reload configuration and language files |
| `/catchcombo reset` | OP Level 2 | Reset your current combo |
| `/combo` | Everyone | Alias for `/catchcombo` |

## Language Files

Language files are stored in `config/cobblecatchcombo/lang/`

Supported languages:
- `en_us.json` (English)
- `ja_jp.json` (Japanese)

You can add custom translations by creating new language files.

## Building

```bash
./gradlew build
```

The built jar will be in `build/libs/`

## License

MIT License - See [LICENSE](LICENSE) for details.

## Troubleshooting / API Compatibility

If you encounter compilation errors with Cobblemon's API, check the following:

### Event Names
The mod uses these Cobblemon events:
- `CobblemonEvents.POKEMON_CAPTURED` - When a Pokemon is caught
- `CobblemonEvents.POKEMON_ENTITY_SPAWN` - When a Pokemon spawns
- `CobblemonEvents.BATTLE_FLED` - When a player flees from battle

If event names have changed in newer Cobblemon versions, check `CobblemonEvents` object for current event names.

### Event Properties
- `PokemonCapturedEvent`: expects `.player` and `.pokemon` properties
- `PokemonEntitySpawnEvent`: expects `.entity` property (PokemonEntity)
- `BattleFledEvent`: expects `.player` property

### Priority Enum
Located at: `com.cobblemon.mod.common.api.Priority`
Values: `LOWEST`, `LOW`, `NORMAL`, `HIGH`, `HIGHEST`

### Reference Projects
- [Cobblemon Unchained](https://github.com/timinc-cobble/cobblemon-unchained-1.5-fabric) - Similar IV/Shiny boost functionality
- [Cobblemon Counter](https://github.com/timinc-cobble/cobblemon-counter-1.4-fabric) - Capture tracking

## Credits

- Inspired by Pokémon Let's Go Catch Combo system
- Inspired by Pixelmon's Catch Combo feature
- Built for [Cobblemon](https://cobblemon.com)
- Reference: [Cobblemon Unchained](https://modrinth.com/mod/cobblemon-unchained) by TimInc
