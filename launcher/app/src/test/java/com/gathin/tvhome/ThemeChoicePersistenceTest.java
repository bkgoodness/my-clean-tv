package com.gathin.tvhome;

import android.content.SharedPreferences;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class ThemeChoicePersistenceTest {
    @Test public void changingThemeDoesNotClearSavedChoices() throws Exception {
        Map<String, Object> values = new HashMap<>();
        values.put("columns", 6);
        values.put("animations", false);
        values.put("theme_signature", "previous-theme");
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class}, (p, m, a) -> {
                    if (m.getName().equals("putString")) { values.put((String) a[0], a[1]); return p; }
                    if (m.getName().equals("remove") || m.getName().equals("clear")) fail("Theme cleared a preference");
                    if (m.getName().equals("commit")) return true;
                    return null;
                });
        SharedPreferences choices = (SharedPreferences) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{SharedPreferences.class}, (p, m, a) -> {
                    if (m.getName().equals("getString")) return values.getOrDefault(a[0], a[1]);
                    if (m.getName().equals("edit")) return editor;
                    return null;
                });
        Method sync = Config.class.getDeclaredMethod("syncThemeChoices", SharedPreferences.class, File.class, File.class);
        sync.setAccessible(true);
        sync.invoke(null, choices, null, null);
        assertEquals(6, values.get("columns"));
        assertEquals(false, values.get("animations"));
        assertNotEquals("previous-theme", values.get("theme_signature"));
        sync.invoke(null, choices, null, null);
        assertEquals(6, values.get("columns"));
    }
}
