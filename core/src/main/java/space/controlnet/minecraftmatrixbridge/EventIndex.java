package space.controlnet.minecraftmatrixbridge;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Index of event class names to fully-qualified class names (FQCNs).
 *
 * <p>Allows resolving simple event names (e.g., "ServerChatEvent") or alias patterns
 * (e.g., "TickEvent.ServerTickEvent") to their FQCNs.
 *
 * <p>The index is built at runtime by scanning the classpath for Event subclasses.
 * This allows third-party mod events to be indexed, not just Forge/NeoForge events.
 */
public final class EventIndex {
    private static final Logger LOGGER = Logger.getLogger("MatrixBridge");

    /** Known event base types (internal format with /) */
    private static final Set<String> KNOWN_EVENT_BASES = Set.of(
            "net/minecraftforge/eventbus/api/Event",
            "net/neoforged/bus/api/Event"
    );



    private final Map<String, List<String>> bySimpleName;
    private final Map<String, List<String>> byAlias;
    private final String loader;
    private final long generatedAtMs;
    private final int totalEventsFound;

    private EventIndex(
            Map<String, List<String>> bySimpleName,
            Map<String, List<String>> byAlias,
            String loader,
            long generatedAtMs,
            int totalEventsFound
    ) {
        this.bySimpleName = Collections.unmodifiableMap(new HashMap<>(bySimpleName));
        this.byAlias = Collections.unmodifiableMap(new HashMap<>(byAlias));
        this.loader = loader;
        this.generatedAtMs = generatedAtMs;
        this.totalEventsFound = totalEventsFound;
    }

    /**
     * Creates an EventIndex from pre-built maps (useful for testing).
     *
     * @param bySimpleName map of simple class names to FQCNs
     * @param byAlias      map of aliases to FQCNs
     * @param loader       loader identifier
     * @return a new EventIndex
     */
    public static EventIndex fromMaps(
            Map<String, List<String>> bySimpleName,
            Map<String, List<String>> byAlias,
            String loader
    ) {
        int totalEvents = (int) bySimpleName.values().stream()
                .flatMap(List::stream)
                .distinct()
                .count();
        return new EventIndex(bySimpleName, byAlias, loader, System.currentTimeMillis(), totalEvents);
    }

    /**
     * Creates an empty index (no events).
     */
    public static EventIndex empty() {
        return new EventIndex(Map.of(), Map.of(), "unknown", 0, 0);
    }

