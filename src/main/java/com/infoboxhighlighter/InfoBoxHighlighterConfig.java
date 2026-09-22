package com.infoboxhighlighter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(InfoBoxHighlighterConfig.GROUP)
public interface InfoBoxHighlighterConfig extends Config
{
    String GROUP = "infoboxhighlighter";

    @Range(
            min = 1,
            max = 8
    )
    @Units(Units.PIXELS)
    @ConfigItem(
            keyName = "borderWidth",
            name = "Outline width",
            description = "Thickness of highlighted infobox outlines.",
            position = 0
    )
    default int borderWidth()
    {
        return 2;
    }

    @Range(
            min = 0,
            max = 8
    )
    @Units(Units.PIXELS)
    @ConfigItem(
            keyName = "feather",
            name = "Feather",
            description = "Softens the inside edge of the outline. 0 gives a crisp outline.",
            position = 1
    )
    default int feather()
    {
        return 0;
    }
}