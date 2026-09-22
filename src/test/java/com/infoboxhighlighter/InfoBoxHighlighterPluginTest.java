package com.infoboxhighlighter;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class InfoBoxHighlighterPluginTest
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(InfoBoxHighlighterPlugin.class);
        RuneLite.main(args);
    }
}