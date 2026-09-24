package com.infoboxhighlighter;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.time.Instant;
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
import net.runelite.api.events.ClientTick;
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
import net.runelite.client.ui.overlay.infobox.Timer;

@PluginDescriptor(
        name = "InfoBox Highlighter",
        description = "Adds customizable colored outlines to RuneLite infoboxes"
)
public class InfoBoxHighlighterPlugin extends Plugin
{
    private static final String CONFIG_GROUP = InfoBoxHighlighterConfig.GROUP;

    private static final String SET_HIGHLIGHT = "Set highlight";
    private static final String REMOVE_HIGHLIGHT = "Remove highlight";

    private static final String SET_FLASH_COLOR = "Set flash color";
    private static final String REMOVE_FLASH_COLOR = "Remove flash color";

    private static final String MENU_TARGET = "InfoBox";

    private static final long FLASH_PULSE_STEP_MS = 180L;

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

    private final Map<InfoBox, Integer> appliedFeathers =
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
        appliedFeathers.clear();
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        syncInfoBoxMenus();
        syncHighlightImages();
    }

    @Subscribe
    public void onClientTick(ClientTick event)
    {
        for (InfoBox infoBox : infoBoxManager.getInfoBoxes())
        {
            if (infoBox instanceof Timer && getFlashColor(infoBox) != null)
            {
                syncHighlightImage(infoBox);
            }
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!CONFIG_GROUP.equals(event.getGroup()))
        {
            return;
        }

        if ("borderWidth".equals(event.getKey())
                || "feather".equals(event.getKey())
                || "flashThreshold".equals(event.getKey())
                || "flashIntensity".equals(event.getKey()))
        {
            appliedColors.clear();
            appliedFeathers.clear();
        }
    }

    @Subscribe
    public void onInfoBoxMenuClicked(InfoBoxMenuClicked event)
    {
        String option = event.getEntry().getOption();
        InfoBox infoBox = event.getInfoBox();

        if (SET_HIGHLIGHT.equals(option))
        {
            openHighlightColorPicker(infoBox);
        }
        else if (REMOVE_HIGHLIGHT.equals(option))
        {
            removeHighlight(infoBox);
        }
        else if (SET_FLASH_COLOR.equals(option))
        {
            openFlashColorPicker(infoBox);
        }
        else if (REMOVE_FLASH_COLOR.equals(option))
        {
            removeFlashColor(infoBox);
        }
    }

    private void syncInfoBoxMenus()
    {
        for (InfoBox infoBox : infoBoxManager.getInfoBoxes())
        {
            syncHighlightMenu(infoBox);
            syncFlashMenu(infoBox);
        }
    }

    private void syncHighlightMenu(InfoBox infoBox)
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

        if (getHighlightColor(infoBox) != null)
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
            removeMenuEntry(infoBox, REMOVE_HIGHLIGHT);
        }
    }

    private void syncFlashMenu(InfoBox infoBox)
    {
        if (!(infoBox instanceof Timer))
        {
            removeMenuEntry(infoBox, SET_FLASH_COLOR);
            removeMenuEntry(infoBox, REMOVE_FLASH_COLOR);
            return;
        }

        if (!hasMenuEntry(infoBox, SET_FLASH_COLOR))
        {
            infoBox.getMenuEntries().add(
                    new OverlayMenuEntry(
                            MenuAction.RUNELITE_INFOBOX,
                            SET_FLASH_COLOR,
                            MENU_TARGET
                    )
            );
        }

        if (getFlashColor(infoBox) != null)
        {
            if (!hasMenuEntry(infoBox, REMOVE_FLASH_COLOR))
            {
                infoBox.getMenuEntries().add(
                        new OverlayMenuEntry(
                                MenuAction.RUNELITE_INFOBOX,
                                REMOVE_FLASH_COLOR,
                                MENU_TARGET
                        )
                );
            }
        }
        else
        {
            removeMenuEntry(infoBox, REMOVE_FLASH_COLOR);
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

        appliedFeathers.keySet().removeIf(
                infoBox -> !activeInfoBoxes.contains(infoBox)
        );

        for (InfoBox infoBox : infoBoxes)
        {
            syncHighlightImage(infoBox);
        }
    }

    private void syncHighlightImage(InfoBox infoBox)
    {
        Color desiredColor = getEffectiveColor(infoBox);
        int desiredFeather = getEffectiveFeather(infoBox);

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
                appliedFeathers.remove(infoBox);
            }

            return;
        }

        if (highlightedImage == null)
        {
            originalImages.put(infoBox, currentImage);

            applyHighlightImage(
                    infoBox,
                    desiredColor,
                    desiredFeather
            );

            return;
        }

        if (currentImage != highlightedImage)
        {
            originalImages.put(infoBox, currentImage);

            applyHighlightImage(
                    infoBox,
                    desiredColor,
                    desiredFeather
            );

            return;
        }

        Color appliedColor = appliedColors.get(infoBox);
        Integer appliedFeather = appliedFeathers.get(infoBox);

        if (!desiredColor.equals(appliedColor)
                || appliedFeather == null
                || desiredFeather != appliedFeather)
        {
            applyHighlightImage(
                    infoBox,
                    desiredColor,
                    desiredFeather
            );
        }
    }

    private Color getEffectiveColor(InfoBox infoBox)
    {
        if (isFlashActive(infoBox))
        {
            return getFlashColor(infoBox);
        }

        return getHighlightColor(infoBox);
    }

    private int getEffectiveFeather(InfoBox infoBox)
    {
        if (isFlashActive(infoBox))
        {
            return getPulseFeather();
        }

        return config.feather();
    }

    private boolean isFlashActive(InfoBox infoBox)
    {
        if (!(infoBox instanceof Timer))
        {
            return false;
        }

        Color flashColor = getFlashColor(infoBox);

        if (flashColor == null)
        {
            return false;
        }

        Timer timer = (Timer) infoBox;
        Instant endTime = timer.getEndTime();

        if (endTime == null)
        {
            return false;
        }

        long remainingMillis =
                Duration.between(Instant.now(), endTime).toMillis();

        long thresholdMillis =
                config.flashThreshold() * 1000L;

        return remainingMillis > 0
                && remainingMillis <= thresholdMillis;
    }

    private int getPulseFeather()
    {
        int baseFeather = Math.max(
                0,
                config.feather()
        );

        int maxFeather = Math.min(
                8,
                baseFeather + config.flashIntensity()
        );

        int range =
                maxFeather - baseFeather;

        if (range <= 0)
        {
            return baseFeather;
        }

        int cycleLength =
                range * 2;

        long step = Math.floorMod(
                System.currentTimeMillis() / FLASH_PULSE_STEP_MS,
                cycleLength
        );

        int offset;

        if (step <= range)
        {
            offset = (int) step;
        }
        else
        {
            offset =
                    cycleLength - (int) step;
        }

        return baseFeather + offset;
    }

    private void applyHighlightImage(
            InfoBox infoBox,
            Color color,
            int feather)
    {
        BufferedImage originalImage =
                originalImages.get(infoBox);

        if (originalImage == null)
        {
            return;
        }

        int size =
                Math.max(
                        2,
                        runeLiteConfig.infoBoxSize()
                );

        BufferedImage highlightedImage =
                new BufferedImage(
                        size,
                        size,
                        BufferedImage.TYPE_INT_ARGB
                );

        Graphics2D graphics =
                highlightedImage.createGraphics();

        int imageWidth =
                originalImage.getWidth();

        int imageHeight =
                originalImage.getHeight();

        double scale = Math.min(
                1.0,
                Math.min(
                        (double) size / imageWidth,
                        (double) size / imageHeight
                )
        );

        int drawWidth = Math.max(
                1,
                (int) Math.round(
                        imageWidth * scale
                )
        );

        int drawHeight = Math.max(
                1,
                (int) Math.round(
                        imageHeight * scale
                )
        );

        int imageX =
                (size - drawWidth) / 2;

        int imageY =
                (size - drawHeight) / 2;

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
                Math.min(
                        config.borderWidth(),
                        size / 2
                )
        );

        drawBorderLayer(
                graphics,
                size,
                0,
                borderWidth,
                color
        );

        int featherAmount =
                Math.max(
                        0,
                        feather
                );

        for (int i = 0; i < featherAmount; i++)
        {
            int offset =
                    borderWidth + i;

            if ((offset * 2) >= size)
            {
                break;
            }

            double strength =
                    (double) (featherAmount - i)
                            / (featherAmount + 1);

            int alpha = Math.max(
                    1,
                    (int) Math.round(
                            color.getAlpha() * strength
                    )
            );

            Color featherColor =
                    new Color(
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

        infoBox.setImage(
                highlightedImage
        );

        infoBoxManager.updateInfoBoxImage(
                infoBox
        );

        highlightedImages.put(
                infoBox,
                highlightedImage
        );

        appliedColors.put(
                infoBox,
                color
        );

        appliedFeathers.put(
                infoBox,
                featherAmount
        );
    }

    private void drawBorderLayer(
            Graphics2D graphics,
            int size,
            int offset,
            int thickness,
            Color color)
    {
        int innerSize =
                size - (offset * 2);

        if (innerSize <= 0
                || thickness <= 0)
        {
            return;
        }

        int actualThickness = Math.min(
                thickness,
                Math.max(
                        1,
                        innerSize / 2
                )
        );

        graphics.setColor(
                color
        );

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

    private void restoreOriginalImage(
            InfoBox infoBox)
    {
        BufferedImage originalImage =
                originalImages.remove(infoBox);

        BufferedImage highlightedImage =
                highlightedImages.remove(infoBox);

        appliedColors.remove(infoBox);
        appliedFeathers.remove(infoBox);

        if (originalImage == null)
        {
            return;
        }

        if (highlightedImage == null
                || infoBox.getImage() == highlightedImage)
        {
            infoBox.setImage(
                    originalImage
            );

            infoBoxManager.updateInfoBoxImage(
                    infoBox
            );
        }
    }

    private boolean hasMenuEntry(
            InfoBox infoBox,
            String option)
    {
        return infoBox.getMenuEntries()
                .stream()
                .anyMatch(
                        entry ->
                                option.equals(
                                        entry.getOption()
                                )
                );
    }

    private void removeMenuEntry(
            InfoBox infoBox,
            String option)
    {
        infoBox.getMenuEntries()
                .removeIf(
                        entry ->
                                option.equals(
                                        entry.getOption()
                                )
                );
    }

    private void removeOurMenuEntries(
            InfoBox infoBox)
    {
        infoBox.getMenuEntries()
                .removeIf(entry ->
                        SET_HIGHLIGHT.equals(
                                entry.getOption()
                        )
                                || REMOVE_HIGHLIGHT.equals(
                                entry.getOption()
                        )
                                || SET_FLASH_COLOR.equals(
                                entry.getOption()
                        )
                                || REMOVE_FLASH_COLOR.equals(
                                entry.getOption()
                        )
                );
    }

    private void openHighlightColorPicker(
            InfoBox infoBox)
    {
        Color currentColor =
                getHighlightColor(infoBox);

        if (currentColor == null)
        {
            currentColor =
                    Color.YELLOW;
        }

        openColorPicker(
                infoBox,
                currentColor,
                "InfoBox Highlight",
                false
        );
    }

    private void openFlashColorPicker(
            InfoBox infoBox)
    {
        Color currentColor =
                getFlashColor(infoBox);

        if (currentColor == null)
        {
            currentColor =
                    Color.RED;
        }

        openColorPicker(
                infoBox,
                currentColor,
                "InfoBox Flash",
                true
        );
    }

    private void openColorPicker(
            InfoBox infoBox,
            Color startingColor,
            String title,
            boolean flashColor)
    {
        SwingUtilities.invokeLater(() ->
        {
            RuneliteColorPicker colorPicker =
                    colorPickerManager.create(
                            client,
                            startingColor,
                            title,
                            false
                    );

            colorPicker.setOnClose(color ->
            {
                if (flashColor)
                {
                    setFlashColor(
                            infoBox,
                            color
                    );
                }
                else
                {
                    setHighlightColor(
                            infoBox,
                            color
                    );
                }
            });

            colorPicker.setVisible(
                    true
            );
        });
    }

    private Color getHighlightColor(
            InfoBox infoBox)
    {
        return configManager.getConfiguration(
                CONFIG_GROUP,
                getHighlightColorConfigKey(
                        infoBox
                ),
                Color.class
        );
    }

    private Color getFlashColor(
            InfoBox infoBox)
    {
        return configManager.getConfiguration(
                CONFIG_GROUP,
                getFlashColorConfigKey(
                        infoBox
                ),
                Color.class
        );
    }

    private void setHighlightColor(
            InfoBox infoBox,
            Color color)
    {
        configManager.setConfiguration(
                CONFIG_GROUP,
                getHighlightColorConfigKey(
                        infoBox
                ),
                color.getRGB()
        );
    }

    private void setFlashColor(
            InfoBox infoBox,
            Color color)
    {
        configManager.setConfiguration(
                CONFIG_GROUP,
                getFlashColorConfigKey(
                        infoBox
                ),
                color.getRGB()
        );
    }

    private void removeHighlight(
            InfoBox infoBox)
    {
        configManager.unsetConfiguration(
                CONFIG_GROUP,
                getHighlightColorConfigKey(
                        infoBox
                )
        );

        syncHighlightImage(
                infoBox
        );
    }

    private void removeFlashColor(
            InfoBox infoBox)
    {
        configManager.unsetConfiguration(
                CONFIG_GROUP,
                getFlashColorConfigKey(
                        infoBox
                )
        );

        syncHighlightImage(
                infoBox
        );
    }

    private String getHighlightColorConfigKey(
            InfoBox infoBox)
    {
        return "color_"
                + infoBox.getName();
    }

    private String getFlashColorConfigKey(
            InfoBox infoBox)
    {
        return "flashColor_"
                + infoBox.getName();
    }
}