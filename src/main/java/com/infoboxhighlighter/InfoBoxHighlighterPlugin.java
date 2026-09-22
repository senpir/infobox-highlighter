package com.infoboxhighlighter;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.InfoBoxMenuClicked;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.ui.components.colorpicker.RuneliteColorPicker;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

@PluginDescriptor(
        name = "InfoBox Highlighter",
        description = "Adds customizable colored outlines to RuneLite infoboxes"
)
public class InfoBoxHighlighterPlugin extends Plugin
{
    private static final String CONFIG_GROUP = InfoBoxHighlighterConfig.GROUP;

    private static final String SET_HIGHLIGHT = "Set highlight";
    private static final String REMOVE_HIGHLIGHT = "Remove highlight";
    private static final String MENU_TARGET = "InfoBox";

    @Inject
    private Client client;

    @Inject
    private InfoBoxManager infoBoxManager;

    @Inject
    private ColorPickerManager colorPickerManager;

    @Inject
    private ConfigManager configManager;

    @Inject
    private RuneLiteConfig runeLiteConfig;

    @Inject
    private InfoBoxHighlighterConfig config;

    private final Map<InfoBox, BufferedImage> originalImages =
            new IdentityHashMap<>();

    private final Map<InfoBox, BufferedImage> highlightedImages =
            new IdentityHashMap<>();

    private final Map<InfoBox, Color> appliedColors =
            new IdentityHashMap<>();

