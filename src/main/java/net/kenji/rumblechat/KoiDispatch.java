package net.kenji.rumblechat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import co.casterlabs.caffeinated.pluginsdk.Caffeinated;
import co.casterlabs.caffeinated.pluginsdk.koi.Koi;
import co.casterlabs.rakurai.json.element.JsonObject;

/**
 * Hands events to Caffeinated's chat without compiling against any Koi class.
 *
 * Why: the plugin_sdk jar on repo.casterlabs.co, the Caffeinated stable release
 * and the GitHub source each ship a different Koi (different packages,
 * constructors vs builders). Koi's JSON wire format is what they share, and
 * every version has KoiEventType.get(JsonObject) to turn that JSON into an event,
 * the same way Caffeinated reads events from Casterlabs' servers. So we build
 * the JSON and let the running app's own Koi do the conversion.
 */
final class KoiDispatch {
    private static final List<String> EVENT_TYPE_CLASSES = Arrays.asList(
        "co.casterlabs.koi.api.types.events.KoiEventType", // Caffeinated stable (legacy branch)
        "co.casterlabs.koi.api.types.KoiEventType" // newer Koi
    );

    private static Method getEvent;
    private static Method broadcast;
    private static String description = "not initialised";

    private KoiDispatch() {}

    static synchronized void init() throws ReflectiveOperationException {
        if (getEvent != null) return;

        Koi koi = Caffeinated.getInstance().getKoi();
        ClassLoader loader = koi.getClass().getClassLoader();

        Class<?> eventType = null;
        for (String name : EVENT_TYPE_CLASSES) {
            try {
                eventType = Class.forName(name, true, loader);
                break;
            } catch (ClassNotFoundException ignored) {}
        }
        if (eventType == null) {
            throw new ClassNotFoundException("No KoiEventType class found in " + EVENT_TYPE_CLASSES);
        }

        Method get = eventType.getMethod("get", JsonObject.class);

        Method bc = null;
        for (Method m : Koi.class.getMethods()) {
            if (m.getName().equals("broadcastEvent") && m.getParameterCount() == 1) {
                bc = m;
                break;
            }
        }
        if (bc == null) throw new NoSuchMethodException("Koi.broadcastEvent");

        getEvent = get;
        broadcast = bc;
        description = eventType.getName();
    }

    static String describe() {
        return description;
    }

    /**
     * @throws IllegalStateException if Koi couldn't turn the JSON into an event.
     *                               Koi logs its own SEVERE error with the reason.
     */
    static void send(JsonObject eventJson, boolean alsoLegacy) throws Exception {
        init();
        Koi koi = Caffeinated.getInstance().getKoi();

        Object event = getEvent.invoke(null, eventJson);
        if (event == null) {
            throw new IllegalStateException("Koi couldn't read the event JSON: " + eventJson);
        }
        broadcast.invoke(koi, event);

        // Caffeinated stable also wants the old-style CHAT/DONATION copy of rich
        // messages (the built-in Ko-fi plugin sends both). Newer Koi has no such method.
        if (alsoLegacy) {
            try {
                Method toLegacy = event.getClass().getMethod("toLegacyEvent");
                Object legacy = toLegacy.invoke(event);
                if (legacy != null) broadcast.invoke(koi, legacy);
            } catch (NoSuchMethodException ignored) {}
        }
    }
}
