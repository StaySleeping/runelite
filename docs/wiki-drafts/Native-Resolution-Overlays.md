<!--
  DRAFT wiki page for https://github.com/runelite/wiki
  Suggested page name: Native-Resolution-Overlays.md
  Suggested sidebar links from: Developer Guide, Stretched-Mode, GPU

  Status: draft accompanying the native resolution overlays feature.
  Remove this HTML comment when publishing to the wiki.
-->

# Native Resolution Overlays (draft)

When [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode) is enabled, the game UI is drawn at canvas resolution and then upscaled. Historically, RuneLite overlays shared that UI buffer, so world overlays (tile highlights, NPC outlines, etc.) were upscaled too and looked soft.

With stretched mode on, **builtin** RuneLite overlays draw into a display-resolution buffer and composite around the stretched UI, so world overlays stay sharp. Plugin Hub / sideloaded overlays stay on the legacy (soft) path until they opt in.

This page is for **core and Plugin Hub developers**. For player-facing stretched/GPU settings, see [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode) and [GPU](https://github.com/runelite/runelite/wiki/GPU).

## Player settings (context)

| Setting | Where | Meaning |
| --- | --- | --- |
| **Fixed overlay size** | Stretched Mode | Off (default): overlays grow with the stretched game (original behaviour). On: overlays keep original smaller size. Affects panels, infoboxes, DYNAMIC text/icons, etc. Not world tile geometry. |
| **Scale tooltips** | Mouse Tooltips | Whether menu-action tooltips follow stretch size (unless fixed overlay size is on) |

Use existing **Interface font**, **Infobox font**, and **Infobox size** to fine-tune readability.

## Rendering model

```
Scene (GPU / software)
    → under-UI native overlays   (ABOVE_SCENE, UNDER_WIDGETS)
    → game UI (bank, inventory, …)  [stretched]
    → above-UI native overlays   (ABOVE_WIDGETS, ALWAYS_ON_TOP, tooltips)
```

Layer ordering matches the classic `OverlayLayer` rules: default under-widget overlays stay **under** the bank; overlays promoted to above-widgets (e.g. after Alt-dragging to a free position) still draw on top.

### Two buffers

Core code uses `NativeOverlayBuffer` with passes `UNDER_UI` and `ABOVE_UI`. GPU composites under before `drawUi`, then above after. That preserves bank-over-infobox behaviour. Active whenever stretched mode is on.

### What “canvas space” means

Even when drawing into the native buffer, **coordinates stay in canvas space** (same values as `Perspective.getCanvasTextLocation`, `getCanvasTilePoly`, widget bounds, etc.). The renderer applies stretch scale on the `Graphics2D` so those coordinates land on the display-sized buffer.

## Plugin Hub / sideloaded plugins (opt-in)

Hub and sideloaded **world** overlays (tiles, outlines, custom `DYNAMIC` drawing) **default to the legacy canvas path**. They keep correct proportions (but stay soft when stretched) until they opt in. This avoids silently breaking external plugins.

**Infoboxes are already included.** Hub plugins that register `InfoBox`es via `InfoBoxManager` are drawn by the core `InfoBoxOverlay`, which always follows the native path + fixed overlay size setting. No hub opt-in is required for infoboxes.

Builtin (core) overlays default to the native path.

### Opting in (tiles, outlines, custom overlays)

Pass your `Plugin` into `Overlay`’s constructor (normal Hub practice), then:

```java
setPreferNativeResolution(true);
```

Only do this after you have verified drawing under stretch (see checklist below). Prefer submitting a PR to the hub plugin after testing with the author when possible.

`ModelOutlineRenderer` follows the **calling overlay’s** preference: hub overlays that have not opted in keep soft outlines; after opt-in, outlines go native with that overlay.

Detection: overlays whose plugin classloader is not the client classloader get `preferNativeResolution = false` automatically in `Overlay(Plugin)`.

## Drawing contracts

### Interface overlays (`TOP_LEFT`, `TOP_RIGHT`, panels, …)

After stretch scale, the renderer applies **panel content scale** from Stretched Mode’s fixed overlay size setting. Prefer `OverlayPanel` / panel components. Hit-testing and Alt drag bounds use visual size automatically for overlays on the native path.

### World / `DYNAMIC` overlays

- Positions: canvas coordinates (unchanged APIs).
- **Text / images**: follow the same fixed-overlay-size setting. Prefer `OverlayUtil` for images.
- **Polygons / strokes**: use stretch scale (sharp at display resolution). Prefer `OverlayUtil.renderPolygon`. If you set your own `BasicStroke`, remember user-space width is multiplied by stretch.

Use `OverlayUtil`:

| API | When to use |
| --- | --- |
| `renderImageLocation(Graphics2D, Point, BufferedImage)` | Point is top-left of the **full bitmap footprint** (e.g. `Perspective.getCanvasImageLocation` / actor canvas image helpers). Applies size factor and **centers** in that footprint |
| `getImageLayoutSize(Graphics2D, BufferedImage)` | Layout size in canvas space after size factor |
| `renderImageLocationExact(Graphics2D, Point, BufferedImage)` | Point is top-left of the **drawn** (layout-sized) sprite — use when packing icons next to text |

### Progress pies / timer pies

`ProgressPieComponent` follows the fixed overlay size setting. Arcs are antialiased into the native buffer.

### Icon + text packs

Wrong (reserves full bitmap width while the sprite draws smaller → gap / off-centre group):

```java
int w = icon.getWidth();
Point loc = new Point(textX - w / 2, textY - icon.getHeight() / 2);
graphics.drawImage(icon, loc.getX(), loc.getY(), null);
```

Right:

```java
Dimension size = OverlayUtil.getImageLayoutSize(graphics, icon);
Point loc = new Point(textX - size.width / 2, textY - size.height / 2);
OverlayUtil.renderImageLocationExact(graphics, loc, icon);
// advance text by size.width / 2 (or size.width), not icon.getWidth()
```

See core examples: `PlayerIndicatorsOverlay`, `PlayerInfoDropOverlay`.

### Widget overlays

Overlays that only reposition client widgets (XP tracker, health bar, etc.) are not sprite-scaled; they follow UI stretch. Leave them on the normal widget-overlay path.

## Checklist for Hub authors enabling native resolution

1. Enable Stretched Mode (integer scaling helps).
2. Call `setPreferNativeResolution(true)` on each overlay you intend to migrate.
3. Exercise: open bank (under-UI overlays covered), Alt-drag overlays, tooltips if any.
4. Replace raw `drawImage` with `OverlayUtil.renderImageLocation*` where appropriate.
5. For any icon beside text, use `getImageLayoutSize` for margins/anchors.
6. Retest with **Fixed overlay size** on and off if you draw interface panels or DYNAMIC text/icons.
7. Confirm minimap icons and world icons still sit on their targets.

## Related code (core)

- `net.runelite.client.plugins.stretchedmode.StretchedModeConfig` — fixed overlay size
- `net.runelite.client.ui.overlay.NativeOverlayBuffer` — buffers / scales
- `net.runelite.client.ui.overlay.OverlayRenderer` — native vs canvas routing, layer passes
- `net.runelite.client.ui.overlay.OverlayUtil` — image/text helpers
- `net.runelite.client.ui.overlay.Overlay#setPreferNativeResolution`

## See also

- [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode)
- [GPU](https://github.com/runelite/runelite/wiki/GPU)
- [Creating a Plugin](https://github.com/runelite/runelite/wiki/Creating-Plugins) (Plugin Hub packaging)
