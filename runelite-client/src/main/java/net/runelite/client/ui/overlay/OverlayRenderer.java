/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.ui.overlay;

import com.google.common.base.MoreObjects;
import com.google.common.primitives.Ints;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.BasicStroke;
import java.awt.Stroke;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.events.BeforeRender;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.FocusChanged;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.PostMenuSort;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.JagexColors;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.HotkeyListener;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

@Singleton
@Slf4j
public class OverlayRenderer extends MouseAdapter
{
	private static final Marker DEDUPLICATE = MarkerFactory.getMarker("DEDUPLICATE");
	private static final int PADDING = 2;
	private static final int OVERLAY_RESIZE_TOLERANCE = 5;
	private static final Color MOVING_OVERLAY_COLOR = new Color(255, 255, 0, 100);
	private static final Color MOVING_OVERLAY_ACTIVE_COLOR = new Color(255, 255, 0, 200);
	private static final Color MOVING_OVERLAY_TARGET_COLOR = Color.RED;
	private static final Color MOVING_OVERLAY_RESIZING_COLOR = new Color(255, 0, 255, 200);

	private final Client client;
	private final OverlayManager overlayManager;
	private final RuneLiteConfig runeLiteConfig;
	private final ClientUI clientUI;
	private final EventBus eventBus;
	private final ChatMessageManager chatMessageManager;
	private final SnapCorners snapCorners;
	private final NativeOverlayBuffer nativeOverlayBuffer;

	private Font font, tooltipFont, interfaceFont;

	// Overlay movement variables
	private final Point overlayOffset = new Point();
	private final Point mousePosition = new Point();
	private Overlay currentManagedOverlay;
	private Overlay dragTargetOverlay;
	private Rectangle currentManagedBounds;
	private boolean inOverlayManagingMode;
	private boolean inOverlayResizingMode;
	private boolean inOverlayDraggingMode;
	private boolean startedMovingOverlay;
	private Overlay curHoveredOverlay; // for building menu entries
	private Overlay lastHoveredOverlay; // for off-thread access

	private boolean dragWarn;

	@Inject
	private OverlayRenderer(
		final Client client,
		final OverlayManager overlayManager,
		final RuneLiteConfig runeLiteConfig,
		final MouseManager mouseManager,
		final KeyManager keyManager,
		final ClientUI clientUI,
		final EventBus eventBus,
		final ChatMessageManager chatMessageManager,
		final SnapCorners snapCorners,
		final NativeOverlayBuffer nativeOverlayBuffer
	)
	{
		this.client = client;
		this.overlayManager = overlayManager;
		this.runeLiteConfig = runeLiteConfig;
		this.clientUI = clientUI;
		this.eventBus = eventBus;
		this.chatMessageManager = chatMessageManager;
		this.snapCorners = snapCorners;
		this.nativeOverlayBuffer = nativeOverlayBuffer;

		HotkeyListener hotkeyListener = new HotkeyListener(runeLiteConfig::dragHotkey)
		{
			@Override
			public void hotkeyPressed()
			{
				inOverlayManagingMode = true;
				snapCorners.getSnapCorners().forEach(s ->
				{
					s.overlay = s.new CornerOverlay();
					overlayManager.saveOverlay(s.overlay); // avoid loadOverlay moving the overlay
					overlayManager.add(s.overlay);
				});
			}

			@Override
			public void hotkeyReleased()
			{
				if (inOverlayManagingMode)
				{
					inOverlayManagingMode = false;
					snapCorners.getSnapCorners().forEach(s ->
					{
						overlayManager.remove(s.overlay);
						s.overlay = null;
						snapCorners.saveSnapcorner(s);
					});
					resetOverlayManagementMode();
				}
			}
		};

		keyManager.registerKeyListener(hotkeyListener);
		mouseManager.registerMouseListener(this);
		eventBus.register(this);
	}

	@Subscribe
	public void onFocusChanged(FocusChanged event)
	{
		if (!event.isFocused())
		{
			if (inOverlayManagingMode)
			{
				inOverlayManagingMode = false;
				resetOverlayManagementMode();
			}

			curHoveredOverlay = null;
		}
	}

	@Subscribe
	private void onMenuOpened(MenuOpened event)
	{
		if (client.isKeyPressed(KeyCode.KC_SHIFT) && curHoveredOverlay != null)
		{
			overlayManager.addOriginMenu(curHoveredOverlay);
		}
	}

