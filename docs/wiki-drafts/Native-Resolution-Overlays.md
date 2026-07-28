<!--
  DRAFT wiki page for https://github.com/runelite/wiki
  Suggested page name: Native-Resolution-Overlays.md
  Suggested sidebar links from: Developer Guide, Stretched-Mode, GPU

  Status: draft accompanying the native resolution overlays feature.
  Remove this HTML comment when publishing to the wiki.
-->

# Native Resolution Overlays (draft)

When [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode) is enabled, the game UI is drawn at canvas resolution and then upscaled. Historically, RuneLite overlays shared that UI buffer, so world overlays (tile highlights, NPC outlines, etc.) were upscaled too and looked soft.

**Native resolution overlays** (RuneLite settings → Overlay settings) draw RuneLite overlays into a display-resolution buffer and composite them around the stretched UI, so world overlays can stay sharp.

This page is for **core and Plugin Hub developers**. For player-facing stretched/GPU settings, see [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode) and [GPU](https://github.com/runelite/runelite/wiki/GPU).

## Player settings (context)

| Setting | Meaning |
| --- | --- |
| **Native resolution overlays** | Master toggle; only applies when stretched mode is on |
| **Overlay size mode** | `Canvas` (default): interface overlays keep unstretched visual size. `Match UI`: interface overlays follow stretched UI size. Does not change world/`DYNAMIC` geometry sharpness |
| **Overlay scale** | Extra % scale for interface overlays after size mode |

Mouse Tooltips has a separate **Scale tooltips** option for menu-action tooltips.

## Rendering model

```
Scene (GPU / software)
    → under-UI native overlays   (ABOVE_SCENE, UNDER_WIDGETS)
    → game UI (bank, inventory, …)  [stretched]
    → above-UI native overlays   (ABOVE_WIDGETS, ALWAYS_ON_TOP, tooltips)
```

Layer ordering matches the classic `OverlayLayer` rules: default under-widget overlays stay **under** the bank; overlays promoted to above-widgets (e.g. after Alt-dragging to a free position) still draw on top.

### Two buffers

Core code uses `NativeOverlayBuffer` with passes `UNDER_UI` and `ABOVE_UI`. GPU composites under before `drawUi`, then above after. That preserves bank-over-infobox behaviour.

### What “canvas space” means

Even when drawing into the native buffer, **coordinates stay in canvas space** (same values as `Perspective.getCanvasTextLocation`, `getCanvasTilePoly`, widget bounds, etc.). The renderer applies stretch scale on the `Graphics2D` so those coordinates land on the display-sized buffer.

## Plugin Hub / sideloaded plugins (opt-in)

Hub and sideloaded **world** overlays (tiles, outlines, custom `DYNAMIC` drawing) **default to the legacy canvas path** when native overlays is on. They keep correct proportions (but stay soft when stretched) until they opt in.

**Infoboxes are already included.** Hub plugins that register `InfoBox`es via `InfoBoxManager` are drawn by the core `InfoBoxOverlay`, which always follows native resolution + size mode / overlay scale. No hub opt-in is required for infoboxes.

Builtin (core) overlays default to the native path.

### Opting in (tiles, outlines, custom overlays)

Pass your `Plugin` into `Overlay`’s constructor (normal Hub practice), then:

```java
setPreferNativeResolution(true);
```

Only do this after you have verified drawing under stretch + native overlays (see checklist below). Prefer submitting a PR to the hub plugin after testing with the author when possible.

`ModelOutlineRenderer` follows the **calling overlay’s** preference: hub overlays that have not opted in keep soft outlines; after opt-in, outlines go native with that overlay.

Detection: overlays whose plugin classloader is not the client classloader get `preferNativeResolution = false` automatically in `Overlay(Plugin)`.

## Drawing contracts

### Interface overlays (`TOP_LEFT`, `TOP_RIGHT`, panels, …)

After stretch scale, the renderer applies **panel content scale** (size mode + overlay scale). Prefer `OverlayPanel` / panel components. Hit-testing and Alt drag bounds use visual size automatically for overlays on the native path.

### World / `DYNAMIC` overlays

- Positions: canvas coordinates (unchanged APIs).
- **Text**: font is inverse-scaled so it stays roughly pre-native visual size and sharp.
- **Polygons / strokes**: use stretch scale (sharp at display resolution). Prefer `OverlayUtil.renderPolygon`. If you set your own `BasicStroke`, remember user-space width is multiplied by stretch.
- **Images**: do **not** call `graphics.drawImage` raw on a stretch-scaled context unless you intend the sprite to appear at old stretched size.

Use `OverlayUtil`:

| API | When to use |
| --- | --- |
| `renderImageLocation(Graphics2D, Point, BufferedImage)` | Point is top-left of the **full bitmap footprint** (e.g. `Perspective.getCanvasImageLocation` / actor canvas image helpers). Inverse-scales and **centers** in that footprint |
| `getImageLayoutSize(Graphics2D, BufferedImage)` | Layout size in canvas space after inverse-scale |
| `renderImageLocationExact(Graphics2D, Point, BufferedImage)` | Point is top-left of the **drawn** (layout-sized) sprite — use when packing icons next to text |

### Progress pies / timer pies

`ProgressPieComponent` follows **Overlay size mode** and **Overlay scale** under native overlays (Canvas = unstretched visual size, Match UI = stretched size). Arcs are always antialiased into the native buffer, so Match UI pies stay smooth instead of looking like upscaled low-res circles.

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

1. Enable Stretched Mode (integer scaling helps) + **Native resolution overlays**.
2. Call `setPreferNativeResolution(true)` on each overlay you intend to migrate.
3. Exercise: open bank (under-UI overlays covered), Alt-drag overlays, tooltips if any.
4. Replace raw `drawImage` with `OverlayUtil.renderImageLocation*` where appropriate.
5. For any icon beside text, use `getImageLayoutSize` for margins/anchors.
6. Retest with **Overlay size mode** Canvas and Match UI if you draw interface panels.
7. Confirm minimap icons and world icons still sit on their targets.

## Related code (core)

- `net.runelite.client.config.RuneLiteConfig` — settings
- `net.runelite.client.ui.overlay.NativeOverlayBuffer` — buffers / scales
- `net.runelite.client.ui.overlay.OverlayRenderer` — native vs canvas routing, layer passes
- `net.runelite.client.ui.overlay.OverlayUtil` — image/text helpers
- `net.runelite.client.ui.overlay.Overlay#setPreferNativeResolution`

## See also

- [Stretched Mode](https://github.com/runelite/runelite/wiki/Stretched-Mode)
- [GPU](https://github.com/runelite/runelite/wiki/GPU)
- [Developer Guide](https://github.com/runelite/runelite/wiki/Developer-Guide)
- [Information about the Plugin Hub](https://github.com/runelite/runelite/wiki/Information-about-the-Plugin-Hub)
