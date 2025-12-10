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

## Credits

- Inspired by Pokémon Let's Go Catch Combo system
- Inspired by Pixelmon's Catch Combo feature
- Built for [Cobblemon](https://cobblemon.com)
