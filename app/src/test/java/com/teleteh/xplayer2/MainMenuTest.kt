package com.teleteh.xplayer2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The toolbar facts the swap-eyes switch rests on, checked against the files because there is no
 * Robolectric in this module — the same approach `PlayerActivityManifestTest` takes.
 *
 * The switch was placed by the owner: immediately left of the glasses-mode action, and only while
 * that action reads "3D …". A toolbar item's position is its declaration order, so "left of" is a
 * fact about the XML; "only while" starts as the item being hidden until `MainActivity` has a
 * reason to show it.
 */
class MainMenuTest {

    private fun resource(path: String): String =
        listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
            .firstOrNull { it.isFile }?.readText()
            ?: error("$path not found from ${File(".").absolutePath}")

    private val menu: String by lazy { resource("menu/main_menu.xml") }

    /** The `<item>` block declaring [id], attributes only. */
    private fun itemAttributes(id: String): String {
        val at = menu.indexOf("@+id/$id")
        assertTrue("$id is not in the menu", at >= 0)
        return menu.substring(menu.lastIndexOf("<item", at), menu.indexOf("/>", at))
    }

    @Test
    fun `the swap-eyes switch is declared immediately before the glasses-mode action`() {
        val swap = menu.indexOf("@+id/menu_swap_eyes")
        val glasses = menu.indexOf("@+id/menu_glasses")
        assertTrue("the switch must exist", swap >= 0)
        assertTrue("the switch sits to the left of the glasses-mode action", swap < glasses)
        // Nothing declared between the two: "left of" means adjacent.
        val between = menu.substring(swap, glasses)
        assertEquals(1, Regex("@\\+id/").findAll(between).count())
    }

    @Test
    fun `both actions are always on the bar, never in the overflow`() {
        assertTrue(itemAttributes("menu_swap_eyes").contains("""app:showAsAction="always""""))
        assertTrue(itemAttributes("menu_glasses").contains("""app:showAsAction="always""""))
    }

    /** In 2D, or with no glasses, the toolbar must not grow a button — see `StereoEyeSwap.toggleShown`. */
    @Test
    fun `the switch starts hidden`() {
        assertTrue(itemAttributes("menu_swap_eyes").contains("""android:visible="false""""))
    }

    @Test
    fun `the switch has an action view of its own, like its neighbour`() {
        assertTrue(itemAttributes("menu_swap_eyes").contains("""app:actionLayout="@layout/action_swap_eyes""""))
        assertTrue(itemAttributes("menu_swap_eyes").contains("android:title="))
    }

    /** It is an icon with no label, so the action view must be named for a screen reader. */
    @Test
    fun `the action view speaks its name`() {
        assertTrue(resource("layout/action_swap_eyes.xml").contains("android:contentDescription="))
    }

    /** Both states have a spoken name in every language the app has. */
    @Test
    fun `every locale can say which way the eyes are`() {
        for (values in listOf("values", "values-ru", "values-es", "values-zh")) {
            val strings = resource("$values/strings.xml")
            assertTrue("$values lacks swap_eyes_off", strings.contains("""<string name="swap_eyes_off">"""))
            assertTrue("$values lacks swap_eyes_on", strings.contains("""<string name="swap_eyes_on">"""))
        }
    }
}