	@Subscribe
	protected void onPostMenuSort(PostMenuSort event)
	{
		lastHoveredOverlay = curHoveredOverlay;

		final Overlay overlay = curHoveredOverlay;
		if (overlay == null || client.isMenuOpen())
		{
			return;
		}

		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);
		if (!shift)
		{
			return;
		}

		List<OverlayMenuEntry> menuEntries = overlay.getMenuEntries();
		if (menuEntries.isEmpty())
		{
			return;
		}

		// Add in reverse order so they display correctly in the right-click menu
		for (int i = menuEntries.size() - 1; i >= 0; --i)
		{
			OverlayMenuEntry overlayMenuEntry = menuEntries.get(i);

			client.createMenuEntry(-2)
				.setOption(overlayMenuEntry.getOption())
				.setTarget(ColorUtil.wrapWithColorTag(overlayMenuEntry.getTarget(), JagexColors.MENU_TARGET))
				.setType(overlayMenuEntry.getMenuAction())
				.onClick(MoreObjects.firstNonNull(overlayMenuEntry.callback, e -> eventBus.post(new OverlayMenuClicked(overlayMenuEntry, overlay))));
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		curHoveredOverlay = null;
		nativeOverlayBuffer.nextFrame();

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			positionSnapcorners();
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted evt)
	{
		if (evt.getCommand().equals("resetanchors"))
		{
			snapCorners.reset();
		}
	}

	public void renderOverlayLayer(Graphics2D graphics, final OverlayLayer layer)
	{
		final Collection<Overlay> overlays = overlayManager.getLayer(layer);
		renderOverlays(graphics, overlays, layer);
	}

	public void renderAfterInterface(Graphics2D graphics, int interfaceId, Collection<WidgetItem> widgetItems)
	{
		Collection<Overlay> overlays = overlayManager.getForInterface(interfaceId);
		overlayManager.setWidgetItems(widgetItems);
		renderOverlays(graphics, overlays, OverlayLayer.ABOVE_WIDGETS);
		overlayManager.setWidgetItems(Collections.emptyList());
	}

	public void renderAfterLayer(Graphics2D graphics, Widget layer, Collection<WidgetItem> widgetItems)
	{
		Collection<Overlay> overlays = overlayManager.getForLayer(layer.getId());
		overlayManager.setWidgetItems(widgetItems);
		renderOverlays(graphics, overlays, OverlayLayer.ABOVE_WIDGETS);
		overlayManager.setWidgetItems(Collections.emptyList());
	}

