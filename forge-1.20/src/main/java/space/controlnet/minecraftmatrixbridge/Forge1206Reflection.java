package space.controlnet.minecraftmatrixbridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Strict, cached lookup support for the Forge 50 runtime mapping boundary. */
final class Forge1206Reflection {
    private Forge1206Reflection() {
    }

    static boolean supports(String minecraft, String forge) {
        return "1.20.6".equals(minecraft) && forge.matches("50\\.\\d+\\.\\d+");
    }

    static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic,
                         Class<?>... parameters) {
        try {
            Method method = owner.getMethod(name, parameters);
            if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic) {
                throw new IllegalStateException("Unexpected Forge 1.20.6 API signature: " + method);
            }
            return method;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Missing Forge 1.20.6 API: " + owner.getName() + "." + name, e);
        }
    }

    static Object invoke(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (ReflectiveOperationException e) {
            // In particular, a failed permission provider must never grant access.
            throw new IllegalStateException("Forge 1.20.6 API call failed: " + method, e);
        }
    }
}
