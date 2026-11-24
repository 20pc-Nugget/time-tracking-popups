package com.timetrackingpopups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import net.runelite.client.config.ConfigItem;
import org.junit.Test;

/**
 * The per-patch toggles in the config interface are generated (tools/generate_config.py). If Time
 * Tracking's patch list or the Category table changes, regenerate it - this test says when.
 */
public class ConfigConsistencyTest
{
	@Test
	public void everyCatalogEntryHasAToggle()
	{
		Set<String> keys = new HashSet<>();
		for (Method method : TimeTrackingPopupsConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null)
			{
				keys.add(item.keyName());
			}
		}

		for (Category category : Category.values())
		{
			assertTrue("missing master toggle " + category.masterKey(), keys.contains(category.masterKey()));
		}

		Set<String> seen = new HashSet<>();
		for (PatchEntry entry : PatchCatalog.build())
		{
			assertTrue("missing toggle " + entry.getKey() + " - run tools/generate_config.py", keys.contains(entry.getKey()));
			assertTrue("duplicate key " + entry.getKey(), seen.add(entry.getKey()));
		}
	}

	@Test
	public void noOrphanedToggles()
	{
		Set<String> expected = new HashSet<>();
		for (Category category : Category.values())
		{
			expected.add(category.masterKey());
		}
		for (PatchEntry entry : PatchCatalog.build())
		{
			expected.add(entry.getKey());
		}

		for (Method method : TimeTrackingPopupsConfig.class.getDeclaredMethods())
		{
			ConfigItem item = method.getAnnotation(ConfigItem.class);
			if (item != null && item.keyName().contains("_"))
			{
				assertTrue("orphaned toggle " + item.keyName() + " - run tools/generate_config.py", expected.contains(item.keyName()));
			}
		}
		assertEquals(expected.size(), expected.stream().distinct().count());
	}
}