    /**
     * Scans the classpath at runtime to build an event index.
     *
     * <p>This method scans all JAR files on the classpath for classes that extend
     * the Forge or NeoForge Event base classes. It uses bytecode inspection to
     * avoid loading classes and triggering static initializers.
     *
     * @param classLoader the class loader to scan (typically the mod's class loader)
     * @param loaderName  identifier for this loader (e.g., "forge-1.20", "neoforge-1.21")
     * @return the built EventIndex
     */
    public static EventIndex scanClasspath(ClassLoader classLoader, String loaderName) {
        long startMs = System.currentTimeMillis();
        LOGGER.info("Starting runtime event index scan...");

        Map<String, String> classToSuper = new HashMap<>();
        Set<String> scannedJars = new HashSet<>();
        int jarCount = 0;
        int classCount = 0;

        try {
            // Get all JAR URLs from the classpath
            Enumeration<URL> resources = classLoader.getResources("META-INF/MANIFEST.MF");
            while (resources.hasMoreElements()) {
                URL manifestUrl = resources.nextElement();
                String urlStr = manifestUrl.toString();

                // Extract JAR path from jar:file:/path/to/file.jar!/META-INF/MANIFEST.MF
                if (urlStr.startsWith("jar:file:")) {
                    int bangIdx = urlStr.indexOf("!/");
                    if (bangIdx > 0) {
                        String jarPath = urlStr.substring(9, bangIdx); // Skip "jar:file:"
                        if (!scannedJars.contains(jarPath)) {
                            scannedJars.add(jarPath);
                            File jarFile = new File(jarPath);
                            if (jarFile.exists() && jarFile.isFile()) {
                                int scanned = scanJarFile(jarFile, classToSuper);
                                if (scanned > 0) {
                                    jarCount++;
                                    classCount += scanned;
                                }
                            }
                        }
                    }
                }
            }

            // Also try to scan from java.class.path
            String classPath = System.getProperty("java.class.path", "");
            for (String path : classPath.split(File.pathSeparator)) {
                if (path.endsWith(".jar") && !scannedJars.contains(path)) {
                    scannedJars.add(path);
                    File jarFile = new File(path);
                    if (jarFile.exists() && jarFile.isFile()) {
                        int scanned = scanJarFile(jarFile, classToSuper);
                        if (scanned > 0) {
                            jarCount++;
                            classCount += scanned;
                        }
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Error scanning classpath: " + e, e);
        }

        // Build index from scanned classes
        Map<String, List<String>> bySimpleName = new HashMap<>();
        Map<String, List<String>> byAlias = new HashMap<>();
        int eventCount = 0;

        for (String className : classToSuper.keySet()) {
            if (isEventSubtype(className, classToSuper)) {
                String fqcn = className.replace('/', '.');
                String simpleName = extractSimpleName(fqcn);
                eventCount++;

                if (simpleName.contains("$")) {
                    // Nested class
                    String outerInner = simpleName;  // e.g., "TickEvent$ServerTickEvent"
                    String dotAlias = simpleName.replace('$', '.');  // e.g., "TickEvent.ServerTickEvent"
                    String innerOnly = simpleName.substring(simpleName.lastIndexOf('$') + 1);

                    bySimpleName.computeIfAbsent(innerOnly, k -> new ArrayList<>()).add(fqcn);
                    byAlias.computeIfAbsent(outerInner, k -> new ArrayList<>()).add(fqcn);
                    byAlias.computeIfAbsent(dotAlias, k -> new ArrayList<>()).add(fqcn);
                } else {
                    bySimpleName.computeIfAbsent(simpleName, k -> new ArrayList<>()).add(fqcn);
                }
            }
        }

        // Sort lists for deterministic output
        bySimpleName.values().forEach(Collections::sort);
        byAlias.values().forEach(Collections::sort);

        long elapsedMs = System.currentTimeMillis() - startMs;
        LOGGER.info("Event index built in " + elapsedMs + " ms: scanned " + jarCount + " jars, " +
                classCount + " classes, found " + eventCount + " events (" +
                bySimpleName.size() + " simple names, " + byAlias.size() + " aliases)");

        return new EventIndex(bySimpleName, byAlias, loaderName, System.currentTimeMillis(), eventCount);
    }

    /**
     * Scans a JAR file and populates the class-to-superclass map.
     *
     * @return the number of classes successfully scanned
     */
    private static int scanJarFile(File jarFile, Map<String, String> classToSuper) {
        int classCount = 0;
        try (JarFile jf = new JarFile(jarFile)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.endsWith(".class") && !name.contains("module-info")) {
                    try (InputStream is = jf.getInputStream(entry)) {
                        ClassInfo info = parseClassHeader(is);
                        if (info != null && info.className != null) {
                            classToSuper.put(info.className, info.superName);
                            classCount++;
                        }
                    } catch (Exception e) {
                        // Skip malformed class files silently
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.fine("Failed to scan JAR " + jarFile.getName() + ": " + e.getMessage());
        }
        return classCount;
    }

    /**
     * Parses a class file header to extract class name and superclass name.
     * This is a minimal parser that only reads the constant pool and class indices.
     */
    private static ClassInfo parseClassHeader(InputStream is) throws IOException {
        DataInputStream dis = new DataInputStream(is);

        // Magic number
        int magic = dis.readInt();
        if (magic != 0xCAFEBABE) {
            return null;
        }

        // Version
        dis.readUnsignedShort(); // minor
        dis.readUnsignedShort(); // major

        // Constant pool
        int constantPoolCount = dis.readUnsignedShort();
        Object[] constantPool = new Object[constantPoolCount];

        for (int i = 1; i < constantPoolCount; i++) {
            int tag = dis.readUnsignedByte();
            switch (tag) {
                case 1: // CONSTANT_Utf8
                    constantPool[i] = dis.readUTF();
                    break;
                case 3: // CONSTANT_Integer
                    dis.readInt();
                    break;
                case 4: // CONSTANT_Float
                    dis.readFloat();
                    break;
                case 5: // CONSTANT_Long
                    dis.readLong();
                    i++; // Takes two slots
                    break;
                case 6: // CONSTANT_Double
                    dis.readDouble();
                    i++; // Takes two slots
                    break;
                case 7: // CONSTANT_Class
                    constantPool[i] = new int[]{7, dis.readUnsignedShort()};
                    break;
                case 8: // CONSTANT_String
                    dis.readUnsignedShort();
                    break;
                case 9: // CONSTANT_Fieldref
                case 10: // CONSTANT_Methodref
                case 11: // CONSTANT_InterfaceMethodref
                    dis.readUnsignedShort();
                    dis.readUnsignedShort();
                    break;
                case 12: // CONSTANT_NameAndType
                    dis.readUnsignedShort();
                    dis.readUnsignedShort();
                    break;
                case 15: // CONSTANT_MethodHandle
                    dis.readUnsignedByte();
                    dis.readUnsignedShort();
                    break;
                case 16: // CONSTANT_MethodType
                    dis.readUnsignedShort();
                    break;
                case 17: // CONSTANT_Dynamic
                case 18: // CONSTANT_InvokeDynamic
                    dis.readUnsignedShort();
                    dis.readUnsignedShort();
                    break;
                case 19: // CONSTANT_Module
                case 20: // CONSTANT_Package
                    dis.readUnsignedShort();
                    break;
                default:
                    return null; // Unknown constant type
            }
        }

        // Access flags
        dis.readUnsignedShort();

        // This class
        int thisClassIndex = dis.readUnsignedShort();
        Object thisClassInfo = constantPool[thisClassIndex];
        if (!(thisClassInfo instanceof int[]) || ((int[]) thisClassInfo)[0] != 7) {
            return null;
        }
        int thisNameIndex = ((int[]) thisClassInfo)[1];
        String thisClassName = (String) constantPool[thisNameIndex];

        // Super class
        int superClassIndex = dis.readUnsignedShort();
        String superClassName = null;
        if (superClassIndex != 0) {
            Object superClassInfo = constantPool[superClassIndex];
            if (superClassInfo instanceof int[] && ((int[]) superClassInfo)[0] == 7) {
                int superNameIndex = ((int[]) superClassInfo)[1];
                superClassName = (String) constantPool[superNameIndex];
            }
        }

        return new ClassInfo(thisClassName, superClassName);
    }

    private static boolean isEventSubtype(String className, Map<String, String> classToSuper) {
        String current = className;
        Set<String> visited = new HashSet<>();

        while (current != null && !"java/lang/Object".equals(current) && !visited.contains(current)) {
            visited.add(current);
            if (KNOWN_EVENT_BASES.contains(current)) {
                return true;
            }
            current = classToSuper.get(current);
        }
        return false;
    }

    private static String extractSimpleName(String fqcn) {
        int dotIndex = fqcn.lastIndexOf('.');
        return dotIndex >= 0 ? fqcn.substring(dotIndex + 1) : fqcn;
    }

    /**
     * Resolves a user input to an FQCN.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>If input looks like an FQCN (contains '.'), return as-is (Success)</li>
     *   <li>Try exact match in {@code bySimpleName}</li>
     *   <li>Try exact match in {@code byAlias}</li>
     *   <li>Convert user-friendly dot notation to $ notation for nested classes and retry alias</li>
     *   <li>Return NotFound if no match</li>
     * </ol>
     *
     * @param userInput the user's input (simple name, alias, or FQCN)
     * @return resolution result
     */
    public ResolveResult resolve(String userInput) {
        if (userInput == null || userInput.trim().isEmpty()) {
            return ResolveResult.notFound();
        }
        String input = userInput.trim();

        // If it looks like an FQCN (has dots and looks like a package path), treat it as direct FQCN
        if (looksLikeFqcn(input)) {
            return ResolveResult.success(input);
        }

        // Try simple name lookup
        List<String> fromSimple = bySimpleName.get(input);
        if (fromSimple != null && !fromSimple.isEmpty()) {
            if (fromSimple.size() == 1) {
                return ResolveResult.success(fromSimple.get(0));
            }
            return ResolveResult.ambiguous(fromSimple);
        }

        // Try alias lookup (e.g., "TickEvent$ServerTickEvent")
        List<String> fromAlias = byAlias.get(input);
        if (fromAlias != null && !fromAlias.isEmpty()) {
            if (fromAlias.size() == 1) {
                return ResolveResult.success(fromAlias.get(0));
            }
            return ResolveResult.ambiguous(fromAlias);
        }

        // Convert dot notation to $ notation for nested classes (e.g., "TickEvent.ServerTickEvent" -> "TickEvent$ServerTickEvent")
        if (input.contains(".") && !looksLikeFqcn(input)) {
            String dollarNotation = input.replace('.', '$');
            List<String> fromDollar = byAlias.get(dollarNotation);
            if (fromDollar != null && !fromDollar.isEmpty()) {
                if (fromDollar.size() == 1) {
                    return ResolveResult.success(fromDollar.get(0));
                }
                return ResolveResult.ambiguous(fromDollar);
            }
        }

        return ResolveResult.notFound();
    }

    /**
     * Searches the index for entries matching a query substring.
     *
     * @param query the search query (case-insensitive substring)
     * @param limit maximum number of results
     * @return list of matching FQCNs
     */
    public List<String> search(String query, int limit) {
        if (query == null || query.trim().isEmpty() || limit <= 0) {
            return List.of();
        }
        String q = query.trim().toLowerCase(Locale.ROOT);

        List<String> results = new ArrayList<>();
        // Search simple names
        for (Map.Entry<String, List<String>> entry : bySimpleName.entrySet()) {
            if (results.size() >= limit) break;
            if (entry.getKey().toLowerCase(Locale.ROOT).contains(q)) {
                for (String fqcn : entry.getValue()) {
                    if (results.size() >= limit) break;
                    if (!results.contains(fqcn)) {
                        results.add(fqcn);
                    }
                }
            }
        }
        // Search FQCNs directly
        for (List<String> fqcns : bySimpleName.values()) {
            if (results.size() >= limit) break;
            for (String fqcn : fqcns) {
                if (results.size() >= limit) break;
                if (fqcn.toLowerCase(Locale.ROOT).contains(q) && !results.contains(fqcn)) {
                    results.add(fqcn);
                }
            }
        }
        return results;
    }

    /**
     * Returns the loader identifier from the index.
     */
    public String getLoader() {
        return loader;
    }

    /**
     * Returns the generation timestamp of the index.
     */
    public long getGeneratedAtMs() {
        return generatedAtMs;
    }

    /**
     * Returns the number of simple name entries in the index.
     */
    public int getSimpleNameCount() {
        return bySimpleName.size();
    }

    /**
     * Returns the number of alias entries in the index.
     */
    public int getAliasCount() {
        return byAlias.size();
    }

    /**
     * Returns the total number of events found during scanning.
     */
    public int getTotalEventsFound() {
        return totalEventsFound;
    }

    /**
     * Returns true if the index is empty.
     */
    public boolean isEmpty() {
        return bySimpleName.isEmpty() && byAlias.isEmpty();
    }

    /**
     * Heuristic to check if input looks like an FQCN.
     *
     * <p>An FQCN typically has:
     * <ul>
     *   <li>Multiple dots (package separators)</li>
     *   <li>Lowercase segments before the class name</li>
     * </ul>
     */
    private static boolean looksLikeFqcn(String input) {
        if (input == null || !input.contains(".")) {
            return false;
        }
        // Simple heuristic: if it starts with a lowercase letter and has dots, it's probably an FQCN
        // e.g., "net.minecraftforge.event.ServerChatEvent"
        // vs "TickEvent.ServerTickEvent" (nested class alias)
        int firstDot = input.indexOf('.');
        if (firstDot <= 0) {
            return false;
        }
        String firstSegment = input.substring(0, firstDot);
        // Package names are typically all lowercase
        return firstSegment.equals(firstSegment.toLowerCase(Locale.ROOT));
    }

    @Override
    public String toString() {
        return "EventIndex{loader='" + loader + "', simpleNames=" + bySimpleName.size() +
                ", aliases=" + byAlias.size() + ", totalEvents=" + totalEventsFound +
                ", generatedAtMs=" + generatedAtMs + "}";
    }

    /**
     * Helper class to hold parsed class information.
     */
    private static final class ClassInfo {
        final String className;
        final String superName;

        ClassInfo(String className, String superName) {
            this.className = className;
            this.superName = superName;
        }
    }
}