    @Provides
    InfoBoxHighlighterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(InfoBoxHighlighterConfig.class);
    }

    @Override
    protected void startUp()
    {
        syncInfoBoxMenus();
        syncHighlightImages();
    }

    @Override
    protected void shutDown()
    {
        for (InfoBox infoBox : new ArrayList<>(highlightedImages.keySet()))
        {
            restoreOriginalImage(infoBox);
        }

        for (InfoBox infoBox : infoBoxManager.getInfoBoxes())
        {
            removeOurMenuEntries(infoBox);
        }

        originalImages.clear();
        highlightedImages.clear();
        appliedColors.clear();
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        syncInfoBoxMenus();
        syncHighlightImages();
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }

        if ("borderWidth".equals(event.getKey())
                || "feather".equals(event.getKey()))
        {
            appliedColors.clear();
        }
    }

    @Subscribe
    public void onInfoBoxMenuClicked(InfoBoxMenuClicked event)
    {
        String option = event.getEntry().getOption();
        InfoBox infoBox = event.getInfoBox();

        if (SET_HIGHLIGHT.equals(option))
        {
            openColorPicker(infoBox);
        }
        else if (REMOVE_HIGHLIGHT.equals(option))
        {
            removeHighlight(infoBox);
        }
    }

    private void syncInfoBoxMenus()
    {
        for (InfoBox infoBox : infoBoxManager.getInfoBoxes())
        {
            if (!hasMenuEntry(infoBox, SET_HIGHLIGHT))
            {
                infoBox.getMenuEntries().add(
                        new OverlayMenuEntry(
                                MenuAction.RUNELITE_INFOBOX,
                                SET_HIGHLIGHT,
                                MENU_TARGET
                        )
                );
            }

            Color highlightColor = getHighlightColor(infoBox);

            if (highlightColor != null)
            {
                if (!hasMenuEntry(infoBox, REMOVE_HIGHLIGHT))
                {
                    infoBox.getMenuEntries().add(
                            new OverlayMenuEntry(
                                    MenuAction.RUNELITE_INFOBOX,
                                    REMOVE_HIGHLIGHT,
                                    MENU_TARGET
                            )
                    );
                }
            }
            else
            {
                infoBox.getMenuEntries().removeIf(
                        entry -> REMOVE_HIGHLIGHT.equals(entry.getOption())
                );
            }
        }
    }

    private void syncHighlightImages()
    {
        List<InfoBox> infoBoxes = infoBoxManager.getInfoBoxes();

        Set<InfoBox> activeInfoBoxes =
                Collections.newSetFromMap(new IdentityHashMap<>());

        activeInfoBoxes.addAll(infoBoxes);

        originalImages.keySet().removeIf(
                infoBox -> !activeInfoBoxes.contains(infoBox)
        );

        highlightedImages.keySet().removeIf(
                infoBox -> !activeInfoBoxes.contains(infoBox)
        );

        appliedColors.keySet().removeIf(
                infoBox -> !activeInfoBoxes.contains(infoBox)
        );

        for (InfoBox infoBox : infoBoxes)
        {
            syncHighlightImage(infoBox);
        }
    }

    private void syncHighlightImage(InfoBox infoBox)
    {
        Color desiredColor = getHighlightColor(infoBox);

        BufferedImage currentImage = infoBox.getImage();
        BufferedImage highlightedImage = highlightedImages.get(infoBox);

        if (desiredColor == null)
        {
            if (highlightedImage != null && currentImage == highlightedImage)
            {
                restoreOriginalImage(infoBox);
            }
            else
            {
                originalImages.remove(infoBox);
                highlightedImages.remove(infoBox);
                appliedColors.remove(infoBox);
            }

            return;
        }

        if (highlightedImage == null)
        {
            originalImages.put(infoBox, currentImage);
            applyHighlightImage(infoBox, desiredColor);
            return;
        }

        if (currentImage != highlightedImage)
        {
            originalImages.put(infoBox, currentImage);
            applyHighlightImage(infoBox, desiredColor);
            return;
        }

        Color appliedColor = appliedColors.get(infoBox);

        if (!desiredColor.equals(appliedColor))
        {
            applyHighlightImage(infoBox, desiredColor);
        }
    }

    private void applyHighlightImage(InfoBox infoBox, Color color)
    {
        BufferedImage originalImage = originalImages.get(infoBox);

        if (originalImage == null)
        {
            return;
        }

        int size = Math.max(2, runeLiteConfig.infoBoxSize());

        BufferedImage highlightedImage =
                new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = highlightedImage.createGraphics();

        int imageWidth = originalImage.getWidth();
        int imageHeight = originalImage.getHeight();

        double scale = Math.min(
                1.0,
                Math.min(
                        (double) size / imageWidth,
                        (double) size / imageHeight
                )
        );

        int drawWidth = Math.max(
                1,
                (int) Math.round(imageWidth * scale)
        );

        int drawHeight = Math.max(
                1,
                (int) Math.round(imageHeight * scale)
        );

        int imageX = (size - drawWidth) / 2;
        int imageY = (size - drawHeight) / 2;

        graphics.drawImage(
                originalImage,
                imageX,
                imageY,
                drawWidth,
                drawHeight,
                null
        );

        int borderWidth = Math.max(
                1,
                Math.min(config.borderWidth(), size / 2)
        );

        drawBorderLayer(
                graphics,
                size,
                0,
                borderWidth,
                color
        );

        int feather = config.feather();

        for (int i = 0; i < feather; i++)
        {
            int offset = borderWidth + i;

            if ((offset * 2) >= size)
            {
                break;
            }

            double strength =
                    (double) (feather - i) / (feather + 1);

            int alpha = Math.max(
                    1,
                    (int) Math.round(color.getAlpha() * strength)
            );

            Color featherColor = new Color(
                    color.getRed(),
                    color.getGreen(),
                    color.getBlue(),
                    alpha
            );

            drawBorderLayer(
                    graphics,
                    size,
                    offset,
                    1,
                    featherColor
            );
        }

        graphics.dispose();

        infoBox.setImage(highlightedImage);
        infoBoxManager.updateInfoBoxImage(infoBox);

        highlightedImages.put(infoBox, highlightedImage);
        appliedColors.put(infoBox, color);
    }

    private void drawBorderLayer(
            Graphics2D graphics,
            int size,
            int offset,
            int thickness,
            Color color)
    {
        int innerSize = size - (offset * 2);

        if (innerSize <= 0 || thickness <= 0)
        {
            return;
        }

        int actualThickness = Math.min(
                thickness,
                Math.max(1, innerSize / 2)
        );

        graphics.setColor(color);

        graphics.fillRect(
                offset,
                offset,
                innerSize,
                actualThickness
        );

        graphics.fillRect(
                offset,
                size - offset - actualThickness,
                innerSize,
                actualThickness
        );

        int sideHeight =
                innerSize - (actualThickness * 2);

        if (sideHeight > 0)
        {
            graphics.fillRect(
                    offset,
                    offset + actualThickness,
                    actualThickness,
                    sideHeight
            );

            graphics.fillRect(
                    size - offset - actualThickness,
                    offset + actualThickness,
                    actualThickness,
                    sideHeight
            );
        }
    }

    private void restoreOriginalImage(InfoBox infoBox)
    {
        BufferedImage originalImage = originalImages.remove(infoBox);
        BufferedImage highlightedImage = highlightedImages.remove(infoBox);

        appliedColors.remove(infoBox);

        if (originalImage == null)
        {
            return;
        }

        if (highlightedImage == null || infoBox.getImage() == highlightedImage)
        {
            infoBox.setImage(originalImage);
            infoBoxManager.updateInfoBoxImage(infoBox);
        }
    }

    private boolean hasMenuEntry(InfoBox infoBox, String option)
    {
        return infoBox.getMenuEntries()
                .stream()
                .anyMatch(entry -> option.equals(entry.getOption()));
    }

    private void removeOurMenuEntries(InfoBox infoBox)
    {
        infoBox.getMenuEntries().removeIf(entry ->
                SET_HIGHLIGHT.equals(entry.getOption())
                        || REMOVE_HIGHLIGHT.equals(entry.getOption())
        );
    }

    private void openColorPicker(InfoBox infoBox)
    {
        Color currentColor = getHighlightColor(infoBox);

        if (currentColor == null)
        {
            currentColor = Color.YELLOW;
        }

        final Color startingColor = currentColor;

        SwingUtilities.invokeLater(() ->
        {
            RuneliteColorPicker colorPicker = colorPickerManager.create(
                    client,
                    startingColor,
                    "InfoBox Highlight",
                    false
            );

            colorPicker.setOnClose(color ->
                    setHighlightColor(infoBox, color)
            );

            colorPicker.setVisible(true);
        });
    }

    private Color getHighlightColor(InfoBox infoBox)
    {
        return configManager.getConfiguration(
                CONFIG_GROUP,
                getColorConfigKey(infoBox),
                Color.class
        );
    }

    private void setHighlightColor(InfoBox infoBox, Color color)
    {
        configManager.setConfiguration(
                CONFIG_GROUP,
                getColorConfigKey(infoBox),
                color.getRGB()
        );
    }

    private void removeHighlight(InfoBox infoBox)
    {
        configManager.unsetConfiguration(
                CONFIG_GROUP,
                getColorConfigKey(infoBox)
        );

        restoreOriginalImage(infoBox);
    }

    private String getColorConfigKey(InfoBox infoBox)
    {
        return "color_" + infoBox.getName();
    }
}