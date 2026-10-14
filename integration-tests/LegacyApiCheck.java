import org.mockbukkit.mockbukkit.MockBukkit;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Load actual older TLibs jars independently of the current test dependency. */
public final class LegacyApiCheck {
    public static void main(String[] args) throws Exception {
        var server = MockBukkit.mock();
        try (var loader =
                new URLClassLoader(
                        new java.net.URL[] {
                            Path.of(args[0]).toUri().toURL(), Path.of(args[1]).toUri().toURL()
                        },
                        LegacyApiCheck.class.getClassLoader()) {
                    @Override
                    protected Class<?> loadClass(String name, boolean resolve)
                            throws ClassNotFoundException {
                        if (name.startsWith("net.tfminecraft.games.")
                                || name.startsWith("net.tfminecraft.tlibs.")) {
                            Class<?> type = findLoadedClass(name);
                            if (type == null) type = findClass(name);
                            if (resolve) resolveClass(type);
                            return type;
                        }
                        return super.loadClass(name, resolve);
                    }
                }) {
            Class<?> identity = loader.loadClass("net.tfminecraft.games.ownership.CharacterAccess");
            if (!Boolean.valueOf(args[2]).equals(identity.getMethod("available").invoke(null)))
                throw new AssertionError("Incorrect older character API capability");
            if (!Boolean.FALSE.equals(identity.getMethod("heirReady").invoke(null)))
                throw new AssertionError("Missing reviewed provider must fail closed");
            if (identity.getMethod("capture", org.bukkit.entity.Player.class)
                            .invoke(null, server.addPlayer())
                    != null)
                throw new AssertionError(
                        "Missing current identity must not capture owner authority");
            Class<?> estates = loader.loadClass("net.tfminecraft.games.ownership.EstateBridge");
            if (!Boolean.FALSE.equals(estates.getMethod("available").invoke(null)))
                throw new AssertionError("Older TLibs must not accept guarded estates");
            Path directory = Path.of(args[3]);
            Files.createDirectories(directory);
            Path source = directory.resolve("legacy.json");
            String original =
                    "{\"id\":\"33333333-3333-3333-3333-333333333333\",\"ownerPlayer\":\"11111111-1111-1111-1111-111111111111\",\"staffMint\":false,\"unknown\":null}";
            Files.writeString(source, original);
            Class<?> migration =
                    loader.loadClass("net.tfminecraft.games.ownership.OwnershipMigration");
            Object reader =
                    migration
                            .getConstructor(Path.class)
                            .newInstance(directory.resolve("migrations"));
            if (!Boolean.FALSE.equals(
                            migration.getMethod("prepare", Path.class).invoke(reader, source))
                    || !original.equals(Files.readString(source))
                    || Files.exists(directory.resolve("migrations")))
                throw new AssertionError(
                        "Unavailable migration must preserve legacy private source exactly");
            System.out.println(
                    "{\"passed\":true,\"checks\":5,\"legacyJar\":\""
                            + args[1]
                            + "\",\"characterApi\":"
                            + args[2]
                            + "}");
        } finally {
            MockBukkit.unmock();
        }
    }
}