	private void renderOverlays(final Graphics2D graphics, Collection<Overlay> overlays, final OverlayLayer layer)
	{
		if (overlays == null
			|| overlays.isEmpty()
			|| client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		final boolean nativePass = nativeOverlayBuffer.isActive();
		Graphics2D drawGraphics = graphics;
		Graphics2D nativeGraphics = null;
		final double scaleX;
		final double scaleY;

		if (nativePass)
		{
			nativeOverlayBuffer.prepareFrame();
			if (nativeOverlayBuffer.getImage() == null)
			{
				return;
			}
			nativeGraphics = nativeOverlayBuffer.getImage().createGraphics();
			scaleX = nativeOverlayBuffer.getScaleX();
			scaleY = nativeOverlayBuffer.getScaleY();
			OverlayUtil.setGraphicProperties(nativeGraphics);
			nativeGraphics.scale(scaleX, scaleY);
			nativeGraphics.setStroke(new BasicStroke((float) (1.0 / Math.max(scaleX, scaleY))));
			drawGraphics = nativeGraphics;
		}
		else
		{
			scaleX = 1;
			scaleY = 1;
			OverlayUtil.setGraphicProperties(graphics);
		}

		try
		{
			renderOverlaysInner(drawGraphics, overlays, layer, nativePass, scaleX, scaleY);
		}
		finally
		{
			if (nativeGraphics != null)
			{
				nativeGraphics.dispose();
			}
		}
	}

	private void renderOverlaysInner(final Graphics2D graphics, Collection<Overlay> overlays, final OverlayLayer layer,
		boolean nativePass, double scaleX, double scaleY)
	{
		// Save graphics2d properties so we can restore them later
		final AffineTransform transform = graphics.getTransform();
		final Stroke stroke = graphics.getStroke();
		final Composite composite = graphics.getComposite();
		final Paint paint = graphics.getPaint();
		final RenderingHints renderingHints = graphics.getRenderingHints();
		final Color background = graphics.getBackground();

		// Cache overlay fonts
		this.font = runeLiteConfig.dynamicOverlayFont().getFont();
		this.tooltipFont = runeLiteConfig.tooltipFont().getFont();
		this.interfaceFont = runeLiteConfig.interfaceFont().getFont();

		final Rectangle clip = clipBounds(layer);
		graphics.setClip(clip);

		final Point location = new Point();
		for (Overlay overlay : overlays)
		{
			final OverlayPosition overlayPosition = getCorrectedOverlayPosition(overlay);
			final Rectangle bounds = overlay.getBounds();
			final Point preferredLocation = overlay.getPreferredLocation();
			SnapCorner snapCorner = null;

			// If the final position is not modified, layout it
			if (overlayPosition != OverlayPosition.DYNAMIC && overlayPosition != OverlayPosition.TOOLTIP
				&& overlayPosition != OverlayPosition.DETACHED && preferredLocation == null)
			{
				snapCorner = snapCorners.forPosition(overlayPosition);
				// Align using visual size so right/bottom anchors sit flush when scaled
				Rectangle alignBounds = new Rectangle(bounds);
				Rectangle visual = getVisualSize(overlay, bounds.width, bounds.height);
				alignBounds.width = visual.width;
				alignBounds.height = visual.height;
				snapCorner.getNextDrawPosition(alignBounds, location);
			}
			else if (preferredLocation != null)
			{
				overlayManager.computeAbsolutePosition(overlay.getOrigin(), overlay.getOriginX(), overlay.getOriginY(), overlay.getPreferredLocation(), location);
			}
			else
			{
				location.setLocation(bounds.x, bounds.y);
			}

			if (overlay.getPreferredSize() != null)
			{
				bounds.setSize(overlay.getPreferredSize());
			}

			// Clamp using visual size so scaled-down overlays can reach screen edges
			Rectangle visualSize = getVisualSize(overlay, bounds.width, bounds.height);
			clampOverlayLocation(location.x, location.y, visualSize.width, visualSize.height, overlay.getParentBounds(), location);

			safeRender(overlay, graphics, location, nativePass, scaleX, scaleY);

			// Adjust snap corner based on where the overlay was drawn (visual bounds)
			if (snapCorner != null && bounds.width + bounds.height > 0)
			{
				snapCorner.shift(getHitBounds(overlay), PADDING);
			}

			// Restore graphics2d properties prior to drawing bounds
			graphics.setTransform(transform);
			graphics.setStroke(stroke);
			graphics.setComposite(composite);
			graphics.setPaint(paint);
			graphics.setRenderingHints(renderingHints);
			graphics.setBackground(background);
			if (!graphics.getClip().equals(clip))
			{
				graphics.setClip(clip);
			}

			if (!bounds.isEmpty())
			{
				if (inOverlayManagingMode && overlay.isMovable())
				{
					Color boundsColor;
					if (inOverlayResizingMode && currentManagedOverlay == overlay)
					{
						boundsColor = MOVING_OVERLAY_RESIZING_COLOR;
					}
					else if (inOverlayDraggingMode && currentManagedOverlay == overlay)
					{
						boundsColor = MOVING_OVERLAY_ACTIVE_COLOR;
					}
					else if (inOverlayDraggingMode && overlay.isDragTargetable() && currentManagedOverlay.isDragTargetable()
						&& currentManagedOverlay.getBounds().intersects(bounds))
					{
						boundsColor = MOVING_OVERLAY_TARGET_COLOR;
						assert currentManagedOverlay != overlay;
						dragTargetOverlay = overlay;
					}
					else
					{
						boundsColor = MOVING_OVERLAY_COLOR;
					}

					graphics.setColor(boundsColor);
					graphics.draw(getHitBounds(overlay));
					graphics.setPaint(paint);
				}

				if (!client.isMenuOpen() && !client.isWidgetSelected() && getHitBounds(overlay).contains(mousePosition))
				{
					if (curHoveredOverlay == null || bounds.width * bounds.height <= curHoveredOverlay.getBounds().width * curHoveredOverlay.getBounds().height)
					{
						curHoveredOverlay = overlay;
					}
					overlay.onMouseOver();
				}
			}
		}
	}

	/**
	 * Visual hit-test bounds in canvas space, accounting for overlay size mode and scale.
	 */
	private Rectangle getHitBounds(Overlay overlay)
	{
		Rectangle bounds = overlay.getBounds();
		Rectangle visual = getVisualSize(overlay, bounds.width, bounds.height);
		if (visual.width == bounds.width && visual.height == bounds.height)
		{
			return bounds;
		}
		return new Rectangle(bounds.x, bounds.y, visual.width, visual.height);
	}

	/**
	 * Canvas-space width/height after native overlay sizing (size mode + overlay scale).
	 */
	private Rectangle getVisualSize(Overlay overlay, int logicalWidth, int logicalHeight)
	{
		if (!nativeOverlayBuffer.isActive())
		{
			return new Rectangle(0, 0, logicalWidth, logicalHeight);
		}

		OverlayPosition position = getCorrectedOverlayPosition(overlay);
		if (position == OverlayPosition.DYNAMIC || position == OverlayPosition.TOOLTIP)
		{
			return new Rectangle(0, 0, logicalWidth, logicalHeight);
		}

		double fx = nativeOverlayBuffer.getVisualSizeFactorX();
		double fy = nativeOverlayBuffer.getVisualSizeFactorY();
		if (fx == 1.0 && fy == 1.0)
		{
			return new Rectangle(0, 0, logicalWidth, logicalHeight);
		}

		return new Rectangle(0, 0,
			Math.max(0, (int) Math.round(logicalWidth * fx)),
			Math.max(0, (int) Math.round(logicalHeight * fy)));
	}

	@Override
	public MouseEvent mousePressed(MouseEvent mouseEvent)
	{
		final Point mousePoint = mouseEvent.getPoint();
		mousePosition.setLocation(mousePoint);

		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		// See if we've clicked on an overlay
		currentManagedOverlay = lastHoveredOverlay;
		if (currentManagedOverlay == null || !currentManagedOverlay.isMovable())
		{
			return mouseEvent;
		}

		if (SwingUtilities.isRightMouseButton(mouseEvent))
		{
			if (currentManagedOverlay.isResettable())
			{
				overlayManager.resetOverlay(currentManagedOverlay);
			}
		}
		else if (SwingUtilities.isLeftMouseButton(mouseEvent))
		{
			final Point offset = new Point(mousePoint.x, mousePoint.y);
			offset.translate(-currentManagedOverlay.getBounds().x, -currentManagedOverlay.getBounds().y);
			overlayOffset.setLocation(offset);

			inOverlayResizingMode = currentManagedOverlay != null && currentManagedOverlay.isResizable() && clientUI.getCurrentCursor() != clientUI.getDefaultCursor();
			inOverlayDraggingMode = !inOverlayResizingMode;
			startedMovingOverlay = true;
			currentManagedBounds = new Rectangle(currentManagedOverlay.getBounds());
		}
		else
		{
			return mouseEvent;
		}

		mouseEvent.consume();
		return mouseEvent;
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent mouseEvent)
	{
		final Point mousePoint = mouseEvent.getPoint();
		mousePosition.setLocation(mousePoint);

		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		if (!inOverlayResizingMode && !inOverlayDraggingMode)
		{
			currentManagedOverlay = lastHoveredOverlay;
		}

		if (currentManagedOverlay == null || !currentManagedOverlay.isResizable())
		{
			clientUI.setCursor(clientUI.getDefaultCursor());
			return mouseEvent;
		}

		final Rectangle toleranceRect = new Rectangle(getHitBounds(currentManagedOverlay));
		toleranceRect.grow(-OVERLAY_RESIZE_TOLERANCE, -OVERLAY_RESIZE_TOLERANCE);
		final int outcode = toleranceRect.outcode(mouseEvent.getPoint());

		switch (outcode)
		{
			case Rectangle.OUT_TOP:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_TOP | Rectangle.OUT_LEFT:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_LEFT:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_LEFT | Rectangle.OUT_BOTTOM:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_BOTTOM:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_BOTTOM | Rectangle.OUT_RIGHT:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_RIGHT:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
				break;
			case Rectangle.OUT_RIGHT | Rectangle.OUT_TOP:
				clientUI.setCursor(Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR));
				break;
			default:
				// center
				clientUI.setCursor(clientUI.getDefaultCursor());
		}

		return mouseEvent;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent mouseEvent)
	{
		final Point p = mouseEvent.getPoint();
		mousePosition.setLocation(p);

		if (!inOverlayManagingMode)
		{
			return mouseEvent;
		}

		if (currentManagedOverlay == null)
		{
			return mouseEvent;
		}

		if (dragTargetOverlay != null && !currentManagedOverlay.getBounds().intersects(dragTargetOverlay.getBounds()))
		{
			// No longer over drag target
			dragTargetOverlay = null;
		}

		final Rectangle canvasRect = new Rectangle(client.getRealDimensions());

		if (!canvasRect.contains(p))
		{
			return mouseEvent;
		}

		if (inOverlayResizingMode)
		{
			final int left = p.x - currentManagedBounds.x; // Distance (in pixels) from the left edge of the bounds
			final int top = p.y - currentManagedBounds.y;  // Distance (in pixels) from the top edge of the bounds
			final int originalX = currentManagedBounds.x;
			final int originalY = currentManagedBounds.y;
			int x = originalX;
			int y = originalY;
			int width = currentManagedBounds.width;
			int height = currentManagedBounds.height;

			switch (clientUI.getCurrentCursor().getType())
			{
				case Cursor.N_RESIZE_CURSOR:
					y += top;
					height -= top;
					break;
				case Cursor.NW_RESIZE_CURSOR:
					x += left;
					y += top;
					width -= left;
					height -= top;
					break;
				case Cursor.W_RESIZE_CURSOR:
					x += left;
					width -= left;
					break;
				case Cursor.SW_RESIZE_CURSOR:
					x += left;
					width -= left;
					height = top;
					break;
				case Cursor.S_RESIZE_CURSOR:
					height = top;
					break;
				case Cursor.SE_RESIZE_CURSOR:
					width = left;
					height = top;
					break;
				case Cursor.E_RESIZE_CURSOR:
					width = left;
					break;
				case Cursor.NE_RESIZE_CURSOR:
					y += top;
					width = left;
					height -= top;
					break;
				default:
					// center
			}

			final int minOverlaySize = currentManagedOverlay.getMinimumSize();
			final int widthOverflow = Math.max(0, minOverlaySize - width);
			final int heightOverflow = Math.max(0, minOverlaySize - height);

			// If this resize operation would cause the dimensions to go below the minimum width/height, reset the
			// dimensions and adjust the x/y position accordingly as needed
			if (widthOverflow > 0)
			{
				width = minOverlaySize;

				if (x > originalX)
				{
					x -= widthOverflow;
				}
			}
			if (heightOverflow > 0)
			{
				height = minOverlaySize;

				if (y > originalY)
				{
					y -= heightOverflow;
				}
			}

			currentManagedBounds.setRect(x, y, width, height);
			currentManagedOverlay.setPreferredSize(new Dimension(currentManagedBounds.width, currentManagedBounds.height));

			Point l = currentManagedOverlay.getPreferredLocation();
			if (l != null)
			{
				l.translate(x - originalX, y -  originalY);
			}
		}
		else if (inOverlayDraggingMode)
		{
			Point overlayPosition = new Point(p);
			overlayPosition.translate(-overlayOffset.x, -overlayOffset.y); // adjust by mouse offset to get overlay position

			// Clamp drag to parent component using visual size
			final Rectangle overlayBounds = currentManagedOverlay.getBounds();
			Rectangle visualSize = getVisualSize(currentManagedOverlay, overlayBounds.width, overlayBounds.height);
			clampOverlayLocation(overlayPosition.x, overlayPosition.y, visualSize.width, visualSize.height, currentManagedOverlay.getParentBounds(), overlayPosition);

			if (currentManagedOverlay.getOrigin() == OverlayOrigin.AUTO)
			{
				// Compute the new origins for the overlay
				overlayManager.computeOverlayOrigins(currentManagedOverlay, overlayPosition.x, overlayPosition.y, overlayBounds.width, overlayBounds.height);
			}

			// Compute new relative position
			overlayPosition = overlayManager.computeOriginPosition(overlayPosition, currentManagedOverlay.getOrigin(), currentManagedOverlay.getOriginX(), currentManagedOverlay.getOriginY());

			currentManagedOverlay.setPreferredPosition(null);
			currentManagedOverlay.setPreferredLocation(overlayPosition);

			currentManagedOverlay.onDrag();
		}
		else
		{
			return mouseEvent;
		}

		if (startedMovingOverlay)
		{
			// Move currently moved overlay to correct layer
			overlayManager.rebuildOverlayLayers();
			startedMovingOverlay = false;
		}

		return mouseEvent;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent mouseEvent)
	{
		final Point mousePoint = mouseEvent.getPoint();
		mousePosition.setLocation(mousePoint);

		if (!inOverlayManagingMode || currentManagedOverlay == null || (!inOverlayDraggingMode && !inOverlayResizingMode))
		{
			return mouseEvent;
		}

		if (dragTargetOverlay != null)
		{
			if (dragTargetOverlay.onDrag(currentManagedOverlay))
			{
				mouseEvent.consume();
				resetOverlayManagementMode();
				return mouseEvent;
			}
		}

		// Check if the overlay is over a snapcorner and snap it if so
		if (currentManagedOverlay.isSnappable() && inOverlayDraggingMode)
		{
			for (SnapCorner snapCorner : snapCorners.getSnapCorners())
			{
				if (snapCorner.corner().contains(mousePosition))
				{
					OverlayPosition position = snapCorner.position;

					if (position == getCorrectedOverlayPosition(currentManagedOverlay))
					{
						// overlay moves back to default position
						position = null;
					}

					if (overlayManager.cycleCheck(currentManagedOverlay, OverlayOrigin.AUTO, position))
					{
						// the only overlays which can be used as origins are widget overlays & snap corners, but
						// snap corners can't be put into other snapcorners.
						assert currentManagedOverlay instanceof WidgetOverlays.WidgetOverlay;
						chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.CONSOLE)
							.runeLiteFormattedMessage("The origin of this anchor is already linked to this overlay, either directly, or indirectly through multiple other overlays. " +
								"Introducing a circular dependency is not permitted.")
							.build());
						break;
					}

					currentManagedOverlay.setPreferredPosition(position);
					currentManagedOverlay.setPreferredLocation(null); // from dragging
					currentManagedOverlay.setOrigin(OverlayOrigin.AUTO);
					currentManagedOverlay.setOriginX(OverlayOriginX.LEFT);
					currentManagedOverlay.setOriginY(OverlayOriginY.TOP);
					currentManagedOverlay.revalidate();
					break;
				}
			}
		}

		if (inOverlayDraggingMode && currentManagedOverlay instanceof WidgetOverlays.WidgetOverlay && !dragWarn)
		{
			dragWarn = true;
			chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage("You've repositioned one of the in-game interfaces. Hold " + runeLiteConfig.dragHotkey() +
					" and drag to reposition the interface again, or " + runeLiteConfig.dragHotkey() + " and right-click to reset.")
				.build());
		}

		overlayManager.saveOverlay(currentManagedOverlay);
		resetOverlayManagementMode();
		mouseEvent.consume();
		return mouseEvent;
	}

	private Rectangle clipBounds(OverlayLayer layer)
	{
		if (!client.isResized() && (layer == OverlayLayer.ABOVE_SCENE || layer == OverlayLayer.UNDER_WIDGETS))
		{
			return new Rectangle(client.getViewportXOffset(),
				client.getViewportYOffset(),
				client.getViewportWidth(),
				client.getViewportHeight());
		}
		else
		{
			return new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
		}
	}

	private void safeRender(Overlay overlay, Graphics2D graphics, Point point, boolean nativePass, double scaleX, double scaleY)
	{
		final OverlayPosition position = overlay.getPosition();

		// Set font based on configuration
		if (position == OverlayPosition.DYNAMIC || position == OverlayPosition.DETACHED)
		{
			if (nativePass && scaleX > 0)
			{
				// Keep world overlay text at unscaled display size
				graphics.setFont(font.deriveFont(font.getSize2D() / (float) scaleX));
			}
			else
			{
				graphics.setFont(font);
			}
		}
		else if (position == OverlayPosition.TOOLTIP)
		{
			graphics.setFont(tooltipFont);
		}
		else
		{
			graphics.setFont(interfaceFont);
		}

		graphics.translate(point.x, point.y);

		if (nativePass && position != OverlayPosition.DYNAMIC && position != OverlayPosition.TOOLTIP)
		{
			double cx = nativeOverlayBuffer.getPanelContentScaleX();
			double cy = nativeOverlayBuffer.getPanelContentScaleY();
			if (cx != 1.0 || cy != 1.0)
			{
				graphics.scale(cx, cy);
			}
		}

		overlay.getBounds().setLocation(point);

		final Dimension overlayDimension;
		try
		{
			overlayDimension = overlay.render(graphics);
		}
		catch (Exception ex)
		{
			log.warn(DEDUPLICATE, "Error during overlay rendering", ex);
			return;
		}

		if (overlayDimension != null)
		{
			overlay.getBounds().setSize(overlayDimension);
		}
		else
		{
			overlay.getBounds().setSize(0, 0);
		}
	}

	/**
	 * Creates a Graphics2D into the native overlay buffer with stretch scale applied.
	 * Caller must dispose. Returns null if native pass is inactive.
	 */
	public Graphics2D createNativeOverlayGraphics()
	{
		if (!nativeOverlayBuffer.isActive())
		{
			return null;
		}
		nativeOverlayBuffer.prepareFrame();
		if (nativeOverlayBuffer.getImage() == null)
		{
			return null;
		}
		Graphics2D g = nativeOverlayBuffer.getImage().createGraphics();
		OverlayUtil.setGraphicProperties(g);
		g.scale(nativeOverlayBuffer.getScaleX(), nativeOverlayBuffer.getScaleY());
		return g;
	}

	public NativeOverlayBuffer getNativeOverlayBuffer()
	{
		return nativeOverlayBuffer;
	}

	private OverlayPosition getCorrectedOverlayPosition(final Overlay overlay)
	{
		OverlayPosition overlayPosition = overlay.getPosition();

		if (overlay.getPreferredPosition() != null)
		{
			overlayPosition = overlay.getPreferredPosition();
		}

		if (!client.isResized())
		{
			// On fixed mode, ABOVE_CHATBOX_RIGHT is in the same location as
			// BOTTOM_RIGHT and CANVAS_TOP_RIGHT is same as TOP_RIGHT.
			// Just use BOTTOM_RIGHT and TOP_RIGHT to prevent overlays from
			// drawing over each other.
			switch (overlayPosition)
			{
				case CANVAS_TOP_RIGHT:
					overlayPosition = OverlayPosition.TOP_RIGHT;
					break;
				case ABOVE_CHATBOX_RIGHT:
					overlayPosition = OverlayPosition.BOTTOM_RIGHT;
					break;
			}
		}

		return overlayPosition;
	}

	private void resetOverlayManagementMode()
	{
		inOverlayResizingMode = false;
		inOverlayDraggingMode = false;
		currentManagedOverlay = null;
		dragTargetOverlay = null;
		currentManagedBounds = null;
		clientUI.setCursor(clientUI.getDefaultCursor());
	}

	private void positionSnapcorners()
	{
		var location = new Point();
		for (SnapCorner s : snapCorners.getSnapCorners())
		{
			location.setLocation(s.curx, s.cury); // relative x,y of align corner
			overlayManager.computeAbsolutePosition(OverlayOrigin.MANUAL, s.originX, s.originY, location, location);
			int cx = location.x, cy = location.y; // absolute x,y of the align corner
			s.translateOffsetForAlignment(location);
			int ax = location.x, ay = location.y; // absolute x,y of the top-left corner
			clampOverlayLocation(ax, ay, s.lastsx, s.lastsy, null, location);
			// apply clamp delta to aligned corner
			cx += location.x - ax;
			cy += location.y - ay;
			s.setPosition(cx, cy);
		}
	}

	/**
	 * Adjust the given overlay position to be within its parent's bounds.
	 *
	 * @param out the clamped position
	 */
	private void clampOverlayLocation(int overlayX, int overlayY, int overlayWidth, int overlayHeight, Rectangle parentBounds, Point out)
	{
		int px, py, pw, ph;
		if (parentBounds == null || parentBounds.isEmpty())
		{
			// If no bounds are set, use the full client bounds
			Dimension dim = client.getRealDimensions();
			px = py = 0;
			pw = dim.width;
			ph = dim.height;
		}
		else
		{
			px = parentBounds.x;
			py = parentBounds.y;
			pw = parentBounds.width;
			ph = parentBounds.height;
		}

		// Constrain overlay position to be within the parent bounds
		out.setLocation(
			Ints.constrainToRange(overlayX, px,
				Math.max(px, px + pw - overlayWidth)),
			Ints.constrainToRange(overlayY, py,
				Math.max(py, py + ph - overlayHeight))
		);
	}
}
